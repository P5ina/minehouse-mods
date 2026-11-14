package pro.minehouse.riftclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** The client half of the Rift mode: the view from above, its cursor and controls, and the mode's interface. */
public final class MinehouseRiftClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("minehouse-rift-client");

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(RiftViewPayload.TYPE, RiftViewPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(RiftViewPayload.TYPE, (payload, context) -> TopDown.apply(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TopDown.stop());
		ClientTickEvents.END_CLIENT_TICK.register(TopDown::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DownedAnim::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minehouse-rift", "cursor"), MinehouseRiftClient::cursor);

		// The mode's own interface: nothing of it goes to the chat.
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Note.TYPE, RiftUiPayloads.Note.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Lobby.TYPE, RiftUiPayloads.Lobby.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Roster.TYPE, RiftUiPayloads.Roster.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Run.TYPE, RiftUiPayloads.Run.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Result.TYPE, RiftUiPayloads.Result.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RiftUiPayloads.Action.TYPE, RiftUiPayloads.Action.CODEC);
		// The fight: the roll and the abilities, their cooldowns, the warnings on the floor.
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Cooldown.TYPE, RiftUiPayloads.Cooldown.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Warn.TYPE, RiftUiPayloads.Warn.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RiftUiPayloads.Cast.TYPE, RiftUiPayloads.Cast.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Cooldown.TYPE, (payload, context) -> Combat.cooldown(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Warn.TYPE, (payload, context) -> Combat.warn(payload));
		ClientTickEvents.START_CLIENT_TICK.register(Combat::keys);
		ClientTickEvents.END_CLIENT_TICK.register(Combat::tick);
		// The lobby's camera is ours: the server only says where it stands.
		PayloadTypeRegistry.clientboundPlay().register(RiftUiPayloads.Scene.TYPE, RiftUiPayloads.Scene.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Scene.TYPE, (payload, context) -> TopDown.scene(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Note.TYPE, (payload, context) -> RiftHud.note(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Lobby.TYPE, (payload, context) -> RiftHud.lobby(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Roster.TYPE, (payload, context) -> RiftHud.roster(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Run.TYPE, (payload, context) -> RiftHud.run(payload));
		ClientPlayNetworking.registerGlobalReceiver(RiftUiPayloads.Result.TYPE, (payload, context) -> RiftHud.result(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RiftHud.clear());
		// Every arrival on a server starts clean: the proxy moves us between servers without a
		// disconnect, and what the last one showed must not stay on the screen.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RiftHud.leftServer());
		ClientTickEvents.END_CLIENT_TICK.register(RiftHud::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minehouse-rift", "hud"), RiftHud::draw);
		// In a run our own health stands in the corner: no hearts, no armour row. The lobby is a
		// screen over a scene: nothing of the usual game interface belongs there.
		for (Identifier vanilla : new Identifier[] {VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR}) {
			HudElementRegistry.replaceElement(vanilla, old -> (g, delta) -> {
				if (!RiftHud.inRun() && !RiftHud.lobby().inLobby()) {
					old.extractRenderState(g, delta);
				}
			});
		}
		for (Identifier vanilla : new Identifier[] {VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.FOOD_BAR,
				VanillaHudElements.AIR_BAR, VanillaHudElements.INFO_BAR, VanillaHudElements.INFO_BAR_FOREGROUND, VanillaHudElements.EXPERIENCE_LEVEL,
				VanillaHudElements.HELD_ITEM_TOOLTIP}) {
			HudElementRegistry.replaceElement(vanilla, old -> (g, delta) -> {
				if (!RiftHud.lobby().inLobby()) {
					old.extractRenderState(g, delta);
				}
			});
		}

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
