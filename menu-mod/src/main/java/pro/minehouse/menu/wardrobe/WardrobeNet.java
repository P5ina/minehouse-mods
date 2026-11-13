package pro.minehouse.menu.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import pro.minehouse.menu.MinehouseMenu;

/**
 * The wardrobe channel of the MineHouse Cosmetics server mod. One JSON string each way; the
 * format is documented in cosmetics-mod {@code WardrobeNet}.
 */
public final class WardrobeNet {
	static final String NS = "minehouse-cosmetics";

	public record StatePayload(String json) implements CustomPacketPayload {
		static final Type<StatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "state"));
		static final StreamCodec<FriendlyByteBuf, StatePayload> CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(StatePayload::new, StatePayload::json).cast();

		@Override
		public Type<StatePayload> type() {
			return TYPE;
		}
	}

	public record ActionPayload(String json) implements CustomPacketPayload {
		static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "action"));
		static final StreamCodec<FriendlyByteBuf, ActionPayload> CODEC = ByteBufCodecs.stringUtf8(4096).map(ActionPayload::new, ActionPayload::json).cast();

		@Override
		public Type<ActionPayload> type() {
			return TYPE;
		}
	}

	public record Item(String id, String slot, String name, int price, boolean owned) {
	}

	public record State(int coins, String store, List<Item> items, Map<String, String> worn) {
		Item item(String id) {
			for (Item item : items) {
				if (item.id().equals(id)) {
					return item;
				}
			}
			return null;
		}
	}

	/** The last state the server sent, or null on servers without the wardrobe. */
	static State state;

	private WardrobeNet() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC.cast());
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC.cast());
		ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> receive(context.client(), payload.json()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = null);
	}

	public static boolean available() {
		return ClientPlayNetworking.canSend(ActionPayload.TYPE);
	}

	static void send(String action, String key, String value) {
		if (!available()) {
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		if (key != null) {
			o.addProperty(key, value);
		}
		ClientPlayNetworking.send(new ActionPayload(o.toString()));
	}

	private static void receive(Minecraft client, String json) {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			MinehouseMenu.LOG.warn("Bad wardrobe state from the server", e);
			return;
		}
		List<Item> items = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("items")) {
			JsonObject o = e.getAsJsonObject();
			items.add(new Item(o.get("id").getAsString(), o.get("slot").getAsString(), o.get("name").getAsString(),
					o.get("price").getAsInt(), o.get("owned").getAsBoolean()));
		}
		Map<String, String> worn = new LinkedHashMap<>();
		root.getAsJsonObject("worn").entrySet().forEach(en -> worn.put(en.getKey(), en.getValue().getAsString()));
		state = new State(root.get("coins").getAsInt(), root.get("store").getAsString(), List.copyOf(items), Map.copyOf(worn));

		if (client.gui.screen() instanceof WardrobeScreen screen) {
			screen.update(state);
		} else if (root.get("open").getAsBoolean()) {
			client.gui.setScreen(new WardrobeScreen(state));
		}
	}
}
