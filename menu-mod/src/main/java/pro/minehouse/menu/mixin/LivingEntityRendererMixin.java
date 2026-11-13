package pro.minehouse.menu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import pro.minehouse.menu.wardrobe.WardrobeSelf;

/**
 * Vanilla hides the name of anyone carrying a passenger. A backpack is an item display riding the
 * player, so with one on, the nickname vanished: such passengers don't count here.
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@Redirect(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isVehicle()Z"))
	private boolean minehouse$backpackKeepsName(LivingEntity entity) {
		for (Entity passenger : entity.getPassengers()) {
			if (!WardrobeSelf.isCosmeticDisplay(passenger)) {
				return true;
			}
		}
		return false;
	}
}
