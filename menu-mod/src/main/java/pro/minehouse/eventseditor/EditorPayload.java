package pro.minehouse.eventseditor;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The show editor's channel, both ways: one JSON message per packet. Must match
 * pro.minehouse.events.EditorPayload in the server mod (events-mod).
 */
public record EditorPayload(String json) implements CustomPacketPayload {
	public static final Type<EditorPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse", "show_editor"));
	public static final StreamCodec<ByteBuf, EditorPayload> CODEC = ByteBufCodecs.stringUtf8(1 << 20)
			.map(EditorPayload::new, EditorPayload::json);

	@Override
	public Type<EditorPayload> type() {
		return TYPE;
	}
}
