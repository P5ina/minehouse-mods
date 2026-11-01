package pro.minehouse.eventseditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the show into the world while editing: the stage, the meteor spots, every cue's positions
 * and camera flights. The selected cue is yellow, the rest faint blue. Called every client tick,
 * which is when the game collects these shapes.
 */
final class WorldOverlay {
	private static final int SELECTED = 0xFFFFD84A;
	private static final int OTHER = 0x9060C8FF;
	private static final int STAGE = 0xFF50FF80;
	private static final int SPOT = 0xFFFF8040;

	private WorldOverlay() {
	}

	static void draw() {
		JsonObject state = EditorClient.state;
		if (state == null) {
			return;
		}
		Vec3 stage = EditorClient.stage();
		if (stage != null) {
			Gizmos.cuboid(new AABB(stage.add(-0.5, 0, -0.5), stage.add(0.5, 0.1, 0.5)), GizmoStyle.stroke(STAGE));
			Gizmos.billboardText("сцена " + EditorClient.show(), stage.add(0, 0.6, 0), TextGizmo.Style.forColorAndCentered(STAGE));
		}
		if (state.has("spots") && state.get("spots").isJsonObject()) {
			for (var e : state.getAsJsonObject("spots").entrySet()) {
				try {
					Vec3 p = EditorClient.vec(e.getValue().getAsJsonObject().getAsJsonArray("pos"));
					Gizmos.line(p, p.add(0, 6, 0), SPOT, 3);
					Gizmos.billboardText("☄ " + e.getKey(), p.add(0, 6.5, 0), TextGizmo.Style.forColorAndCentered(SPOT));
				} catch (RuntimeException ignored) {
					// spot from another dimension or broken: skip
				}
			}
		}
		if (!state.has("geo") || !state.get("geo").isJsonObject()) {
			return;
		}
		String selected = EditorClient.selTrack == null ? null : EditorClient.selTrack + "/" + EditorClient.selIndex;
		for (var e : state.getAsJsonObject("geo").entrySet()) {
			boolean sel = e.getKey().equals(selected);
			int color = sel ? SELECTED : OTHER;
			JsonObject g = e.getValue().getAsJsonObject();
			if (g.has("line")) {
				JsonArray line = g.getAsJsonArray("line");
				Vec3 prev = null;
				for (JsonElement p : line) {
					Vec3 v = EditorClient.vec(p.getAsJsonArray());
					if (prev != null) {
						Gizmos.line(prev, v, color, sel ? 3 : 1.5f);
					}
					prev = v;
				}
			}
			if (g.has("points")) {
				for (JsonElement p : g.getAsJsonArray("points")) {
					JsonArray a = p.getAsJsonArray();
					Vec3 v = EditorClient.vec(a);
					Gizmos.point(v, color, sel ? 8 : 5);
					if (sel) {
						String kind = a.size() > 3 ? a.get(3).getAsString() : "";
						Gizmos.billboardText(label(kind), v.add(0, 0.4, 0), TextGizmo.Style.forColorAndCentered(color).withScale(0.25f));
					}
				}
			}
		}
	}

	private static String label(String kind) {
		return switch (kind) {
			case "pos" -> "место";
			case "to" -> "куда";
			case "look" -> "смотрит";
			case "key" -> "точка пути";
			default -> kind;
		};
	}
}
