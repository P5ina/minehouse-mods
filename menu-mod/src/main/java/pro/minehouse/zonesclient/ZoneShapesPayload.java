package pro.minehouse.zonesclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Zone outlines the server wants drawn for this player (in the player's current dimension). An empty list
 * hides everything. Must match pro.minehouse.bank.zones.ZoneShapesPayload in bank-mod.
 */
public record ZoneShapesPayload(List<Shape> shapes) implements CustomPacketPayload {
	public static final Type<ZoneShapesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse", "zone_shapes"));
	public static final StreamCodec<FriendlyByteBuf, ZoneShapesPayload> CODEC = CustomPacketPayload.codec(ZoneShapesPayload::write, ZoneShapesPayload::read);

	/**
	 * One polygon. {@code draft}: the admin's unsaved markup (vertices numbered, open while it has fewer than 3).
	 * Colour is 0xRRGGBB.
	 */
	public record Shape(int rgb, boolean draft, int yMin, int yMax, String label, int[] xs, int[] zs) {
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(shapes.size());
		for (Shape s : shapes) {
			buf.writeInt(s.rgb());
			buf.writeBoolean(s.draft());
			buf.writeVarInt(s.yMin());
			buf.writeVarInt(s.yMax());
			buf.writeUtf(s.label(), 128);
			buf.writeVarInt(s.xs().length);
			for (int i = 0; i < s.xs().length; i++) {
				buf.writeVarInt(s.xs()[i]);
				buf.writeVarInt(s.zs()[i]);
			}
		}
	}

	private static ZoneShapesPayload read(FriendlyByteBuf buf) {
		int count = Math.min(buf.readVarInt(), 256);
		List<Shape> shapes = new ArrayList<>(count);
		for (int k = 0; k < count; k++) {
			int rgb = buf.readInt();
			boolean draft = buf.readBoolean();
			int yMin = buf.readVarInt();
			int yMax = buf.readVarInt();
			String label = buf.readUtf(128);
			int n = Math.min(buf.readVarInt(), 4096);
			int[] xs = new int[n], zs = new int[n];
			for (int i = 0; i < n; i++) {
				xs[i] = buf.readVarInt();
				zs[i] = buf.readVarInt();
			}
			shapes.add(new Shape(rgb, draft, yMin, yMax, label, xs, zs));
		}
		return new ZoneShapesPayload(shapes);
	}

	@Override
	public Type<ZoneShapesPayload> type() {
		return TYPE;
	}
}
