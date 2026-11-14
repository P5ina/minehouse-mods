package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import pro.minehouse.riftclient.TopDown;

/**
 * In the Rift mode a click acts on what the cursor is on (a strike with nothing under the cursor
 * goes to the nearest mob in front), not on what is straight ahead of the character's eyes.
 */
@Mixin(Minecraft.class)
public abstract class RiftClickMixin {
	@Shadow
	public HitResult hitResult;
	@Shadow
	public LocalPlayer player;

	@Inject(method = "startAttack()Z", at = @At("HEAD"))
	private void minehouse$riftStrike(CallbackInfoReturnable<Boolean> cir) {
		minehouse$aim(true);
	}

	@Inject(method = "startUseItem()V", at = @At("HEAD"))
	private void minehouse$riftUse(CallbackInfo ci) {
		minehouse$aim(false);
	}

	private void minehouse$aim(boolean attack) {
		if (!TopDown.active() || player == null) {
			return;
		}
		HitResult hit = TopDown.pick((Minecraft) (Object) this, attack);
		hitResult = hit != null ? hit : BlockHitResult.miss(player.getEyePosition(), Direction.UP, player.blockPosition());
	}
}
