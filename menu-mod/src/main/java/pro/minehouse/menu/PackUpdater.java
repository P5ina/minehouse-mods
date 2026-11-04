package pro.minehouse.menu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Keeps the modpack in sync with the site, in any launcher.
 *
 * The site publishes /modpack/manifest.json (pack version + every file with its
 * sha512). config/minehouse-pack.json remembers which files the pack installed.
 * On start, in the background: new or changed files are downloaded next to the
 * target as *.mhnew and checked; files the pack no longer has are marked for
 * removal. Loaded jars can't be replaced while the game runs (Windows locks
 * them), so the swap happens after the game exits: {@link UpdateApplier} runs
 * as a separate process, waits for this one to end and moves the files.
 * A file the player added themselves is never touched.
 *
 * Optional mods: the manifest lists `options` the player may turn off, and
 * each file carries `choices`, the options that keep it installed (none means
 * always). The choice is `disabled` in config/minehouse-pack.json, shared with
 * the MineHouse launcher. An optional mod the player deleted or swapped for
 * another file counts as turned off and is not downloaded again; a required
 * file always comes back. {@link PackModsScreen} changes the choice in game.
 */
public final class PackUpdater {
	public enum Phase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, FAILED }

	/** `mod` is the Modrinth slug; `choices` the options that keep the file (null: always). */
	public record PackFile(String path, String url, String sha512, long size, String mod, List<String> choices) {
	}

	/** A mod the player may turn off; it can't run without the options in `requires`. */
	public record Option(String id, String name, String why, List<String> requires) {
	}

	public record Manifest(String version, List<PackFile> files, List<Option> options) {
		List<Option> optionList() {
			return options == null ? List.of() : options;
		}
	}

	/**
	 * config/minehouse-pack.json. `disabled`: options the player turned off;
	 * `installed`: option id -> its main file as installed, to notice when the
	 * player deletes it. Older versions wrote only version and files.
	 */
	public record State(String version, List<String> files, List<String> disabled, Map<String, String> installed) {
		Set<String> disabledSet() {
			return disabled == null ? new TreeSet<>() : new TreeSet<>(disabled);
		}

		Map<String, String> installedMap() {
			return installed == null ? Map.of() : installed;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static volatile Phase phase = Phase.IDLE;
	private static volatile String remoteVersion = "";
	private static volatile int done;
	private static volatile int total;
	private static volatile String localVersion = "";
	private static volatile Manifest manifest;
	/** What is on disk now (the state file as read at start). */
	private static State installedState;
	/** The player's choice as of the last run, applied after restart if it changed files. */
	private static volatile Set<String> disabledNow = Set.of();
	private static volatile boolean changed;

	private PackUpdater() {
	}

	public static Phase phase() {
		return phase;
	}

	public static String remoteVersion() {
		return remoteVersion;
	}

	public static String localVersion() {
		return localVersion;
	}

	public static int done() {
		return done;
	}

	public static int total() {
		return total;
	}

	/** The manifest once it is loaded, else null. */
	public static Manifest manifest() {
		return manifest;
	}

	public static Set<String> disabled() {
		return disabledNow;
	}

	/** Whether this run changed files (an update or another choice of mods). */
	public static boolean changed() {
		return changed;
	}

	/** False when the game was not installed from our pack (no state file): nothing to choose. */
	public static boolean managed() {
		return installedState != null;
	}

	private static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	private static Path stateFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("minehouse-pack.json");
	}

	public static void start() {
		State state = readState();
		if (state == null) {
			// Not installed from our pack (e.g. a dev run): nothing to keep in sync.
			MinehouseMenu.LOG.info("No config/minehouse-pack.json, auto-update is off");
			return;
		}
		installedState = state;
		disabledNow = state.disabledSet();
		localVersion = state.version();
		if (MenuConfig.updateUrl.isEmpty()) {
			return;
		}
		background(null);
	}

	/** Applies the player's choice of optional mods: downloads now, swaps files after the game exits. */
	public static void choose(Set<String> disabled) {
		if (installedState == null) {
			return;
		}
		background(new TreeSet<>(disabled));
	}

	private static void background(Set<String> disabled) {
		Thread t = new Thread(() -> {
			try {
				run(disabled);
			} catch (Exception e) {
				phase = Phase.FAILED;
				MinehouseMenu.LOG.warn("Modpack update failed", e);
			}
		}, "MineHouse pack updater");
		t.setDaemon(true);
		t.start();
	}

	private static State readState() {
		try {
			return GSON.fromJson(Files.readString(stateFile(), StandardCharsets.UTF_8), State.class);
		} catch (Exception e) {
			return null;
		}
	}

	private static Manifest fetchManifest() throws Exception {
		HttpResponse<String> res = HTTP.send(
			HttpRequest.newBuilder(URI.create(MenuConfig.updateUrl)).timeout(Duration.ofSeconds(20)).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
		if (res.statusCode() != 200) {
			throw new IOException("manifest: HTTP " + res.statusCode());
		}
		return GSON.fromJson(res.body(), Manifest.class);
	}

	/** Options that end up on: not turned off, and everything they require is on. */
	public static Set<String> enabled(Manifest m, Set<String> disabled) {
		Map<String, Option> byId = new HashMap<>();
		for (Option o : m.optionList()) {
			byId.put(o.id(), o);
		}
		Set<String> on = new HashSet<>();
		for (Option o : m.optionList()) {
			if (isOn(o.id(), byId, disabled, new HashSet<>())) {
				on.add(o.id());
			}
		}
		return on;
	}

	private static boolean isOn(String id, Map<String, Option> byId, Set<String> disabled, Set<String> seen) {
		Option o = byId.get(id);
		if (o == null || disabled.contains(id)) {
			return false;
		}
		if (!seen.add(id) || o.requires() == null) {
			return true;
		}
		for (String r : o.requires()) {
			if (!isOn(r, byId, disabled, seen)) {
				return false;
			}
		}
		return true;
	}

	private static boolean wanted(PackFile f, Set<String> on) {
		return f.choices() == null || f.choices().isEmpty() || f.choices().stream().anyMatch(on::contains);
	}

	private static synchronized void run(Set<String> chosen) throws Exception {
		phase = Phase.CHECKING;
		if (manifest == null) {
			manifest = fetchManifest();
		}
		Manifest m = manifest;
		State state = installedState;
		remoteVersion = m.version();
		Path root = gameDir().toAbsolutePath().normalize();
		for (PackFile f : m.files()) {
			safe(root, f.path());
		}

		Set<String> disabled;
		if (chosen != null) {
			disabled = chosen;
		} else {
			disabled = state.disabledSet();
			// The player deleted an optional mod (or swapped it for their own file): keep it off.
			Set<String> old = new HashSet<>(state.files());
			for (Option o : m.optionList()) {
				if (disabled.contains(o.id())) {
					continue;
				}
				PackFile main = m.files().stream().filter(f -> o.id().equals(f.mod())).findFirst().orElse(null);
				if (main == null) {
					continue;
				}
				String prev = state.installedMap().get(o.id());
				if (prev == null && old.contains(main.path())) {
					prev = main.path();
				}
				if (prev != null && !Files.exists(safe(root, prev)) && !Files.exists(safe(root, main.path()))) {
					disabled.add(o.id());
					MinehouseMenu.LOG.info("{} was removed by the player, keeping it off", o.id());
				}
			}
		}
		disabledNow = Set.copyOf(disabled);
		Set<String> on = enabled(m, disabled);
		List<PackFile> keep = m.files().stream().filter(f -> wanted(f, on)).toList();
		Set<String> keepPaths = keep.stream().map(PackFile::path).collect(Collectors.toSet());

		List<PackFile> download = new ArrayList<>();
		for (PackFile f : keep) {
			Path target = safe(root, f.path());
			if (!Files.exists(target) || !f.sha512().equals(sha512(target))) {
				download.add(f);
			}
		}
		Set<String> remove = new LinkedHashSet<>();
		for (String p : state.files()) {
			if (!keepPaths.contains(p)) {
				remove.add(p);
			}
		}
		List<String> deletes = remove.stream()
			.map(p -> safe(root, p))
			.filter(Files::exists)
			.map(Path::toString)
			.toList();

		Map<String, String> installed = new TreeMap<>();
		for (PackFile f : keep) {
			if (f.mod() != null && on.contains(f.mod())) {
				installed.put(f.mod(), f.path());
			}
		}
		State next = new State(m.version(), keep.stream().map(PackFile::path).toList(), List.copyOf(new TreeSet<>(disabled)), installed);

		if (download.isEmpty() && deletes.isEmpty()) {
			UpdateApplier.cancel();
			changed = false;
			if (!GSON.toJson(next).equals(GSON.toJson(state))) {
				Files.writeString(stateFile(), GSON.toJson(next), StandardCharsets.UTF_8);
				installedState = next;
			}
			localVersion = m.version();
			phase = Phase.UP_TO_DATE;
			return;
		}

		phase = Phase.DOWNLOADING;
		total = download.size();
		done = 0;
		List<UpdateApplier.Move> moves = new ArrayList<>();
		for (PackFile f : download) {
			Path target = safe(root, f.path());
			Path tmp = target.resolveSibling(target.getFileName() + ".mhnew");
			Files.createDirectories(target.getParent());
			HttpResponse<InputStream> body = HTTP.send(
				HttpRequest.newBuilder(URI.create(f.url())).timeout(Duration.ofMinutes(5)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream()
			);
			if (body.statusCode() != 200) {
				throw new IOException(f.path() + ": HTTP " + body.statusCode());
			}
			try (InputStream in = body.body()) {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			}
			if (Files.size(tmp) != f.size() || !f.sha512().equals(sha512(tmp))) {
				Files.deleteIfExists(tmp);
				throw new IOException(f.path() + ": hash mismatch");
			}
			moves.add(new UpdateApplier.Move(tmp.toString(), target.toString()));
			done++;
		}

		Path nextState = stateFile().resolveSibling("minehouse-pack.json.mhnew");
		Files.writeString(nextState, GSON.toJson(next), StandardCharsets.UTF_8);
		moves.add(new UpdateApplier.Move(nextState.toString(), stateFile().toString()));

		UpdateApplier.scheduleOnExit(new UpdateApplier.Plan(moves, deletes));
		changed = true;
		phase = Phase.READY;
		MinehouseMenu.LOG.info("Modpack {} -> {}: {} files downloaded, {} to remove after exit",
			state.version(), m.version(), download.size(), deletes.size());
	}

	/** Resolves a manifest path inside the game dir; refuses anything that escapes it. */
	private static Path safe(Path root, String rel) {
		Path p = root.resolve(rel).normalize();
		if (!p.startsWith(root) || rel.contains("\\")) {
			throw new IllegalArgumentException("bad path in manifest: " + rel);
		}
		return p;
	}

	static String sha512(Path file) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-512");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buf = new byte[65536];
				int n;
				while ((n = in.read(buf)) > 0) {
					md.update(buf, 0, n);
				}
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
