package pro.minehouse.eventseditor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Client half of the show editor. Does nothing for players: the server only talks to it for
 * admins (it says "hello" on join), and the K key only opens anything after that.
 */
public final class MinehouseEventsEditor implements ClientModInitializer {
	public static final String MOD_ID = "minehouse-events-editor";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static KeyMapping panelKey;

	static KeyMapping panelKey() {
		return panelKey;
	}

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
		// The receiver is also how the server learns this client can edit.
		ClientPlayNetworking.registerGlobalReceiver(EditorPayload.TYPE, (payload, context) -> EditorClient.receive(payload.json()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(EditorClient::reset));

		panelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".panel", InputConstants.KEY_K,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "editor"))));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), EditorHud::extract);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (panelKey.consumeClick()) {
				openPanel(client);
			}
			EditorClient.tick();
			if (client.level != null) {
				WorldOverlay.draw();
			}
		});

		if (Boolean.getBoolean("minehouse.selftest")) {
			SelfTest.register();
		}
	}

	private static void openPanel(Minecraft client) {
		if (client.gui.screen() != null) {
			return;
		}
		if (EditorClient.editing()) {
			client.gui.setScreen(new EditorScreen());
		} else {
			// Always ask the server: rights can change after joining (op, LuckPerms), and the
			// server's greeting on join can come before it knows this client has the editor.
			EditorClient.askToOpen();
		}
	}
}
