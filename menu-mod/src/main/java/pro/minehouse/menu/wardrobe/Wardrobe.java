package pro.minehouse.menu.wardrobe;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


/** The wardrobe: key O (or «Гардероб» in the server menu) on servers with MineHouse Cosmetics. */
public final class Wardrobe {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minehouse", "keys"));
	private static KeyMapping key;

	private Wardrobe() {
	}

	public static void init() {
		WardrobeNet.init();
		PictureInPictureRendererRegistry.register(context -> new WardrobePreview());
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minehouse.wardrobe", InputConstants.KEY_O, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) {
				if (client.player == null) {
					continue;
				}
				if (WardrobeNet.available()) {
					WardrobeNet.send("open", null, null);
				} else {
					client.player.sendOverlayMessage(Component.literal("На этом сервере нет гардероба"));
				}
			}
		});
	}
}
