package pro.minehouse.riftclient;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Which of the mode's screen effects is on right now (post effects in
 * {@code assets/minehouse-rift/post_effect}, one shader with different settings): the usual look of
 * a run, the look when health is low, the look of a downed player, the look of the lobby.
 */
public final class RiftFx {
	public static final Identifier RUN = Identifier.fromNamespaceAndPath("minehouse-rift", "rift");
	public static final Identifier LOW = Identifier.fromNamespaceAndPath("minehouse-rift", "rift_low");
	public static final Identifier DOWNED = Identifier.fromNamespaceAndPath("minehouse-rift", "rift_downed");
	public static final Identifier LOBBY = Identifier.fromNamespaceAndPath("minehouse-rift", "rift_lobby");
	/** Below this share of health the screen says so. */
	static final float LOW_HEALTH = 0.35f;

	private RiftFx() {
	}

	/** The effect for this state, or null for none. */
	static Identifier pick(boolean topDown, boolean lobbyCamera, boolean inRun, boolean downed, float healthShare) {
		if (topDown && inRun) {
			return downed ? DOWNED : healthShare <= LOW_HEALTH ? LOW : RUN;
		}
		return lobbyCamera ? LOBBY : null;
	}

	/** Asked by the game every frame. */
	public static Identifier current() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		RiftUiPayloads.Run run = RiftHud.run();
		float share = mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth());
		return pick(TopDown.active(), TopDown.fixed(), run.inRun(), run.downLeft() >= 0 || TopDown.downed(), share);
	}
}
