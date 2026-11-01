package pro.minehouse.menu;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a downloaded modpack update after the game has exited.
 *
 * The game registers a shutdown hook that starts this class as a separate Java
 * process (from a temp copy of our jar, since the jar itself may be replaced),
 * passing the game's pid and a plan file. It waits for the game to exit, then
 * moves *.mhnew files into place and deletes files the pack dropped.
 */
public final class UpdateApplier {
	public record Move(String from, String to) {
	}

	public record Plan(List<Move> moves, List<String> deletes) {
	}

	private static final Gson GSON = new Gson();

	private UpdateApplier() {
	}

	static void scheduleOnExit(Plan plan) throws Exception {
		Path tmpDir = Files.createTempDirectory("minehouse-update");
		Path planFile = tmpDir.resolve("plan.json");
		Files.writeString(planFile, GSON.toJson(plan), StandardCharsets.UTF_8);

		// Our own classes + Gson, copied out so the originals can be replaced.
		Path self = Path.of(UpdateApplier.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path selfCopy = Files.isDirectory(self) ? self : Files.copy(self, tmpDir.resolve("applier.jar")); // dev runs use a classes dir
		Path gsonCopy = Files.copy(gson, tmpDir.resolve("gson.jar"));

		String javaBin = ProcessHandle.current().info().command().orElse("java");
		long pid = ProcessHandle.current().pid();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				new ProcessBuilder(javaBin, "-cp", selfCopy + java.io.File.pathSeparator + gsonCopy,
					UpdateApplier.class.getName(), Long.toString(pid), planFile.toString())
					.redirectErrorStream(true)
					.redirectOutput(tmpDir.resolve("apply.log").toFile())
					.start();
			} catch (Exception e) {
				MinehouseMenu.LOG.warn("Can't start modpack update", e);
			}
		}, "MineHouse pack update"));
	}

	/** Entry point of the helper process: UpdateApplier <pid> <plan.json> */
	public static void main(String[] args) throws Exception {
		long pid = Long.parseLong(args[0]);
		ProcessHandle.of(pid).ifPresent(h -> h.onExit().join());
		Plan plan = GSON.fromJson(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), Plan.class);
		List<String> errors = new ArrayList<>();
		for (Move m : plan.moves()) {
			retry(() -> Files.move(Path.of(m.from()), Path.of(m.to()), StandardCopyOption.REPLACE_EXISTING), m.to(), errors);
		}
		for (String d : plan.deletes()) {
			retry(() -> Files.deleteIfExists(Path.of(d)), d, errors);
		}
		System.out.println(errors.isEmpty() ? "MineHouse pack updated" : "Failed: " + errors);
	}

	private interface IoAction {
		void run() throws Exception;
	}

	/** Windows can hold a file lock for a moment after the process exits. */
	private static void retry(IoAction action, String what, List<String> errors) throws InterruptedException {
		for (int i = 0; i < 20; i++) {
			try {
				action.run();
				return;
			} catch (Exception e) {
				if (i == 19) {
					errors.add(what + ": " + e);
				}
				Thread.sleep(500);
			}
		}
	}
}
