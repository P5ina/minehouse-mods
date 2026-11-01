package pro.minehouse.eventseditor;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The «Логика» tab: nodes on a canvas, wires from an output (right) to an input (left). Drag a
 * node to move it, drag from an output to a node to wire them, right-click an output or input to
 * cut its wires, drag the empty canvas to look around it.
 */
final class LogicCanvas {
	static final int NODE_W = 104;
	private static final int HEAD = 12;
	private static final int ROW = 10;
	private static final int BG = 0xC0101418;
	private static final int GRID = 0x20FFFFFF;

	private enum Drag { NONE, NODE, WIRE, PAN }

	/** What a click hit: a node's body, one of its outputs, or its input. */
	record Hit(String node, String out, boolean input) {
	}

	int x0;
	int y0;
	int x1;
	int y1;
	private double panX = -20;
	private double panY = -20;

	private Drag drag = Drag.NONE;
	private String dragNode;
	private String dragOut;
	private double grabX;
	private double grabY;
	private int dragX;
	private int dragY;
	private boolean moved;
	private double mouseX;
	private double mouseY;

	void area(int x0, int y0, int x1, int y1) {
		this.x0 = x0;
		this.y0 = y0;
		this.x1 = x1;
		this.y1 = y1;
	}

	boolean contains(double x, double y) {
		return x >= x0 && x < x1 && y >= y0 && y < y1;
	}

	/** Canvas coordinates of the middle of the visible area, for a new node. */
	int[] centre() {
		return new int[] { (int) Math.round(panX + (x1 - x0) / 2.0 - NODE_W / 2.0), (int) Math.round(panY + (y1 - y0) / 2.0 - 20) };
	}

	private static int nodeX(JsonObject n) {
		return n.has("x") ? n.get("x").getAsInt() : 0;
	}

	private static int nodeY(JsonObject n) {
		return n.has("y") ? n.get("y").getAsInt() : 0;
	}

	private int sx(String id, JsonObject n) {
		int x = id.equals(dragNode) && drag == Drag.NODE ? dragX : nodeX(n);
		return x0 + (int) Math.round(x - panX);
	}

	private int sy(String id, JsonObject n) {
		int y = id.equals(dragNode) && drag == Drag.NODE ? dragY : nodeY(n);
		return y0 + (int) Math.round(y - panY);
	}

	static int height(JsonObject n) {
		return HEAD + Math.max(1, LogicSchema.outputs(n).size()) * ROW + 2;
	}

	// --- Drawing -----------------------------------------------------------------------------

	void extract(GuiGraphicsExtractor g, Font font, int mx, int my) {
		mouseX = mx;
		mouseY = my;
		g.fill(x0, y0, x1, y1, BG);
		int step = 32;
		int gx = (int) Math.floorMod((long) -Math.round(panX), step);
		for (int x = x0 + gx; x < x1; x += step) {
			g.fill(x, y0, x + 1, y1, GRID);
		}
		int gy = (int) Math.floorMod((long) -Math.round(panY), step);
		for (int y = y0 + gy; y < y1; y += step) {
			g.fill(x0, y, x1, y + 1, GRID);
		}
		g.enableScissor(x0, y0, x1, y1);
		JsonObject nodes = EditorClient.nodes();

		// Wires under the nodes.
		for (JsonElement e : EditorClient.links()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject l = e.getAsJsonObject();
			String from = CueSchema.str(l, "from", "");
			String to = CueSchema.str(l, "to", "");
			if (!(nodes.get(from) instanceof JsonObject a) || !(nodes.get(to) instanceof JsonObject b)) {
				continue;
			}
			int[] p = outPort(from, a, CueSchema.str(l, "out", "out"));
			if (p == null) {
				continue;
			}
			boolean hot = recently(from);
			wire(g, p[0], p[1], sx(to, b), sy(to, b) + 6, hot ? 0xFFFFFFFF : 0xFFB8C0C8);
		}
		if (drag == Drag.WIRE && nodes.get(dragNode) instanceof JsonObject a) {
			int[] p = outPort(dragNode, a, dragOut);
			if (p != null) {
				wire(g, p[0], p[1], mx, my, 0xFFFFD040);
			}
		}

		for (var entry : nodes.entrySet()) {
			if (entry.getValue() instanceof JsonObject n) {
				node(g, font, entry.getKey(), n);
			}
		}
		g.disableScissor();

		if (nodes.isEmpty()) {
			int y = y0 + 8;
			for (String line : List.of("§eЛогика пока пустая.",
					"«+ Нода»: событие (зелёное), условие (жёлтое), действие (синее) или Lua.",
					"Потяните от выхода справа у ноды к другой ноде — это провод: сигнал идёт по нему.",
					"Например: «Начало шоу» → «Шанс 50%» → «Действие: молния».",
					"ПКМ по выходу — убрать провода, Delete — удалить ноду, Tab — обратно к таймлайну.")) {
				g.text(font, line, x0 + 8, y, 0xFFE8E8E8);
				y += 11;
			}
		}
	}

	private static boolean recently(String id) {
		Long at = EditorClient.fired.get(id);
		return at != null && System.currentTimeMillis() - at < 700;
	}

	private void node(GuiGraphicsExtractor g, Font font, String id, JsonObject n) {
		int x = sx(id, n);
		int y = sy(id, n);
		int h = height(n);
		int color = LogicSchema.color(CueSchema.str(n, "type", ""));
		boolean selected = id.equals(EditorClient.selNode);
		int border = recently(id) ? 0xFFFFFFFF : selected ? 0xFFFFD040 : 0xFF000000;
		g.fill(x - 1, y - 1, x + NODE_W + 1, y + h + 1, border);
		g.fill(x, y, x + NODE_W, y + h, 0xF0202830);
		g.fill(x, y, x + NODE_W, y + HEAD, color);
		g.text(font, font.plainSubstrByWidth(LogicSchema.label(n), NODE_W - 4), x + 3, y + 2, 0xFFFFFFFF);
		String summary = summary(id, n);
		g.text(font, font.plainSubstrByWidth(summary, NODE_W - 30), x + 3, y + HEAD + 1, 0xFF9AA4AE);
		if (LogicSchema.hasInput(n)) {
			g.fill(x - 3, y + 4, x + 2, y + 9, 0xFFE8E8E8);
		}
		List<String> outs = LogicSchema.outputs(n);
		for (int i = 0; i < outs.size(); i++) {
			int py = y + HEAD + i * ROW + 3;
			String label = outs.get(i);
			if (!label.equals("out")) {
				g.text(font, label, x + NODE_W - 4 - font.width(label), py - 1, 0xFFE8E8E8);
			}
			g.fill(x + NODE_W - 2, py, x + NODE_W + 3, py + 5, 0xFFE8E8E8);
		}
	}

	/** One short line under the title: the node's main field, or its id. */
	private static String summary(String id, JsonObject n) {
		String key = switch (CueSchema.str(n, "type", "")) {
			case "on_marker" -> "marker";
			case "at_time" -> "at";
			case "every" -> "interval";
			case "on_trigger", "set_var", "check" -> "name";
			case "wait" -> "time";
			case "chance" -> "percent";
			case "viewers" -> "min";
			case "counter" -> "times";
			case "cue" -> "do";
			case "jump" -> "to";
			default -> null;
		};
		String value = key != null && n.has(key) ? CueSchema.toText(n.get(key)) : "";
		if (key != null && key.equals("percent")) {
			value += "%";
		}
		return value.isEmpty() ? "§8" + id : value;
	}

	private int[] outPort(String id, JsonObject n, String out) {
		int i = LogicSchema.outputs(n).indexOf(out);
		if (i < 0) {
			return null;
		}
		return new int[] { sx(id, n) + NODE_W + 1, sy(id, n) + HEAD + i * ROW + 5 };
	}

	private static void wire(GuiGraphicsExtractor g, int ax, int ay, int bx, int by, int color) {
		double bend = Math.max(30, Math.abs(bx - ax) / 2.0);
		int steps = 40;
		double px = ax;
		double py = ay;
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			double u = 1 - t;
			double x = u * u * u * ax + 3 * u * u * t * (ax + bend) + 3 * u * t * t * (bx - bend) + t * t * t * bx;
			double y = u * u * u * ay + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * by;
			int n = (int) Math.max(1, Math.ceil(Math.hypot(x - px, y - py)));
			for (int k = 0; k < n; k++) {
				int qx = (int) Math.round(px + (x - px) * k / n);
				int qy = (int) Math.round(py + (y - py) * k / n);
				g.fill(qx, qy, qx + 1, qy + 1, color);
			}
			px = x;
			py = y;
		}
	}

	// --- Mouse -------------------------------------------------------------------------------

	Hit hit(double mx, double my) {
		JsonObject nodes = EditorClient.nodes();
		List<String> ids = List.copyOf(nodes.keySet());
		for (int k = ids.size() - 1; k >= 0; k--) { // topmost (last drawn) first
			String id = ids.get(k);
			if (!(nodes.get(id) instanceof JsonObject n)) {
				continue;
			}
			int x = sx(id, n);
			int y = sy(id, n);
			List<String> outs = LogicSchema.outputs(n);
			for (int i = 0; i < outs.size(); i++) {
				int py = y + HEAD + i * ROW + 5;
				if (Math.abs(mx - (x + NODE_W)) <= 5 && Math.abs(my - py) <= 5) {
					return new Hit(id, outs.get(i), false);
				}
			}
			if (LogicSchema.hasInput(n) && Math.abs(mx - x) <= 5 && Math.abs(my - (y + 6)) <= 5) {
				return new Hit(id, null, true);
			}
			if (mx >= x && mx < x + NODE_W && my >= y && my < y + height(n)) {
				return new Hit(id, null, false);
			}
		}
		return null;
	}

	/** Left button pressed on the canvas. Returns the node to select (or "" to clear). */
	String pressLeft(double mx, double my) {
		Hit h = hit(mx, my);
		if (h == null) {
			drag = Drag.PAN;
			return "";
		}
		if (h.out() != null) {
			drag = Drag.WIRE;
			dragNode = h.node();
			dragOut = h.out();
			return null;
		}
		JsonObject n = EditorClient.nodes().getAsJsonObject(h.node());
		drag = Drag.NODE;
		dragNode = h.node();
		grabX = mx - sx(h.node(), n);
		grabY = my - sy(h.node(), n);
		dragX = nodeX(n);
		dragY = nodeY(n);
		moved = false;
		return h.node();
	}

	void pan(double dx, double dy) {
		panX -= dx;
		panY -= dy;
	}

	boolean dragging() {
		return drag != Drag.NONE;
	}

	boolean dragged(double mx, double my, double dx, double dy) {
		switch (drag) {
			case PAN -> pan(dx, dy);
			case NODE -> {
				int nx = (int) Math.round(mx - grabX - x0 + panX);
				int ny = (int) Math.round(my - grabY - y0 + panY);
				nx = Math.round(nx / 4f) * 4;
				ny = Math.round(ny / 4f) * 4;
				if (nx != dragX || ny != dragY) {
					moved = true;
				}
				dragX = nx;
				dragY = ny;
			}
			case WIRE -> {
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	void released(double mx, double my) {
		Drag was = drag;
		drag = Drag.NONE;
		if (was == Drag.NODE && moved && EditorClient.nodes().get(dragNode) instanceof JsonObject n) {
			n.addProperty("x", dragX); // shown right away; the server's copy follows
			n.addProperty("y", dragY);
			JsonObject o = EditorClient.op("move_node");
			o.addProperty("id", dragNode);
			o.addProperty("x", dragX);
			o.addProperty("y", dragY);
			EditorClient.send(o);
		}
		if (was == Drag.WIRE) {
			Hit h = hit(mx, my);
			if (h != null && !h.node().equals(dragNode) && EditorClient.nodes().get(h.node()) instanceof JsonObject target
					&& LogicSchema.hasInput(target)) {
				JsonObject o = EditorClient.op("add_link");
				o.addProperty("from", dragNode);
				o.addProperty("out", dragOut);
				o.addProperty("to", h.node());
				EditorClient.send(o);
			}
		}
		dragNode = null;
	}

	/** Right click: cut the wires at an output or an input. True if it hit a port. */
	boolean pressRight(double mx, double my) {
		Hit h = hit(mx, my);
		if (h == null || (h.out() == null && !h.input())) {
			return false;
		}
		JsonObject o = EditorClient.op("remove_links");
		if (h.input()) {
			o.addProperty("to", h.node());
		} else {
			o.addProperty("from", h.node());
			o.addProperty("out", h.out());
		}
		EditorClient.send(o);
		return true;
	}
}
