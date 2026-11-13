package pro.minehouse.zonesclient;

import java.util.List;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * CI check (./gradlew runSelftestClient -Pselftest=zones under Xvfb): once the client has started, checks the packet
 * receiver, round-trips a packet through the codec, draws two made-up zones into a gizmo collector
 * and prints MINEHOUSE_ZONES_CLIENT_OK.
 */
final class SelfTest {
	private static int ticks;

	private SelfTest() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++ticks != 100) return;

			boolean receiver = ClientPlayNetworking.getGlobalReceivers().contains(ZoneShapesPayload.TYPE.id());
			ZoneShapesPayload sent = new ZoneShapesPayload(List.of(
					new ZoneShapesPayload.Shape(0x55E05A, false, 30, 120, "Зона 1", new int[] {0, 40, 40, 0}, new int[] {0, 0, 30, 30}),
					new ZoneShapesPayload.Shape(0xB04CFF, true, 30, 120, "", new int[] {5, 9}, new int[] {5, 7})));
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			ZoneShapesPayload.CODEC.encode(buf, sent);
			ZoneShapesPayload got = ZoneShapesPayload.CODEC.decode(buf);
			boolean codec = got.shapes().size() == 2 && got.shapes().get(0).label().equals("Зона 1")
					&& got.shapes().get(0).xs()[1] == 40 && got.shapes().get(1).draft() && got.shapes().get(1).zs()[1] == 7;

			SimpleGizmoCollector collector = new SimpleGizmoCollector();
			try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(collector)) {
				ZoneRenderer.draw(got.shapes(), new Vec3(20, 64, 15));
			}
			int gizmos = collector.drainGizmos().size();
			boolean drawn = gizmos > 20;

			MinehouseZonesClient.LOG.info(receiver && codec && drawn ? "MINEHOUSE_ZONES_CLIENT_OK"
					: "MINEHOUSE_ZONES_CLIENT_FAIL: receiver " + receiver + ", codec " + codec + ", gizmos " + gizmos);
			client.stop();
		});
	}
}
