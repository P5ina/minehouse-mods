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

	/**
	 * `relaunch`: the game's command line to start again once the files are in
	 * place (null: don't), run in `dir`.
	 */
	public record Plan(List<Move> moves, List<String> deletes, List<String> relaunch, String dir) {
	}

	private static final Gson GSON = new Gson();

	private UpdateApplier() {
	}

	private static Path planFile;
	private static boolean hooked;

	/**
	 * Runs the plan after the game exits. A later call replaces the plan (the
	 * player changed the mods again), so one helper process applies the last one.
	 */
	static synchronized void scheduleOnExit(Plan plan) throws Exception {
		if (planFile == null) {
			planFile = Files.createTempDirectory("minehouse-update").resolve("plan.json");
		}
		Files.writeString(planFile, GSON.toJson(plan), StandardCharsets.UTF_8);
		if (hooked) {
			return;
		}
		hooked = true;
		Path tmpDir = planFile.getParent();
		Path plan0 = planFile;

		// Our own classes + Gson, copied out so the originals can be replaced.
		Path self = Path.of(UpdateApplier.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path selfCopy = Files.isDirectory(self) ? self : Files.copy(self, tmpDir.resolve("applier.jar")); // dev runs use a classes dir
		Path gsonCopy = Files.copy(gson, tmpDir.resolve("gson.jar"));

		String javaBin = ProcessHandle.current().info().command().orElse("java");
		long pid = ProcessHandle.current().pid();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (!Files.exists(plan0)) {
				return; // cancelled
			}
			try {
				new ProcessBuilder(javaBin, "-cp", selfCopy + java.io.File.pathSeparator + gsonCopy,
					UpdateApplier.class.getName(), Long.toString(pid), plan0.toString())
					.redirectErrorStream(true)
					.redirectOutput(tmpDir.resolve("apply.log").toFile())
					.start();
			} catch (Exception e) {
				MinehouseMenu.LOG.warn("Can't start modpack update", e);
			}
		}, "MineHouse pack update"));
	}

	/** Nothing to apply any more (the player switched the mods back). */
	static synchronized void cancel() {
		if (planFile != null) {
			try {
				Files.deleteIfExists(planFile);
			} catch (Exception e) {
				MinehouseMenu.LOG.warn("Can't cancel modpack update", e);
			}
		}
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
		if (plan.relaunch() != null && !plan.relaunch().isEmpty()) {
			try {
				new ProcessBuilder(plan.relaunch())
					.directory(plan.dir() == null ? null : new java.io.File(plan.dir()))
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start();
				System.out.println("Game restarted");
			} catch (Exception e) {
				System.out.println("Can't restart the game: " + e);
				UpdateWindow.tellRestart();
			}
		}
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
