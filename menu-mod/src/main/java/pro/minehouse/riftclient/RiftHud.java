package pro.minehouse.riftclient;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;

/**
 * What the Rift mode says, drawn by us and kept out of the chat: a feed of events on the left,
 * a banner across the screen, a status line above the hotbar, and the party in the lobby.
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

	private RiftHud() {
	}

	static RiftUiPayloads.Lobby lobby() {
		return lobby;
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
			case RiftUiPayloads.CLOSE -> PartyScreen.close();
			default -> {
			}
		}
	}

	static void lobby(RiftUiPayloads.Lobby now) {
		lobby = now;
		if (!now.inLobby()) {
			PartyScreen.close();
		}
	}

	static void clear() {
		feed.clear();
		bannerTitle = null;
		status = null;
		lobby = RiftUiPayloads.Lobby.NONE;
	}

	/** 0..1: full, then fading out over the last half second of its life. */
	static float alpha(long age, long life) {
		if (age < 0 || age >= life) {
			return 0;
		}
		return Math.min(1f, (life - age) / (float) FADE_MS);
	}

	private static int color(float alpha) {
		return ARGB.color(Math.max(4, (int) (alpha * 255)), 0xFFFFFF);
	}

	static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		long now = Util.getMillis();
		boolean screen = mc.gui.screen() != null;

		// The feed: the newest line at the bottom, each on a dark strip.
		feed.removeIf(l -> now - l.born() >= FEED_MS);
		int y = 34;
		for (Line l : feed) {
			float a = alpha(now - l.born(), FEED_MS);
			int w = font.width(l.text());
			g.fill(4, y - 2, 4 + w + 8, y + 10, ARGB.color((int) (a * 120), 0x0B0714));
			g.fill(4, y - 2, 5, y + 10, ARGB.color((int) (a * 255), 0xC77DFF));
			g.text(font, l.text(), 8, y, color(a), true);
			y += 13;
		}

		if (bannerTitle != null && !screen) {
			float a = alpha(now - bannerBorn, BANNER_MS);
			if (a <= 0) {
				bannerTitle = null;
			} else {
				int cx = g.guiWidth() / 2;
				int cy = g.guiHeight() / 4;
				g.pose().pushMatrix();
				g.pose().translate(cx, cy);
				g.pose().scale(2.5f, 2.5f);
				g.centeredText(font, bannerTitle, 0, -4, color(a));
				g.pose().popMatrix();
				g.centeredText(font, bannerSub, cx, cy + 18, color(a));
			}
		}

		if (status != null) {
			float a = alpha(now - statusBorn, STATUS_MS);
			if (a <= 0) {
				status = null;
			} else if (!screen) {
				int w = font.width(status);
				int cx = g.guiWidth() / 2;
				int sy = g.guiHeight() - 72;
				g.fill(cx - w / 2 - 5, sy - 3, cx + w / 2 + 5, sy + 11, ARGB.color((int) (a * 120), 0x0B0714));
				g.centeredText(font, status, cx, sy, color(a));
			}
		}

		if (lobby.inLobby() && !screen) {
			party(g, font);
		}
	}

	/** The party in the corner of the lobby: who is here, who is ready. */
	private static void party(GuiGraphicsExtractor g, Font font) {
		int w = 132;
		int x = g.guiWidth() - w - 6;
		int y = 6;
		int h = 20 + lobby.slots().size() * 11 + 14;
		g.fill(x, y, x + w, y + h, 0x900B0714);
		g.fill(x, y, x + w, y + 1, 0xFFC77DFF);
		g.text(font, lobby.startIn() >= 0 ? "Заходим через " + lobby.startIn() + " с" : "Отряд Разлома", x + 6, y + 6, 0xFFE0AAFF, true);
		int ly = y + 20;
		for (RiftUiPayloads.Slot s : lobby.slots()) {
			g.text(font, slotLine(s, lobby.searching()), x + 6, ly, 0xFFFFFFFF, true);
			ly += 11;
		}
		g.text(font, "Shift: меню отряда", x + 6, ly + 3, 0xFF9A8FA8, true);
	}

	static Component slotLine(RiftUiPayloads.Slot s, boolean searching) {
		if (s.name().isEmpty()) {
			return RiftText.component(searching ? "&d… ищем игрока" : "&8свободно");
		}
		return RiftText.component((s.ready() ? "&a✔ " : "&7○ ") + "&f" + s.name() + (s.leader() ? " &6★" : ""));
	}
}
