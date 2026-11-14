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

	/** Someone else on the server, for the list of the lobby: 0 free, 1 called by your party, 2 in a run. */
	public record Person(String name, byte state) {
	}

	/** Who else is on the server (the lobby calls them into the party with one button). */
	public record Roster(List<Person> people) implements CustomPacketPayload {
		public static final Type<Roster> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "roster"));
		public static final StreamCodec<FriendlyByteBuf, Roster> CODEC = StreamCodec.of(
				(buf, r) -> {
					buf.writeVarInt(r.people().size());
					for (Person x : r.people()) {
						buf.writeUtf(x.name(), 64);
						buf.writeByte(x.state());
					}
				},
				buf -> {
					int n = Math.min(64, buf.readVarInt());
					List<Person> people = new ArrayList<>();
					for (int i = 0; i < n; i++) {
						people.add(new Person(buf.readUtf(64), buf.readByte()));
					}
					return new Roster(List.copyOf(people));
				});

		@Override
		public Type<Roster> type() {
			return TYPE;
		}
	}

	/** A teammate in the run: health in half hearts; state 0 fights, 1 is down ({@code downLeft} seconds to go), 2 is out. */
	public record Mate(String name, int health, int max, byte state, int downLeft) {
	}

	/**
	 * The run as its screen shows it: the room and the wave, the boss ({@code bossShare} in 1/1000,
	 * -1 without one), this player's bleed-out (seconds, -1 when standing), a revive in progress
	 * ({@code revive} percent, -1 for none; {@code reviveMine}: this player is being raised by
	 * {@code reviveName}, otherwise raises them), and the rest of the party.
	 */
	public record Run(boolean inRun, int room, int rooms, int wave, int waves, String boss, int bossShare, int downLeft, int downTotal, int revive,
			boolean reviveMine, String reviveName, List<Mate> mates) implements CustomPacketPayload {
		public static final Type<Run> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "run"));
		public static final StreamCodec<FriendlyByteBuf, Run> CODEC = StreamCodec.of(
				(buf, r) -> {
					buf.writeBoolean(r.inRun());
					buf.writeVarInt(r.room());
					buf.writeVarInt(r.rooms());
					buf.writeVarInt(r.wave());
					buf.writeVarInt(r.waves());
					buf.writeUtf(r.boss(), 64);
					buf.writeVarInt(r.bossShare() + 1);
					buf.writeVarInt(r.downLeft() + 1);
					buf.writeVarInt(r.downTotal());
					buf.writeVarInt(r.revive() + 1);
					buf.writeBoolean(r.reviveMine());
					buf.writeUtf(r.reviveName(), 64);
					buf.writeVarInt(r.mates().size());
					for (Mate m : r.mates()) {
						buf.writeUtf(m.name(), 64);
						buf.writeVarInt(m.health());
						buf.writeVarInt(m.max());
						buf.writeByte(m.state());
						buf.writeVarInt(m.downLeft() + 1);
					}
				},
				buf -> {
					boolean in = buf.readBoolean();
					int room = buf.readVarInt();
					int rooms = buf.readVarInt();
					int wave = buf.readVarInt();
					int waves = buf.readVarInt();
					String boss = buf.readUtf(64);
					int share = buf.readVarInt() - 1;
					int downLeft = buf.readVarInt() - 1;
					int downTotal = buf.readVarInt();
					int revive = buf.readVarInt() - 1;
					boolean mine = buf.readBoolean();
					String name = buf.readUtf(64);
					int n = Math.min(16, buf.readVarInt());
					List<Mate> mates = new ArrayList<>();
					for (int i = 0; i < n; i++) {
						mates.add(new Mate(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readVarInt() - 1));
					}
					return new Run(in, room, rooms, wave, waves, boss, share, downLeft, downTotal, revive, mine, name, List.copyOf(mates));
				});

		@Override
		public Type<Run> type() {
			return TYPE;
		}
	}

	/** A line of the results: what this player did in the run; {@code out}: did not last to the end. */
	public record Score(String name, int kills, int revives, int downs, boolean out) {
	}

	/** The run is over: won or not, how long it took, everyone's line, seconds until the lobby. */
	public record Result(boolean won, int seconds, int backIn, List<Score> scores) implements CustomPacketPayload {
		public static final Type<Result> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse-rift", "result"));
		public static final StreamCodec<FriendlyByteBuf, Result> CODEC = StreamCodec.of(
				(buf, r) -> {
					buf.writeBoolean(r.won());
					buf.writeVarInt(r.seconds());
					buf.writeVarInt(r.backIn());
					buf.writeVarInt(r.scores().size());
					for (Score x : r.scores()) {
						buf.writeUtf(x.name(), 64);
						buf.writeVarInt(x.kills());
						buf.writeVarInt(x.revives());
						buf.writeVarInt(x.downs());
						buf.writeBoolean(x.out());
					}
				},
				buf -> {
					boolean won = buf.readBoolean();
					int seconds = buf.readVarInt();
					int backIn = buf.readVarInt();
					int n = Math.min(16, buf.readVarInt());
					List<Score> scores = new ArrayList<>();
					for (int i = 0; i < n; i++) {
						scores.add(new Score(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
					}
					return new Result(won, seconds, backIn, List.copyOf(scores));
				});

		@Override
		public Type<Result> type() {
			return TYPE;
		}
	}

	public static final Run NOT_IN_RUN = new Run(false, 0, 0, 0, 0, "", -1, -1, 0, -1, false, "", List.of());
}
