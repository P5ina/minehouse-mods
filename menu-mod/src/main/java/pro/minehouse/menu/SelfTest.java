package pro.minehouse.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * CI check (./gradlew runSelftestClient under Xvfb): waits on the title screen,
 * saves a screenshot to run-selftest/screenshots and closes the game.
 * Prints MINEHOUSE_MENU_OK only if our layout was applied. With a
 * config/minehouse-pack.json in the run dir it then opens «Моды сборки» and
 * screenshots it too.
 */
public final class SelfTest {
	private static final boolean ENABLED = Boolean.getBoolean("minehouse.selftest");
	private static int ticks;

	private SelfTest() {
	}

	public static void onTitleTick(Minecraft minecraft, boolean layoutApplied) {
		if (!ENABLED) {
			return;
		}
		ticks++;
		if (ticks == 200) {
			shot(minecraft, "minehouse-menu.png");
		} else if (ticks == 240) {
			MinehouseMenu.LOG.info(layoutApplied ? "MINEHOUSE_MENU_OK" : "MINEHOUSE_MENU_FAIL: layout not applied");
			if (PackUpdater.managed()) {
				minecraft.gui.setScreen(new PackModsScreen(minecraft.gui.screen()));
			} else {
				minecraft.stop();
			}
		}
	}

	public static void onPackModsTick(Minecraft minecraft) {
		if (!ENABLED) {
			return;
		}
		ticks++;
		if (ticks == 320) {
			shot(minecraft, "minehouse-pack-mods.png");
		} else if (ticks == 360) {
			minecraft.stop();
		}
	}

	private static void shot(Minecraft minecraft, String name) {
		Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1, msg -> MinehouseMenu.LOG.info("Screenshot: {}", msg.getString()));
	}
}
