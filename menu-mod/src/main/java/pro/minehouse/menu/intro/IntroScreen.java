package pro.minehouse.menu.intro;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import org.lwjgl.system.MemoryUtil;
import pro.minehouse.menu.MinehouseMenu;

/**
 * Plays the intro full screen, then runs what comes next (the title screen, or quick play when
 * the MineHouse launcher starts the game straight onto the server).
 * The first, automatic showing can't be skipped; a replay (the «Интро» button) ends on any key
 * or click. Waits for the loading overlay (and the animated Mojang logo)
 * to finish first, so the intro starts on a clean screen with a few frames already decoded.
 */
public final class IntroScreen extends Screen {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(MinehouseMenu.MOD_ID, "intro/frame");
	private static final double FADE_OUT = 0.6;
	private static final boolean INTRO_TEST = Boolean.getBoolean("minehouse.introtest");

	private final Runnable then;
	private final Intro.Meta meta;
	private final boolean skippable;
	private long playStart = -1;
	private IntroDecoder decoder;
	private IntroAudio audio;
	private DynamicTexture texture;
	private int[] rgba;
	private boolean started;
	private boolean hasFrame;
	private boolean finished;
	private long waitStart = -1;
	private double length;
	private long silentStart = -1;
	private boolean shot;
	private boolean released;
	private int lastShown = -1;
	private int shownFrames;
	private int droppedFrames;

	public IntroScreen(Screen next, Intro.Meta meta, boolean skippable) {
		this(() -> Minecraft.getInstance().gui.setScreen(next), meta, skippable);
	}

	/** {@code then} runs when the intro ends: one of the game's initial screens, quick play or the title screen. */
	public IntroScreen(Runnable then, Intro.Meta meta, boolean skippable) {
		super(Component.literal("MineHouse"));
		this.then = then;
		this.meta = meta;
		this.skippable = skippable;
	}

	@Override
	protected void init() {
		if (this.decoder != null) {
			return; // resized
		}
		try {
			// leave a core for the render thread and one for the game
			int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
			this.decoder = new IntroDecoder(Intro.dir().resolve(this.meta.video()).toFile(), this.meta.frames(), this.meta.gop(), threads);
			this.audio = this.meta.audio() == null ? null : IntroAudio.load(Intro.dir().resolve(this.meta.audio()));
			this.texture = new VideoTexture(this.meta.width(), this.meta.height());
			this.minecraft.getTextureManager().register(TEXTURE, this.texture);
			this.rgba = new int[this.meta.width() * this.meta.height()];
			this.length = this.meta.frames() / (double) this.meta.fps();
			Intro.markPlayed(this.meta);
			MinehouseMenu.LOG.info("Intro {}: {}x{} {} frames, {} decoder threads, audio {}", this.meta.version(), this.meta.width(), this.meta.height(),
				this.meta.frames(), this.decoder.threads(), this.audio == null ? "none" : "loaded");
		} catch (Throwable t) {
			MinehouseMenu.LOG.warn("Can't play the intro", t);
			this.finish();
		}
	}

	@Override
	public void tick() {
		this.minecraft.getMusicManager().stopPlaying();
		if (this.finished || this.decoder == null) {
			return;
		}
		if (this.decoder.error() != null) {
			this.finish();
			return;
		}
		if (!this.started) {
			if (this.minecraft.gui.overlay() != null) {
				return; // still loading / Mojang logo
			}
			if (this.waitStart < 0) {
				this.waitStart = System.nanoTime();
			}
			boolean ready = this.decoder.primed(this.meta.gop());
			boolean waitedTooLong = System.nanoTime() - this.waitStart > 3_000_000_000L;
			if (ready || waitedTooLong) {
				this.started = true;
				this.playStart = System.nanoTime();
				// master volume, not music: many players turn the music slider off
				float volume = this.minecraft.options.getFinalSoundSourceVolume(SoundSource.MASTER);
				if (this.audio != null) {
					this.audio.start(volume);
				} else {
					this.silentStart = System.nanoTime();
				}
			}
		} else if (this.time() >= this.length || (System.nanoTime() - this.playStart) / 1e9 > this.length + 10) {
			// the wall-clock limit keeps an unskippable intro from hanging if audio stalls
			this.finish();
		}
	}

	private double time() {
		if (this.audio != null) {
			return this.audio.time();
		}
		return this.silentStart < 0 ? 0.0 : (System.nanoTime() - this.silentStart) / 1e9;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xFF000000);
		if (!this.started || this.texture == null) {
			return;
		}
		double t = this.time();
		int index = (int) Math.floor(t * this.meta.fps());
		IntroDecoder.Frame frame = this.decoder.frame(Math.min(index, this.meta.frames() - 1));
		if (frame != null) {
			Yuv.toAbgr(frame, this.rgba);
			if (this.lastShown >= 0 && frame.index > this.lastShown + 1) {
				this.droppedFrames += frame.index - this.lastShown - 1;
			}
			this.lastShown = frame.index;
			this.shownFrames++;
			this.decoder.release(frame);
			long ptr = this.texture.getPixels().getPointer();
			MemoryUtil.memIntBuffer(ptr, this.rgba.length).put(this.rgba);
			this.texture.upload();
			this.hasFrame = true;
		}
		if (this.hasFrame) {
			// cover the screen, keep aspect
			float scale = Math.max(this.width / (float) this.meta.width(), this.height / (float) this.meta.height());
			int w = Math.round(this.meta.width() * scale);
			int h = Math.round(this.meta.height() * scale);
			int x = (this.width - w) / 2;
			int y = (this.height - h) / 2;
			float alpha = (float) Math.max(0.0, Math.min(1.0, (this.length - t) / FADE_OUT));
			graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F, w, h, this.meta.width(), this.meta.height(), this.meta.width(), this.meta.height(), ARGB.white(alpha));
		}
		if (INTRO_TEST && !this.shot && t > 3.0 && this.hasFrame) {
			this.shot = true;
			net.minecraft.client.Screenshot.grab(this.minecraft.gameDirectory, "minehouse-intro.png", this.minecraft.gameRenderer.mainRenderTarget(), 1,
				msg -> MinehouseMenu.LOG.info("Intro screenshot: {}", msg.getString()));
			MinehouseMenu.LOG.info("MINEHOUSE_INTRO_OK t={} frame={}", t, index);
		}
		if (t > 1.5 && t < this.length - 2.0) {
			String hint = "Любая клавиша — пропустить";
			int a = (int) (Math.min(1.0, (t - 1.5) / 0.5) * 110);
			graphics.text(this.font, hint, this.width - this.font.width(hint) - 6, this.height - 14, ARGB.color(a, 0xFFFFFF), true);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// the video is the background
	}

	/** Linear filtering: the video is scaled to the window, nearest would look pixelated. */
	private static final class VideoTexture extends DynamicTexture {
		VideoTexture(int width, int height) {
			super(() -> "minehouse intro", width, height, false);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.skippable) {
			this.finish();
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.skippable) {
			this.finish();
		}
		return true;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return this.skippable;
	}

	@Override
	public void onClose() {
		if (this.skippable) {
			this.finish();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void finish() {
		if (this.finished) {
			return;
		}
		this.finished = true;
		this.release();
		this.then.run();
	}

	@Override
	public void removed() {
		this.release();
	}

	private void release() {
		if (this.decoder != null) {
			if (!this.released) {
				MinehouseMenu.LOG.info("Intro done: played {} s, {} frames shown, {} dropped (late); decoder {}",
					String.format("%.1f", this.started ? this.time() : 0.0), this.shownFrames, this.droppedFrames, this.decoder.stats());
			}
			this.decoder.close();
		}
		if (this.audio != null) {
			this.audio.close();
		}
		this.released = true;
		if (this.texture != null) {
			this.minecraft.getTextureManager().release(TEXTURE);
			this.texture = null;
		}
	}
}
