package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import pro.minehouse.riftclient.TopDown;

/** In the Rift mode the camera hangs above the player and looks down with a narrow lens. */
@Mixin(Camera.class)
public abstract class RiftCameraMixin {
	@Shadow
	private boolean detached;
	@Shadow
	private Entity entity;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void minehouse$riftCamera(float partialTicks, CallbackInfo ci) {
		TopDown.Shot shot = TopDown.shot(entity, partialTicks);
		if (shot != null) {
			detached = true;
			setRotation(shot.yRot(), shot.xRot());
			setPosition(shot.position());
		}
	}

	@Inject(method = "calculateFov(F)F", at = @At("HEAD"), cancellable = true)
	private void minehouse$riftFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (TopDown.shot(entity, partialTicks) != null) {
			cir.setReturnValue(TopDown.FOV);
		}
	}
}
