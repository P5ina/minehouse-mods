package pro.minehouse.menu.intro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intro soundtrack: the whole OGG is decoded up front with stb_vorbis (shipped with the game)
 * and played through Java Sound on its own line, independent of the game's sound engine.
 * The line's frame position is the clock the video follows.
 */
public final class IntroAudio implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("MineHouse Intro");

	private final byte[] pcm;
	private final int channels;
	private final int rate;
	private SourceDataLine line;
	private Thread feeder;
	private volatile boolean closed;
	private volatile long startNanos = -1;

	private IntroAudio(byte[] pcm, int channels, int rate) {
		this.pcm = pcm;
		this.channels = channels;
		this.rate = rate;
	}

	/** Decodes the file; null if it can't be read. */
	public static IntroAudio load(Path ogg) {
		ByteBuffer in = null;
		long handle = 0;
		try {
			byte[] file = Files.readAllBytes(ogg);
			in = MemoryUtil.memAlloc(file.length);
			in.put(file).flip();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var err = stack.mallocInt(1);
				handle = STBVorbis.stb_vorbis_open_memory(in, err, null);
				if (handle == 0) {
					throw new IOException("stb_vorbis error " + err.get(0) + " for " + ogg);
				}
				STBVorbisInfo info = STBVorbisInfo.malloc(stack); // stack memory, freed with the stack
				{
					STBVorbis.stb_vorbis_get_info(handle, info);
					int channels = info.channels();
					int rate = info.sample_rate();
					int total = STBVorbis.stb_vorbis_stream_length_in_samples(handle) * channels;
					ShortBuffer out = MemoryUtil.memAllocShort(Math.max(total, channels));
					try {
						int got = 0;
						while (out.hasRemaining()) {
							int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, out);
							if (n <= 0) {
								break;
							}
							got += n * channels;
							out.position(got);
						}
						out.flip();
						ByteBuffer bytes = ByteBuffer.allocate(out.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
						bytes.asShortBuffer().put(out);
						return new IntroAudio(bytes.array(), channels, rate);
					} finally {
						MemoryUtil.memFree(out);
					}
				}
			}
		} catch (Throwable t) {
			LOG.warn("Intro audio unavailable", t);
			return null;
		} finally {
			if (handle != 0) {
				STBVorbis.stb_vorbis_close(handle);
			}
			if (in != null) {
				MemoryUtil.memFree(in);
			}
		}
	}

	/** Starts playback at {@code volume} (0..1). Without a sound device the clock still runs. */
	public void start(float volume) {
		this.startNanos = System.nanoTime();
		if (volume <= 0.0F) {
			LOG.info("Intro audio muted (master volume is 0)");
			return;
		}
		try {
			AudioFormat format = new AudioFormat(this.rate, 16, this.channels, true, false);
			this.line = AudioSystem.getSourceDataLine(format);
			this.line.open(format, this.rate * this.channels * 2 / 4); // ~250 ms buffer
			if (this.line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl gain = (FloatControl) this.line.getControl(FloatControl.Type.MASTER_GAIN);
				float db = (float) (20.0 * Math.log10(Math.max(volume, 0.0001F)));
				gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
			}
			this.line.start();
			LOG.info("Intro audio: {} Hz, {} ch, volume {}", this.rate, this.channels, volume);
			this.startNanos = System.nanoTime();
			this.feeder = new Thread(() -> {
				int chunk = 4096;
				for (int off = 0; off < this.pcm.length && !this.closed; off += chunk) {
					this.line.write(this.pcm, off, Math.min(chunk, this.pcm.length - off));
				}
				if (!this.closed) {
					this.line.drain();
				}
			}, "MineHouse intro audio");
			this.feeder.setDaemon(true);
			this.feeder.start();
		} catch (Throwable t) {
			LOG.warn("No sound device for the intro, playing silent", t);
			this.line = null;
		}
	}

	/** Seconds since start: from the sound card when there is one, so the picture follows the music. */
	public double time() {
		if (this.startNanos < 0) {
			return 0.0;
		}
		if (this.line != null) {
			return this.line.getLongFramePosition() / (double) this.rate;
		}
		return (System.nanoTime() - this.startNanos) / 1e9;
	}

	public double duration() {
		return this.pcm.length / (double) (this.channels * 2 * this.rate);
	}

	@Override
	public void close() {
		this.closed = true;
		if (this.line != null) {
			this.line.stop();
			this.line.flush();
			this.line.close();
		}
	}
}
