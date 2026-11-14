package pro.minehouse.riftclient.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import pro.minehouse.riftclient.RiftFx;

/** The Rift mode asks the game for its screen effect the same way a player's own effects are asked for. */
@Mixin(GameRenderer.class)
public abstract class RiftFxMixin {
	@Shadow
	@Final
	private List<Identifier> requestedPostEffects;

	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void minehouse$riftFx(DeltaTracker deltaTracker, CallbackInfo ci) {
		Identifier fx = RiftFx.current();
		if (fx != null) {
			requestedPostEffects.add(fx);
		}
	}
}
