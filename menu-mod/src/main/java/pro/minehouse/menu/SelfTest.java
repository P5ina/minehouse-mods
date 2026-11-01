package pro.minehouse.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * CI check (./gradlew runSelftestClient under Xvfb): waits on the title screen,
 * saves a screenshot to run-selftest/screenshots and closes the game.
 * Prints MINEHOUSE_MENU_OK only if our layout was applied.
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
			Screenshot.grab(minecraft.gameDirectory, "minehouse-menu.png", minecraft.gameRenderer.mainRenderTarget(), 1, msg -> MinehouseMenu.LOG.info("Screenshot: {}", msg.getString()));
		} else if (ticks == 240) {
			MinehouseMenu.LOG.info(layoutApplied ? "MINEHOUSE_MENU_OK" : "MINEHOUSE_MENU_FAIL: layout not applied");
			minecraft.stop();
		}
	}
}
