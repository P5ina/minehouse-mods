package pro.minehouse.menu.wardrobe;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The wardrobe's camera: in front of the player, a little above, looking back at them, so you see
 * yourself in the world (the fitting room) instead of a picture in the window. Pulled in when a
 * wall is in the way.
 */
public final class WardrobeCamera {
	private static final double DISTANCE = 2.8;
	private static final double LOOK_AT = 1.0;
	private static final double HEIGHT = 1.35;

	public record Shot(Vec3 position, float yRot, float xRot) {
	}

	private WardrobeCamera() {
	}

	public static Shot shot(Entity entity, Level level, float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.gui.screen() instanceof WardrobeScreen) || entity == null || entity != mc.player || level == null) {
			return null;
		}
		float yaw = entity.getYRot();
		Vec3 feet = entity.getPosition(partialTicks);
		Vec3 target = feet.add(0, LOOK_AT, 0);
		double rad = Math.toRadians(yaw);
		Vec3 front = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
		Vec3 eye = feet.add(0, HEIGHT, 0);
		Vec3 wanted = eye.add(front.scale(DISTANCE));
		HitResult hit = level.clip(new ClipContext(eye, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
		Vec3 pos = wanted;
		if (hit.getType() != HitResult.Type.MISS) {
			double d = Math.max(0.6, hit.getLocation().distanceTo(eye) - 0.25);
			pos = eye.add(front.scale(d));
		}
		Vec3 look = target.subtract(pos);
		float xRot = (float) Math.toDegrees(Math.atan2(-look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
		return new Shot(pos, yaw + 180, xRot);
	}
}
