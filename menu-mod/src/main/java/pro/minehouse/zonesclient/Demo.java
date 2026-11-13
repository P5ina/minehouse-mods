package pro.minehouse.zonesclient;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development only (-Dminehouse.zones.demo=true): once in a world, draws made-up zones around the player
 * as if the server had sent them, saves a screenshot and quits. Used to look at the rendering without the
 * real server.
 */
final class Demo {
	private static int ticks;

	private Demo() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) return;
			ticks++;
			if (ticks == 40) {
				int px = (int) Math.floor(client.player.getX()), py = (int) Math.floor(client.player.getY()), pz = (int) Math.floor(client.player.getZ());
				shapes(px, py, pz);
				client.player.connection.sendCommand("gamemode spectator");
				client.player.connection.sendCommand("tp @s " + px + " " + (py + 14) + " " + (pz - 22) + " 0 22");
			}
			if (ticks == 100) Screenshot.grab(client, false);
			if (ticks == 110) {
				// Inside zone 1, looking at its far corner.
				client.player.connection.sendCommand("tp @s ~18 ~-12 ~40 30 0");
			}
			if (ticks == 170) Screenshot.grab(client, false);
			if (ticks == 190) client.stop();
		});
	}

	private static void shapes(int px, int py, int pz) {
		MinehouseZonesClient.shapes = List.of(
				new ZoneShapesPayload.Shape(0x55E05A, false, py - 4, py + 30, "Зона 1 · свободна",
						new int[] {px + 6, px + 30, px + 34, px + 12}, new int[] {pz + 10, pz + 6, pz + 30, pz + 36}),
				new ZoneShapesPayload.Shape(0xFFB000, false, py - 4, py + 30, "Зона 2 · аукцион",
						new int[] {px - 30, px - 4, px - 8, px - 34}, new int[] {pz + 14, pz + 12, pz + 40, pz + 38}),
				new ZoneShapesPayload.Shape(0xB04CFF, true, py - 4, py + 30, "Черновик",
						new int[] {px - 2, px + 4, px + 8}, new int[] {pz + 50, pz + 46, pz + 56}));
	}
}
