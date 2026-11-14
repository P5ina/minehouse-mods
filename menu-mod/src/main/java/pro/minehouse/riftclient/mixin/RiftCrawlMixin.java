package pro.minehouse.riftclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import pro.minehouse.riftclient.TopDown;

/**
 * Down in a Rift run the character crawls. Teammates get the pose from the server; one's own
 * client chooses the pose of its player itself, so it has to be told (the view packet).
 */
@Mixin(Player.class)
public abstract class RiftCrawlMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void minehouse$riftCrawl(CallbackInfo ci) {
		if (TopDown.downed() && (Object) this instanceof LocalPlayer player) {
			player.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
