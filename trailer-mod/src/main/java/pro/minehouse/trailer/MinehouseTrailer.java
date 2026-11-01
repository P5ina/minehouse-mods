package pro.minehouse.trailer;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * MineHouse Trailer: shoots the 30-second server trailer in a singleplayer world.
 * /trailer stage, /trailer play [bar], /trailer stop, /trailer music.
 */
public final class MinehouseTrailer implements ClientModInitializer {
	public static final String MOD_ID = "minehouse_trailer";
	public static final Logger LOG = LoggerFactory.getLogger("minehouse-trailer");

	@Override
	public void onInitializeClient() {
		Config.load();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal("trailer")
				.executes(MinehouseTrailer::help)
				.then(ClientCommands.literal("stage").executes(MinehouseTrailer::stage))
				.then(ClientCommands.literal("play").executes(c -> play(c, 0))
						.then(ClientCommands.argument("bar", DoubleArgumentType.doubleArg(0, 15.9))
								.executes(c -> play(c, DoubleArgumentType.getDouble(c, "bar") - 1))))
				.then(ClientCommands.literal("stop").executes(c -> {
					Trailer.stop();
					return 1;
				}))
				.then(ClientCommands.literal("music").executes(MinehouseTrailer::music))));

		ClientTickEvents.END_CLIENT_TICK.register(client -> Trailer.tick());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> Trailer.origin = Config.origin()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Trailer::stop));

		for (Identifier id : new Identifier[] {VanillaHudElements.MISC_OVERLAYS, VanillaHudElements.CROSSHAIR, VanillaHudElements.SPECTATOR_MENU,
				VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
				VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
				VanillaHudElements.INFO_BAR_FOREGROUND, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.SPECTATOR_TOOLTIP,
				VanillaHudElements.MOB_EFFECTS, VanillaHudElements.BOSS_BAR, VanillaHudElements.SCOREBOARD, VanillaHudElements.OVERLAY_MESSAGE,
				VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.CHAT, VanillaHudElements.PLAYER_LIST, VanillaHudElements.SUBTITLES}) {
			HudElementRegistry.replaceElement(id, original -> (g, delta) -> {
				if (!Trailer.hidesHud()) {
					original.extractRenderState(g, delta);
				}
			});
		}
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "overlay"), Trailer::overlay);

		if (Boolean.getBoolean("minehouse.selftest")) {
			SelfTest.register();
		}
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		c.getSource().sendFeedback(Component.literal("""
				§6Трейлер MineHouse§r (одиночный мир с читами)
				§e/trailer stage§r — сцена здесь (ровное место, вокруг 50 блоков без построек)
				§e/trailer music§r — папка для музыки
				§e/trailer play§r — снять весь трейлер (2 с чёрного экрана, потом 30.5 с)
				§e/trailer play 9§r — с 9-го такта, для проверки
				§e/trailer stop§r — остановить и всё вернуть"""));
		return 1;
	}

	private static int stage(CommandContext<FabricClientCommandSource> c) {
		if (Minecraft.getInstance().getSingleplayerServer() == null) {
			c.getSource().sendError(Component.literal("Только в одиночном мире."));
			return 0;
		}
		BlockPos pos = c.getSource().getPlayer().blockPosition();
		Config.setOrigin(pos);
		Trailer.origin = pos;
		c.getSource().sendFeedback(Component.literal("§aСцена здесь: " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
				+ ". Спавн и толпа будут тут, метеорит упадёт в 38 блоках на северо-восток."));
		return 1;
	}

	private static int play(CommandContext<FabricClientCommandSource> c, double fromBar) {
		String error = Trailer.play(fromBar);
		if (error != null) {
			c.getSource().sendError(Component.literal(error));
			return 0;
		}
		if (fromBar == 0 && !Config.hasMusic()) {
			c.getSource().sendFeedback(Component.literal("§eМузыки нет, снимаю без неё (/trailer music — как добавить)."));
		}
		return 1;
	}

	private static int music(CommandContext<FabricClientCommandSource> c) {
		try {
			Path sounds = Config.makeMusicPack();
			c.getSource().sendFeedback(Component.literal("""
					§6Музыка трейлера§r
					1. Обрежьте трек так, чтобы дроп был на 15.24 с от начала, и сохраните как music.ogg:
					§7ffmpeg -ss <секунда_дропа минус 15.24> -t 33 -i трек.mp3 -c:a libvorbis -q:a 6 music.ogg§r
					2. Положите music.ogg в папку:
					§7%s§r
					3. Настройки → Пакеты ресурсов → включите «%s».
					Готово, если /trailer play не пишет «Музыки нет».""".formatted(sounds.toAbsolutePath(), Config.PACK)));
			return 1;
		} catch (IOException e) {
			c.getSource().sendError(Component.literal("Не получилось создать папку: " + e.getMessage()));
			return 0;
		}
	}
}
