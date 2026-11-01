package pro.minehouse.trailer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.ClientClockManager;

import pro.minehouse.trailer.CameraHook;

/** The sky shows the shot's time of day at once, with no wait for the server. */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClockMixin {
	@Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true)
	private void minehouseTrailer$ticks(CallbackInfoReturnable<Long> cir) {
		long time = CameraHook.dayTime();
		if (time >= 0) {
			cir.setReturnValue(time);
		}
	}

	@Inject(method = "partialTick", at = @At("HEAD"), cancellable = true)
	private void minehouseTrailer$partial(CallbackInfoReturnable<Float> cir) {
		if (CameraHook.dayTime() >= 0) {
			cir.setReturnValue(0f);
		}
	}
}
