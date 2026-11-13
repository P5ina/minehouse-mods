package pro.minehouse.menu.wardrobe;

import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import pro.minehouse.menu.MinehouseMenu;


/** The wardrobe: key O (or «Гардероб» in the server menu) on servers with MineHouse Cosmetics. */
public final class Wardrobe {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minehouse", "keys"));
	private static KeyMapping key;

	private Wardrobe() {
	}

	public static void init() {
		WardrobeNet.init();
		WardrobeSelf.init();
		PictureInPictureRendererRegistry.register(context -> new WardrobePreview());
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minehouse.wardrobe", InputConstants.KEY_O, CATEGORY));
		ClientLifecycleEvents.CLIENT_STARTED.register(Wardrobe::freeKeyO);
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

	/**
	 * Vanilla puts the friends list on O, and it does nothing on our server. Unbind it once, so O
	 * opens only the wardrobe; a player who binds it again afterwards keeps their choice.
	 */
	private static void freeKeyO(Minecraft client) {
		Path marker = FabricLoader.getInstance().getConfigDir().resolve("minehouse-menu/friends-key-freed");
		if (Files.exists(marker)) {
			return;
		}
		KeyMapping friends = client.options.keyFriends;
		if (friends.isDefault()) {
			friends.setKey(InputConstants.UNKNOWN);
			KeyMapping.resetMapping();
			client.options.save();
			MinehouseMenu.LOG.info("Unbound vanilla key.friends from O for the wardrobe");
		}
		try {
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "key.friends unbound from O by MineHouse Menu\n");
		} catch (IOException e) {
			MinehouseMenu.LOG.warn("Can't write {}", marker, e);
		}
	}
}
