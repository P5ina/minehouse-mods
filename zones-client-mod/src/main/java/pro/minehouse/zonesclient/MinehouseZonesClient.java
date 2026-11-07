package pro.minehouse.zonesclient;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.gizmos.Gizmos;

public final class MinehouseZonesClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("minehouse-zones-client");

	/** Shapes from the last packet; replaced as a whole, read on the client thread only. */
	static volatile List<ZoneShapesPayload.Shape> shapes = List.of();

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(ZoneShapesPayload.TYPE, ZoneShapesPayload.CODEC);
		// Registering the receiver is also how the server learns this client has the mod
		// (and stops sending particles).
		ClientPlayNetworking.registerGlobalReceiver(ZoneShapesPayload.TYPE, (payload, context) -> shapes = List.copyOf(payload.shapes()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> shapes = List.of());

		// Per-tick gizmos: the game keeps the last tick's set and draws it every frame, in any debug mode.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			List<ZoneShapesPayload.Shape> current = shapes;
			if (current.isEmpty() || client.player == null || client.level == null) return;
			try (Gizmos.TemporaryCollection ignored = client.collectPerTickGizmos()) {
				ZoneRenderer.draw(current, client.player.position());
			}
		});

		if (Boolean.getBoolean("minehouse.selftest")) {
			SelfTest.register();
		}
		if (Boolean.getBoolean("minehouse.zones.demo")) {
			Demo.register();
		}
	}
}
