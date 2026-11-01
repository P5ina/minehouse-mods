package pro.minehouse.eventseditor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The editor's panel: the timeline along the bottom (tracks, cues, playhead), the selected cue's
 * properties on the right. The world stays visible above; K or Esc hides the panel so the admin
 * can fly around, K brings it back. Every change goes to the server, which answers with the show.
 */
public final class EditorScreen extends Screen {
	private static final int PANEL_W = 184;
	private static final int LABEL_W = 62;
	private static final int ROW_H = 13;
	private static final int FIELD_H = 24;

	private static final int BG = 0xD0101418;
	private static final int BG_ROW = 0x60202830;
	private static final int BG_ROW_ALT = 0x60283038;
	private static final int TEXT = 0xFFE8E8E8;
	private static final int DIM = 0xFF9AA4AE;
	private static final int PLAYHEAD = 0xFFFF4040;

	// Timeline view, kept between openings.
	private static double viewStart;
	private static double ticksPerPx = -1;
	private static int rowScroll;
	private static int propsScroll;
	/** Text typed but not applied yet, per selection, so a state update doesn't wipe it. */
	private static final Map<String, Map<String, String>> PENDING = new HashMap<>();

	private enum Drag { NONE, SCRUB, CUE, PAN, LOOK, LOGIC }

	private Drag drag = Drag.NONE;
	private double dragGrab;
	private int dragTick;
	private String dragTrack;
	private boolean dragMoved;
	private long lastSeek;
	private int scrubTick = -1;

	private int seenVersion = -1;
	private boolean addMenu;
	private long exitArmed;

	private Button playButton;
	private Button followButton;
	private Button undoButton;
	private Button redoButton;
	private Button saveButton;
	private Button exitButton;

	private final Map<String, EditBox> fields = new LinkedHashMap<>();
	private List<CueSchema.Field> fieldDefs = List.of();
	private int fieldsTop;
	private int fieldsBottom;
	private String propsTitle = "";
	private final LogicCanvas canvas = new LogicCanvas();
	/** The Lua node's code, when one is selected. */
	private MultiLineEditBox codeBox;

	public EditorScreen() {
		super(Component.literal("Редактор шоу"));
	}

	// --- Layout ------------------------------------------------------------------------------

	private int timelineTop() {
		return height - Math.max(110, (int) (height * 0.38));
	}

	private int rulerTop() {
		return timelineTop() + 22;
	}

	private int rowsTop() {
		return rulerTop() + 12;
	}

	private int rowsLeft() {
		return LABEL_W;
	}

	private int rowsRight() {
		return width - 4;
	}

	private int visibleRows() {
		return Math.max(1, (height - 4 - rowsTop()) / ROW_H);
	}

	private int panelLeft() {
		return width - PANEL_W;
	}

	private void fitView() {
		int len = EditorClient.length();
		ticksPerPx = Math.max(0.05, (len + 40) / (double) Math.max(50, rowsRight() - rowsLeft()));
		viewStart = 0;
	}

	private int tickToX(double tick) {
		return rowsLeft() + (int) Math.round((tick - viewStart) / ticksPerPx);
	}

	private double xToTick(double x) {
		return viewStart + (x - rowsLeft()) * ticksPerPx;
	}

	// --- Widgets -----------------------------------------------------------------------------

	@Override
	protected void init() {
		if (ticksPerPx <= 0) {
			fitView();
		}
		seenVersion = EditorClient.version;
		fields.clear();
		int y = timelineTop() + 2;
		int x = 4;
		addRenderableWidget(button("|<", x, y, 20, () -> EditorClient.seek(0, false), "В начало"));
		x += 22;
		playButton = addRenderableWidget(button("Пуск", x, y, 44, this::togglePlay, "Пуск/пауза (P)"));
		x += 46;
		followButton = addRenderableWidget(button("Камера", x, y, 58, () -> {
			JsonObject o = EditorClient.op("follow");
			o.addProperty("on", !EditorClient.flag("follow"));
			EditorClient.send(o);
		}, "Смотреть через камеру шоу при проигрывании"));
		x += 60;
		undoButton = addRenderableWidget(button("<-", x, y, 22, () -> EditorClient.simple("undo"), "Отменить (Ctrl+Z)"));
		x += 24;
		redoButton = addRenderableWidget(button("->", x, y, 22, () -> EditorClient.simple("redo"), "Повторить (Ctrl+Y)"));
		x += 26;
		boolean logic = EditorClient.logicTab;
		addRenderableWidget(button(addMenu ? (logic ? "x Нода" : "x Действие") : (logic ? "+ Нода" : "+ Действие"), x, y, 70, () -> {
			addMenu = !addMenu;
			rebuildWidgets();
		}, logic ? "Добавить ноду логики" : "Добавить действие в момент, где стоит курсор времени"));
		x += 72;
		addRenderableWidget(button(logic ? "Таймлайн" : "Логика", x, y, 58, this::toggleLogic,
				logic ? "Спрятать ноды (Tab)" : "Ноды: события, условия, действия, Lua (Tab)"));
		x += 60;
		saveButton = addRenderableWidget(button("Сохранить", x, y, 64, () -> EditorClient.simple("save"), "Сохранить шоу (Ctrl+S)"));
		x += 66;
		exitButton = addRenderableWidget(button("Выйти", x, y, 44, this::exit, "Закончить редактирование"));

		codeBox = null;
		if (addMenu) {
			initAddMenu();
		} else {
			initProperties();
		}
		updateButtons();
	}

	private static Button button(String label, int x, int y, int w, Runnable action, String tooltip) {
		Button b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 18).build();
		if (tooltip != null) {
			b.setTooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return b;
	}

	/** Where each group of the add menu goes, worked out again for drawing its heading. */
	private final List<int[]> menuGroups = new ArrayList<>();
	private final List<String> menuTitles = new ArrayList<>();

	private void initAddMenu() {
		menuGroups.clear();
		menuTitles.clear();
		int cols = Math.max(1, Math.min(6, (width - 8) / 116));
		int top = 28;
		int col = 0;
		int blockHeight = 0;
		Map<String, List<Object[]>> groups = new LinkedHashMap<>();
		if (EditorClient.logicTab) {
			LogicSchema.groups().forEach((title, types) -> groups.put(title, types.stream()
					.map(t -> new Object[] { t.label(), t.description(), (Runnable) () -> addNode(t) }).toList()));
		} else {
			CueSchema.groups().forEach((title, types) -> groups.put(title, types.stream()
					.map(t -> new Object[] { t.label(), CueSchema.description(t.name()), (Runnable) () -> addCue(t) }).toList()));
		}
		for (var group : groups.entrySet()) {
			if (col == cols) {
				col = 0;
				top += blockHeight + 6;
				blockHeight = 0;
			}
			int cx = 4 + col * 116;
			menuGroups.add(new int[] { cx, top, group.getValue().size() });
			menuTitles.add(group.getKey());
			int cy = top + 12;
			for (Object[] item : group.getValue()) {
				addRenderableWidget(button((String) item[0], cx, cy, 112, (Runnable) item[2], (String) item[1]));
				cy += 20;
			}
			blockHeight = Math.max(blockHeight, cy - top);
			col++;
		}
	}

	private void toggleLogic() {
		EditorClient.logicTab = !EditorClient.logicTab;
		addMenu = false;
		if (!EditorClient.logicTab && EditorClient.selNode != null) {
			EditorClient.clearSelection();
		}
		propsScroll = 0;
		rebuildWidgets();
	}

	private void addNode(LogicSchema.Type type) {
		addMenu = false;
		JsonObject nodes = EditorClient.nodes();
		int n = 1;
		while (nodes.has(type.name() + n)) {
			n++;
		}
		String id = type.name() + n;
		int[] at = canvas.centre();
		at[0] += (nodes.size() % 5) * 12; // a little apart, so new nodes don't stack exactly
		at[1] += (nodes.size() % 5) * 12;
		JsonObject o = EditorClient.op("set_node");
		o.addProperty("id", id);
		o.add("node", LogicSchema.newNode(type.name(), EditorClient.here(), at[0], at[1]));
		EditorClient.send(o);
		rebuildWidgets();
	}

	private void addCue(CueSchema.Type type) {
		addMenu = false;
		double[] here = type.name().equals("cut") ? hereEye() : EditorClient.here();
		String actor = firstActor(type.name().equals("cut") || type.name().equals("path") ? "camera" : null);
		JsonObject cue = CueSchema.newCue(type.name(), EditorClient.tick, here, actor);
		if (type.name().equals("cut")) {
			cue.remove("look");
			cue.addProperty("yaw", EditorClient.yaw());
			cue.addProperty("pitch", EditorClient.pitch());
		}
		if (cue.has("actor") && cue.get("actor").getAsString().isEmpty()) {
			cue.remove("actor");
		}
		String track = EditorClient.selTrack != null ? EditorClient.selTrack : type.track();
		JsonObject o = EditorClient.op("add_cue");
		o.addProperty("track", track);
		o.add("cue", cue);
		EditorClient.send(o);
		rebuildWidgets();
	}

	private static double[] hereEye() {
		double[] p = EditorClient.here();
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.getCameraEntity() != null) {
			p[1] = Math.round((p[1] + mc.getCameraEntity().getEyeHeight()) * 10) / 10.0;
		}
		return p;
	}

	/** The first actor of this type (camera), or the first non-camera one. */
	private static String firstActor(String type) {
		for (var e : EditorClient.actors().entrySet()) {
			String t = e.getValue().isJsonObject() ? CueSchema.str(e.getValue().getAsJsonObject(), "type", "") : "";
			if (type == null ? !t.equals("camera") : t.equals(type)) {
				return e.getKey();
			}
		}
		return null;
	}

	private String selectionKey() {
		if (EditorClient.selNode != null) {
			return "node:" + EditorClient.selNode;
		}
		if (EditorClient.selTrack != null) {
			return "cue:" + EditorClient.selTrack + "/" + EditorClient.selIndex;
		}
		if (EditorClient.selActor != null) {
			return "actor:" + EditorClient.selActor;
		}
		return "show";
	}

	private void initProperties() {
		int left = panelLeft() + 4;
		int w = PANEL_W - 8;
		int top = 20;
		int bottomButtons = timelineTop() - 24;
		JsonObject source;
		List<Runnable> extra = new ArrayList<>();
		Map<String, Runnable> actions = new LinkedHashMap<>();

		JsonObject cue = EditorClient.selectedCue();
		JsonObject node = EditorClient.selectedNode();
		boolean lua = false;
		if (node != null) {
			LogicSchema.Type type = LogicSchema.type(CueSchema.str(node, "type", ""));
			propsTitle = LogicSchema.label(node) + "  §7" + EditorClient.selNode;
			source = node;
			fieldDefs = LogicSchema.fields(node);
			lua = type != null && type.name().equals("lua");
			actions.put("Применить", this::applyNode);
			actions.put("Удалить", this::deleteSelected);
			if (type != null && type.name().equals("on_trigger")) {
				actions.put("Сигнал", () -> {
					JsonObject o = EditorClient.op("trigger");
					o.addProperty("name", CueSchema.str(node, "name", ""));
					EditorClient.send(o);
				});
			}
			actions.put("Назад", () -> {
				EditorClient.clearSelection();
				rebuildWidgets();
			});
		} else if (cue != null) {
			CueSchema.Type type = CueSchema.type(CueSchema.str(cue, "do", ""));
			propsTitle = (type != null ? type.label() : CueSchema.str(cue, "do", "?")) + "  §7" + EditorClient.selTrack + " #" + EditorClient.selIndex;
			source = cue;
			fieldDefs = CueSchema.fields(cue);
			actions.put("Применить", this::applyCue);
			actions.put("Копия", this::duplicate);
			actions.put("Удалить", this::deleteSelected);
			String kind = CueSchema.str(cue, "do", "");
			if (kind.equals("cut")) {
				actions.put("Ракурс отсюда", this::cutFromHere);
			}
			if (kind.equals("path")) {
				actions.put("+ точка отсюда", this::addPathPoint);
			}
		} else if (EditorClient.selActor != null && EditorClient.actors().get(EditorClient.selActor) instanceof JsonObject actor) {
			propsTitle = "Актёр " + EditorClient.selActor;
			source = actor;
			fieldDefs = CueSchema.actorFields(actor);
			actions.put("Применить", this::applyActor);
			actions.put("Удалить", this::deleteSelected);
			actions.put("Назад", () -> {
				EditorClient.clearSelection();
				rebuildWidgets();
			});
		} else {
			propsTitle = "Шоу " + EditorClient.show();
			source = EditorClient.doc();
			fieldDefs = List.of(new CueSchema.Field("title", CueSchema.Kind.TEXT, "название"),
					new CueSchema.Field("length", CueSchema.Kind.TIME, "длина, пусто = по последнему действию"),
					new CueSchema.Field("audience", CueSchema.Kind.JSON, "{gather: teleport/none, radius}"),
					new CueSchema.Field("markers", CueSchema.Kind.JSON, "{имя: время}"));
			actions.put("Применить", this::applyMeta);
			actions.put("Сцена здесь", () -> EditorClient.simple("stage_here"));
			actions.put("+ Актёр", this::addActor);
			extra.add(() -> {
			});
		}

		// Field boxes, scrolled inside the panel.
		Map<String, String> pending = PENDING.getOrDefault(selectionKey(), Map.of());
		fieldsTop = top;
		int actorRows = cue == null && node == null && EditorClient.selActor == null ? EditorClient.actors().size() : 0;
		fieldsBottom = bottomButtons - 4 - (actions.size() > 3 ? 22 : 0) - (actorRows > 0 ? 12 + ((actorRows + 1) / 2) * 20 : 0);
		if (lua) {
			// The code takes what the fields leave of the panel.
			int codeTop = Math.min(fieldsBottom - 60, top + fieldDefs.size() * FIELD_H + 10);
			int codeBottom = fieldsBottom;
			fieldsBottom = codeTop - 10;
			codeBox = MultiLineEditBox.builder().setX(left).setY(codeTop).setPlaceholder(Component.literal("Lua-код"))
					.build(font, w, Math.max(40, codeBottom - codeTop), Component.literal("code"));
			codeBox.setCharacterLimit(8000);
			String key = selectionKey();
			Map<String, String> waiting = PENDING.getOrDefault(key, Map.of());
			codeBox.setValue(waiting.containsKey("code") ? waiting.get("code") : CueSchema.str(node, "code", ""), true);
			codeBox.setValueListener(text -> PENDING.computeIfAbsent(key, k -> new HashMap<>()).put("code", text));
			addRenderableWidget(codeBox);
		}
		int y = top - propsScroll;
		for (CueSchema.Field field : fieldDefs) {
			boolean vec = field.kind() == CueSchema.Kind.VEC;
			EditBox box = new EditBox(font, left, y + 9, vec ? w - 34 : w, 14, Component.literal(field.name()));
			box.setMaxLength(4000);
			String key = selectionKey();
			String value = pending.containsKey(field.name()) ? pending.get(field.name()) : CueSchema.toText(source.get(field.name()));
			box.setValue(value);
			box.moveCursorToStart(false);
			box.setHint(Component.literal(field.hint()));
			box.setResponder(text -> PENDING.computeIfAbsent(key, k -> new HashMap<>()).put(field.name(), text));
			boolean shown = y >= fieldsTop - 1 && y + FIELD_H <= fieldsBottom;
			box.visible = shown;
			if (field.hint() != null && !field.hint().isEmpty()) {
				box.setTooltip(Tooltip.create(Component.literal(field.name() + ": " + field.hint())));
			}
			addRenderableWidget(box);
			fields.put(field.name(), box);
			if (vec) {
				Button here = button("Сюда", left + w - 32, y + 8, 32, () -> {
					double[] p = field.name().equals("look") || CueSchema.str(source, "do", "").equals("cut") ? hereEye() : EditorClient.here();
					box.setValue(CueSchema.number(p[0]) + " " + CueSchema.number(p[1]) + " " + CueSchema.number(p[2]));
				}, "Подставить место, где вы стоите");
				here.visible = shown;
				addRenderableWidget(here);
			}
			y += FIELD_H;
		}

		// Actors list when nothing is selected.
		if (actorRows > 0) {
			int ay = fieldsBottom + 14;
			int i = 0;
			for (String name : EditorClient.actors().keySet()) {
				int ax = left + (i % 2) * (w / 2 + 1);
				addRenderableWidget(button(name, ax, ay + (i / 2) * 20, w / 2 - 1, () -> {
					EditorClient.clearSelection();
					EditorClient.selActor = name;
					propsScroll = 0;
					rebuildWidgets();
				}, "Изменить актёра"));
				i++;
			}
		}

		// Action buttons along the bottom of the panel, two rows when needed.
		List<Map.Entry<String, Runnable>> list = new ArrayList<>(actions.entrySet());
		int perRow = Math.min(3, list.size());
		int rowY = bottomButtons - (list.size() > 3 ? 22 : 0);
		for (int i = 0; i < list.size(); i++) {
			if (i == 3) {
				rowY += 22;
				perRow = list.size() - 3;
			}
			int col = i < 3 ? i : i - 3;
			int bw = (w - (perRow - 1) * 2) / perRow;
			var entry = list.get(i);
			addRenderableWidget(button(entry.getKey(), left + col * (bw + 2), rowY, bw, entry.getValue(), null));
		}
	}

	private void updateButtons() {
		playButton.setMessage(Component.literal(EditorClient.playing ? "Пауза" : "Пуск"));
		followButton.setMessage(Component.literal(EditorClient.flag("follow") ? "Камера ✔" : "Камера"));
		undoButton.active = EditorClient.count("undo") > 0;
		redoButton.active = EditorClient.count("redo") > 0;
		saveButton.setMessage(Component.literal(EditorClient.flag("dirty") ? "Сохранить*" : "Сохранить"));
		exitButton.setMessage(Component.literal(System.currentTimeMillis() < exitArmed ? "Точно?" : "Выйти"));
	}

	// --- Actions -----------------------------------------------------------------------------

	private void togglePlay() {
		if (EditorClient.playing) {
			EditorClient.simple("pause");
			EditorClient.playing = false;
		} else {
			EditorClient.simple("play");
			EditorClient.playing = true;
		}
	}

	private void exit() {
		if (EditorClient.flag("dirty") && System.currentTimeMillis() > exitArmed) {
			exitArmed = System.currentTimeMillis() + 3000;
			EditorClient.toast("§eЕсть несохранённые изменения. Нажмите «Точно?», чтобы выйти без сохранения.");
			return;
		}
		PENDING.clear();
		EditorClient.simple("close");
	}

	/** Reads the boxes into a copy of the source; null (and a toast) if a box can't be read. */
	private JsonObject readFields(JsonObject source) {
		JsonObject out = source.deepCopy();
		for (CueSchema.Field field : fieldDefs) {
			EditBox box = fields.get(field.name());
			if (box == null) {
				continue;
			}
			try {
				JsonElement value = CueSchema.fromText(field, box.getValue());
				if (value == null) {
					out.remove(field.name());
				} else {
					out.add(field.name(), value);
				}
			} catch (RuntimeException e) {
				EditorClient.toast("§c" + e.getMessage());
				return null;
			}
		}
		return out;
	}

	private void applyCue() {
		JsonObject cue = EditorClient.selectedCue();
		if (cue == null) {
			return;
		}
		JsonObject edited = readFields(cue);
		if (edited == null) {
			return;
		}
		sendCue(EditorClient.selTrack, EditorClient.selIndex, edited, null);
		PENDING.remove(selectionKey());
	}

	private static void sendCue(String track, int index, JsonObject cue, String to) {
		JsonObject o = EditorClient.op("set_cue");
		o.addProperty("track", track);
		o.addProperty("index", index);
		o.add("cue", cue);
		if (to != null && !to.equals(track)) {
			o.addProperty("to", to);
		}
		EditorClient.send(o);
	}

	private void applyNode() {
		JsonObject node = EditorClient.selectedNode();
		if (node == null) {
			return;
		}
		JsonObject edited = readFields(node);
		if (edited == null) {
			return;
		}
		if (codeBox != null) {
			edited.addProperty("code", codeBox.getValue());
		}
		JsonObject o = EditorClient.op("set_node");
		o.addProperty("id", EditorClient.selNode);
		o.add("node", edited);
		EditorClient.send(o);
		PENDING.remove(selectionKey());
	}

	private void applyActor() {
		JsonObject actor = EditorClient.actors().get(EditorClient.selActor) instanceof JsonObject a ? a : null;
		if (actor == null) {
			return;
		}
		JsonObject edited = readFields(actor);
		if (edited == null) {
			return;
		}
		JsonObject o = EditorClient.op("set_actor");
		o.addProperty("name", EditorClient.selActor);
		o.add("actor", edited);
		EditorClient.send(o);
		PENDING.remove(selectionKey());
	}

	private void applyMeta() {
		JsonObject doc = EditorClient.doc();
		for (CueSchema.Field field : fieldDefs) {
			EditBox box = fields.get(field.name());
			JsonElement value;
			try {
				value = CueSchema.fromText(field, box.getValue());
			} catch (RuntimeException e) {
				EditorClient.toast("§c" + e.getMessage());
				return;
			}
			JsonElement old = doc.get(field.name());
			if (value == null ? old == null : value.equals(old)) {
				continue;
			}
			JsonObject o = EditorClient.op("set_meta");
			o.addProperty("key", field.name());
			o.add("value", value);
			EditorClient.send(o);
		}
		PENDING.remove(selectionKey());
	}

	private void addActor() {
		int n = 1;
		while (EditorClient.actors().has("actor" + n)) {
			n++;
		}
		String name = "actor" + n;
		JsonObject o = EditorClient.op("set_actor");
		o.addProperty("name", name);
		o.add("actor", JsonParser.parseString("{\"type\": \"block\", \"block\": \"minecraft:stone\"}"));
		EditorClient.send(o);
		EditorClient.clearSelection();
		EditorClient.selActor = name;
		propsScroll = 0;
	}

	private void deleteSelected() {
		if (EditorClient.selNode != null) {
			JsonObject o = EditorClient.op("remove_node");
			o.addProperty("id", EditorClient.selNode);
			EditorClient.send(o);
		} else if (EditorClient.selTrack != null) {
			JsonObject o = EditorClient.op("remove_cue");
			o.addProperty("track", EditorClient.selTrack);
			o.addProperty("index", EditorClient.selIndex);
			EditorClient.send(o);
		} else if (EditorClient.selActor != null) {
			JsonObject o = EditorClient.op("remove_actor");
			o.addProperty("name", EditorClient.selActor);
			EditorClient.send(o);
		}
		PENDING.remove(selectionKey());
		EditorClient.clearSelection();
		rebuildWidgets();
	}

	private void duplicate() {
		JsonObject cue = EditorClient.selectedCue();
		if (cue == null) {
			return;
		}
		JsonObject copy = cue.deepCopy();
		copy.addProperty("at", Time.format(EditorClient.tick));
		JsonObject o = EditorClient.op("add_cue");
		o.addProperty("track", EditorClient.selTrack);
		o.add("cue", copy);
		EditorClient.send(o);
	}

	private void cutFromHere() {
		JsonObject cue = EditorClient.selectedCue();
		if (cue == null) {
			return;
		}
		JsonObject edited = cue.deepCopy();
		double[] p = hereEye();
		JsonArray pos = new JsonArray();
		for (double v : p) {
			pos.add(v);
		}
		edited.add("pos", pos);
		edited.remove("look");
		edited.addProperty("yaw", EditorClient.yaw());
		edited.addProperty("pitch", EditorClient.pitch());
		PENDING.remove(selectionKey());
		sendCue(EditorClient.selTrack, EditorClient.selIndex, edited, null);
	}

	private void addPathPoint() {
		JsonObject cue = EditorClient.selectedCue();
		if (cue == null) {
			return;
		}
		JsonObject edited = cue.deepCopy();
		JsonArray points = edited.has("points") && edited.get("points").isJsonArray() ? edited.getAsJsonArray("points") : new JsonArray();
		JsonObject point = new JsonObject();
		JsonArray pos = new JsonArray();
		for (double v : hereEye()) {
			pos.add(v);
		}
		point.add("pos", pos);
		point.addProperty("yaw", EditorClient.yaw());
		point.addProperty("pitch", EditorClient.pitch());
		points.add(point);
		edited.add("points", points);
		edited.remove("orbit");
		PENDING.remove(selectionKey());
		sendCue(EditorClient.selTrack, EditorClient.selIndex, edited, null);
		EditorClient.toast("§aТочка " + points.size() + " добавлена");
	}

	// --- Drawing -----------------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, a);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (!EditorClient.editing()) {
			onClose();
			return;
		}
		if (EditorClient.version != seenVersion) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!EditorClient.editing()) {
			return;
		}
		updateButtons();
		int top = timelineTop();
		// Panels.
		g.fill(0, top, width, height, BG);
		if (!addMenu) {
			g.fill(panelLeft(), 0, width, top, BG);
			g.text(font, propsTitle, panelLeft() + 4, 6, TEXT);
			int y = fieldsTop - propsScroll;
			for (CueSchema.Field field : fieldDefs) {
				if (y >= fieldsTop - 1 && y + FIELD_H <= fieldsBottom) {
					g.text(font, field.name(), panelLeft() + 4, y, DIM);
				}
				y += FIELD_H;
			}
			if (EditorClient.selectedCue() == null && EditorClient.selActor == null && !EditorClient.actors().isEmpty()) {
				g.text(font, "Актёры:", panelLeft() + 4, fieldsBottom + 3, DIM);
			}
		} else {
			int menuBottom = 30;
			int menuRight = 0;
			for (int i = 0; i < menuGroups.size(); i++) {
				int[] at = menuGroups.get(i);
				int count = at[2];
				menuBottom = Math.max(menuBottom, at[1] + 12 + count * 20);
				menuRight = Math.max(menuRight, at[0] + 114);
			}
			g.fill(0, 0, Math.min(width, menuRight + 2), menuBottom + 4, BG);
			g.text(font, EditorClient.logicTab ? "Какую ноду добавить? Наведите на кнопку, чтобы увидеть, что она делает. Точки берутся оттуда, где вы стоите."
					: "Что добавить на " + Time.format(EditorClient.tick) + " (красная линия)? Наведите на кнопку, чтобы увидеть, что она делает."
					+ " Места берутся оттуда, где вы стоите.", 6, 4, TEXT);
			g.text(font, "§7Esc — закрыть список", 6, 14, DIM);
			for (int i = 0; i < menuGroups.size(); i++) {
				g.text(font, menuTitles.get(i), menuGroups.get(i)[0] + 2, menuGroups.get(i)[1] + 2, 0xFFFFD040);
			}
		}

		if (EditorClient.logicTab && !addMenu) {
			canvas.area(0, 0, panelLeft(), top - 14);
			canvas.extract(g, font, mouseX, mouseY);
		}
		if (codeBox != null) {
			g.text(font, "code (Lua)", panelLeft() + 4, codeBox.getY() - 9, DIM);
		}

		// Time and status next to the toolbar.
		int length = EditorClient.length();
		int shown = scrubTick >= 0 ? scrubTick : EditorClient.tick;
		String status = Time.format(shown) + " / " + Time.format(length) + (EditorClient.logicPaused ? " §e(пауза логикой)§r" : "") + "   " + EditorClient.show();
		int statusX = exitButton.getX() + exitButton.getWidth() + 8;
		if (statusX + font.width(status) < width) {
			g.text(font, status, statusX, top + 7, TEXT);
		}

		extractTimeline(g, mouseX, mouseY);

		// Errors and messages over the world, top left.
		int msgY = 4;
		String error = EditorClient.error();
		if (error != null) {
			for (var line : font.split(Component.literal("Ошибка в шоу: " + error), Math.max(100, panelLeft() - 16))) {
				g.fill(2, msgY - 1, 6 + font.width(line), msgY + 9, 0xC0300000);
				g.text(font, line, 4, msgY, 0xFFFF8080);
				msgY += 10;
			}
		}
		if (EditorClient.toast != null && System.currentTimeMillis() < EditorClient.toastUntil && !addMenu) {
			g.fill(2, msgY - 1, 6 + font.width(EditorClient.toast), msgY + 9, 0xA0000000);
			g.text(font, EditorClient.toast, 4, msgY, TEXT);
			msgY += 12;
		}
		if (!addMenu) {
			String hint = EditorClient.logicTab
					? "Тяните от выхода к ноде — провод · ПКМ по выходу/входу — убрать провода · Delete — удалить · Tab — таймлайн · P — пуск"
					: "WASD, пробел, Shift — лететь · ПКМ по миру — смотреть · P — пуск/пауза · Tab — логика · K — спрятать панель";
			g.fill(2, top - 12, 6 + font.width(hint), top - 2, 0x90000000);
			g.text(font, hint, 4, top - 11, DIM);
		}

		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private boolean noCues() {
		for (String name : EditorClient.trackNames()) {
			if (!EditorClient.track(name).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private void extractTimeline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (noCues()) {
			int y = rowsTop() + 4;
			for (String line : List.of("§eШоу пока пустое. Как начать:",
					"1. Долетите туда, где что-то должно случиться (панель не мешает: WASD, пробел, Shift).",
					"2. Нажмите «+ Действие» и выберите, что произойдёт: оно встанет на время под красной линией.",
					"3. Справа поправьте свойства и нажмите «Применить». P — посмотреть, «Сохранить» — записать.")) {
				g.text(font, line, rowsLeft() + 6, y, TEXT);
				y += 11;
			}
			return;
		}
		int left = rowsLeft();
		int right = rowsRight();
		int ruler = rulerTop();
		int rows = rowsTop();
		int length = EditorClient.length();

		// Ruler: a label every 1/2/5/10/... seconds, whatever leaves room.
		g.fill(left, ruler, right, ruler + 11, 0xFF20262C);
		int[] steps = { 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000 };
		int step = steps[steps.length - 1];
		for (int s : steps) {
			if (s / ticksPerPx >= 44) {
				step = s;
				break;
			}
		}
		g.enableScissor(left, ruler, right, height);
		int first = (int) Math.floor(viewStart / step) * step;
		for (int t = Math.max(0, first); tickToX(t) < right; t += step) {
			int x = tickToX(t);
			g.verticalLine(x, ruler, ruler + 11, 0xFF5A6670);
			g.text(font, Time.format(t), x + 2, ruler + 2, DIM, false);
			g.verticalLine(x, rows, height, 0x20FFFFFF);
		}
		// End of the show.
		int endX = tickToX(length);
		if (endX < right) {
			g.fill(endX, rows, right, height, 0x40000000);
			g.verticalLine(endX, ruler, height, 0xFF808080);
		}
		// Markers.
		JsonObject doc = EditorClient.doc();
		if (doc.has("markers") && doc.get("markers").isJsonObject()) {
			for (var m : doc.getAsJsonObject("markers").entrySet()) {
				try {
					int x = tickToX(CueSchema.time(m.getValue()));
					g.fill(x, ruler, x + 1, ruler + 11, 0xFFFFD040);
					g.text(font, m.getKey(), x + 2, ruler - 9, 0xFFFFD040);
				} catch (RuntimeException ignored) {
					// half-typed marker
				}
			}
		}
		g.disableScissor();

		// Tracks.
		List<String> names = EditorClient.trackNames();
		int visible = visibleRows();
		rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, names.size() + 1 - visible)));
		for (int r = 0; r < visible; r++) {
			int i = r + rowScroll;
			int y = rows + r * ROW_H;
			g.fill(0, y, right, y + ROW_H - 1, i % 2 == 0 ? BG_ROW : BG_ROW_ALT);
			if (i >= names.size()) {
				if (i == names.size() && drag == Drag.CUE) {
					g.text(font, "§7новая", 4, y + 2, DIM);
				}
				continue;
			}
			String name = names.get(i);
			boolean selectedTrack = name.equals(EditorClient.selTrack);
			g.text(font, font.plainSubstrByWidth(name, LABEL_W - 6), 4, y + 2, selectedTrack ? TEXT : DIM);

			g.enableScissor(left, y, right, y + ROW_H);
			JsonArray cues = EditorClient.track(name);
			for (int c = 0; c < cues.size(); c++) {
				if (!cues.get(c).isJsonObject()) {
					continue;
				}
				boolean selected = selectedTrack && c == EditorClient.selIndex;
				if (selected && drag == Drag.CUE && dragMoved) {
					continue; // drawn where it's being dragged
				}
				drawCue(g, cues.get(c).getAsJsonObject(), CueSchema.at(cues.get(c).getAsJsonObject()), y, selected);
			}
			g.disableScissor();
		}
		// The cue being dragged.
		if (drag == Drag.CUE && dragMoved && EditorClient.selectedCue() != null) {
			int r = names.indexOf(dragTrack);
			if (r < 0) {
				r = names.size();
			}
			int y = rows + (r - rowScroll) * ROW_H;
			if (y >= rows && y < height) {
				g.enableScissor(left, rows, right, height);
				drawCue(g, EditorClient.selectedCue(), dragTick, y, true);
				g.disableScissor();
				g.text(font, Time.format(dragTick), Math.max(left, tickToX(dragTick)), ruler - 9, TEXT);
			}
		}

		// Playhead.
		int shown = scrubTick >= 0 ? scrubTick : EditorClient.tick;
		int px = tickToX(shown);
		if (px >= left && px < right) {
			g.fill(px, ruler, px + 1, height, PLAYHEAD);
			g.fill(px - 2, ruler, px + 3, ruler + 3, PLAYHEAD);
		}
	}

	private void drawCue(GuiGraphicsExtractor g, JsonObject cue, int at, int y, boolean selected) {
		int x0 = tickToX(at);
		int dur = CueSchema.duration(cue);
		int x1 = Math.max(x0 + 4, tickToX(at + dur));
		int color = CueSchema.color(CueSchema.str(cue, "do", ""));
		int fill = (color & 0x00FFFFFF) | (selected ? 0xF0000000 : 0xB0000000);
		g.fill(x0, y + 1, x1, y + ROW_H - 2, fill);
		g.fill(x0, y + 1, x0 + 1, y + ROW_H - 2, 0xFFFFFFFF);
		if (selected) {
			g.outline(x0 - 1, y, x1 - x0 + 2, ROW_H - 1, 0xFFFFFFFF);
		}
		CueSchema.Type type = CueSchema.type(CueSchema.str(cue, "do", ""));
		String label = type != null ? type.label() : CueSchema.str(cue, "do", "?");
		String actor = CueSchema.str(cue, "actor", null);
		if (actor != null) {
			label += " " + actor;
		}
		int room = Math.max(x1 - x0 - 3, 60);
		g.text(font, font.plainSubstrByWidth(label, room), x0 + 2, y + 2, 0xFF101010, false);
	}

	// --- Mouse -------------------------------------------------------------------------------

	/** Track row under y (index into the track list, may be one past the end), or -1. */
	private int rowAt(double y) {
		if (y < rowsTop()) {
			return -1;
		}
		return (int) ((y - rowsTop()) / ROW_H) + rowScroll;
	}

	/** The cue under the mouse in this track, preferring the one that starts last. */
	private int cueAt(String track, double mouseX) {
		JsonArray cues = EditorClient.track(track);
		int found = -1;
		int foundAt = Integer.MIN_VALUE;
		for (int c = 0; c < cues.size(); c++) {
			if (!cues.get(c).isJsonObject()) {
				continue;
			}
			JsonObject cue = cues.get(c).getAsJsonObject();
			int at = CueSchema.at(cue);
			int x0 = tickToX(at);
			int x1 = Math.max(x0 + 4, tickToX(at + CueSchema.duration(cue)));
			if (mouseX >= x0 - 1 && mouseX <= x1 && at >= foundAt) {
				found = c;
				foundAt = at;
			}
		}
		return found;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			if (typing()) {
				KeyMapping.releaseAll(); // stop flying when a field takes the keyboard
			}
			return true;
		}
		double mx = event.x();
		double my = event.y();
		setFocused(null);
		if (EditorClient.logicTab && !addMenu && canvas.contains(mx, my)) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				String select = canvas.pressLeft(mx, my);
				drag = Drag.LOGIC;
				if (select != null && !select.equals(EditorClient.selNode == null ? "" : EditorClient.selNode)) {
					EditorClient.clearSelection();
					EditorClient.selNode = select.isEmpty() ? null : select;
					propsScroll = 0;
					rebuildWidgets();
				}
				return true;
			}
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && canvas.pressRight(mx, my)) {
				return true;
			}
			if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
				canvas.pressLeft(-10000, -10000); // empty space: pan
				drag = Drag.LOGIC;
				return true;
			}
		}
		if (my < timelineTop()) {
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && !addMenu && mx < panelLeft()) {
				drag = Drag.LOOK; // right mouse over the world turns the camera
				return true;
			}
			return false;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && my >= rulerTop())) {
			drag = Drag.PAN;
			return true;
		}
		if (mx < rowsLeft() || mx > rowsRight()) {
			return false;
		}
		if (my >= rulerTop() && my < rowsTop()) {
			drag = Drag.SCRUB;
			scrub(mx, true);
			return true;
		}
		int row = rowAt(my);
		List<String> names = EditorClient.trackNames();
		if (row >= 0 && row < names.size()) {
			String track = names.get(row);
			int c = cueAt(track, mx);
			if (c >= 0) {
				boolean changed = !track.equals(EditorClient.selTrack) || c != EditorClient.selIndex;
				EditorClient.clearSelection();
				EditorClient.selTrack = track;
				EditorClient.selIndex = c;
				JsonObject cue = EditorClient.selectedCue();
				int at = CueSchema.at(cue);
				drag = Drag.CUE;
				dragGrab = xToTick(mx) - at;
				dragTick = at;
				dragTrack = track;
				dragMoved = false;
				if (changed) {
					propsScroll = 0;
					rebuildWidgets();
				}
				return true;
			}
		}
		// Empty space: deselect and move the playhead there.
		if (EditorClient.selTrack != null || EditorClient.selActor != null) {
			EditorClient.clearSelection();
			propsScroll = 0;
			rebuildWidgets();
		}
		drag = Drag.SCRUB;
		scrub(mx, true);
		return true;
	}

	private void scrub(double mx, boolean force) {
		int t = (int) Math.round(Math.max(0, Math.min(EditorClient.length(), xToTick(mx))));
		scrubTick = t;
		long now = System.currentTimeMillis();
		if (force || now - lastSeek > 120) {
			lastSeek = now;
			EditorClient.seek(t, false);
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		switch (drag) {
			case SCRUB -> {
				scrub(event.x(), false);
				return true;
			}
			case PAN -> {
				viewStart = Math.max(-20, viewStart - dx * ticksPerPx);
				return true;
			}
			case LOGIC -> {
				return canvas.dragged(event.x(), event.y(), dx, dy);
			}
			case LOOK -> {
				if (minecraft.player != null) {
					double f = minecraft.options.sensitivity().get() * 0.6 + 0.2;
					double k = f * f * f * 8.0 * (minecraft.getWindow().getScreenWidth() / (double) width);
					minecraft.player.turn(dx * k, dy * k);
				}
				return true;
			}
			case CUE -> {
				int t = (int) Math.round(Math.max(0, xToTick(event.x()) - dragGrab));
				if (!event.hasShiftDown()) {
					t = Math.round(t / 5f) * 5; // quarter seconds unless Shift
				}
				int row = rowAt(event.y());
				List<String> names = EditorClient.trackNames();
				String track = row >= 0 && row < names.size() ? names.get(row) : dragTrack;
				if (t != dragTick || !track.equals(dragTrack)) {
					dragMoved = true;
				}
				dragTick = t;
				dragTrack = track;
				return true;
			}
			default -> {
				return super.mouseDragged(event, dx, dy);
			}
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		Drag was = drag;
		drag = Drag.NONE;
		if (was == Drag.LOGIC) {
			canvas.released(event.x(), event.y());
			return true;
		}
		if (was == Drag.SCRUB) {
			if (scrubTick >= 0) {
				EditorClient.seek(scrubTick, false);
			}
			scrubTick = -1;
			return true;
		}
		if (was == Drag.CUE) {
			JsonObject cue = EditorClient.selectedCue();
			if (dragMoved && cue != null) {
				JsonObject moved = cue.deepCopy();
				moved.addProperty("at", Time.format(dragTick));
				sendCue(EditorClient.selTrack, EditorClient.selIndex, moved, dragTrack);
				PENDING.remove(selectionKey());
			}
			dragMoved = false;
			return true;
		}
		if (was == Drag.PAN) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (y >= timelineTop()) {
			boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
			boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
			if (shift) {
				viewStart = Math.max(-20, viewStart - scrollY * 40 * ticksPerPx);
			} else if (ctrl || y < rowsTop()) {
				double anchor = xToTick(x);
				ticksPerPx = Math.max(0.05, Math.min(200, ticksPerPx * (scrollY > 0 ? 0.8 : 1.25)));
				viewStart = Math.max(-20, anchor - (x - rowsLeft()) * ticksPerPx);
			} else {
				rowScroll = Math.max(0, rowScroll - (int) Math.signum(scrollY));
			}
			return true;
		}
		if (!addMenu && x >= panelLeft()) {
			int total = fieldDefs.size() * FIELD_H;
			int room = fieldsBottom - fieldsTop;
			int next = Math.max(0, Math.min(Math.max(0, total - room), propsScroll - (int) Math.signum(scrollY) * FIELD_H));
			if (next != propsScroll) {
				propsScroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	// --- Keys --------------------------------------------------------------------------------

	private boolean typing() {
		return getFocused() instanceof EditBox || getFocused() instanceof MultiLineEditBox;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (typing()) {
			if (event.isEscape()) {
				setFocused(null);
				return true;
			}
			if (event.isConfirmation() && getFocused() instanceof EditBox) {
				if (EditorClient.selectedNode() != null) {
					applyNode();
				} else if (EditorClient.selectedCue() != null) {
					applyCue();
				} else if (EditorClient.selActor != null) {
					applyActor();
				} else {
					applyMeta();
				}
				setFocused(null);
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.isEscape() || MinehouseEventsEditor.panelKey().matches(event)) {
			if (addMenu) {
				addMenu = false;
				rebuildWidgets();
				return true;
			}
			onClose();
			return true;
		}
		if (moveKey(event, true)) {
			return true;
		}
		int letter = event.shortcutKey();
		if (event.hasControlDown()) {
			switch (letter) {
				case 'z' -> EditorClient.simple(event.hasShiftDown() ? "redo" : "undo");
				case 'y' -> EditorClient.simple("redo");
				case 's' -> EditorClient.simple("save");
				case 'd' -> duplicate();
				default -> {
					return super.keyPressed(event);
				}
			}
			return true;
		}
		switch (key) {
			case InputConstants.KEY_P -> togglePlay();
			case InputConstants.KEY_TAB -> toggleLogic();
			case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> deleteSelected();
			case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT -> {
				int step = event.hasShiftDown() ? 1 : 10;
				EditorClient.seek(EditorClient.tick + (key == InputConstants.KEY_LEFT ? -step : step), false);
			}
			case InputConstants.KEY_HOME -> EditorClient.seek(0, false);
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	/** The movement keys keep working with the panel open, so the admin can fly while editing. */
	private boolean moveKey(KeyEvent event, boolean down) {
		var o = minecraft.options;
		for (KeyMapping mapping : List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint)) {
			if (mapping.matches(event)) {
				mapping.setDown(down);
				return !event.hasControlDown() || mapping == o.keySprint;
			}
		}
		return false;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		moveKey(event, false);
		return super.keyReleased(event);
	}

	@Override
	public void removed() {
		KeyMapping.releaseAll();
		super.removed();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(null);
	}
}
