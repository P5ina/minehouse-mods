package pro.minehouse.riftclient;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * From the server of the Rift mode ({@code RiftView} in rift-mod): which view to show, and whether
 * the player is down and crawls. Listening to
 * this channel is also how that server knows the mod is installed.
 */
public record RiftViewPayload(boolean topDown, float yaw, float pitch, float distance, boolean downed) implements CustomPacketPayload {
	public static final Type<RiftViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "view"));
	public static final StreamCodec<FriendlyByteBuf, RiftViewPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.topDown());
				buf.writeFloat(p.yaw());
				buf.writeFloat(p.pitch());
				buf.writeFloat(p.distance());
				buf.writeBoolean(p.downed());
			},
			buf -> new RiftViewPayload(buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));

	@Override
	public Type<RiftViewPayload> type() {
		return TYPE;
	}
}
