package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import pro.minehouse.riftclient.Combat;
import pro.minehouse.riftclient.TopDown;

/** In the Rift mode W, A, S, D walk up, left, down and right on the screen, and Space rolls. */
@Mixin(KeyboardInput.class)
public abstract class RiftKeyboardMixin extends ClientInput {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void minehouse$riftWalk(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (TopDown.active() && mc.player != null) {
			moveVector = TopDown.walk(moveVector, mc.player.getYRot());
			// Space rolls instead of jumping.
			Combat.jumpKey(keyPresses.jump(), moveVector, mc.player.getYRot());
			if (keyPresses.jump()) {
				keyPresses = new Input(keyPresses.forward(), keyPresses.backward(), keyPresses.left(), keyPresses.right(), false, keyPresses.shift(),
						keyPresses.sprint());
			}
		}
	}
}
