package pro.minehouse.menu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The command line that started this game, to start it again after an update.
 *
 * Only for games launched straight into Fabric's Knot (our launcher, the
 * official launcher, MultiMC-style launchers that pass everything on the
 * command line). Prism and similar wrappers feed the launch parameters through
 * stdin, so repeating their command would hang: then the player restarts.
 */
final class Relaunch {
	private Relaunch() {
	}

	static List<String> command() {
		try {
			String main = System.getProperty("sun.java.command", "");
			if (!main.startsWith("net.fabricmc.loader.impl.launch.knot.KnotClient")
				&& !main.startsWith("net.fabricmc.loader.launch.knot.KnotClient")) {
				MinehouseMenu.LOG.info("Launched via {}, the player restarts the game after an update", first(main));
				return null;
			}
			ProcessHandle.Info info = ProcessHandle.current().info();
			String java = info.command().orElse(null);
			if (java == null) {
				return null;
			}
			List<String> cmd = null;
			String[] args = info.arguments().orElse(null);
			if (args != null && args.length > 0) {
				cmd = new ArrayList<>();
				cmd.add(java);
				cmd.addAll(Arrays.asList(args));
			} else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
				// Windows doesn't give Java the arguments of a process: ask kernel32 through JNA (shipped with Minecraft).
				String line = windowsCommandLine();
				if (line != null) {
					List<String> parsed = parseWindows(line);
					if (parsed.size() > 1) {
						cmd = new ArrayList<>(parsed);
						cmd.set(0, java);
					}
				}
			}
			if (cmd == null || !cmd.contains(first(main))) {
				return null;
			}
			// Natives in a folder the launcher deletes on exit can't be reused.
			for (String dir : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
				if (!dir.isBlank() && dir.toLowerCase(Locale.ROOT).contains("temp")) {
					MinehouseMenu.LOG.info("Natives in a temp folder ({}), the player restarts the game after an update", dir);
					return null;
				}
			}
			return cmd;
		} catch (Throwable t) {
			MinehouseMenu.LOG.warn("Can't read the game's command line", t);
			return null;
		}
	}

	private static String first(String s) {
		int i = s.indexOf(' ');
		return i < 0 ? s : s.substring(0, i);
	}

	private static String windowsCommandLine() {
		try {
			Class<?> fn = Class.forName("com.sun.jna.Function");
			Object f = fn.getMethod("getFunction", String.class, String.class).invoke(null, "kernel32", "GetCommandLineW");
			return (String) fn.getMethod("invokeString", Object[].class, boolean.class).invoke(f, new Object[] {new Object[0], true});
		} catch (Throwable t) {
			MinehouseMenu.LOG.warn("GetCommandLineW failed", t);
			return null;
		}
	}

	/** Splits a Windows command line the way the C runtime does (CommandLineToArgvW rules). */
	static List<String> parseWindows(String line) {
		List<String> out = new ArrayList<>();
		int i = 0;
		int n = line.length();
		while (i < n && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		// argv[0]: no escapes, quotes only group.
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		while (i < n) {
			char c = line.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (!quoted && (c == ' ' || c == '\t')) {
				break;
			} else {
				cur.append(c);
			}
			i++;
		}
		out.add(cur.toString());
		while (true) {
			while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
				i++;
			}
			if (i >= n) {
				break;
			}
			cur = new StringBuilder();
			quoted = false;
			while (i < n) {
				char c = line.charAt(i);
				if (c == '\\') {
					int slashes = 0;
					while (i < n && line.charAt(i) == '\\') {
						slashes++;
						i++;
					}
					if (i < n && line.charAt(i) == '"') {
						cur.append("\\".repeat(slashes / 2));
						if (slashes % 2 == 1) {
							cur.append('"');
							i++;
						}
					} else {
						cur.append("\\".repeat(slashes));
					}
					continue;
				}
				if (c == '"') {
					if (quoted && i + 1 < n && line.charAt(i + 1) == '"') {
						cur.append('"');
						i += 2;
						continue;
					}
					quoted = !quoted;
					i++;
					continue;
				}
				if (!quoted && (c == ' ' || c == '\t')) {
					break;
				}
				cur.append(c);
				i++;
			}
			out.add(cur.toString());
		}
		return out;
	}
}
