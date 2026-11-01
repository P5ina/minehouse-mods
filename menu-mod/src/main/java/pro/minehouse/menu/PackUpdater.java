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
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 */
public final class PackUpdater {
	public enum Phase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, FAILED }

	public record PackFile(String path, String url, String sha512, long size) {
	}

	public record Manifest(String version, List<PackFile> files) {
	}

	/** config/minehouse-pack.json */
	public record State(String version, List<String> files) {
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
		localVersion = state.version();
		if (MenuConfig.updateUrl.isEmpty()) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				run(state);
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

	private static void run(State state) throws Exception {
		phase = Phase.CHECKING;
		HttpResponse<String> res = HTTP.send(
			HttpRequest.newBuilder(URI.create(MenuConfig.updateUrl)).timeout(Duration.ofSeconds(20)).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
		if (res.statusCode() != 200) {
			throw new IOException("manifest: HTTP " + res.statusCode());
		}
		Manifest manifest = GSON.fromJson(res.body(), Manifest.class);
		remoteVersion = manifest.version();
		if (manifest.version().equals(state.version())) {
			phase = Phase.UP_TO_DATE;
			return;
		}

		Path root = gameDir().toAbsolutePath().normalize();
		Map<String, PackFile> wanted = manifest.files().stream()
			.collect(Collectors.toMap(PackFile::path, Function.identity(), (a, b) -> a));
		for (String p : wanted.keySet()) {
			safe(root, p);
		}

		List<PackFile> download = new ArrayList<>();
		for (PackFile f : manifest.files()) {
			Path target = safe(root, f.path());
			if (!Files.exists(target) || !f.sha512().equals(sha512(target))) {
				download.add(f);
			}
		}
		Set<String> remove = new LinkedHashSet<>();
		for (String p : state.files()) {
			if (!wanted.containsKey(p)) {
				remove.add(p);
			}
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

		List<String> deletes = remove.stream()
			.map(p -> safe(root, p))
			.filter(Files::exists)
			.map(Path::toString)
			.toList();
		State next = new State(manifest.version(), manifest.files().stream().map(PackFile::path).toList());
		Path nextState = stateFile().resolveSibling("minehouse-pack.json.mhnew");
		Files.writeString(nextState, GSON.toJson(next), StandardCharsets.UTF_8);
		moves.add(new UpdateApplier.Move(nextState.toString(), stateFile().toString()));

		UpdateApplier.Plan plan = new UpdateApplier.Plan(moves, deletes);
		UpdateApplier.scheduleOnExit(plan);
		phase = Phase.READY;
		MinehouseMenu.LOG.info("Modpack {} -> {}: {} files downloaded, {} to remove after exit",
			state.version(), manifest.version(), download.size(), deletes.size());
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
