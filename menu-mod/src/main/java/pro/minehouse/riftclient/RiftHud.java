package pro.minehouse.riftclient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * What the Rift mode shows over the game, drawn by us and kept out of the chat: the feed of
 * events, the banner, the status line; in a run the room and the wave, the boss, the party, our
 * own health, the screen of a downed player and the results. The lobby is {@link PartyScreen}.
 */
public final class RiftHud {
	static final int FEED_LINES = 5;
	static final long FEED_MS = 7000;
	static final long BANNER_MS = 2800;
	static final long STATUS_MS = 2500;
	private static final long FADE_MS = 500;

	private record Line(Component text, long born) {
	}

	private static final Deque<Line> feed = new ArrayDeque<>();
	private static Component bannerTitle;
	private static Component bannerSub;
	private static long bannerBorn;
	private static Component status;
	private static long statusBorn;
	private static RiftUiPayloads.Lobby lobby = RiftUiPayloads.Lobby.NONE;
	private static RiftUiPayloads.Roster roster = new RiftUiPayloads.Roster(List.of());
	private static RiftUiPayloads.Run run = RiftUiPayloads.NOT_IN_RUN;
	private static RiftUiPayloads.Result result;
	private static long resultBorn;

	private RiftHud() {
	}

	static RiftUiPayloads.Lobby lobby() {
		return lobby;
	}

	static RiftUiPayloads.Roster roster() {
		return roster;
	}

	static RiftUiPayloads.Run run() {
		return run;
	}

	static RiftUiPayloads.Result result() {
		return result;
	}

	/** In a run that shows its own health and boss: the vanilla ones are hidden. */
	static boolean inRun() {
		return run.inRun();
	}

	static int feedSize() {
		return feed.size();
	}

	static void note(RiftUiPayloads.Note note) {
		long now = Util.getMillis();
		switch (note.kind()) {
			case RiftUiPayloads.FEED -> {
				feed.addLast(new Line(RiftText.component(note.text()), now));
				while (feed.size() > FEED_LINES) {
					feed.removeFirst();
				}
			}
			case RiftUiPayloads.BANNER -> {
				bannerTitle = RiftText.component(note.text());
				bannerSub = RiftText.component(note.sub());
				bannerBorn = now;
			}
			case RiftUiPayloads.STATUS -> {
				status = RiftText.component(note.text());
				statusBorn = now;
			}
			case RiftUiPayloads.OPEN -> PartyScreen.open();
			case RiftUiPayloads.CLOSE -> {
				// The lobby screen stays while we are in the lobby; it goes with the lobby.
			}
			default -> {
			}
		}
	}

	static void lobby(RiftUiPayloads.Lobby now) {
		lobby = now;
		if (now.inLobby()) {
			result = null;
		} else {
			PartyScreen.close();
		}
	}

	static void roster(RiftUiPayloads.Roster now) {
		roster = now;
	}

	static void run(RiftUiPayloads.Run now) {
		run = now;
		if (!now.inRun()) {
			result = null;
		}
	}

	static void result(RiftUiPayloads.Result now) {
		result = now;
		resultBorn = Util.getMillis();
	}

	static void clear() {
		feed.clear();
		bannerTitle = null;
		status = null;
		lobby = RiftUiPayloads.Lobby.NONE;
		roster = new RiftUiPayloads.Roster(List.of());
		run = RiftUiPayloads.NOT_IN_RUN;
		result = null;
	}

	/** Each client tick: in the lobby its screen is always there (after Esc and the game menu too). */
	static void tick(Minecraft mc) {
		if (lobby.inLobby() && mc.level != null && mc.gui.screen() == null) {
			PartyScreen.open();
		}
	}

	/** 0..1: full, then fading out over the last half second of its life. */
	static float alpha(long age, long life) {
		if (age < 0 || age >= life) {
			return 0;
		}
		return Math.min(1f, (life - age) / (float) FADE_MS);
	}

	/** What stands over the room: "ЗАЛ 2 ИЗ 3 · ВОЛНА 1 ИЗ 2"; the entry hall has no number. */
	static String objective(RiftUiPayloads.Run r) {
		String room = r.room() <= 0 ? "ВХОД В РАЗЛОМ" : "ЗАЛ " + r.room() + " ИЗ " + r.rooms();
		return r.waves() > 0 ? room + " · ВОЛНА " + Math.max(1, r.wave()) + " ИЗ " + r.waves() : room;
	}

	/** The line of a teammate in the run: health, or how long they have left on the ground. */
	static String mateNote(RiftUiPayloads.Mate m) {
		return switch (m.state()) {
			case 1 -> "УПАЛ · " + Math.max(0, m.downLeft()) + " с";
			case 2 -> "ВЫБЫЛ";
			default -> String.valueOf(m.health());
		};
	}

	/** Who did best: the most kills, then the most revives, among those who lasted; -1 when nobody did anything. */
	static int best(List<RiftUiPayloads.Score> scores) {
		int best = -1;
		for (int i = 0; i < scores.size(); i++) {
			RiftUiPayloads.Score s = scores.get(i);
			if (s.out() || s.kills() + s.revives() == 0) {
				continue;
			}
			if (best < 0 || s.kills() > scores.get(best).kills()
					|| (s.kills() == scores.get(best).kills() && s.revives() > scores.get(best).revives())) {
				best = i;
			}
		}
		return best;
	}

	static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		long now = Util.getMillis();
		boolean lobbyScreen = mc.gui.screen() instanceof PartyScreen;
		boolean screen = mc.gui.screen() != null && !lobbyScreen;
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (result != null && run.inRun()) {
			results(g, font, w, h, now);
			return;
		}
		if (run.inRun() && !screen) {
			runHud(g, font, mc, w, h);
		}

		// The feed: in the lobby it grows up from the bottom left, in a run it hangs on the right.
		feed.removeIf(l -> now - l.born() >= FEED_MS);
		if (!screen) {
			boolean right = run.inRun();
			int y = right ? 40 : h - 14 - feed.size() * 14;
			for (Line l : feed) {
				float a = alpha(now - l.born(), FEED_MS);
				int lw = font.width(l.text()) + 12;
				int x = right ? w - 6 - lw : 8;
				Ui.panel(g, x, y, lw, 12, Ui.PANEL, 0.85f * a);
				g.fill(right ? x + lw - 2 : x, y, right ? x + lw : x + 2, y + 12, Ui.a(Ui.ACCENT, a));
				g.text(font, l.text(), x + 6, y + 2, Ui.ink(Ui.WHITE, a), false);
				y += 14;
			}
		}

		if (bannerTitle != null && !screen) {
			float a = alpha(now - bannerBorn, BANNER_MS);
			if (a <= 0) {
				bannerTitle = null;
			} else if (run.downLeft() < 0) {
				int cx = w / 2;
				int cy = h / 4;
				Ui.big(g, font, bannerTitle, cx, cy - 10, 2.5f, Ui.ink(Ui.WHITE, a), 1);
				g.centeredText(font, bannerSub, cx, cy + 18, Ui.ink(Ui.WHITE, a));
			}
		}

		if (status != null) {
			float a = alpha(now - statusBorn, STATUS_MS);
			if (a <= 0) {
				status = null;
			} else if (!screen && !lobbyScreen) {
				int sw = font.width(status);
				int cx = w / 2;
				int sy = h - 72;
				Ui.panel(g, cx - sw / 2 - 6, sy - 3, sw + 12, 14, Ui.INK, 0.6f * a);
				g.centeredText(font, status, cx, sy, Ui.ink(Ui.WHITE, a));
			}
		}
	}

	// --- The run -----------------------------------------------------------------------------

	private static void runHud(GuiGraphicsExtractor g, Font font, Minecraft mc, int w, int h) {
		int cx = w / 2;
		// Top: where we are, and the boss.
		Ui.big(g, font, objective(run), cx, 6, 0.75f, Ui.a(0xD8C8F2, 1), 1);
		if (run.bossShare() >= 0) {
			g.centeredText(font, Component.literal(run.boss()), cx, 15, 0xFFFFFFFF);
			int bw = Math.min(200, w / 2);
			Ui.bar(g, cx - bw / 2, 27, bw, 5, run.bossShare() / 1000f, Ui.ACCENT, Ui.EDGE, 1);
		}

		// Left: the rest of the party.
		int y = 40;
		for (RiftUiPayloads.Mate m : run.mates()) {
			float share = m.max() <= 0 ? 0 : m.health() / (float) m.max();
			int rgb = m.state() == 1 ? Ui.RED : m.state() == 2 ? 0x6B5A8F : Ui.healthColor(share);
			Ui.panel(g, 6, y, 96, 20, m.state() == 1 ? 0x3B0D1A : 0x120A1C, 0.88f);
			g.fill(6, y, 8, y + 20, Ui.a(rgb, 1));
			g.text(font, font.plainSubstrByWidth(m.name(), 54), 11, y + 3, Ui.a(m.state() == 2 ? Ui.MUTED : Ui.WHITE, 1), false);
			String note = mateNote(m);
			if (m.state() == 0) {
				g.text(font, note, 99 - font.width(note), y + 3, 0xFFFFFFFF, false);
			} else {
				Ui.big(g, font, note, 99, y + 4, 0.75f, Ui.a(m.state() == 1 ? 0xFECDD3 : Ui.MUTED, 1), 2);
			}
			float bar = m.state() == 1 ? m.downLeft() / (float) Math.max(1, run.downTotal()) : m.state() == 2 ? 0 : share;
			g.fill(11, y + 14, 99, y + 17, Ui.a(Ui.INK, 1));
			g.fill(11, y + 14, 11 + Math.round(88 * Math.max(0, Math.min(1, bar))), y + 17, Ui.a(rgb, 1));
			y += 23;
		}

		// Bottom left: our own health instead of the hearts.
		if (mc.player != null) {
			float health = mc.player.getHealth();
			float max = Math.max(1, mc.player.getMaxHealth());
			boolean down = run.downLeft() >= 0;
			int by = h - 30;
			g.fill(7, by - 1, 29, by + 21, 0xFFFFFFFF);
			Ui.face(g, mc.player.getGameProfile().name(), 8, by, 20);
			g.text(font, mc.player.getGameProfile().name(), 34, by + 1, 0xFFFFFFFF, true);
			String hp = down ? "0 / " + Math.round(max) : (int) Math.ceil(health) + " / " + Math.round(max);
			g.text(font, hp, 134 - font.width(hp), by + 1, 0xFFFFFFFF, true);
			Ui.bar(g, 34, by + 13, 100, 6, down ? 0 : health / max, Ui.healthColor(health / max), 0x14532D, 1);
		}

		if (run.downLeft() >= 0) {
			downed(g, font, w, h);
		} else if (run.revive() >= 0 && !run.reviveMine()) {
			// We are raising someone.
			int bw = 140;
			int sy = h - 78;
			g.centeredText(font, Component.literal("Поднимаешь " + run.reviveName()), cx, sy - 11, Ui.a(0xBBF7D0, 1));
			Ui.bar(g, cx - bw / 2, sy, bw, 6, run.revive() / 100f, Ui.GREEN, 0x14532D, 1);
		} else {
			for (RiftUiPayloads.Mate m : run.mates()) {
				if (m.state() == 1) {
					String text = "Держи рядом с упавшим, чтобы поднять " + m.name();
					int tw = font.width(text) + 44;
					int sy = h - 80;
					Ui.panel(g, cx - tw / 2, sy, tw, 16, 0x120A1C, 0.9f);
					g.fill(cx - tw / 2, sy, cx + tw / 2, sy + 1, Ui.a(Ui.GREEN, 1));
					g.outline(cx - tw / 2 + 4, sy + 3, 30, 11, 0xFFFFFFFF);
					Ui.big(g, font, "SHIFT", cx - tw / 2 + 19, sy + 6, 0.75f, 0xFFFFFFFF, 1);
					g.text(font, text, cx - tw / 2 + 39, sy + 4, 0xFFFFFFFF, false);
					break;
				}
			}
		}
	}

	/** We are down: a red edge, how long is left, and who is raising us. */
	private static void downed(GuiGraphicsExtractor g, Font font, int w, int h) {
		int edge = Ui.a(0xBE123C, 0.35f);
		int t = 10;
		g.fill(0, 0, w, t, edge);
		g.fill(0, h - t, w, h, edge);
		g.fill(0, t, t, h - t, edge);
		g.fill(w - t, t, w, h - t, edge);
		int cx = w / 2;
		int cy = h / 5;
		Ui.big(g, font, "ТЫ УПАЛ", cx, cy, 3f, Ui.a(Ui.RED, 1), 1);
		g.centeredText(font, Component.literal("Ползи к напарнику. Без помощи выбываешь через " + run.downLeft() + " с"), cx, cy + 30, Ui.a(0xFECDD3, 1));
		if (run.revive() >= 0 && run.reviveMine()) {
			int bw = 120;
			int ry = cy + 50;
			g.centeredText(font, Component.literal(run.reviveName().isEmpty() ? "Тебя поднимают" : run.reviveName() + " поднимает тебя"), cx, ry,
					Ui.a(0xBBF7D0, 1));
			Ui.bar(g, cx - bw / 2, ry + 12, bw, 6, run.revive() / 100f, Ui.GREEN, 0x14532D, 1);
		}
		int bw = Math.min(200, w / 2);
		int by = h - 78;
		Ui.bar(g, cx - bw / 2, by, bw, 7, run.downLeft() / (float) Math.max(1, run.downTotal()), Ui.RED, 0x9F1239, 1);
		Ui.big(g, font, "ДО ВЫБЫВАНИЯ", cx, by + 12, 0.75f, Ui.a(0xFECDD3, 1), 1);
	}

	// --- The results -------------------------------------------------------------------------

	private static void results(GuiGraphicsExtractor g, Font font, int w, int h, long now) {
		RiftUiPayloads.Result r = result;
		float a = Math.min(1f, (now - resultBorn) / 400f);
		g.fill(0, 0, w, h, Ui.a(0x0D0818, 0.9f * a));
		int band = Math.max(54, h / 4);
		g.fill(0, 0, w, band, Ui.a(Ui.PANEL, a));
		g.fill(0, band, w, band + 2, Ui.a(r.won() ? Ui.ACCENT : Ui.RED, a));
		int m = Math.max(12, w / 16);
		Ui.label(g, font, "ЗАБЕГ ОКОНЧЕН", m, band - 44, Ui.MUTED);
		Ui.big(g, font, r.won() ? "РАЗЛОМ ЗАКРЫТ" : "ОТРЯД ПАЛ", m, band - 34, 3f, Ui.ink(r.won() ? Ui.YELLOW : Ui.RED, a), 0);
		Ui.big(g, font, "ВРЕМЯ", w - m, band - 44, 0.75f, Ui.a(Ui.MUTED, 1), 2);
		Ui.big(g, font, Ui.clock(r.seconds()), w - m, band - 30, 2f, Ui.ink(Ui.WHITE, a), 2);

		int n = Math.max(1, r.scores().size());
		int gap = 6;
		int cw = Math.min(110, (w - 2 * m - gap * (n - 1)) / n);
		int x = m;
		int y = band + 14;
		int best = r.won() ? best(r.scores()) : -1;
		for (int i = 0; i < r.scores().size(); i++) {
			RiftUiPayloads.Score s = r.scores().get(i);
			int top = s.out() ? Ui.RED : i == best ? Ui.YELLOW : 0x6B5A8F;
			Ui.panel(g, x, y, cw, 78, s.out() ? 0x1F0C16 : i == best ? Ui.PANEL : Ui.PANEL_DIM, a);
			g.fill(x, y, x + cw, y + 2, Ui.a(top, a));
			Ui.face(g, s.name(), x + 6, y + 8, 16);
			g.text(font, font.plainSubstrByWidth(s.name(), cw - 32), x + 26, y + 8, Ui.ink(Ui.WHITE, a), false);
			String tag = s.out() ? "ВЫБЫЛ" : i == best ? "ЛУЧШИЙ В ОТРЯДЕ" : r.won() ? "ДОШЁЛ ДО КОНЦА" : "ДЕРЖАЛСЯ";
			Ui.big(g, font, tag, x + 26, y + 18, 0.6f, Ui.a(s.out() ? 0xFECDD3 : i == best ? 0xFDE68A : Ui.MUTED, 1), 0);
			String[][] rows = { { "Убито", String.valueOf(s.kills()) }, { "Поднял", String.valueOf(s.revives()) }, { "Падал", String.valueOf(s.downs()) } };
			int ry = y + 32;
			for (String[] row : rows) {
				g.text(font, row[0], x + 6, ry, Ui.ink(Ui.MUTED, a), false);
				g.text(font, row[1], x + cw - 6 - font.width(row[1]), ry, Ui.ink(Ui.WHITE, a), false);
				ry += 14;
			}
			x += cw + gap;
		}
		int left = Math.max(0, r.backIn() - (int) ((now - resultBorn) / 1000));
		g.text(font, "Возврат в лобби через " + left + " с", m, y + 92, Ui.ink(Ui.MUTED, a), false);
	}
}
