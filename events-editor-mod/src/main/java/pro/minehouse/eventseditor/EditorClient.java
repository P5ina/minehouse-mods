package pro.minehouse.eventseditor;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * What the client knows about the editing session: the server owns the show and sends it back
 * whole after every change, the client only remembers what is selected and shows it.
 */
public final class EditorClient {
	/** The server has the editor and this player may use it. */
	static boolean admin;
	static List<String> shows = new ArrayList<>();

	/** The last "state" from the server; null when not editing. */
	static JsonObject state;
	static int tick;
	static boolean playing;

	/** Selected cue ({@code track} and {@code index}), or an actor by name, or nothing. */
	static String selTrack;
	static int selIndex = -1;
	static String selActor;
	/** Selected node of the logic, by id. */
	static String selNode;
	/** The «Логика» tab is open instead of the timeline tools. */
	static boolean logicTab;
	/** When each node last fired in the preview, to light it up. */
	static final java.util.Map<String, Long> fired = new java.util.HashMap<>();
	/** The preview's timeline is held by a «Пауза» node. */
	static boolean logicPaused;

	/** K was pressed outside the editor: open the show list when the server answers. */
	static long openAsked;

	static String toast;
	static long toastUntil;
	/** Bumped whenever the state changes, so the screen knows to refresh its fields. */
	static int version;

	private EditorClient() {
	}

	static boolean editing() {
		return state != null;
	}

	static void reset() {
		admin = false;
		shows = new ArrayList<>();
		state = null;
		clearSelection();
		version++;
	}

	static void clearSelection() {
		selTrack = null;
		selIndex = -1;
		selActor = null;
		selNode = null;
	}

	static void receive(String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			MinehouseEventsEditor.LOG.warn("Bad editor message: {}", e.toString());
			return;
		}
		String type = CueSchema.str(o, "type", "");
		switch (type) {
			case "hello" -> {
				admin = true;
				shows = new ArrayList<>();
				if (o.has("shows")) {
					o.getAsJsonArray("shows").forEach(e -> shows.add(e.getAsString()));
				}
				shows.sort(null);
				if (openAsked != 0) {
					openAsked = 0;
					Minecraft mc = Minecraft.getInstance();
					if (mc.gui.screen() == null) {
						mc.gui.setScreen(new ShowPickerScreen());
					}
				}
			}
			case "state" -> {
				boolean opened = state == null || !CueSchema.str(state, "show", "").equals(CueSchema.str(o, "show", ""));
				admin = true;
				state = o;
				tick = o.has("tick") ? o.get("tick").getAsInt() : 0;
				playing = o.has("playing") && o.get("playing").getAsBoolean();
				if (opened) {
					clearSelection();
				}
				if (o.has("select") && o.get("select").isJsonObject()) {
					JsonObject s = o.getAsJsonObject("select");
					clearSelection();
					if (s.has("node")) {
						selNode = s.get("node").getAsString();
					} else {
						selTrack = s.get("track").getAsString();
						selIndex = s.get("index").getAsInt();
					}
				}
				if (selNode != null && !nodes().has(selNode)) {
					clearSelection();
				}
				if (selTrack != null && selectedCue() == null) {
					clearSelection();
				}
				if (selActor != null && !actors().has(selActor)) {
					clearSelection();
				}
				version++;
				if (opened) {
					Minecraft mc = Minecraft.getInstance();
					mc.gui.setScreen(new EditorScreen());
				}
			}
			case "tick" -> {
				tick = o.get("tick").getAsInt();
				playing = o.get("playing").getAsBoolean();
				logicPaused = o.has("paused") && o.get("paused").getAsBoolean();
				if (o.has("fired")) {
					long now = System.currentTimeMillis();
					o.getAsJsonArray("fired").forEach(e -> fired.put(e.getAsString(), now));
				}
			}
			case "closed" -> {
				state = null;
				clearSelection();
				version++;
				Minecraft mc = Minecraft.getInstance();
				if (mc.gui.screen() instanceof EditorScreen) {
					mc.gui.setScreen(null);
				}
			}
			case "error" -> toast("§c" + CueSchema.str(o, "text", "ошибка"));
			default -> {
			}
		}
	}

	static void askToOpen() {
		if (!ClientPlayNetworking.canSend(EditorPayload.TYPE)) {
			toast("§7На этом сервере нет редактора шоу");
			return;
		}
		openAsked = System.currentTimeMillis();
		simple("hello");
	}

	/** No answer to K: the server only answers admins. */
	static void tick() {
		if (openAsked != 0 && System.currentTimeMillis() - openAsked > 2000) {
			openAsked = 0;
			admin = false;
			toast("§7Редактор шоу только для админов (minehouse.events.command или оператор)");
		}
	}

	static void toast(String text) {
		toast = text;
		toastUntil = System.currentTimeMillis() + 6000;
	}

	static void send(JsonObject op) {
		String json = op.toString();
		if (json.length() > 30000) {
			toast("§cСлишком большое изменение, разбейте его на части");
			return;
		}
		if (ClientPlayNetworking.canSend(EditorPayload.TYPE)) {
			ClientPlayNetworking.send(new EditorPayload(json));
		} else {
			toast("§cНа этом сервере нет редактора шоу");
		}
	}

	static JsonObject op(String name) {
		JsonObject o = new JsonObject();
		o.addProperty("op", name);
		return o;
	}

	static void simple(String name) {
		send(op(name));
	}

	static void seek(int toTick, boolean play) {
		JsonObject o = op("seek");
		o.addProperty("tick", Math.max(0, toTick));
		o.addProperty("play", play);
		tick = Math.max(0, toTick);
		playing = play;
		send(o);
	}

	// --- Reading the state ---------------------------------------------------------------------

	static JsonObject doc() {
		return state != null && state.has("doc") ? state.getAsJsonObject("doc") : new JsonObject();
	}

	static JsonObject tracks() {
		JsonObject doc = doc();
		return doc.has("tracks") && doc.get("tracks").isJsonObject() ? doc.getAsJsonObject("tracks") : new JsonObject();
	}

	static JsonObject actors() {
		JsonObject doc = doc();
		return doc.has("actors") && doc.get("actors").isJsonObject() ? doc.getAsJsonObject("actors") : new JsonObject();
	}

	static JsonObject logic() {
		JsonObject doc = doc();
		return doc.has("logic") && doc.get("logic").isJsonObject() ? doc.getAsJsonObject("logic") : new JsonObject();
	}

	static JsonObject nodes() {
		JsonObject logic = logic();
		return logic.has("nodes") && logic.get("nodes").isJsonObject() ? logic.getAsJsonObject("nodes") : new JsonObject();
	}

	static JsonArray links() {
		JsonObject logic = logic();
		return logic.has("links") && logic.get("links").isJsonArray() ? logic.getAsJsonArray("links") : new JsonArray();
	}

	static JsonObject selectedNode() {
		return selNode != null && nodes().get(selNode) instanceof JsonObject n ? n : null;
	}

	static List<String> trackNames() {
		return new ArrayList<>(tracks().keySet());
	}

	static JsonArray track(String name) {
		JsonElement e = tracks().get(name);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	static JsonObject cue(String track, int index) {
		JsonArray t = track(track);
		if (index < 0 || index >= t.size() || !t.get(index).isJsonObject()) {
			return null;
		}
		return t.get(index).getAsJsonObject();
	}

	static JsonObject selectedCue() {
		return selTrack == null ? null : cue(selTrack, selIndex);
	}

	static String show() {
		return state == null ? "" : CueSchema.str(state, "show", "");
	}

	static int length() {
		return state != null && state.has("length") ? Math.max(state.get("length").getAsInt(), 1) : 1;
	}

	static String error() {
		return state != null && state.has("error") ? state.get("error").getAsString() : null;
	}

	static boolean flag(String key) {
		return state != null && state.has(key) && state.get(key).getAsBoolean();
	}

	static int count(String key) {
		return state != null && state.has(key) ? state.get(key).getAsInt() : 0;
	}

	/** Stage origin in the world, or null when the show has no place yet. */
	static Vec3 stage() {
		if (state == null || !state.has("stage")) {
			return null;
		}
		return vec(state.getAsJsonObject("stage").getAsJsonArray("pos"));
	}

	static Vec3 vec(JsonArray a) {
		return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	/** Where the camera (or player) is, relative to the stage, rounded to a tenth. */
	static double[] here() {
		Minecraft mc = Minecraft.getInstance();
		Vec3 origin = stage();
		Vec3 p = mc.getCameraEntity() != null ? mc.getCameraEntity().position() : Vec3.ZERO;
		if (origin != null) {
			p = p.subtract(origin);
		}
		return new double[] { round(p.x), round(p.y), round(p.z) };
	}

	static float yaw() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getCameraEntity() != null ? Math.round(mc.getCameraEntity().getYRot()) : 0;
	}

	static float pitch() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getCameraEntity() != null ? Math.round(mc.getCameraEntity().getXRot()) : 0;
	}

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
