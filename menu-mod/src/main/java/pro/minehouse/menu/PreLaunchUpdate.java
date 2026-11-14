package pro.minehouse.menu;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Checks for a pack update before Minecraft loads, see {@link PackUpdater#preLaunch}. */
public final class PreLaunchUpdate implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		try {
			MenuConfig.load();
			PackUpdater.preLaunch();
		} catch (Throwable t) {
			MinehouseMenu.LOG.warn("Pre-launch update check failed", t);
		}
	}
}
