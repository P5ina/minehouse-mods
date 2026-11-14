package pro.minehouse.riftclient;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * CI check (./gradlew runSelftestClient -Pselftest=rift under Xvfb): once the client has started,
 * checks the packet receiver, the codec, that the mixins found their targets (the game would not
 * have started otherwise) and the geometry of the view, then prints MINEHOUSE_RIFT_CLIENT_OK.
 */
final class SelfTest {
	private static int ticks;

	private SelfTest() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++ticks != 100) return;

			boolean receiver = ClientPlayNetworking.getGlobalReceivers().contains(RiftViewPayload.TYPE.id());
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			RiftViewPayload.CODEC.encode(buf, new RiftViewPayload(true, 0f, 56f, 17f, true));
			RiftViewPayload got = RiftViewPayload.CODEC.decode(buf);
			boolean codec = got.topDown() && got.pitch() == 56f && got.distance() == 17f && got.downed() && buf.readableBytes() == 0;

			// The middle of the screen is the player; W walks up the screen whatever way they face.
			Vec3 feet = new Vec3(10, 64, 20);
			Vec3 camera = TopDown.cameraPosition(feet, 0f, 56f, 17f);
			Vec3 middle = TopDown.aimPoint(camera, 0f, 56f, TopDown.FOV, 0, 0, feet.y + TopDown.LOOK_AT);
			Vec2 w = TopDown.screenToPlayer(new Vec2(0, 1), 0f, 90f);
			boolean geometry = middle != null && middle.distanceTo(feet.add(0, TopDown.LOOK_AT, 0)) < 1e-6
					&& Math.abs(w.x - 1) < 1e-5 && Math.abs(w.y) < 1e-5;

			TopDown.apply(got);
			boolean on = TopDown.active() && TopDown.downed();
			TopDown.apply(new RiftViewPayload(false, 0, 0, 0, false));
			boolean off = !TopDown.active() && !TopDown.downed();

			// The mode's interface: its channels, a feed line, the party of the lobby.
			boolean ui = ClientPlayNetworking.getGlobalReceivers().contains(RiftUiPayloads.Note.TYPE.id())
					&& ClientPlayNetworking.getGlobalReceivers().contains(RiftUiPayloads.Lobby.TYPE.id());
			RiftHud.note(new RiftUiPayloads.Note(RiftUiPayloads.FEED, "&dРазлом &7открыт", ""));
			RiftHud.lobby(new RiftUiPayloads.Lobby(true, java.util.List.of(new RiftUiPayloads.Slot("Tester", true, false)), false, "", true, true, true,
					false, "", -1));
			ui &= RiftHud.feedSize() == 1 && RiftHud.lobby().inLobby() && "Разлом открыт".equals(RiftText.component("&dРазлом &7открыт").getString());
			RiftHud.clear();
			ui &= RiftHud.feedSize() == 0 && !RiftHud.lobby().inLobby();

			MinehouseRiftClient.LOG.info(receiver && codec && geometry && on && off && ui ? "MINEHOUSE_RIFT_CLIENT_OK"
					: "MINEHOUSE_RIFT_CLIENT_FAIL: receiver " + receiver + ", codec " + codec + ", geometry " + geometry + ", on " + on + ", off " + off
							+ ", ui " + ui);
			client.stop();
		});
	}
}
