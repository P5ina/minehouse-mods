package pro.minehouse.eventseditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pro.minehouse.eventseditor.CueSchema.Field;
import pro.minehouse.eventseditor.CueSchema.Kind;

/** The node types of the «Логика» tab: what they are called, their fields and outputs. Mirrors the server's Logic. */
public final class LogicSchema {
	public record Type(String name, String label, String group, String description, List<Field> fields, List<String> outputs, String defaults) {
		public boolean event() {
			return group.equals(EVENTS);
		}
	}

	public static final String EVENTS = "События";
	public static final String FLOW = "Условия";
	public static final String ACTIONS = "Действия";

	private static final Map<String, Type> TYPES = new LinkedHashMap<>();

	private static Field f(String name, Kind kind, String hint) {
		return new Field(name, kind, hint);
	}

	private static void type(String name, String label, String group, String description, List<String> outputs, String defaults, Field... fields) {
		TYPES.put(name, new Type(name, label, group, description, List.of(fields), outputs, defaults));
	}

	static {
		List<String> out = List.of("out");
		List<String> yesNo = List.of("yes", "no");
		type("on_start", "Начало шоу", EVENTS, "Срабатывает, когда шоу началось.", out, "{}");
		type("on_marker", "Маркер", EVENTS, "Срабатывает, когда таймлайн дошёл до маркера.", out, "{\"marker\": \"\"}",
				f("marker", Kind.TEXT, "имя маркера из свойств шоу"));
		type("at_time", "Момент", EVENTS, "Срабатывает, когда таймлайн дошёл до этого времени.", out, "{\"at\": \"0:10\"}",
				f("at", Kind.TIME, "0:10, 12s"));
		type("every", "Каждые N секунд", EVENTS, "Срабатывает снова и снова, даже на паузе.", out, "{\"interval\": \"10s\"}",
				f("interval", Kind.TIME, "10s"));
		type("on_trigger", "По команде", EVENTS, "Срабатывает от /show trigger <имя> (или кнопкой «Сигнал»).", out, "{\"name\": \"go\"}",
				f("name", Kind.TEXT, "имя для /show trigger"));
		type("on_near", "Игрок рядом", EVENTS, "Срабатывает, когда рядом с точкой собралось сколько надо зрителей.", out,
				"{\"pos\": \"$here\", \"radius\": 8, \"count\": 1}",
				f("pos", Kind.VEC, "точка"), f("radius", Kind.NUMBER, "радиус"), f("count", Kind.NUMBER, "сколько игроков"),
				f("repeat", Kind.BOOL, "каждый раз, а не один"));

		type("wait", "Ждать", FLOW, "Передаёт сигнал дальше через время.", out, "{\"time\": \"3s\"}", f("time", Kind.TIME, "3s"));
		type("chance", "Шанс", FLOW, "С таким шансом выход «да», иначе «нет».", yesNo, "{\"percent\": 50}", f("percent", Kind.NUMBER, "0..100"));
		type("random", "Случайно", FLOW, "Один случайный выход из нескольких.", List.of(), "{\"outputs\": 3}", f("outputs", Kind.NUMBER, "сколько выходов, 2..8"));
		type("viewers", "Зрителей", FLOW, "«да», если зрителей не меньше.", yesNo, "{\"min\": 5}", f("min", Kind.NUMBER, "сколько"));
		type("once", "Один раз", FLOW, "Пропускает сигнал только в первый раз.", out, "{}");
		type("counter", "Счётчик", FLOW, "Первые N раз выход «out», потом «done». Удобно для повторов.", List.of("out", "done"),
				"{\"times\": 3}", f("times", Kind.NUMBER, "сколько раз"));
		type("set_var", "Переменная", FLOW, "Ставит значение (value) или прибавляет (add).", out, "{\"name\": \"x\", \"add\": 1}",
				f("name", Kind.TEXT, "имя"), f("value", Kind.JSON, "значение"), f("add", Kind.NUMBER, "прибавить"));
		type("check", "Проверка", FLOW, "Сравнивает переменную со значением.", yesNo, "{\"name\": \"x\", \"op\": \">=\", \"value\": 3}",
				f("name", Kind.TEXT, "имя"), f("op", Kind.TEXT, "==, !=, >, <, >=, <="), f("value", Kind.JSON, "с чем"));

		type("cue", "Действие", ACTIONS, "Любое действие таймлайна, прямо сейчас. «done» — когда закончилось.", List.of("out", "done"),
				"{\"do\": \"lightning\", \"pos\": \"$here\"}", f("do", Kind.TEXT, "тип действия: lightning, title, meteors..."));
		type("jump", "Перейти", ACTIONS, "Таймлайн прыгает на маркер или время.", out, "{\"to\": \"0:00\"}", f("to", Kind.TEXT, "маркер или время"));
		type("pause", "Пауза", ACTIONS, "Таймлайн стоит, логика работает дальше.", out, "{}");
		type("resume", "Продолжить", ACTIONS, "Таймлайн идёт дальше.", out, "{}");
		type("end", "Конец шоу", ACTIONS, "Заканчивает шоу.", List.of(), "{}");
		type("lua", "Lua", ACTIONS, "Скрипт на Lua: show.viewers(), show.cue(...), show.out(...). Работает до 50 мс.", List.of(),
				"{\"code\": \"-- show.viewers(), show.players(), show.cue('lightning', {pos = {0, 0, 0}})\\nreturn 'out'\", \"outputs\": [\"out\"]}",
				f("outputs", Kind.JSON, "[\"out\", \"другой\"]"));
	}

	private LogicSchema() {
	}

	public static Map<String, Type> types() {
		return TYPES;
	}

	public static Type type(String name) {
		return TYPES.get(name);
	}

	public static Map<String, List<Type>> groups() {
		Map<String, List<Type>> out = new LinkedHashMap<>();
		for (Type t : TYPES.values()) {
			out.computeIfAbsent(t.group(), k -> new ArrayList<>()).add(t);
		}
		return out;
	}

	public static int color(String type) {
		Type t = TYPES.get(type);
		if (t == null) {
			return 0xFF808080;
		}
		if (type.equals("lua")) {
			return 0xFF8A5CD0;
		}
		return switch (t.group()) {
			case EVENTS -> 0xFF3FA05A;
			case FLOW -> 0xFFC89A30;
			default -> 0xFF3A78C8;
		};
	}

	public static String label(JsonObject node) {
		Type t = TYPES.get(CueSchema.str(node, "type", ""));
		return t != null ? t.label() : CueSchema.str(node, "type", "?");
	}

	public static List<String> outputs(JsonObject node) {
		String type = CueSchema.str(node, "type", "");
		if (type.equals("random")) {
			int n = node.has("outputs") ? node.get("outputs").getAsInt() : 2;
			List<String> out = new ArrayList<>();
			for (int i = 1; i <= Math.max(2, Math.min(8, n)); i++) {
				out.add(String.valueOf(i));
			}
			return out;
		}
		if (type.equals("lua")) {
			List<String> out = new ArrayList<>();
			if (node.has("outputs") && node.get("outputs").isJsonArray()) {
				node.getAsJsonArray("outputs").forEach(e -> out.add(e.getAsString()));
			}
			return out.isEmpty() ? List.of("out") : out;
		}
		Type t = TYPES.get(type);
		return t != null ? t.outputs() : List.of();
	}

	public static boolean hasInput(JsonObject node) {
		Type t = TYPES.get(CueSchema.str(node, "type", ""));
		return t == null || !t.event();
	}

	/** Fields for the properties panel; a cue node also gets the fields of its cue type. */
	public static List<Field> fields(JsonObject node) {
		String type = CueSchema.str(node, "type", "");
		Type t = TYPES.get(type);
		List<Field> out = new ArrayList<>(t != null ? t.fields() : List.of());
		if (type.equals("cue")) {
			for (Field field : CueSchema.fields(node)) {
				if (!field.name().equals("at") && !field.name().equals("type") && !field.name().equals("x") && !field.name().equals("y")
						&& out.stream().noneMatch(o -> o.name().equals(field.name()))) {
					out.add(field);
				}
			}
		}
		for (String key : node.keySet()) {
			if (List.of("type", "x", "y", "code").contains(key) || out.stream().anyMatch(o -> o.name().equals(key))) {
				continue;
			}
			out.add(f(key, Kind.JSON, ""));
		}
		return out;
	}

	public static JsonObject newNode(String type, double[] here, int x, int y) {
		Type t = TYPES.get(type);
		String json = t.defaults().replace("\"$here\"", "[" + CueSchema.number(here[0]) + ", " + CueSchema.number(here[1]) + ", " + CueSchema.number(here[2]) + "]");
		JsonObject node = new JsonObject();
		node.addProperty("type", type);
		node.addProperty("x", x);
		node.addProperty("y", y);
		JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> node.add(e.getKey(), e.getValue()));
		return node;
	}
}
