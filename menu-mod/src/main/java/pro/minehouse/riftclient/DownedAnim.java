package pro.minehouse.riftclient;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/**
 * Our own animation of a player who is down in a Rift run, instead of the swimming strokes of the
 * crawl pose: the character drops with the arms thrown out, then lies wounded, one hand pressed to
 * the body; to move, the other arm reaches forward and drags the body along, the legs trail.
 * The numbers are angles of the model's parts in radians, in the frame of the lying body.
 */
public final class DownedAnim {
	/** How long the drop takes, in ticks. */
	public static final float FALL_TICKS = 12;

	public record Limbs(float headX, float headY, float dragArmX, float dragArmZ, float holdArmX, float holdArmY, float holdArmZ, float leftLegX,
			float leftLegZ, float rightLegX, float rightLegZ) {
	}

	private static final Map<Integer, Long> since = new HashMap<>();
	private static long ticks;

	private DownedAnim() {
	}

	/** Each client tick: who lies down in the run, and since when. */
	static void tick(Minecraft mc) {
		ticks++;
		if (mc.level == null || !TopDown.active()) {
			since.clear();
			return;
		}
		Map<Integer, Long> now = new HashMap<>();
		for (Player p : mc.level.players()) {
			if (p.getPose() == Pose.SWIMMING && !p.isInWater()) {
				now.put(p.getId(), since.getOrDefault(p.getId(), ticks));
			}
		}
		since.clear();
		since.putAll(now);
	}

	/** Ticks since this player went down, or -1 when they are on their feet. */
	public static float downFor(int entityId, float partialTick) {
		Long from = since.get(entityId);
		return from == null ? -1 : ticks - from + partialTick;
	}

	private static float ease(float t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/**
	 * @param downFor ticks since the fall
	 * @param stride  the walk animation position (grows as the player moves)
	 * @param moving  0..1, how fast the player moves
	 * @param age     ticks, for breathing
	 */
	public static Limbs limbs(float downFor, float stride, float moving, float age) {
		float settled = ease(downFor / FALL_TICKS);
		float flung = 1 - settled;
		moving = Math.max(0, Math.min(1, moving * 2.5f));
		float breath = (float) Math.sin(age * 0.09f);
		// One pull of the dragging arm: reach far forward, plant, pull under the shoulder.
		float pull = 0.5f - 0.5f * (float) Math.cos(stride * 0.55f);
		float reach = -2.95f + 1.25f * pull;
		float rest = -2.55f + 0.04f * breath;
		float dragArmX = rest + (reach - rest) * moving;
		float dragArmZ = -0.18f - 1.25f * flung;
		// The other hand holds the wound; it shakes a little.
		float holdArmX = -0.85f + 0.03f * (float) Math.sin(age * 0.9f);
		float holdArmY = 0.75f * settled;
		float holdArmZ = 0.45f * settled + 1.25f * flung;
		// The head lifts to look where to crawl, and hangs when lying still.
		float headX = -0.3f - 0.55f * moving + 0.06f * breath + 0.5f * flung;
		float headY = 0.12f * (float) Math.sin(stride * 0.275f) * moving;
		// The legs trail: no kicks, only a small sway with each pull.
		float sway = 0.07f * (float) Math.sin(stride * 0.55f) * moving;
		return new Limbs(headX, headY, dragArmX, dragArmZ, holdArmX, holdArmY, holdArmZ, 0.06f + sway, -0.1f - 0.25f * flung, 0.22f - sway,
				0.14f + 0.25f * flung);
	}
}
