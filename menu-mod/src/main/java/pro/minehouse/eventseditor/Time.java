package pro.minehouse.eventseditor;

import java.util.Locale;

/** Show time in ticks, written like the server writes it: "1:05.5", "12s", "500ms", "40t" or seconds. */
public final class Time {
	public static final int TICKS_PER_SECOND = 20;

	private Time() {
	}

	public static int parse(String text) {
		String s = text.trim().toLowerCase(Locale.ROOT);
		try {
			int ticks;
			if (s.endsWith("ms")) {
				ticks = (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) / 50.0);
			} else if (s.endsWith("t")) {
				ticks = Integer.parseInt(s.substring(0, s.length() - 1));
			} else if (s.endsWith("s")) {
				ticks = (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * TICKS_PER_SECOND);
			} else if (s.endsWith("m")) {
				ticks = (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 60 * TICKS_PER_SECOND);
			} else if (s.contains(":")) {
				String[] parts = s.split(":");
				double seconds = 0;
				for (String part : parts) {
					seconds = seconds * 60 + Double.parseDouble(part);
				}
				ticks = (int) Math.round(seconds * TICKS_PER_SECOND);
			} else {
				ticks = (int) Math.round(Double.parseDouble(s) * TICKS_PER_SECOND);
			}
			if (ticks < 0) {
				throw new IllegalArgumentException("время не может быть отрицательным: " + text);
			}
			return ticks;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("не понимаю время \"" + text + "\"");
		}
	}

	/** "0:12", "1:05.5": minutes, seconds and tenths when there are any. */
	public static String format(int ticks) {
		int tenths = Math.round(ticks / 2f);
		int seconds = tenths / 10;
		String out = seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
		return tenths % 10 == 0 ? out : out + "." + tenths % 10;
	}
}
