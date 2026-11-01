package pro.minehouse.eventseditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * What the editor knows about each kind of cue and actor: its fields (to show them even when the
 * script leaves them out), how to turn a field's text box back into JSON, what a new one looks
 * like, which track it goes to and its colour on the timeline. The server checks everything again.
 */
public final class CueSchema {
	private static final Gson GSON = new Gson();

	/** How a field's text is read back. */
	public enum Kind {
		/** Time: "0:12", "3s", "40t" or seconds. */
		TIME,
		NUMBER,
		/** [x, y, z] from the stage; the editor can fill it from where the admin is. */
		VEC,
		TEXT,
		BOOL,
		/** Anything else, as JSON. */
		JSON
	}

	public record Field(String name, Kind kind, String hint) {
	}

	public record Type(String name, String label, String track, int color, List<Field> fields, String defaults) {
	}

	private static final Map<String, Type> TYPES = new LinkedHashMap<>();
	private static final Map<String, List<Field>> ACTORS = new LinkedHashMap<>();

	private static Field f(String name, Kind kind, String hint) {
		return new Field(name, kind, hint);
	}

	private static final Field AT = f("at", Kind.TIME, "когда: 0:12, 3s");
	private static final Field OVER = f("over", Kind.TIME, "сколько длится");
	private static final Field EASE = f("ease", Kind.TEXT, "linear, inQuad, outBack, inOutSine...");
	private static final Field ACTOR = f("actor", Kind.TEXT, "имя актёра");

	private static void type(String name, String label, String track, int color, String defaults, Field... fields) {
		List<Field> all = new ArrayList<>();
		all.add(AT);
		all.addAll(List.of(fields));
		TYPES.put(name, new Type(name, label, track, color, List.copyOf(all), defaults));
	}

	static {
		int camera = 0xFF4FA3FF;
		int actors = 0xFF6BD36B;
		int world = 0xFFB98CFF;
		int fx = 0xFFFF8A3D;
		int text = 0xFFF2D04B;

		type("cinematic", "Кино-камера", "camera", camera, "{\"on\": true}",
				f("on", Kind.BOOL, "true: смотреть через камеру, false: отпустить"), f("actor", Kind.TEXT, "камера (cam)"));
		type("cut", "Склейка", "camera", camera, "{\"pos\": \"$here\", \"look\": [0, 1, 0]}",
				f("pos", Kind.VEC, "где камера"), f("look", Kind.VEC, "куда смотрит"), f("yaw", Kind.NUMBER, "или поворот"),
				f("pitch", Kind.NUMBER, "и наклон"), f("actor", Kind.TEXT, "камера (cam)"));
		type("path", "Пролёт", "camera", camera, "{\"orbit\": {\"center\": [0, 0, 0], \"radius\": 20, \"height\": 10, \"from\": 0, \"to\": 180}, \"over\": \"8s\"}",
				OVER, EASE, f("orbit", Kind.JSON, "{center, radius, height, from, to}"), f("points", Kind.JSON, "[{pos, yaw, pitch}, ...]"),
				f("path", Kind.TEXT, "записанный путь (/show cam)"), f("look", Kind.VEC, "всё время смотреть сюда"),
				f("actor", Kind.TEXT, "камера (cam)"));
		type("shake", "Тряска", "camera", camera, "{\"strength\": 0.5, \"over\": \"1s\"}",
				f("strength", Kind.NUMBER, "сила, 0.2-2"), OVER);

		type("spawn", "Появление", "actors", actors, "{\"actor\": \"$actor\", \"pos\": \"$here\"}",
				ACTOR, f("pos", Kind.VEC, "где"), f("yaw", Kind.NUMBER, "поворот"), f("scale", Kind.JSON, "размер: 2 или [x, y, z]"));
		type("move", "Движение", "actors", actors, "{\"actor\": \"$actor\", \"to\": \"$here\", \"over\": \"3s\"}",
				ACTOR, f("to", Kind.VEC, "куда"), OVER, EASE, f("yaw", Kind.NUMBER, "повернуть к"));
		type("transform", "Поворот/размер", "actors", actors, "{\"actor\": \"$actor\", \"rotate\": [0, 90, 0], \"over\": \"2s\"}",
				ACTOR, f("rotate", Kind.VEC, "[x, y, z] в градусах"), f("scale", Kind.JSON, "2 или [x, y, z]"), OVER, EASE);
		type("remove", "Убрать", "actors", actors, "{\"actor\": \"$actor\"}", ACTOR);

		type("time", "Время суток", "world", world, "{\"to\": 18000, \"over\": \"5s\"}",
				f("to", Kind.NUMBER, "0-24000: 6000 полдень, 18000 полночь"), OVER, EASE, f("backward", Kind.BOOL, "назад"));
		type("weather", "Погода", "world", world, "{\"to\": \"thunder\", \"over\": \"5s\"}",
				f("to", Kind.TEXT, "clear, rain, thunder"), OVER);
		type("blocks", "Блоки", "world", world,
				"{\"mode\": \"ghost\", \"pos\": \"$here\", \"shape\": {\"type\": \"sphere\", \"radius\": 3, \"palette\": [\"minecraft:stone\"]}}",
				f("mode", Kind.TEXT, "ghost, temporary, permanent"), f("pos", Kind.VEC, "где"),
				f("shape", Kind.JSON, "{type: sphere/box/disc/bowl, radius, palette}"), f("set", Kind.TEXT, "снятая постройка (/show capture)"));
		type("clear_blocks", "Убрать блоки", "world", world, "{\"mode\": \"all\"}", f("mode", Kind.TEXT, "ghost, temporary, all"));

		type("sound", "Звук", "audio", fx, "{\"id\": \"minecraft:entity.generic.explode\"}",
				f("id", Kind.TEXT, "minecraft:..."), f("volume", Kind.NUMBER, "громкость"), f("pitch", Kind.NUMBER, "высота, 0.5-2"),
				f("pos", Kind.VEC, "откуда (без места: у каждого)"), f("actor", Kind.TEXT, "от актёра"), f("source", Kind.TEXT, "master, music..."));
		type("particles", "Частицы", "fx", fx, "{\"id\": \"minecraft:flame\", \"pos\": \"$here\", \"count\": 20, \"over\": \"2s\"}",
				f("id", Kind.TEXT, "minecraft:flame, end_rod..."), f("pos", Kind.VEC, "где"), f("actor", Kind.TEXT, "у актёра"),
				f("count", Kind.NUMBER, "сколько за тик"), f("spread", Kind.JSON, "разброс: 1 или [x, y, z]"), f("speed", Kind.NUMBER, "скорость"), OVER);
		type("lightning", "Молния", "fx", fx, "{\"pos\": \"$here\"}", f("pos", Kind.VEC, "куда"));
		type("explosion", "Взрыв", "fx", fx, "{\"pos\": \"$here\"}", f("pos", Kind.VEC, "где"), f("sound", Kind.BOOL, "со звуком"));
		type("meteors", "Метеоритный дождь", "fx", fx,
				"{\"over\": \"30s\", \"count\": 20, \"spots\": \"all\", \"aroundPlayers\": true, \"radius\": 30}",
				OVER, f("count", Kind.NUMBER, "сколько на каждое место"), f("spots", Kind.JSON, "\"all\" или [\"имя\", ...] (/show spot)"),
				f("aroundPlayers", Kind.BOOL, "ещё и вокруг каждого игрока"), f("radius", Kind.NUMBER, "разброс вокруг места"),
				f("size", Kind.JSON, "размер: 1 или [от, до]"), f("block", Kind.TEXT, "minecraft:magma_block"),
				f("fall", Kind.TIME, "сколько летит один (до 2.9s)"), f("height", Kind.NUMBER, "откуда падают"),
				f("craters", Kind.BOOL, "кратеры"));
		type("rift", "Разлом", "fx", fx,
				"{\"name\": \"rift\", \"pos\": \"$here\", \"length\": 30, \"width\": 3, \"over\": \"20s\"}",
				f("name", Kind.TEXT, "имя: по нему летают ракеты"), f("pos", Kind.VEC, "середина разлома"), OVER,
				f("length", Kind.NUMBER, "длина, блоков"), f("width", Kind.NUMBER, "ширина"), f("yaw", Kind.NUMBER, "направление, градусы"),
				f("tilt", Kind.NUMBER, "наклон, градусы"), f("open", Kind.TIME, "раскрывается за"), f("close", Kind.TIME, "закрывается за"),
				f("glow", Kind.TEXT, "цвет свечения RGB, c040ff"), f("core", Kind.TEXT, "блок середины"), f("edge", Kind.TEXT, "блок края"),
				f("sound", Kind.BOOL, "со звуком"));
		type("rockets", "Ракеты между разломами", "fx", fx,
				"{\"between\": [\"a\", \"b\"], \"over\": \"20s\", \"count\": 30, \"flight\": \"3s\"}",
				f("between", Kind.JSON, "[\"разлом\", ...] или [[x, y, z], ...]"), OVER, f("count", Kind.NUMBER, "сколько всего"),
				f("flight", Kind.TIME, "сколько летит одна"), f("arc", Kind.NUMBER, "высота дуги"), f("size", Kind.NUMBER, "размер"),
				f("body", Kind.TEXT, "блок корпуса"), f("nose", Kind.TEXT, "блок носа и крыльев"), f("flame", Kind.TEXT, "блок сопла"),
				f("sound", Kind.BOOL, "со звуком"));
		type("shockwave", "Ударная волна", "fx", fx, "{\"pos\": \"$here\", \"radius\": 30, \"over\": \"1.5s\"}",
				f("pos", Kind.VEC, "центр"), f("radius", Kind.NUMBER, "до какого радиуса"), OVER, EASE,
				f("id", Kind.TEXT, "частицы: minecraft:cloud, flame..."), f("ground", Kind.BOOL, "по рельефу"));

		type("title", "Титры", "text", text, "{\"text\": \"&6Заголовок\", \"sub\": \"&7подзаголовок\"}",
				f("text", Kind.TEXT, "&-коды цвета"), f("sub", Kind.TEXT, "подзаголовок"), f("fadeIn", Kind.TIME, "появление"),
				f("stay", Kind.TIME, "держится"), f("fadeOut", Kind.TIME, "исчезает"));
		type("actionbar", "Над хотбаром", "text", text, "{\"text\": \"&7...\"}", f("text", Kind.TEXT, "&-коды цвета"));
		type("chat", "В чат", "text", text, "{\"text\": \"&7...\"}", f("text", Kind.TEXT, "&-коды цвета"));

		List<Field> common = List.of(f("scale", Kind.JSON, "2 или [x, y, z]"), f("bright", Kind.BOOL, "светится в темноте"),
				f("viewRange", Kind.NUMBER, "видно издалека: 1 = 64 блока"));
		ACTORS.put("camera", List.of());
		ACTORS.put("block", concat(List.of(f("block", Kind.TEXT, "minecraft:stone или с [свойствами]")), common));
		ACTORS.put("item", concat(List.of(f("item", Kind.TEXT, "minecraft:diamond")), common));
		ACTORS.put("text", concat(List.of(f("text", Kind.TEXT, "&-коды цвета"), f("lineWidth", Kind.NUMBER, "ширина строки"),
				f("background", Kind.TEXT, "фон ARGB, 40000000")), common));
		ACTORS.put("model", concat(List.of(f("blocks", Kind.TEXT, "снятая постройка (/show capture)"),
				f("pivot", Kind.VEC, "ось вращения")), common));
	}

	private static List<Field> concat(List<Field> a, List<Field> b) {
		List<Field> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private CueSchema() {
	}

	private static final Map<String, String> GROUP_TITLES = Map.of("camera", "Камера", "actors", "Актёры", "world", "Мир",
			"audio", "Звук", "fx", "Эффекты", "text", "Текст");
	private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
			Map.entry("cinematic", "Включает кино-режим: зрители смотрят через камеру шоу, HUD скрыт. on: false — вернуть обычный вид."),
			Map.entry("cut", "Мгновенно ставит камеру туда, где вы стоите, и направляет на точку look."),
			Map.entry("path", "Камера плавно летит: по кругу вокруг центра (orbit) или по точкам."),
			Map.entry("shake", "Трясёт камеру зрителей, например при взрыве."),
			Map.entry("spawn", "Показывает актёра (блок, предмет, текст, постройку) там, где вы стоите. Актёра сначала создайте справа."),
			Map.entry("move", "Плавно переносит актёра туда, где вы стоите."),
			Map.entry("transform", "Плавно поворачивает или меняет размер актёра."),
			Map.entry("remove", "Убирает актёра со сцены."),
			Map.entry("time", "Меняет время суток у зрителей (18000 — полночь)."),
			Map.entry("weather", "Меняет погоду у зрителей: clear, rain, thunder."),
			Map.entry("blocks", "Ставит блоки: призрачные (видны только зрителям) или временные."),
			Map.entry("clear_blocks", "Убирает поставленные шоу блоки."),
			Map.entry("sound", "Проигрывает звук."),
			Map.entry("particles", "Пускает частицы там, где вы стоите."),
			Map.entry("lightning", "Бьёт молнией (без урона)."),
			Map.entry("explosion", "Взрыв без разрушений и урона."),
			Map.entry("meteors", "Метеоритный дождь: падают огненные шары на точки, отмеченные /show spot add."),
			Map.entry("rift", "Разлом в небе: раскрывается, искрит и закрывается в конце. Мир не меняется."),
			Map.entry("rockets", "Ракеты вылетают из одного открытого разлома и улетают в другой."),
			Map.entry("shockwave", "Кольцо частиц разбегается от точки по земле."),
			Map.entry("title", "Большой заголовок по центру экрана."),
			Map.entry("actionbar", "Строка над хотбаром."),
			Map.entry("chat", "Сообщение в чат зрителям."));

	/** The add menu: types grouped by track, in the order they were declared. */
	public static Map<String, List<Type>> groups() {
		Map<String, List<Type>> out = new LinkedHashMap<>();
		for (Type t : TYPES.values()) {
			out.computeIfAbsent(GROUP_TITLES.getOrDefault(t.track(), t.track()), k -> new ArrayList<>()).add(t);
		}
		return out;
	}

	public static String description(String type) {
		return DESCRIPTIONS.getOrDefault(type, type);
	}

	public static Map<String, Type> types() {
		return TYPES;
	}

	public static Type type(String name) {
		return TYPES.get(name);
	}

	public static List<String> actorTypes() {
		return List.copyOf(ACTORS.keySet());
	}

	public static int color(String type) {
		Type t = TYPES.get(type);
		return t != null ? t.color() : 0xFF9A9A9A;
	}

	/** The cue's fields to show: the known ones for its type, then whatever else it has. */
	public static List<Field> fields(JsonObject cue) {
		Type t = TYPES.get(str(cue, "do", ""));
		List<Field> out = new ArrayList<>(t != null ? t.fields() : List.of(AT));
		addExtra(cue, out, "do");
		return out;
	}

	public static List<Field> actorFields(JsonObject actor) {
		List<Field> out = new ArrayList<>();
		out.add(f("type", Kind.TEXT, String.join(", ", ACTORS.keySet())));
		out.addAll(ACTORS.getOrDefault(str(actor, "type", ""), List.of()));
		addExtra(actor, out, null);
		return out;
	}

	private static void addExtra(JsonObject o, List<Field> out, String skip) {
		for (String key : o.keySet()) {
			if (key.equals(skip) || out.stream().anyMatch(field -> field.name().equals(key))) {
				continue;
			}
			out.add(f(key, Kind.JSON, ""));
		}
	}

	/** How long the cue runs, for its bar on the timeline (0: it happens at once). */
	public static int duration(JsonObject cue) {
		try {
			String type = str(cue, "do", "");
			int over = cue.has("over") ? time(cue.get("over")) : 0;
			return switch (type) {
				case "meteors" -> (cue.has("over") ? over : 600) + (cue.has("fall") ? time(cue.get("fall")) : 32);
				case "rift" -> cue.has("over") ? over : 400;
				case "rockets" -> (cue.has("over") ? over : 400) + (cue.has("flight") ? time(cue.get("flight")) : 60) + 1;
				case "shockwave" -> cue.has("over") ? over : 30;
				case "title" -> (cue.has("fadeIn") ? time(cue.get("fadeIn")) : 10) + (cue.has("stay") ? time(cue.get("stay")) : 60)
						+ (cue.has("fadeOut") ? time(cue.get("fadeOut")) : 20);
				case "path" -> cue.has("over") ? over : 100;
				case "shake" -> cue.has("over") ? over : 20;
				default -> over;
			};
		} catch (RuntimeException e) {
			return 0;
		}
	}

	public static int at(JsonObject cue) {
		try {
			return cue.has("at") ? time(cue.get("at")) : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	public static int time(JsonElement e) {
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return (int) Math.round(e.getAsDouble() * Time.TICKS_PER_SECOND);
		}
		return Time.parse(e.getAsString());
	}

	static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
	}

	// --- Field text <-> JSON ------------------------------------------------------------------

	/** What a text box shows for a value: plain text for strings, compact JSON for the rest. */
	public static String toText(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return "";
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			return value.getAsString();
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return number(value.getAsDouble());
		}
		return GSON.toJson(value).replace(",", ", ").replace(":", ": ");
	}

	static String number(double v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e9) {
			return Long.toString((long) v);
		}
		String s = String.format(Locale.ROOT, "%.3f", v);
		return s.replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	/** A text box back to JSON; null means leave the field out. Throws with a message for the admin. */
	public static JsonElement fromText(Field field, String text) {
		String s = text.trim();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return switch (field.kind()) {
				case TEXT -> new JsonPrimitive(text);
				case TIME -> {
					Time.parse(s); // checks it
					yield s.matches("-?[0-9.]+") ? new JsonPrimitive(Double.parseDouble(s)) : new JsonPrimitive(s);
				}
				case NUMBER -> {
					double v = Double.parseDouble(s.replace(',', '.'));
					yield v == Math.rint(v) ? new JsonPrimitive((long) v) : new JsonPrimitive(v);
				}
				case BOOL -> switch (s.toLowerCase(Locale.ROOT)) {
					case "true", "да", "1", "+", "вкл" -> new JsonPrimitive(true);
					case "false", "нет", "0", "-", "выкл" -> new JsonPrimitive(false);
					default -> throw new IllegalArgumentException("да или нет");
				};
				case VEC -> {
					JsonElement e = JsonParser.parseString(s.startsWith("[") ? s : "[" + s.replace(' ', ',').replaceAll(",+", ",") + "]");
					if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
						throw new IllegalArgumentException("нужно три числа: x y z");
					}
					yield e;
				}
				case JSON -> {
					try {
						yield JsonParser.parseString(s);
					} catch (RuntimeException e) {
						yield new JsonPrimitive(text); // a bare word is a string
					}
				}
			};
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(field.name() + ": " + e.getMessage(), e);
		}
	}

	/** A new cue of this type at this time; "$here" becomes the given position, "$actor" the first actor. */
	public static JsonObject newCue(String type, int at, double[] here, String actor) {
		Type t = TYPES.get(type);
		String json = t.defaults()
				.replace("\"$here\"", "[" + number(here[0]) + ", " + number(here[1]) + ", " + number(here[2]) + "]")
				.replace("$actor", actor == null ? "" : actor);
		JsonObject cue = new JsonObject();
		cue.addProperty("at", Time.format(at));
		cue.addProperty("do", type);
		JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> cue.add(e.getKey(), e.getValue()));
		return cue;
	}
}
