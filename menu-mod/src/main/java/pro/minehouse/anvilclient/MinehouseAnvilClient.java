package pro.minehouse.anvilclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class MinehouseAnvilClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("minehouse-anvil-client");

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(AnvilCostPayload.TYPE, AnvilCostPayload.CODEC);
		// Registering the receiver is also how the server learns this client has the mod
		// (and stops writing the cost to the action bar).
		ClientPlayNetworking.registerGlobalReceiver(AnvilCostPayload.TYPE,
				(payload, context) -> AnvilCost.set(payload.containerId(), payload.cost()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AnvilCost.clear());

		if ("anvil".equals(System.getProperty("minehouse.selftest"))) {
			SelfTest.register();
		}
	}
}
