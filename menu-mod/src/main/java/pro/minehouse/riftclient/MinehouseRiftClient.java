package pro.minehouse.riftclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** The client half of the Rift mode: the view from above, its cursor and controls. */
public final class MinehouseRiftClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("minehouse-rift-client");

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(RiftViewPayload.TYPE, RiftViewPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(RiftViewPayload.TYPE, (payload, context) -> TopDown.apply(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TopDown.stop());
		ClientTickEvents.END_CLIENT_TICK.register(TopDown::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minehouse-rift", "cursor"), MinehouseRiftClient::cursor);

		if ("rift".equals(System.getProperty("minehouse.selftest"))) {
			SelfTest.register();
		}
	}

	/** The cursor on the ground: a ring with a dot, where the character looks and will strike. */
	private static void cursor(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!TopDown.active() || mc.gui.screen() != null) {
			return;
		}
		double half = g.guiHeight() / 2.0;
		int x = (int) Math.round(g.guiWidth() / 2.0 + TopDown.cursorX() * half);
		int y = (int) Math.round(half - TopDown.cursorY() * half);
		int dark = 0xC0101018;
		int light = 0xFFF2E6FF;
		// Four ticks around a dot, with a dark edge so it reads on any floor.
		for (int[] t : new int[][] {{-7, 0, -3, 1}, {4, 0, 8, 1}, {0, -7, 1, -3}, {0, 4, 1, 8}}) {
			g.fill(x + t[0] - 1, y + t[1] - 1, x + t[2] + 1, y + t[3] + 1, dark);
		}
		g.fill(x - 1, y - 1, x + 2, y + 2, dark);
		for (int[] t : new int[][] {{-7, 0, -3, 1}, {4, 0, 8, 1}, {0, -7, 1, -3}, {0, 4, 1, 8}}) {
			g.fill(x + t[0], y + t[1], x + t[2], y + t[3], light);
		}
		g.fill(x, y, x + 1, y + 1, light);
	}
}
