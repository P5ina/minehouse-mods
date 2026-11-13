package pro.minehouse.menu.intro;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import pro.minehouse.menu.MenuConfig;
import pro.minehouse.menu.MinehouseMenu;

/**
 * The cinematic intro shown once before the title screen.
 *
 * Files come with the modpack (and its auto-update) into config/minehouse-intro/:
 * intro.json (what to play), intro.mp4 (H.264, fixed GOP, no B-frames) and intro.ogg.
 * It plays once per intro version; config/minehouse-intro-played.txt remembers which one
 * was shown. Without the files nothing happens.
 */
public final class Intro {
	/** config/minehouse-intro/intro.json */
	public record Meta(String version, int fps, int gop, int frames, int width, int height, String video, String audio) {
	}

	private static final Gson GSON = new Gson();
	private static boolean checked;

	private Intro() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("minehouse-intro");
	}

	private static Path playedFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("minehouse-intro-played.txt");
	}

	public static Meta meta() {
		try {
			Meta m = GSON.fromJson(Files.readString(dir().resolve("intro.json"), StandardCharsets.UTF_8), Meta.class);
			if (m == null || m.frames() <= 0 || m.gop() <= 0 || m.fps() <= 0 || m.video() == null) {
				return null;
			}
			if (!Files.isRegularFile(dir().resolve(m.video()))) {
				return null;
			}
			return m;
		} catch (Exception e) {
			return null;
		}
	}

	/** Called when the title screen is about to open: the intro to show first, or null. Consumed once per session. */
	public static Meta takePending() {
		if (checked) {
			return null;
		}
		checked = true;
		if (!MenuConfig.introEnabled || (System.getProperty("minehouse.selftest") != null && !Boolean.getBoolean("minehouse.introtest"))) {
			return null;
		}
		Meta m = meta();
		if (m == null) {
			return null;
		}
		try {
			if (Files.exists(playedFile()) && Files.readString(playedFile(), StandardCharsets.UTF_8).trim().equals(m.version())) {
				return null;
			}
		} catch (Exception e) {
			// unreadable: show it
		}
		return m;
	}

	public static void markPlayed(Meta m) {
		try {
			Files.writeString(playedFile(), m.version(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			MinehouseMenu.LOG.warn("Can't write {}", playedFile(), e);
		}
	}
}
