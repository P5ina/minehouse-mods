package pro.minehouse.riftclient;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The view of the Rift mode: the camera hangs above the player at a fixed angle, the mouse moves a
 * cursor over the ground instead of turning the head, the character faces the cursor and walks
 * where the keys point on the screen (W is up the screen, whatever way the character looks).
 *
 * <p>All the geometry is here as plain functions of numbers, so it is tested without a game.
 */
public final class TopDown {
	/** Narrow lens from far away: close to a drawing with no perspective, without losing depth. */
	public static final float FOV = 34f;
	/** The camera looks at the player's chest; the cursor is measured on the same height. */
	public static final double LOOK_AT = 1.0;
	/** How far (in screen heights) the cursor may be from the middle. */
	private static final double EDGE = 0.96;
	/** A strike reaches mobs this many degrees to either side of where the character looks. */
	static final float STRIKE_ARC = 60f;

	private static boolean active;
	private static float camYaw;
	private static float camPitch = 56f;
	private static float distance = 17f;
	/** Cursor from the middle of the screen: x to the right, y up, in halves of the screen height. */
	private static double cursorX;
	private static double cursorY = 0.35;
	private static CameraType before;
	/** Down in a run: the character crawls until a teammate gets them up. */
	private static boolean downed;

	public record Shot(Vec3 position, float yRot, float xRot) {
	}

	private TopDown() {
	}

	public static boolean downed() {
		return downed;
	}

	public static boolean active() {
		return active;
	}

	static void apply(RiftViewPayload p) {
		Minecraft mc = Minecraft.getInstance();
		downed = p.downed();
		if (p.topDown()) {
			if (!active) {
				before = mc.options.getCameraType();
				cursorX = 0;
				cursorY = 0.35;
			}
			camYaw = p.yaw();
			camPitch = Mth.clamp(p.pitch(), 20f, 89f);
			distance = Mth.clamp(p.distance(), 4f, 64f);
			active = true;
		} else {
			stop();
		}
	}

	/** Back to the usual view: the server said so, or the player left it. */
	static void stop() {
		downed = false;
		if (active && before != null) {
			Minecraft.getInstance().options.setCameraType(before);
		}
		active = false;
		before = null;
	}

	/** Each tick: third person is what makes the game draw the player and hide the hand. */
	static void tick(Minecraft mc) {
		if (active && mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
	}

	// --- Camera ------------------------------------------------------------------------------

	public static Shot shot(Entity entity, float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || entity == null || entity != mc.player) {
			return null;
		}
		return new Shot(cameraPosition(entity.getPosition(partialTicks), camYaw, camPitch, distance), camYaw, camPitch);
	}

	static Vec3 forward(float yaw, float pitch) {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/** To the right on the screen. */
	static Vec3 right(float yaw) {
		double y = Math.toRadians(yaw);
		return new Vec3(-Math.cos(y), 0, -Math.sin(y));
	}

	static Vec3 cameraPosition(Vec3 feet, float yaw, float pitch, float distance) {
		return feet.add(0, LOOK_AT, 0).subtract(forward(yaw, pitch).scale(distance));
	}

	// --- Cursor ------------------------------------------------------------------------------

	/** Mouse moved by this many window pixels (y down, as the mouse reports). */
	public static void moveCursor(double dx, double dy, int windowHeight, double aspect) {
		if (windowHeight <= 0) {
			return;
		}
		double half = windowHeight / 2.0;
		cursorX = Mth.clamp(cursorX + dx / half, -aspect * EDGE, aspect * EDGE);
		cursorY = Mth.clamp(cursorY - dy / half, -EDGE, EDGE);
	}

	public static double cursorX() {
		return cursorX;
	}

	public static double cursorY() {
		return cursorY;
	}

	/**
	 * Where on the level plane {@code planeY} the cursor points: the ray from the camera through
	 * the cursor ({@code cx}, {@code cy} in halves of the screen height from the middle). Null when
	 * the ray never comes down to the plane.
	 */
	static Vec3 aimPoint(Vec3 camera, float yaw, float pitch, double fovDegrees, double cx, double cy, double planeY) {
		Vec3 f = forward(yaw, pitch);
		Vec3 r = right(yaw);
		Vec3 u = r.cross(f);
		double t = Math.tan(Math.toRadians(fovDegrees) / 2);
		Vec3 dir = f.add(r.scale(cx * t)).add(u.scale(cy * t));
		if (dir.y > -1e-4) {
			return null;
		}
		double k = (planeY - camera.y) / dir.y;
		return camera.add(dir.scale(k));
	}

	/** The yaw that makes someone at {@code from} face {@code to}. */
	static float yawTowards(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	/** Each frame: the player turns to the cursor. */
	public static void face(LocalPlayer player, float partialTicks) {
		Vec3 feet = player.getPosition(partialTicks);
		Vec3 camera = cameraPosition(feet, camYaw, camPitch, distance);
		Vec3 aim = aimPoint(camera, camYaw, camPitch, FOV, cursorX, cursorY, feet.y + LOOK_AT);
		if (aim == null) {
			return;
		}
		double dx = aim.x - feet.x;
		double dz = aim.z - feet.z;
		if (dx * dx + dz * dz < 0.3 * 0.3) {
			return; // the cursor is on the character: keep looking where they looked
		}
		float yaw = yawTowards(feet, aim);
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setXRot(0);
	}

	// --- What the cursor is on --------------------------------------------------------------

	/** The ray from the camera through the cursor, as (start, direction). */
	private static Vec3[] ray(LocalPlayer player, float partialTicks) {
		Vec3 feet = player.getPosition(partialTicks);
		Vec3 camera = cameraPosition(feet, camYaw, camPitch, distance);
		Vec3 f = forward(camYaw, camPitch);
		Vec3 r = right(camYaw);
		Vec3 u = r.cross(f);
		double t = Math.tan(Math.toRadians(FOV) / 2);
		Vec3 dir = f.add(r.scale(cursorX * t)).add(u.scale(cursorY * t)).normalize();
		return new Vec3[] {camera, dir};
	}

	/**
	 * What a click does: the mob or block under the cursor when it is within reach; for a strike
	 * with nothing under the cursor, the nearest mob in front of the character. Null: nothing.
	 */
	public static HitResult pick(Minecraft mc, boolean attack) {
		LocalPlayer player = mc.player;
		if (!active || player == null || mc.level == null) {
			return null;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3[] ray = ray(player, partial);
		Vec3 from = ray[0];
		Vec3 to = from.add(ray[1].scale(64));
		BlockHitResult block = seen(mc.level, from, to);
		Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
		Vec3 eye = player.getEyePosition();
		double entityReach = player.entityInteractionRange();
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, from, end, new AABB(from, end).inflate(1), TopDown::clickable, from.distanceToSqr(end));
		if (entity != null && distanceTo(eye, entity.getEntity().getBoundingBox()) <= entityReach) {
			return entity;
		}
		if (attack) {
			Entity near = inFront(player, entityReach);
			return near != null ? new EntityHitResult(near) : null;
		}
		if (block.getType() == HitResult.Type.BLOCK && distanceTo(eye, new AABB(block.getBlockPos())) <= player.blockInteractionRange()) {
			return block;
		}
		return null;
	}

	/**
	 * The first block on the ray that can be seen. The roof and the wall on the camera's side are
	 * barriers and the lamps are light blocks: the cursor goes through them as the eye does.
	 */
	private static BlockHitResult seen(Level level, Vec3 from, Vec3 to) {
		return BlockGetter.traverseBlocks(from, to, level, (l, pos) -> {
			BlockState state = l.getBlockState(pos);
			if (state.isAir() || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)) {
				return null;
			}
			return l.clipWithInteractionOverride(from, to, pos, state.getShape(l, pos), state);
		}, l -> BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to)));
	}

	private static boolean clickable(Entity e) {
		return e.isPickable() && !e.isSpectator();
	}

	private static double distanceTo(Vec3 p, AABB box) {
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** The nearest living thing (not a player) within reach and inside the arc the character faces. */
	private static Entity inFront(LocalPlayer player, double reach) {
		Vec3 eye = player.getEyePosition();
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(reach + 1), x -> x instanceof LivingEntity && !(x instanceof Player) && clickable(x) && x.isAlive())) {
			double d = distanceTo(eye, e.getBoundingBox());
			if (d > reach || d >= bestDistance || !inArc(player.position(), player.getYRot(), e.position(), STRIKE_ARC) || !player.hasLineOfSight(e)) {
				continue;
			}
			best = e;
			bestDistance = d;
		}
		return best;
	}

	/** Whether {@code target} is within {@code arc} degrees to either side of where {@code yaw} looks from {@code from}. */
	static boolean inArc(Vec3 from, float yaw, Vec3 target, float arc) {
		double dx = target.x - from.x;
		double dz = target.z - from.z;
		if (dx * dx + dz * dz < 0.25) {
			return true; // right on top of the character
		}
		return Math.abs(Mth.wrapDegrees(yawTowards(from, target) - yaw)) <= arc;
	}

	// --- Walking -----------------------------------------------------------------------------

	/**
	 * Keys say where to go on the screen; the game wants it relative to where the player looks.
	 * {@code move} is (left, forward) as the keyboard gives it.
	 */
	static Vec2 screenToPlayer(Vec2 move, float cameraYaw, float playerYaw) {
		double d = Math.toRadians(cameraYaw - playerYaw);
		float cos = (float) Math.cos(d);
		float sin = (float) Math.sin(d);
		return new Vec2(move.x * cos - move.y * sin, move.y * cos + move.x * sin);
	}

	public static Vec2 walk(Vec2 move, float playerYaw) {
		return screenToPlayer(move, camYaw, playerYaw);
	}
}
