package pro.minehouse.menu;

import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;

/** Pings the MineHouse server for the title screen (online count). */
public final class ServerStatus {
	private static final long REFRESH_MS = 30_000L;
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "MineHouse server ping");
		t.setDaemon(true);
		return t;
	});

	private static final ServerStatusPinger PINGER = new ServerStatusPinger();
	private static ServerData data;
	private static long lastPing;

	private ServerStatus() {
	}

	public static ServerData data() {
		if (data == null || !data.ip.equals(MenuConfig.serverAddress)) {
			data = new ServerData(MenuConfig.serverName, MenuConfig.serverAddress, ServerData.Type.OTHER);
			lastPing = 0;
		}
		return data;
	}

	/** Starts a ping if the last one is older than 30 s. Call from the render thread. */
	public static void refresh(Minecraft minecraft) {
		ServerData d = data();
		long now = Util.getMillis();
		if (d.state() == ServerData.State.PINGING || now - lastPing < REFRESH_MS) {
			return;
		}
		lastPing = now;
		d.setState(ServerData.State.PINGING);
		POOL.submit(() -> {
			try {
				PINGER.pingServer(d, () -> {}, () -> d.setState(
					d.protocol == SharedConstants.getCurrentVersion().protocolVersion()
						? ServerData.State.SUCCESSFUL
						: ServerData.State.INCOMPATIBLE
				), EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
			} catch (UnknownHostException e) {
				d.setState(ServerData.State.UNREACHABLE);
			} catch (Exception e) {
				d.setState(ServerData.State.UNREACHABLE);
			}
		});
	}

	public static void tick() {
		PINGER.tick();
	}

	/** "▶ Играть на MineHouse · 12 онлайн" */
	public static Component playLabel() {
		ServerData d = data();
		var label = Component.literal("▶ Играть на " + MenuConfig.serverName).withColor(0xD8B4FE);
		return switch (d.state()) {
			case SUCCESSFUL -> d.players != null
				? label.append(Component.literal("  " + d.players.online() + " онлайн").withColor(0x7CF08A))
				: label;
			case UNREACHABLE -> label.append(Component.literal("  офлайн").withColor(0xFF7070));
			default -> label;
		};
	}

	/** Full status for the play button tooltip. */
	public static Component line() {
		ServerData d = data();
		return switch (d.state()) {
			case SUCCESSFUL -> d.players != null
				? Component.literal("● ").withColor(0x55FF55).append(Component.literal(
					"Онлайн: " + d.players.online() + " из " + d.players.max()).withColor(0xE0E0E0))
				: Component.literal("● ").withColor(0x55FF55).append(Component.literal("Сервер работает").withColor(0xE0E0E0));
			case INCOMPATIBLE -> Component.literal("● ").withColor(0xFFAA00)
				.append(Component.literal("Нужна версия " + d.version.getString()).withColor(0xE0E0E0));
			case UNREACHABLE -> Component.literal("● ").withColor(0xFF5555)
				.append(Component.literal("Сервер недоступен").withColor(0xE0E0E0));
			default -> Component.literal("Проверяем сервер…").withColor(0xA0A0A0);
		};
	}
}
