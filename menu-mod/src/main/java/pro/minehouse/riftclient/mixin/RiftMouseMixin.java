package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import pro.minehouse.riftclient.TopDown;

/** In the Rift mode the mouse moves the cursor over the ground; the character turns to it. */
@Mixin(MouseHandler.class)
public abstract class RiftMouseMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
	private void minehouse$riftCursor(double mousea, CallbackInfo ci) {
		if (!TopDown.active() || minecraft.player == null) {
			return;
		}
		Window window = minecraft.getWindow();
		double speed = minecraft.options.sensitivity().get() + 0.5; // 0.5..1.5, the default 0.5 is one to one
		TopDown.moveCursor(accumulatedDX * speed, accumulatedDY * speed, window.getScreenHeight(),
				(double) window.getScreenWidth() / Math.max(1, window.getScreenHeight()));
		TopDown.face(minecraft.player, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		ci.cancel();
	}
}
