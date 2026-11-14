package pro.minehouse.riftclient;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The fight of the Rift on our side: the keys of the roll and the two abilities, their slots with
 * the cooldowns, and the circles on the floor that warn of a blow. The server decides everything;
 * we only ask ({@code cast}) and show what it answered ({@code cooldown}, {@code warn}).
 */
public final class Combat {
	static final int ROLL = 0;
	static final int WAVE = 1;
	static final int SHARD = 2;
	static final int COUNT = 3;
	private static final String[] NAMES = {"ПЕРЕКАТ", "ВОЛНА", "ОСКОЛОК"};

	/** When each skill is ready again (ms of {@link Util#getMillis()}) and how long its cooldown was. */
	private static final long[] readyAt = new long[COUNT];
	private static final long[] total = new long[COUNT];
	private static boolean jumpHeld;

	private record Ring(Vec3 at, float radius, long born, long life) {
	}

	private static final List<Ring> rings = new ArrayList<>();

	private Combat() {
	}

	// --- From the server ---------------------------------------------------------------------

	static void cooldown(RiftUiPayloads.Cooldown c) {
		cooldown(c.skill(), c.ticks(), Util.getMillis());
	}

	static void cooldown(int skill, int ticks, long now) {
		if (skill < 0) {
			reset();
		} else if (skill < COUNT) {
			total[skill] = ticks * 50L;
			readyAt[skill] = now + total[skill];
		}
	}

	static void warn(RiftUiPayloads.Warn w) {
		rings.add(new Ring(new Vec3(w.x(), w.y(), w.z()), w.radius(), Util.getMillis(), Math.max(1, w.ticks()) * 50L));
	}

	static void reset() {
		for (int i = 0; i < COUNT; i++) {
			readyAt[i] = 0;
			total[i] = 0;
		}
		rings.clear();
		jumpHeld = false;
	}

	/** Milliseconds until the skill is ready; 0 when it is. */
	static long left(int skill, long now) {
		return Math.max(0, readyAt[skill] - now);
	}

	/** 1 right after the use, 0 when ready: how much of the slot is still covered. */
	static float covered(int skill, long now) {
		return total[skill] <= 0 ? 0 : Math.min(1f, left(skill, now) / (float) total[skill]);
	}

	/** What the slot says while cooling down: "1.2" under ten seconds, whole seconds above. */
	static String countdown(long leftMs) {
		if (leftMs <= 0) {
			return "";
		}
		return leftMs < 9950 ? String.format(java.util.Locale.ROOT, "%.1f", leftMs / 1000f) : String.valueOf((leftMs + 999) / 1000);
	}

	// --- Keys --------------------------------------------------------------------------------

	/** In a run, on our feet, nothing over the game: the skills can be used. */
	static boolean usable(Minecraft mc) {
		RiftUiPayloads.Run run = RiftHud.run();
		return TopDown.active() && run.inRun() && run.downLeft() < 0 && RiftHud.result() == null && mc.gui.screen() == null && mc.player != null
				&& !mc.player.isSpectator();
	}

	private static void cast(int skill, float dx, float dz) {
		long now = Util.getMillis();
		if (left(skill, now) > 0 || !ClientPlayNetworking.canSend(RiftUiPayloads.Cast.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new RiftUiPayloads.Cast((byte) skill, dx, dz));
		// Until the server answers: no second request for the same press.
		readyAt[skill] = now + 250;
		if (total[skill] <= 0) {
			total[skill] = 250;
		}
	}

	/**
	 * Each tick before the game reads its keys: in the view from above the keys of dropping an item
	 * and of the inventory use the abilities instead (so the sword can't be thrown away by mistake).
	 */
	static void keys(Minecraft mc) {
		if (!TopDown.active() || !RiftHud.inRun()) {
			return;
		}
		boolean wave = false;
		boolean shard = false;
		while (mc.options.keyDrop.consumeClick()) {
			wave = true;
		}
		while (mc.options.keyInventory.consumeClick()) {
			shard = true;
		}
		if (!usable(mc)) {
			return;
		}
		if (wave) {
			cast(WAVE, 0, 0);
		}
		if (shard) {
			cast(SHARD, 0, 0);
		}
	}

	/** From the keyboard each tick: the jump key rolls where the keys lead, once per press. */
	public static void jumpKey(boolean held, Vec2 move, float playerYaw) {
		boolean pressed = held && !jumpHeld;
		jumpHeld = held;
		Minecraft mc = Minecraft.getInstance();
		if (pressed && usable(mc)) {
			Vec2 dir = TopDown.worldDirection(move, playerYaw);
			cast(ROLL, dir.x, dir.y);
		}
	}

	// --- The floor ---------------------------------------------------------------------------

	/** How far the inner circle of a warning has grown: 0 when it appears, 1 when the blow lands. */
	static float grown(long age, long life) {
		return Math.max(0, Math.min(1, age / (float) life));
	}

	/** Each tick: the warnings on the floor, an edge that stays and a filling that grows to it. */
	static void tick(Minecraft mc) {
		long now = Util.getMillis();
		rings.removeIf(r -> now - r.born() > r.life() + 150);
		if (rings.isEmpty() || mc.level == null) {
			return;
		}
		try (Gizmos.TemporaryCollection ignored = mc.collectPerTickGizmos()) {
			for (Ring r : rings) {
				float g = grown(now - r.born(), r.life());
				Vec3 at = r.at().add(0, 0.06, 0);
				Gizmos.circle(at, r.radius(), GizmoStyle.strokeAndFill(Ui.a(Ui.RED, 1), 4f, Ui.a(Ui.RED, 0.16f)));
				Gizmos.circle(at.add(0, 0.02, 0), Math.max(0.05f, r.radius() * g), GizmoStyle.fill(Ui.a(Ui.RED, 0.28f + 0.3f * g)));
			}
		}
	}

	// --- The slots ---------------------------------------------------------------------------

	/** The keys as the player has them bound, short enough for a slot. */
	private static String keyName(Minecraft mc, int slot) {
		Component c = switch (slot) {
			case 0 -> mc.options.keyAttack.getTranslatedKeyMessage();
			case 1 -> mc.options.keyJump.getTranslatedKeyMessage();
			case 2 -> mc.options.keyDrop.getTranslatedKeyMessage();
			default -> mc.options.keyInventory.getTranslatedKeyMessage();
		};
		return shortKey(c.getString());
	}

	/** "Левая кнопка" and the like don't fit a slot. */
	static String shortKey(String name) {
		String n = name.trim();
		String low = n.toLowerCase(java.util.Locale.ROOT);
		if (low.startsWith("лев") || low.startsWith("left b") || low.equals("left click")) {
			return "ЛКМ";
		}
		if (low.startsWith("прав") || low.startsWith("right b") || low.equals("right click")) {
			return "ПКМ";
		}
		if (low.startsWith("проб") || low.equals("space")) {
			return "ПРОБЕЛ";
		}
		n = n.toUpperCase(java.util.Locale.ROOT);
		return n.length() > 6 ? n.substring(0, 6) : n;
	}

	static final int SLOT = 22;
	static final int GAP = 26;

	/** Four slots to the right of the hotbar: the blow, the roll, the wave, the shard. */
	static void draw(GuiGraphicsExtractor g, Font font, Minecraft mc, int w, int h) {
		long now = Util.getMillis();
		int width = 4 * SLOT + 3 * GAP;
		int x = Math.max(w / 2 + 91 + 8, w - 8 - width - 10);
		int y = h - 12 - SLOT;
		if (x + width > w - 2) {
			// A narrow window: no room beside the hotbar, the slots go over it.
			x = w / 2 - width / 2;
			y = h - 62;
		}
		String[] names = {"УДАР", NAMES[0], NAMES[1], NAMES[2]};
		for (int i = 0; i < 4; i++) {
			int sx = x + i * (SLOT + GAP);
			int skill = i - 1;
			float covered = skill < 0 ? 0 : covered(skill, now);
			long left = skill < 0 ? 0 : left(skill, now);
			boolean ready = left <= 0;
			int edge = !ready ? 0x6B5A8F : i == 0 ? Ui.WHITE : Ui.ACCENT;
			g.fill(sx - 1, y - 1, sx + SLOT + 1, y + SLOT + 1, Ui.a(edge, 1));
			g.fill(sx, y, sx + SLOT, y + SLOT, Ui.a(0x120A1C, 1));
			icon(g, i, sx + SLOT / 2, y + SLOT / 2, Ui.a(ready ? (i == 0 ? Ui.WHITE : 0xE9D5FF) : 0x6B5A8F, 1));
			if (!ready) {
				int cover = Math.round(SLOT * covered);
				g.fill(sx, y + SLOT - cover, sx + SLOT, y + SLOT, Ui.a(0x3A2A5C, 0.85f));
				if (total[skill] > 300) {
					Ui.big(g, font, countdown(left), sx + SLOT / 2, y + 7, 1f, 0xFFFFFFFF, 1);
				}
			}
			Ui.big(g, font, keyName(mc, i) + " · " + names[i], sx + SLOT / 2, y + SLOT + 3, 0.5f, Ui.a(0xD8C8F2, 1), 1);
		}
	}

	/** Little pictures out of rectangles: a blade, a dash, rings, a splinter. */
	private static void icon(GuiGraphicsExtractor g, int slot, int cx, int cy, int argb) {
		switch (slot) {
			case 0 -> {
				for (int i = -5; i <= 4; i++) {
					g.fill(cx + i, cy - i - 1, cx + i + 2, cy - i + 1, argb);
				}
				g.fill(cx - 6, cy + 2, cx - 1, cy + 4, argb);
			}
			case 1 -> {
				g.fill(cx - 6, cy - 4, cx + 1, cy - 2, argb);
				g.fill(cx - 4, cy - 1, cx + 4, cy + 1, argb);
				g.fill(cx - 6, cy + 2, cx + 1, cy + 4, argb);
				g.fill(cx + 4, cy - 3, cx + 6, cy + 3, argb);
			}
			case 2 -> {
				g.outline(cx - 7, cy - 7, 14, 14, argb);
				g.outline(cx - 3, cy - 3, 6, 6, argb);
			}
			default -> {
				for (int i = 0; i < 6; i++) {
					g.fill(cx - 1 - (i > 2 ? 0 : 1), cy - 7 + i * 2, cx + 1 + (i > 2 ? 0 : 1), cy - 5 + i * 2, argb);
				}
				g.fill(cx - 1, cy + 5, cx + 1, cy + 7, argb);
			}
		}
	}
}
