package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import pro.minehouse.riftclient.TopDown;

/** In the Rift mode W, A, S, D walk up, left, down and right on the screen. */
@Mixin(KeyboardInput.class)
public abstract class RiftKeyboardMixin extends ClientInput {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void minehouse$riftWalk(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (TopDown.active() && mc.player != null) {
			moveVector = TopDown.walk(moveVector, mc.player.getYRot());
		}
	}
}
