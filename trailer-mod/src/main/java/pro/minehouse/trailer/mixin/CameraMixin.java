package pro.minehouse.trailer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;

import pro.minehouse.trailer.CameraHook;

/** While the trailer plays, the camera goes where the shot list says instead of following the player. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void minehouseTrailer$align(float partialTicks, CallbackInfo ci) {
		double[] cam = CameraHook.camera();
		if (cam != null) {
			setRotation((float) cam[3], (float) cam[4]);
			setPosition(cam[0], cam[1], cam[2]);
		}
	}

	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void minehouseTrailer$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		double[] cam = CameraHook.camera();
		if (cam != null) {
			cir.setReturnValue((float) cam[5]);
		}
	}
}
