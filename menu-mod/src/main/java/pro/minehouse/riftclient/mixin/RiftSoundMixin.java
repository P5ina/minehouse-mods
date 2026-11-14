package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import pro.minehouse.riftclient.TopDown;

/**
 * The game hears with the camera, and in the Rift mode the camera hangs some twenty blocks away
 * from the character: most sounds reach about sixteen, so the fight was silent. Here the ears stay
 * on the character; left and right still follow the camera, so they match the screen.
 */
@Mixin(SoundEngine.class)
public abstract class RiftSoundMixin {
	@Redirect(method = "updateSource(Lnet/minecraft/client/Camera;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 minehouse$riftEars(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		if (TopDown.active() && mc.player != null) {
			return mc.player.getEyePosition();
		}
		return camera.position();
	}
}
