package pro.minehouse.trailer;

import java.io.IOException;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * xvfb-run ./gradlew runSelftestClient: creates a fresh world, plays the whole trailer, takes a
 * screenshot in every shot and prints MINEHOUSE_TRAILER_OK when it ends cleanly.
 */
final class SelfTest {
	private static int ticks;
	private static int inWorld;
	private static boolean creating;
	private static int shotsTaken;
	private static boolean played;
	private static int stoppedAt = -1;
	private static final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();

	private SelfTest() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void tick(Minecraft mc) {
		ticks++;
		try {
			if (!creating && mc.gui.screen() instanceof TitleScreen && ticks > 40) {
				creating = true;
				LevelSettings settings = new LevelSettings("Trailer selftest", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true,
						WorldDataConfiguration.DEFAULT);
				String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), "Trailer selftest", "");
				mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(Long.getLong("minehouse.seed", 126L), false, false),
						WorldPresets::createNormalWorldDimensions, new TitleScreen());
				return;
			}
			if (mc.player == null || mc.level == null) {
				return;
			}
			inWorld++;
			if (inWorld == 150) {
				// Sheep eat grass, which would look like blocks the trailer failed to put back.
				var server = mc.getSingleplayerServer();
				server.execute(() -> {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule spawn_mobs false");
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=!player]");
				});
			}
			if (inWorld == 200) {
				Trailer.origin = mc.player.blockPosition();
				snapshot(mc, before);
				String error = Trailer.play(Double.parseDouble(System.getProperty("minehouse.fromBar", "0")));
				MinehouseTrailer.LOG.info("Selftest: play at {} -> {}", Trailer.origin.toShortString(), error == null ? "ok" : error);
				played = error == null;
				if (!played) {
					finish(mc, "play failed: " + error);
				}
				return;
			}
			if (played && Trailer.playing()) {
				double t = Trailer.now();
				double[] at = {0.8, 2.6, 5.7, 9.5, 12.4, 13.8, 14.3, 14.8, 15.6, 16.6, 17.6, 18.4, 19.4, 20.3, 21.4, 22.3, 23.3, 24.2, 26.5, 29.5};
				if (shotsTaken < at.length && t >= at[shotsTaken]) {
					String name = String.format("trailer_%02d.png", shotsTaken);
					Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> { });
					MinehouseTrailer.LOG.info("Selftest: screenshot {} at {}s", name, String.format("%.2f", t));
					shotsTaken++;
				}
			} else if (played && !Trailer.playing()) {
				if (stoppedAt < 0) {
					stoppedAt = inWorld;
				} else if (inWorld - stoppedAt == 40) {
					java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> after = new java.util.HashMap<>();
					snapshot(mc, after);
					long changed = before.entrySet().stream().filter(e -> !e.getValue().equals(after.get(e.getKey()))).count();
					MinehouseTrailer.LOG.info("Selftest: {} of {} blocks differ after the trailer", changed, before.size());
					before.entrySet().stream().filter(e -> !e.getValue().equals(after.get(e.getKey()))).limit(40)
							.forEach(e -> MinehouseTrailer.LOG.info("Selftest: diff {} {} -> {}", e.getKey().toShortString(), e.getValue(), after.get(e.getKey())));
					finish(mc, shotsTaken < 5 ? "only " + shotsTaken + " screenshots" : changed > 0 ? changed + " blocks not restored" : null);
				}
			}
		} catch (IOException | RuntimeException e) {
			MinehouseTrailer.LOG.error("MINEHOUSE_TRAILER_FAIL", e);
			mc.stop();
		}
	}

	/** Blocks around every place the trailer builds something. */
	private static void snapshot(Minecraft mc, java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> into) {
		double[][] spots = {{0, 0}, {8, -4}, {-22, 10}, {30, -24}, {22, 22}, {-16, -18}, {-8, -8}};
		for (double[] spot : spots) {
			net.minecraft.core.BlockPos c = net.minecraft.core.BlockPos.containing(Trailer.at(spot[0], spot[1]));
			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -6; dz <= 6; dz++) {
					for (int dy = -4; dy <= 8; dy++) {
						net.minecraft.core.BlockPos p = c.offset(dx, dy, dz);
						into.put(p, mc.level.getBlockState(p));
					}
				}
			}
		}
	}

	private static void finish(Minecraft mc, String problem) {
		MinehouseTrailer.LOG.info(problem == null ? "MINEHOUSE_TRAILER_OK" : "MINEHOUSE_TRAILER_FAIL: " + problem);
		played = false;
		mc.stop();
	}
}
