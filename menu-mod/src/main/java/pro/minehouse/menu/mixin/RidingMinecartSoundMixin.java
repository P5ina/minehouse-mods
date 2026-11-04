package pro.minehouse.menu.mixin;

import net.minecraft.client.resources.sounds.RidingMinecartSoundInstance;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A silent minecart (on the server: named "Тихо", MineHouse Tweaks) is quiet for its rider too.
 * Vanilla's rolling sound heard from outside ({@code MinecartSoundInstance}) already checks the
 * Silent flag, the one the rider hears does not.
 */
@Mixin(RidingMinecartSoundInstance.class)
public abstract class RidingMinecartSoundMixin {
	@Shadow
	@Final
	private AbstractMinecart minecart;

	@Inject(method = "shoudlPlaySound", at = @At("HEAD"), cancellable = true)
	private void minehouse$silent(CallbackInfoReturnable<Boolean> cir) {
		if (minecart.isSilent()) {
			cir.setReturnValue(false);
		}
	}
}
