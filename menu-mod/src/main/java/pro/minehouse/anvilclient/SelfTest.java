package pro.minehouse.anvilclient;

import org.spongepowered.asm.mixin.MixinEnvironment;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * CI check (./gradlew runSelftestClient -Pselftest=anvil under Xvfb): once the client has started, applies every
 * mixin (a missing injection point fails here, not when a player opens an anvil), checks that the
 * cost packet has a receiver and prints MINEHOUSE_ANVIL_CLIENT_OK.
 */
final class SelfTest {
	private static int ticks;

	private SelfTest() {
	}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			MixinEnvironment.getCurrentEnvironment().audit();
			MinehouseAnvilClient.LOG.info("Mixin audit done");
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++ticks != 100) {
				return;
			}

			boolean receiver = ClientPlayNetworking.getGlobalReceivers().contains(AnvilCostPayload.TYPE.id());
			AnvilCost.set(7, 55);
			boolean logic = AnvilCost.real(7, 39) == 55 && AnvilCost.real(7, 12) == -1 && AnvilCost.real(8, 39) == -1;
			AnvilCost.clear();

			MinehouseAnvilClient.LOG.info(receiver && logic ? "MINEHOUSE_ANVIL_CLIENT_OK"
					: "MINEHOUSE_ANVIL_CLIENT_FAIL: receiver " + receiver + ", logic " + logic);
			client.stop();
		});
	}
}
