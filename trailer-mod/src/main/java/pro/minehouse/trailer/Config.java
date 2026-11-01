package pro.minehouse.trailer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

/** config/minehouse-trailer.json: where the stage is (per world) and which skins the NPCs wear. */
final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static final String PACK = "MineHouse Trailer Music";
	private static JsonObject json = new JsonObject();

	private Config() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("minehouse-trailer.json");
	}

	static void load() {
		try {
			if (Files.exists(file())) {
				json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			MinehouseTrailer.LOG.warn("Не прочитался {}: {}", file(), e.toString());
		}
		if (!json.has("skins")) {
			json.add("skins", new JsonArray());
			save();
		}
	}

	private static void save() {
		try {
			Files.writeString(file(), GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MinehouseTrailer.LOG.warn("Не записался {}: {}", file(), e.toString());
		}
	}

	private static String worldKey() {
		var server = Minecraft.getInstance().getSingleplayerServer();
		return server != null ? server.getWorldData().getLevelName() : "?";
	}

	static BlockPos origin() {
		JsonObject stages = json.has("stages") ? json.getAsJsonObject("stages") : null;
		if (stages == null || !stages.has(worldKey())) {
			return null;
		}
		JsonArray a = stages.getAsJsonArray(worldKey());
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	static void setOrigin(BlockPos pos) {
		if (!json.has("stages")) {
			json.add("stages", new JsonObject());
		}
		JsonArray a = new JsonArray();
		a.add(pos.getX());
		a.add(pos.getY());
		a.add(pos.getZ());
		json.getAsJsonObject("stages").add(worldKey(), a);
		save();
	}

	/** Player names whose skins the NPCs wear (looked up online); empty means the nine default skins. */
	static List<String> skins() {
		List<String> out = new ArrayList<>();
		if (json.has("skins")) {
			json.getAsJsonArray("skins").forEach(e -> out.add(e.getAsString()));
		}
		return out;
	}

	static boolean hasMusic() {
		return Minecraft.getInstance().getResourceManager()
				.getResource(Identifier.fromNamespaceAndPath(MinehouseTrailer.MOD_ID, "sounds/music.ogg")).isPresent();
	}

	/** Makes resourcepacks/MineHouse Trailer Music with an empty sounds folder; returns that folder. */
	static Path makeMusicPack() throws IOException {
		Path pack = Minecraft.getInstance().getResourcePackDirectory().resolve(PACK);
		Path sounds = pack.resolve("assets/" + MinehouseTrailer.MOD_ID + "/sounds");
		Files.createDirectories(sounds);
		int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
		JsonObject meta = new JsonObject();
		JsonObject p = new JsonObject();
		p.addProperty("description", "Музыка для трейлера MineHouse");
		p.addProperty("min_format", format);
		p.addProperty("max_format", format);
		meta.add("pack", p);
		Files.writeString(pack.resolve("pack.mcmeta"), GSON.toJson(meta), StandardCharsets.UTF_8);
		return sounds;
	}
}
