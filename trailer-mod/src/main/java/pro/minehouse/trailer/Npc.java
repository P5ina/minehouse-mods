package pro.minehouse.trailer;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A mannequin that exists only on this client. The trailer moves it every tick; its legs swing by
 * how far it was moved, as a real player's would.
 */
final class Npc extends ClientMannequin {
	private final @Nullable PlayerSkin defaultSkin;
	private float moved;
	boolean inLevel;

	Npc(Level level, int index, @Nullable String skinName) {
		super(level, Minecraft.getInstance().playerSkinRenderCache());
		setId(-731_000 - index);
		if (skinName != null && !skinName.isBlank()) {
			defaultSkin = null;
			entityData.set(DATA_PROFILE, ResolvableProfile.createUnresolved(skinName.trim()));
		} else {
			// Nine default skins, wide and slim arms; a fixed UUID per index keeps the cast the same every take.
			defaultSkin = DefaultPlayerSkin.get(new UUID(0x4D484E5043L, index * 0x9E3779B97F4A7C15L + 7));
		}
	}

	@Override
	public PlayerSkin getSkin() {
		return defaultSkin != null ? defaultSkin : super.getSkin();
	}

	@Override
	protected @Nullable Component getDescription() {
		return null;
	}

	@Override
	public void calculateEntityAnimation(boolean useY) {
		updateWalkAnimation(moved);
	}

	/** Puts the NPC somewhere new without sliding there from the old place. */
	void teleport(Vec3 pos, float yaw) {
		setPos(pos);
		setRot(yaw, 0);
		yHeadRot = yHeadRotO = yBodyRot = yBodyRotO = yaw;
		setOldPosAndRot();
		moved = 0;
		walkAnimation.stop();
	}

	/** This tick's position; the client draws the NPC gliding there from the last one. */
	void moveTo(Vec3 pos, float yaw, float pitch) {
		Vec3 old = position();
		moved = (float) Math.sqrt(Math.pow(pos.x - old.x, 2) + Math.pow(pos.z - old.z, 2));
		setPos(pos);
		setYRot(yaw);
		setXRot(pitch);
		yHeadRot = yaw;
		yBodyRot = yaw;
	}

	void swingArm() {
		swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}

	void hold(ItemStack stack) {
		setItemSlot(EquipmentSlot.MAINHAND, stack);
	}

	void wear(ItemStack chest) {
		setItemSlot(EquipmentSlot.CHEST, chest);
	}

	void flying(boolean on) {
		setSharedFlag(FLAG_FALL_FLYING, on);
		setPose(on ? Pose.FALL_FLYING : Pose.STANDING);
	}

	void crouch(boolean on) {
		setShiftKeyDown(on);
		setPose(on ? Pose.CROUCHING : Pose.STANDING);
	}

	void reset() {
		hold(ItemStack.EMPTY);
		wear(ItemStack.EMPTY);
		flying(false);
		crouch(false);
	}
}
