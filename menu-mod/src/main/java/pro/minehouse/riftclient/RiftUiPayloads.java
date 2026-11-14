package pro.minehouse.riftclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The interface of the Rift mode on the wire ({@code RiftUi} in rift-mod): what the mode says goes
 * to the feed, the banner and the status line, the lobby sends its party, the buttons go back.
 */
public final class RiftUiPayloads {
	public static final byte FEED = 0;
	public static final byte BANNER = 1;
	public static final byte STATUS = 2;
	public static final byte OPEN = 3;
	public static final byte CLOSE = 4;

	private RiftUiPayloads() {
	}

	/** One thing to show: a feed line, a banner (title and subtitle) or the status line. */
	public record Note(byte kind, String text, String sub) implements CustomPacketPayload {
		public static final Type<Note> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "note"));
		public static final StreamCodec<FriendlyByteBuf, Note> CODEC = StreamCodec.of(
				(buf, n) -> {
					buf.writeByte(n.kind());
					buf.writeUtf(n.text(), 512);
					buf.writeUtf(n.sub(), 512);
				},
				buf -> new Note(buf.readByte(), buf.readUtf(512), buf.readUtf(512)));

		@Override
		public Type<Note> type() {
			return TYPE;
		}
	}

	/** A place in the party; an empty name is a free place. */
	public record Slot(String name, boolean ready, boolean leader) {
	}

	/** The party of the lobby and what its screen may do. {@code startIn}: seconds, -1 when not going. */
	public record Lobby(boolean inLobby, List<Slot> slots, boolean searching, String note, boolean ready, boolean canInvite, boolean canSearch,
			boolean canLeave, String invitedBy, int startIn) implements CustomPacketPayload {
		public static final Type<Lobby> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "lobby"));
		public static final Lobby NONE = new Lobby(false, List.of(), false, "", false, false, false, false, "", -1);
		public static final StreamCodec<FriendlyByteBuf, Lobby> CODEC = StreamCodec.of(
				(buf, l) -> {
					buf.writeBoolean(l.inLobby());
					buf.writeVarInt(l.slots().size());
					for (Slot s : l.slots()) {
						buf.writeUtf(s.name(), 64);
						buf.writeBoolean(s.ready());
						buf.writeBoolean(s.leader());
					}
					buf.writeBoolean(l.searching());
					buf.writeUtf(l.note(), 512);
					buf.writeBoolean(l.ready());
					buf.writeBoolean(l.canInvite());
					buf.writeBoolean(l.canSearch());
					buf.writeBoolean(l.canLeave());
					buf.writeUtf(l.invitedBy(), 64);
					buf.writeVarInt(l.startIn() + 1);
				},
				buf -> {
					boolean in = buf.readBoolean();
					int n = Math.min(16, buf.readVarInt());
					List<Slot> slots = new ArrayList<>();
					for (int i = 0; i < n; i++) {
						slots.add(new Slot(buf.readUtf(64), buf.readBoolean(), buf.readBoolean()));
					}
					return new Lobby(in, List.copyOf(slots), buf.readBoolean(), buf.readUtf(512), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
							buf.readBoolean(), buf.readUtf(64), buf.readVarInt() - 1);
				});

		@Override
		public Type<Lobby> type() {
			return TYPE;
		}
	}

	/** A button of the party screen: ready, search, invite (with a nick), accept, leave, hub. */
	public record Action(String id, String arg) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "action"));
		public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of(
				(buf, a) -> {
					buf.writeUtf(a.id(), 32);
					buf.writeUtf(a.arg(), 64);
				},
				buf -> new Action(buf.readUtf(32), buf.readUtf(64)));

		@Override
		public Type<Action> type() {
			return TYPE;
		}
	}
}
