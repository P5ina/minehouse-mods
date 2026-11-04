package pro.minehouse.menu.intro;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes an H.264 MP4 in real time on several threads, pure Java (jcodec).
 *
 * jcodec alone is too slow for 1080p on one core, so the video is encoded with a fixed
 * GOP (every {@code gop}-th frame is a keyframe, no scene-cut keyframes, no B-frames).
 * Worker {@code w} of {@code n} decodes GOPs w, w+n, w+2n... from its own file handle, so n
 * GOPs are decoded at once. Each worker can queue a whole GOP, so by the time the player
 * reaches it, it is already decoded.
 *
 * jcodec decodes every frame into the same per-thread buffer, so each frame is copied into
 * one of the worker's own {@link Frame}s before it is queued; the player hands frames back
 * with {@link #release} once it has converted them. Memory: n * (gop + 2) YUV 4:2:0 frames
 * (about 3 MB each at 1080p).
 *
 * If decoding falls behind, {@link #frame} returns null (the caller keeps the last frame) and
 * workers skip GOPs the player has already passed.
 */
public final class IntroDecoder implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("MineHouse Intro");

	/** One decoded frame in a reusable buffer: planes in jcodec layout (signed, value - 128). */
	public static final class Frame {
		public int index;
		public int width;
		public int height;
		public int startX;
		public int startY;
		public int strideY;
		public int strideC;
		public final byte[][] planes = new byte[3][];
		final Worker owner;

		Frame(Worker owner) {
			this.owner = owner;
		}

		void copyFrom(Picture p, int index) {
			this.index = index;
			this.width = p.getCroppedWidth();
			this.height = p.getCroppedHeight();
			this.startX = p.getStartX();
			this.startY = p.getStartY();
			this.strideY = p.getPlaneWidth(0);
			this.strideC = p.getPlaneWidth(1);
			for (int i = 0; i < 3; i++) {
				byte[] src = p.getPlaneData(i);
				if (this.planes[i] == null || this.planes[i].length != src.length) {
					this.planes[i] = new byte[src.length];
				}
				System.arraycopy(src, 0, this.planes[i], 0, src.length);
			}
		}
	}

	private final File file;
	private final int frames;
	private final int gop;
	private final int gops;
	private final Worker[] workers;
	private volatile int playerGop;
	private volatile boolean closed;
	private volatile Throwable error;
	private final long startNanos = System.nanoTime();
	private volatile int decoded;
	private volatile int skippedGops;

	public IntroDecoder(File file, int frames, int gop, int threads) {
		this.file = file;
		this.frames = frames;
		this.gop = gop;
		this.gops = (frames + gop - 1) / gop;
		this.workers = new Worker[Math.max(1, Math.min(threads, this.gops))];
		for (int i = 0; i < this.workers.length; i++) {
			this.workers[i] = new Worker(i, gop);
		}
		for (Worker w : this.workers) {
			w.thread.start();
		}
	}

	public int frames() {
		return this.frames;
	}

	public int threads() {
		return this.workers.length;
	}

	public Throwable error() {
		return this.error;
	}

	/** Frames decoded so far and GOPs skipped because decoding fell behind. */
	public String stats() {
		double s = (System.nanoTime() - this.startNanos) / 1e9;
		return String.format("%d frames in %.1f s (%.1f fps), %d GOPs skipped, %d threads",
			this.decoded, s, this.decoded / Math.max(s, 0.001), this.skippedGops, this.workers.length);
	}

	/**
	 * The frame {@code index}, or null if it isn't decoded yet. Frames before {@code index}
	 * that are still queued are dropped. Call with non-decreasing indices and hand the result
	 * back with {@link #release} once it is used.
	 */
	public Frame frame(int index) {
		if (index < 0 || index >= this.frames) {
			return null;
		}
		int g = index / this.gop;
		if (g > this.playerGop) {
			this.playerGop = g;
		}
		Worker w = this.workers[g % this.workers.length];
		while (true) {
			Frame f = w.queue.peek();
			if (f == null) {
				return null;
			}
			if (f.index < index) {
				w.queue.poll();
				w.free.offer(f);
				continue;
			}
			if (f.index == index) {
				w.queue.poll();
				return f;
			}
			return null; // the worker is already past this frame (it skipped ahead)
		}
	}

	public void release(Frame f) {
		if (f != null) {
			f.owner.free.offer(f);
		}
	}

	/** True when the first {@code count} frames are decoded and waiting, so playback can start without a stall. */
	public boolean primed(int count) {
		Worker w = this.workers[0];
		return w.queue.size() >= Math.min(count, this.frames) || w.finished;
	}

	@Override
	public void close() {
		this.closed = true;
		for (Worker w : this.workers) {
			w.thread.interrupt();
			w.queue.clear();
			w.free.clear();
		}
	}

	private final class Worker implements Runnable {
		final int id;
		final BlockingQueue<Frame> queue;
		final BlockingQueue<Frame> free;
		final Thread thread;
		volatile boolean finished;

		Worker(int id, int gop) {
			this.id = id;
			int capacity = Math.max(2, gop);
			this.queue = new ArrayBlockingQueue<>(capacity);
			// capacity queued + one the player holds + one being filled
			this.free = new ArrayBlockingQueue<>(capacity + 2);
			for (int i = 0; i < capacity + 2; i++) {
				this.free.offer(new Frame(this));
			}
			this.thread = new Thread(this, "MineHouse intro decoder " + id);
			this.thread.setDaemon(true);
			this.thread.setPriority(Thread.NORM_PRIORITY - 1);
		}

		private boolean behind(int g) {
			return g < IntroDecoder.this.playerGop;
		}

		@Override
		public void run() {
			try (SeekableByteChannel ch = NIOUtils.readableChannel(IntroDecoder.this.file)) {
				FrameGrab grab = FrameGrab.createFrameGrab(ch);
				int next = -1; // frame index the grab will return next, -1 = unknown
				for (int g = this.id; g < IntroDecoder.this.gops && !IntroDecoder.this.closed; g += IntroDecoder.this.workers.length) {
					if (behind(g)) {
						IntroDecoder.this.skippedGops++;
						continue; // the player is already past it
					}
					int start = g * IntroDecoder.this.gop;
					int end = Math.min(start + IntroDecoder.this.gop, IntroDecoder.this.frames);
					if (next != start) {
						grab.seekToFrameSloppy(start); // a keyframe, so sloppy is exact
					}
					for (int f = start; f < end && !IntroDecoder.this.closed; f++) {
						Picture p = grab.getNativeFrame();
						if (p == null) {
							break;
						}
						next = f + 1;
						if (behind(g)) {
							IntroDecoder.this.skippedGops++;
							next = -1;
							break;
						}
						Frame buf = null;
						while (buf == null && !IntroDecoder.this.closed && !behind(g)) {
							buf = this.free.poll(50, TimeUnit.MILLISECONDS);
						}
						if (buf == null) {
							next = -1;
							break;
						}
						buf.copyFrom(p, f);
						IntroDecoder.this.decoded++;
						boolean queued = false;
						while (!queued && !IntroDecoder.this.closed && !behind(g)) {
							queued = this.queue.offer(buf, 50, TimeUnit.MILLISECONDS);
						}
						if (!queued) {
							this.free.offer(buf);
							next = -1;
							break;
						}
					}
				}
			} catch (InterruptedException e) {
				// closed
			} catch (Throwable t) {
				if (!IntroDecoder.this.closed) {
					IntroDecoder.this.error = t;
					LOG.warn("Intro decoder {} failed", this.id, t);
				}
			} finally {
				this.finished = true;
			}
		}
	}
}
