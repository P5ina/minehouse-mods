package pro.minehouse.anvilclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Real anvil cost from the MineHouse Anvil server mod. The anvil's data slot carries at most 39
 * (so the vanilla screen never says "Too Expensive!"), the real number comes in this packet.
 * Must match pro.minehouse.anvil.AnvilCostPayload in anvil-mod.
 */
public record AnvilCostPayload(int containerId, int cost) implements CustomPacketPayload {
	public static final Type<AnvilCostPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minehouse", "anvil_cost"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AnvilCostPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AnvilCostPayload::containerId,
			ByteBufCodecs.VAR_INT, AnvilCostPayload::cost,
			AnvilCostPayload::new);

	@Override
	public Type<AnvilCostPayload> type() {
		return TYPE;
	}
}
