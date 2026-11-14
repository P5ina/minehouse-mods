package pro.minehouse.riftclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/** The look of the Rift's screens: its colours, flat panels and buttons, bars, big letters. */
final class Ui {
	static final int INK = 0x080411;
	static final int PANEL = 0x1A0F30;
	static final int PANEL_DIM = 0x150C28;
	static final int ACCENT = 0xC77DFF;
	static final int EDGE = 0x4B3A73;
	static final int MUTED = 0xCBBDE6;
	static final int GREEN = 0x4ADE80;
	static final int YELLOW = 0xFACC15;
	static final int RED = 0xFB7185;
	static final int WHITE = 0xFFFFFF;

	private Ui() {
	}

	static int a(int rgb, float alpha) {
		return ARGB.color(Math.max(0, Math.min(255, (int) (alpha * 255))), rgb);
	}

	/** Text colours below alpha 4 are drawn opaque by the font, so faded text stays just above it. */
	static int ink(int rgb, float alpha) {
		return ARGB.color(Math.max(4, Math.min(255, (int) (alpha * 255))), rgb);
	}

	static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int rgb, float alpha) {
		g.fill(x, y, x + w, y + h, a(rgb, alpha));
	}

	/** A bar filled to {@code share} (0..1) on a dark track with a thin edge. */
	static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float share, int rgb, int edge, float alpha) {
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, a(edge, alpha));
		g.fill(x, y, x + w, y + h, a(INK, alpha));
		int filled = Math.round(w * Math.max(0, Math.min(1, share)));
		if (filled > 0) {
			g.fill(x, y, x + filled, y + h, a(rgb, alpha));
		}
	}

	/** Letters {@code scale} times the usual size; {@code align}: 0 from x, 1 centred on x, 2 ending at x. */
	static void big(GuiGraphicsExtractor g, Font font, Component text, int x, int y, float scale, int argb, int align) {
		int w = font.width(text);
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, text, align == 1 ? -w / 2 : align == 2 ? -w : 0, 0, argb, true);
		g.pose().popMatrix();
	}

	static void big(GuiGraphicsExtractor g, Font font, String text, int x, int y, float scale, int argb, int align) {
		big(g, font, Component.literal(text), x, y, scale, argb, align);
	}

	/** Small capitals over a block, as the labels of the screens. */
	static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int rgb) {
		big(g, font, text, x, y, 0.75f, a(rgb, 1), 0);
	}

	/** The face of a player who is on the server; a plain square for anyone else. */
	static void face(GuiGraphicsExtractor g, String name, int x, int y, int size) {
		Minecraft mc = Minecraft.getInstance();
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(name);
		if (info != null) {
			PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
		} else {
			g.fill(x, y, x + size, y + size, a(0x6B5A8F, 1));
		}
	}

	/** Seconds as m:ss. */
	static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	/** Health colour: green, yellow below half, red below a quarter. */
	static int healthColor(float share) {
		return share < 0.25f ? RED : share < 0.5f ? YELLOW : GREEN;
	}

	/** A flat button of the Rift's screens: a filled block that lightens under the cursor. */
	static final class Flat extends AbstractButton {
		private final Runnable action;
		private final int fill;
		private final int text;
		private final int edge;
		private final float scale;

		/** @param edge the colour of a thin outline, or -1 for none */
		Flat(int x, int y, int w, int h, Component message, int fill, int text, int edge, float scale, Runnable action) {
			super(x, y, w, h, message);
			this.action = action;
			this.fill = fill;
			this.text = text;
			this.edge = edge;
			this.scale = scale;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			this.action.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			int x = this.getX();
			int y = this.getY();
			boolean hot = this.active && this.isHoveredOrFocused();
			int body = hot ? ARGB.srgbLerp(0.25f, a(this.fill, 1), 0xFFFFFFFF) : a(this.fill, this.active ? 0.95f : 0.5f);
			g.fill(x, y, x + this.width, y + this.height, body);
			if (this.edge >= 0) {
				g.outline(x, y, this.width, this.height, a(hot ? WHITE : this.edge, 1));
			}
			Font font = Minecraft.getInstance().font;
			int ty = y + Math.round((this.height - 8 * this.scale) / 2f) + 1;
			g.pose().pushMatrix();
			g.pose().translate(x + this.width / 2f, ty);
			g.pose().scale(this.scale, this.scale);
			g.text(font, this.getMessage(), -font.width(this.getMessage()) / 2, 0, a(this.text, this.active ? 1 : 0.6f), false);
			g.pose().popMatrix();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}
}
