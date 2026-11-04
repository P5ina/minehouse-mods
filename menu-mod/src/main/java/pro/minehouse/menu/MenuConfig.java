package pro.minehouse.menu;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/minehouse-menu.properties. Created with defaults on first start,
 * so the server address and links can be changed without rebuilding the mod.
 */
public final class MenuConfig {
	public static String serverName = "MineHouse";
	public static String serverAddress = "play.minehouse.pro";
	public static String siteUrl = "https://www.minehouse.pro";
	public static String discordUrl = "https://discord.gg/gCYGxDzaCb";
	public static String packVersion = "";
	public static String updateUrl = "https://www.minehouse.pro/modpack/manifest.json";
	public static boolean introEnabled = true;

	private static final String HEADER = """
		Настройки главного меню MineHouse.
		server.address  адрес сервера для кнопки «Играть»
		site.url        ссылка кнопки «Сайт»
		discord.url     ссылка кнопки «Discord» (пусто = кнопки нет)
		pack.version    версия сборки, видна в углу меню
		update.url      откуда брать обновления сборки (пусто = не обновлять)
		intro           показывать вступительный ролик перед меню (true/false)""";

	private MenuConfig() {
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("minehouse-menu.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				MinehouseMenu.LOG.warn("Can't read {}", file, e);
			}
		}
		serverName = p.getProperty("server.name", serverName).trim();
		serverAddress = p.getProperty("server.address", serverAddress).trim();
		siteUrl = p.getProperty("site.url", siteUrl).trim();
		discordUrl = p.getProperty("discord.url", discordUrl).trim();
		packVersion = p.getProperty("pack.version", packVersion).trim();
		updateUrl = p.getProperty("update.url", updateUrl).trim();
		introEnabled = !"false".equalsIgnoreCase(p.getProperty("intro", "true").trim());
		if (!Files.exists(file)) {
			p.setProperty("server.name", serverName);
			p.setProperty("server.address", serverAddress);
			p.setProperty("site.url", siteUrl);
			p.setProperty("discord.url", discordUrl);
			p.setProperty("pack.version", packVersion);
			p.setProperty("update.url", updateUrl);
			try {
				Files.createDirectories(file.getParent());
				try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					p.store(w, HEADER);
				}
			} catch (IOException e) {
				MinehouseMenu.LOG.warn("Can't write {}", file, e);
			}
		}
	}
}
