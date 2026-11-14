package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import pro.minehouse.riftclient.DownedAnim;

/** A player who is down in a Rift run gets our animation instead of the swimming strokes. */
@Mixin(PlayerModel.class)
public abstract class RiftDownedModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void minehouse$riftDowned(AvatarRenderState state, CallbackInfo ci) {
		float downFor = DownedAnim.downFor(state.id, 0);
		if (downFor < 0) {
			return;
		}
		HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
		DownedAnim.Limbs l = DownedAnim.limbs(downFor, state.walkAnimationPos, state.walkAnimationSpeed, state.ageInTicks);
		model.head.xRot = l.headX();
		model.head.yRot = l.headY();
		model.head.zRot = 0;
		model.rightArm.xRot = l.dragArmX();
		model.rightArm.yRot = 0;
		model.rightArm.zRot = l.dragArmZ();
		model.leftArm.xRot = l.holdArmX();
		model.leftArm.yRot = l.holdArmY();
		model.leftArm.zRot = l.holdArmZ();
		model.leftLeg.xRot = l.leftLegX();
		model.leftLeg.yRot = 0;
		model.leftLeg.zRot = l.leftLegZ();
		model.rightLeg.xRot = l.rightLegX();
		model.rightLeg.yRot = 0;
		model.rightLeg.zRot = l.rightLegZ();
	}
}
