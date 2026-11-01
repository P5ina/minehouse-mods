package pro.minehouse.menu;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinehouseMenu implements ClientModInitializer {
	public static final String MOD_ID = "minehouse-menu";
	public static final Logger LOG = LoggerFactory.getLogger("MineHouse Menu");

	@Override
	public void onInitializeClient() {
		MenuConfig.load();
		PackUpdater.start();
	}
}
