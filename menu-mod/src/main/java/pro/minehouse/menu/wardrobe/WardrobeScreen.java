package pro.minehouse.menu.wardrobe;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3fc;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;

/**
 * The wardrobe in the fitting room, «floating panels» (variant E): the camera stands in front of
 * the player in the world ({@link WardrobeCamera}) and two cards hang in the air beside them, in the
 * site's colours (shadcn/ui zinc) with the Minecraft font. Left: the slot tabs and the cosmetics; right: the picked
 * one, its colours and what to do. Dots on the head and the back are the slots themselves (click
 * one), a dashed line runs from the right card to the slot being dressed. Drag anywhere else to turn
 * round.
 */
public final class WardrobeScreen extends Screen {
	// The site's palette (zinc, dark).
	private static final int CARD = 0xDB09090B;
	private static final int BORDER = 0xFF27272A;
	private static final int MUTED_BG = 0xFF18181B;
	private static final int HOVER_BG = 0xFF27272A;
	private static final int FG = 0xFFFAFAFA;
	private static final int MUTED = 0xFFA1A1AA;
	private static final int FAINT = 0xFF71717A;
	private static final int DISABLED = 0xFF52525B;
	private static final int PRIMARY = 0xFFFAFAFA;
	private static final int PRIMARY_HOVER = 0xFFE4E4E7;
	private static final int ON_PRIMARY = 0xFF18181B;
	private static final int ACCENT = 0xFFA78BFA;
	private static final int GREEN = 0xFF4ADE80;
	private static final int RED = 0xFFF87171;


	private static final int PAD = 8;
	private static final int LEFT_W = 144;
	private static final int RIGHT_W = 134;
	private static final int TILE_W = 40;
	private static final int TILE_H = 46;
	private static final int GAP = 4;
	private static final int COLS = 3;
	private static final int TABS_H = 18;

	/** The slots; the ones without a server slot are shown as coming later. */
	private enum Slot {
		HAT("Голова", "hat"),
		BACKPACK("Спина", "backpack"),
		CAPE("Плащ", null);

		final String title;
		final String id;

		Slot(String title, String id) {
			this.title = title;
			this.id = id;
		}
	}

	private WardrobeNet.State state;
	private Slot slot = Slot.HAT;
	private String selected;
	/** Slot → what the player wears here: what is worn, plus what is being tried on. */
	private final Map<String, String> trying = new HashMap<>();
	/** id → colour being tried on (sent to the server at once if the player owns it). */
	private final Map<String, Integer> tint = new HashMap<>();
	private float previewYaw = 20;
	private boolean dragging;
	private double scroll;
	private final Map<String, ItemStackRenderState> models = new HashMap<>();

	// Layout, recomputed every frame (the panels follow the points beside the player).
	private Box left = new Box(0, 0, 0, 0);
	private Box right = new Box(0, 0, 0, 0);
	private Box bar = new Box(0, 0, 0, 0);
	private int headX;
	private int headY;
	private int backX;
	private int backY;
	private boolean backVisible;
	private int swatchY = -1;
	private int buttonsY;

	/** The HUD is hidden while you look at yourself; put back as it was. */
	private Boolean hudWasHidden;

	WardrobeScreen(WardrobeNet.State state) {
		super(Component.literal("Гардероб"));
		this.state = state;
		trying.putAll(state.worn());
		String hat = state.worn().get(Slot.HAT.id);
		if (hat != null && state.item(hat) != null) {
			selected = hat;
		}
	}

	void update(WardrobeNet.State state) {
		this.state = state;
		trying.clear();
		trying.putAll(state.worn());
		if (selected != null) {
			WardrobeNet.Item item = state.item(selected);
			if (item != null) {
				trying.put(item.slot(), item.id());
			}
		}
	}

	@Override
	public void added() {
		super.added();
		hudWasHidden = minecraft.gui.hud.isHidden();
		if (!hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
	}

	@Override
	public void removed() {
		if (hudWasHidden != null && !hudWasHidden && minecraft.gui.hud.isHidden()) {
			minecraft.gui.hud.toggle();
		}
		super.removed();
	}

	Map<String, String> trying() {
		return trying;
	}

	float previewYaw() {
		return previewYaw;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ data

	private List<WardrobeNet.Item> shown() {
		List<WardrobeNet.Item> out = new ArrayList<>();
		for (WardrobeNet.Item item : state.items()) {
			if (item.slot().equals(slot.id)) {
				out.add(item);
			}
		}
		return out;
	}

	/** The item for a cosmetic, in its colour: the one being tried in the open wardrobe, else the player's. */
	static ItemStack stack(String id) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(WardrobeNet.NS, id));
		Integer rgb = colorOf(id);
		if (rgb != null) {
			stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
		}
		return stack;
	}

	static Integer colorOf(String id) {
		if (Minecraft.getInstance().gui.screen() instanceof WardrobeScreen screen && screen.tint.containsKey(id)) {
			return screen.tint.get(id);
		}
		WardrobeNet.State st = WardrobeNet.state;
		WardrobeNet.Item item = st == null ? null : st.item(id);
		return item == null ? null : item.color();
	}

	private ItemStackRenderState model(String id) {
		Integer rgb = colorOf(id);
		return models.computeIfAbsent(id + "#" + rgb, k -> {
			ItemStackRenderState out = new ItemStackRenderState();
			minecraft.getItemModelResolver().updateForTopItem(out, stack(id), ItemDisplayContext.HEAD, minecraft.level, null, 0);
			return out;
		});
	}

	private boolean worn(WardrobeNet.Item item) {
		return item.id().equals(state.worn().get(item.slot()));
	}

	private WardrobeNet.Item selectedItem() {
		return selected == null ? null : state.item(selected);
	}

	// ------------------------------------------------------------------ colours

	private static final int[] DYES;
	static {
		DyeColor[] all = DyeColor.values();
		DYES = new int[all.length];
		for (int i = 0; i < all.length; i++) {
			DYES[i] = all[i].getTextureDiffuseColor() & 0xFFFFFF;
		}
	}
	private static final String[] DYE_NAMES = {"Белый", "Оранжевый", "Сиреневый", "Голубой", "Жёлтый", "Лаймовый", "Розовый",
			"Серый", "Светло-серый", "Бирюзовый", "Фиолетовый", "Синий", "Коричневый", "Зелёный", "Красный", "Чёрный"};
	private static final int SWATCH = 12;
	private static final int SWATCH_GAP = 4;
	private static final int SWATCH_COLS = 7;

	/** Default colour first, then the 16 dyes. */
	private int swatchCount() {
		return DYES.length + 1;
	}

	private int swatchColor(WardrobeNet.Item item, int i) {
		return i == 0 ? item.defaultColor() : DYES[i - 1];
	}

	private Box swatch(int i) {
		int sx = right.x0 + PAD + (i % SWATCH_COLS) * (SWATCH + SWATCH_GAP);
		int sy = swatchY + (i / SWATCH_COLS) * (SWATCH + SWATCH_GAP);
		return new Box(sx, sy, sx + SWATCH, sy + SWATCH);
	}

	private int swatchRows() {
		return (swatchCount() + SWATCH_COLS - 1) / SWATCH_COLS;
	}

	// ------------------------------------------------------------------ text

	private static MutableComponent txt(String s) {
		return Component.literal(s);
	}

	private static MutableComponent bold(String s) {
		return Component.literal(s).withStyle(st -> st.withBold(true));
	}

	private void text(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
		g.text(font, c, x, y, color, false);
	}

	private void centered(GuiGraphicsExtractor g, Component c, int cx, int y, int color) {
		g.text(font, c, cx - font.width(c) / 2, y, color, false);
	}

	// ------------------------------------------------------------------ shapes

	private record Box(int x0, int y0, int x1, int y1) {
		boolean over(double mx, double my) {
			return mx >= x0 && mx < x1 && my >= y0 && my < y1;
		}

		int w() {
			return x1 - x0;
		}
	}

	/** A card with 3 px rounded corners: background and a 1 px border, without overlapping (the background is see-through). */
	private static void card(GuiGraphicsExtractor g, Box b, int bg, int border) {
		int x0 = b.x0, y0 = b.y0, x1 = b.x1, y1 = b.y1;
		g.fill(x0 + 3, y0 + 1, x1 - 3, y0 + 2, bg);
		g.fill(x0 + 2, y0 + 2, x1 - 2, y0 + 3, bg);
		g.fill(x0 + 1, y0 + 3, x1 - 1, y1 - 3, bg);
		g.fill(x0 + 2, y1 - 3, x1 - 2, y1 - 2, bg);
		g.fill(x0 + 3, y1 - 2, x1 - 3, y1 - 1, bg);
		if (border == 0) {
			return;
		}
		g.fill(x0 + 3, y0, x1 - 3, y0 + 1, border);
		g.fill(x0 + 3, y1 - 1, x1 - 3, y1, border);
		g.fill(x0, y0 + 3, x0 + 1, y1 - 3, border);
		g.fill(x1 - 1, y0 + 3, x1, y1 - 3, border);
		g.fill(x0 + 1, y0 + 2, x0 + 2, y0 + 3, border);
		g.fill(x0 + 2, y0 + 1, x0 + 3, y0 + 2, border);
		g.fill(x1 - 2, y0 + 2, x1 - 1, y0 + 3, border);
		g.fill(x1 - 3, y0 + 1, x1 - 2, y0 + 2, border);
		g.fill(x0 + 1, y1 - 3, x0 + 2, y1 - 2, border);
		g.fill(x0 + 2, y1 - 2, x0 + 3, y1 - 1, border);
		g.fill(x1 - 2, y1 - 3, x1 - 1, y1 - 2, border);
		g.fill(x1 - 3, y1 - 2, x1 - 2, y1 - 1, border);
	}

	private static void dashedLine(GuiGraphicsExtractor g, int ax, int ay, int bx, int by, int color) {
		double len = Math.hypot(bx - ax, by - ay);
		int steps = (int) (len / 2);
		for (int i = 0; i < steps; i++) {
			if (i % 3 == 2) {
				continue;
			}
			int x = (int) Math.round(ax + (bx - ax) * (double) i / steps);
			int y = (int) Math.round(ay + (by - ay) * (double) i / steps);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private static void dot(GuiGraphicsExtractor g, int x, int y, int color, boolean ring) {
		g.fill(x - 1, y - 2, x + 2, y + 3, color);
		g.fill(x - 2, y - 1, x + 3, y + 2, color);
		if (ring) {
			int c = (color & 0xFFFFFF) | 0x70000000;
			g.fill(x - 2, y - 5, x + 3, y - 4, c);
			g.fill(x - 2, y + 5, x + 3, y + 6, c);
			g.fill(x - 5, y - 2, x - 4, y + 3, c);
			g.fill(x + 5, y - 2, x + 6, y + 3, c);
			g.fill(x - 4, y - 4, x - 3, y - 3, c);
			g.fill(x + 4, y - 4, x + 5, y - 3, c);
			g.fill(x - 4, y + 4, x - 3, y + 5, c);
			g.fill(x + 4, y + 4, x + 5, y + 5, c);
		}
	}

	// ------------------------------------------------------------------ the world

	/** Where a world point is on the screen (GUI pixels), or null if it is behind the camera. */
	private int[] project(Vec3 p) {
		Camera cam = minecraft.gameRenderer.mainCamera();
		Vec3 v = p.subtract(cam.position());
		Vector3fc f = cam.forwardVector();
		Vector3fc u = cam.upVector();
		Vector3fc l = cam.leftVector();
		double z = v.x * f.x() + v.y * f.y() + v.z * f.z();
		if (z < 0.05) {
			return null;
		}
		double x = -(v.x * l.x() + v.y * l.y() + v.z * l.z());
		double y = v.x * u.x() + v.y * u.y() + v.z * u.z();
		double k = (height / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2);
		return new int[] {(int) Math.round(width / 2.0 + x / z * k), (int) Math.round(height / 2.0 - y / z * k)};
	}

	/** Lay the cards out beside the player and find the slot points on them. */
	private void layout(float partial) {
		int[] center = {width / 2, height / 2};
		Vec3 feet = minecraft.player.getPosition(partial);
		Camera cam = minecraft.gameRenderer.mainCamera();
		Vector3fc l = cam.leftVector();
		Vec3 side = new Vec3(l.x(), 0, l.z()).normalize();

		double bodyYaw = Math.toRadians(minecraft.player.getYRot() + previewYaw);
		Vec3 front = new Vec3(-Math.sin(bodyYaw), 0, Math.cos(bodyYaw));
		int[] head = project(feet.add(0, 1.78, 0));
		Vec3 toCam = cam.position().subtract(feet);
		Vec3 backAt = feet.add(0, 1.12, 0).subtract(front.scale(0.28));
		if (front.dot(new Vec3(toCam.x, 0, toCam.z).normalize()) > 0.3) {
			// Facing the camera: the back is behind the body, show its dot at the shoulder.
			backAt = feet.add(0, 1.3, 0).subtract(front.scale(0.2)).subtract(side.scale(0.42));
		}
		int[] back = project(backAt);
		int[] leftAt = project(feet.add(0, 1.05, 0).add(side.scale(1.15)));
		int[] rightAt = project(feet.add(0, 1.05, 0).subtract(side.scale(1.15)));
		if (head == null) {
			head = new int[] {center[0], center[1] - 60};
		}
		if (back == null) {
			back = new int[] {center[0], center[1] - 20};
		}
		headX = head[0];
		headY = head[1];
		backX = back[0];
		backY = back[1];
		// The back is hidden behind the body when facing the camera.
		backVisible = true;

		int leftH = Math.min(height - 48, TABS_H + PAD * 3 + 2 * (TILE_H + GAP) + 14 + rowsExtra());
		int rightH = rightHeight();
		int lx = leftAt == null ? center[0] - 70 - LEFT_W : leftAt[0] - LEFT_W;
		int rx = rightAt == null ? center[0] + 70 : rightAt[0];
		// Keep them on the screen and off the player.
		lx = Mth.clamp(lx, 8, Math.max(8, center[0] - 46 - LEFT_W));
		rx = Mth.clamp(rx, Math.min(width - 8 - RIGHT_W, center[0] + 46), width - 8 - RIGHT_W);
		int ly = Mth.clamp((leftAt == null ? center[1] : leftAt[1]) - leftH / 2, 8, height - 40 - leftH);
		int ry = Mth.clamp((rightAt == null ? center[1] : rightAt[1]) - rightH / 2 + 12, 8, height - 40 - rightH);
		left = new Box(lx, ly, lx + LEFT_W, ly + leftH);
		right = new Box(rx, ry, rx + RIGHT_W, ry + rightH);
	}

	/** A third row of tiles when there is room for it. */
	private int rowsExtra() {
		int rows = (shown().size() + COLS - 1) / COLS;
		return rows > 2 && height > 260 ? TILE_H + GAP : 0;
	}

	/** Must match {@link #rightCard}: header, content, buttons, a rule, the balance. */
	private int rightHeight() {
		WardrobeNet.Item item = selectedItem();
		int content;
		if (item == null) {
			content = 40;
		} else {
			content = font.split(bold(item.name()), RIGHT_W - PAD * 2).size() * 11 + 4;
			if (item.dyeable()) {
				content += 12 + swatchRows() * (SWATCH + SWATCH_GAP);
			}
		}
		return PAD + 16 + content + 6 + 18 + BALANCE_H + PAD - 2;
	}

	private static final int BALANCE_H = 6 + 5 + 12;

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// No shade: the player stands in the fitting room, the camera in front of them.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		if (minecraft.player == null) {
			return;
		}
		layout(a);
		slots(g, mouseX, mouseY);
		leftCard(g, mouseX, mouseY, a);
		rightCard(g, mouseX, mouseY);
		bottomBar(g, mouseX, mouseY);
	}

	/** The dots on the head and the back, and the line from the right card to the slot being dressed. */
	private void slots(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int sx = slot == Slot.BACKPACK ? backX : headX;
		int sy = slot == Slot.BACKPACK ? backY : headY;
		dashedLine(g, right.x0, right.y0 + 14, sx, sy, (ACCENT & 0xFFFFFF) | 0xC0000000);
		boolean hoverHead = Math.abs(mouseX - headX) <= 6 && Math.abs(mouseY - headY) <= 6;
		boolean hoverBack = Math.abs(mouseX - backX) <= 6 && Math.abs(mouseY - backY) <= 6;
		dot(g, headX, headY, slot == Slot.HAT ? ACCENT : (hoverHead ? FG : 0xFFD4D4D8), slot == Slot.HAT);
		if (backVisible) {
			dot(g, backX, backY, slot == Slot.BACKPACK ? ACCENT : (hoverBack ? FG : 0xFFD4D4D8), slot == Slot.BACKPACK);
		}
		if (hoverHead) {
			g.setTooltipForNextFrame(font, txt("Голова"), mouseX, mouseY);
		} else if (hoverBack && backVisible) {
			g.setTooltipForNextFrame(font, txt("Спина"), mouseX, mouseY);
		}
	}

	private Box tab(Slot s) {
		int w = (LEFT_W - PAD * 2 - 4) / Slot.values().length;
		int x = left.x0 + PAD + 2 + s.ordinal() * w;
		return new Box(x, left.y0 + PAD + 2, x + w, left.y0 + PAD + TABS_H - 2);
	}

	private int gridY0() {
		return left.y0 + PAD + TABS_H + PAD;
	}

	private int gridY1() {
		return left.y1 - PAD - 12;
	}

	private int tileX(int i) {
		return left.x0 + PAD + (i % COLS) * (TILE_W + GAP);
	}

	private int tileY(int i) {
		return gridY0() + (i / COLS) * (TILE_H + GAP) - (int) scroll;
	}

	private int maxScroll() {
		int rows = (shown().size() + COLS - 1) / COLS;
		return Math.max(0, rows * (TILE_H + GAP) - GAP - (gridY1() - gridY0()));
	}

	private void leftCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		card(g, left, CARD, BORDER);
		// Tabs, like shadcn's: a muted track with the active one raised.
		card(g, new Box(left.x0 + PAD, left.y0 + PAD, left.x1 - PAD, left.y0 + PAD + TABS_H), MUTED_BG, 0);
		for (Slot s : Slot.values()) {
			Box t = tab(s);
			boolean on = s == slot;
			if (on) {
				card(g, t, 0xFF09090B, 0xFF27272A);
			}
			int color = s.id == null ? DISABLED : (on ? FG : (t.over(mouseX, mouseY) ? FG : MUTED));
			centered(g, txt(s.title), (t.x0 + t.x1) / 2, t.y0 + 4, color);
		}

		List<WardrobeNet.Item> items = shown();
		g.enableScissor(left.x0 + 1, gridY0() - 1, left.x1 - 1, gridY1());
		if (items.isEmpty()) {
			centered(g, txt(slot.id == null ? "Скоро" : "Пока пусто"), (left.x0 + left.x1) / 2, gridY0() + 30, MUTED);
		}
		float time = (minecraft.level != null ? minecraft.level.getGameTime() : 0) + a;
		for (int i = 0; i < items.size(); i++) {
			WardrobeNet.Item item = items.get(i);
			int x = tileX(i);
			int y = tileY(i);
			if (y > gridY1() || y + TILE_H < gridY0()) {
				continue;
			}
			Box b = new Box(x, y, x + TILE_W, y + TILE_H);
			boolean hover = b.over(mouseX, mouseY) && mouseY >= gridY0() && mouseY < gridY1();
			boolean sel = item.id().equals(selected);
			card(g, b, hover ? HOVER_BG : MUTED_BG, sel ? ACCENT : (worn(item) ? 0xFF3F3F46 : BORDER));
			float spin = hover || sel ? time * 4 : 35;
			g.guiRenderState.addPicturesInPictureState(new WardrobePreview.State(null, 0, model(item.id()), spin,
					x + 3, y + 2, x + TILE_W - 3, y + TILE_H - 12, 28, g.scissorStack.peek()));
			String tag;
			int color;
			if (worn(item)) {
				tag = "Надето";
				color = ACCENT;
			} else if (item.owned()) {
				tag = "Есть";
				color = GREEN;
			} else {
				tag = item.price() + " MX";
				color = MUTED;
			}
			centered(g, txt(tag), x + TILE_W / 2, y + TILE_H - 10, color);
			if (hover) {
				g.setTooltipForNextFrame(font, txt(item.name()), mouseX, mouseY);
			}
		}
		g.disableScissor();
		String hint = maxScroll() > 0 ? "Колесо мыши: ещё" : "Двойной клик: надеть";
		text(g, txt(hint), left.x0 + PAD, left.y1 - PAD - 8, FAINT);
	}

	private Box actionButton() {
		int w = (RIGHT_W - PAD * 2 - 6) / 2;
		return new Box(right.x0 + PAD, buttonsY, right.x0 + PAD + w, buttonsY + 18);
	}

	private Box takeOffButton() {
		int w = (RIGHT_W - PAD * 2 - 6) / 2;
		return new Box(right.x1 - PAD - w, buttonsY, right.x1 - PAD, buttonsY + 18);
	}

	private String actionLabel(WardrobeNet.Item item) {
		if (item == null) {
			return "Надеть";
		}
		if (worn(item)) {
			return "Снять";
		}
		if (item.owned()) {
			return "Надеть";
		}
		return item.price() + " MX";
	}

	private boolean actionEnabled() {
		WardrobeNet.Item item = selectedItem();
		return item != null && (item.owned() || state.coins() >= item.price());
	}

	private void rightCard(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		card(g, right, CARD, BORDER);
		WardrobeNet.Item item = selectedItem();
		int x = right.x0 + PAD;
		int y = right.y0 + PAD;
		text(g, txt(slot.title), x, y + 2, MUTED);
		if (item != null) {
			String badge = worn(item) ? "Надето" : (item.owned() ? "Есть" : item.price() + " MX");
			int bw = font.width(txt(badge)) + 10;
			Box b = new Box(right.x1 - PAD - bw, y, right.x1 - PAD, y + 12);
			card(g, b, 0x00000000, worn(item) ? ACCENT : BORDER);
			centered(g, txt(badge), (b.x0 + b.x1) / 2, y + 2, worn(item) ? ACCENT : (item.owned() ? GREEN : FG));
		}
		y += 16;
		swatchY = -1;
		if (item == null) {
			text(g, txt("Выбери вещь слева"), x, y + 2, FG);
			text(g, txt("или точку на себе,"), x, y + 13, MUTED);
			text(g, txt("чтобы примерить"), x, y + 24, MUTED);
			y += 40;
		} else {
			for (FormattedCharSequence line : font.split(bold(item.name()), RIGHT_W - PAD * 2)) {
				g.text(font, line, x, y, FG, false);
				y += 11;
			}
			y += 4;
			if (item.dyeable()) {
				text(g, txt("Цвет"), x, y, MUTED);
				y += 12;
				swatchY = y;
				Integer c = colorOf(item.id());
				int current = c == null ? item.defaultColor() : c;
				for (int i = 0; i < swatchCount(); i++) {
					Box s = swatch(i);
					int rgb = swatchColor(item, i);
					boolean on = rgb == current;
					boolean hover = s.over(mouseX, mouseY);
					if (on || hover) {
						card(g, new Box(s.x0 - 2, s.y0 - 2, s.x1 + 2, s.y1 + 2), 0x00000000, on ? ACCENT : 0xFF71717A);
					}
					card(g, s, 0xFF000000 | rgb, 0x30FFFFFF);
					if (i == 0) {
						centered(g, Component.literal("↺"), (s.x0 + s.x1) / 2 + 1, s.y0 + 2, (rgb & 0xFFFFFF) > 0x808080 ? 0xFF18181B : 0xFFFFFFFF);
					}
					if (hover) {
						g.setTooltipForNextFrame(font, txt(i == 0 ? "Как было" : DYE_NAMES[i - 1]), mouseX, mouseY);
					}
				}
				y += swatchRows() * (SWATCH + SWATCH_GAP);
			}
		}
		y += 6;
		buttonsY = y;

		Box act = actionButton();
		boolean on = actionEnabled();
		boolean hover = on && act.over(mouseX, mouseY);
		boolean remove = item != null && worn(item);
		if (remove) {
			card(g, act, hover ? 0x33F87171 : 0x00000000, RED);
			centered(g, txt(actionLabel(item)), (act.x0 + act.x1) / 2, act.y0 + 5, RED);
		} else {
			card(g, act, on ? (hover ? PRIMARY_HOVER : PRIMARY) : 0xFF27272A, 0);
			centered(g, txt(actionLabel(item)), (act.x0 + act.x1) / 2, act.y0 + 5, on ? ON_PRIMARY : DISABLED);
		}
		Box off = takeOffButton();
		boolean offOn = !state.worn().isEmpty();
		card(g, off, offOn && off.over(mouseX, mouseY) ? HOVER_BG : 0x00000000, BORDER);
		centered(g, txt("Снять всё"), (off.x0 + off.x1) / 2, off.y0 + 5, offOn ? FG : DISABLED);

		// The balance, under a rule.
		g.fill(right.x0 + 1, buttonsY + 24, right.x1 - 1, buttonsY + 25, BORDER);
		int by = buttonsY + 29;
		text(g, bold(state.coins() + " MX"), x, by + 2, FG);
		Component top = txt("Пополнить");
		int tw = font.width(top);
		boolean topHover = topUpButton().over(mouseX, mouseY);
		text(g, top, right.x1 - PAD - tw, by + 2, topHover ? FG : ACCENT);
	}

	private Box topUpButton() {
		int by = buttonsY + 29;
		int tw = font.width(txt("Пополнить"));
		return new Box(right.x1 - PAD - tw - 2, by - 2, right.x1 - PAD + 2, by + 12);
	}

	/** The hints along the bottom, like keyboard shortcuts on the site. */
	private void bottomBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		String[][] parts = {{"ЛКМ", "выбрать"}, {"тяни", "повернуться"}, {"Esc", "выйти"}};
		int w = 12;
		for (String[] p : parts) {
			w += font.width(txt(p[0])) + 8 + 4 + font.width(txt(p[1])) + 12;
		}
		int bx0 = width / 2 - w / 2;
		int by0 = height - 26;
		bar = new Box(bx0, by0, bx0 + w, by0 + 18);
		card(g, bar, CARD, BORDER);
		int x = bx0 + 12;
		for (String[] p : parts) {
			int kw = font.width(txt(p[0])) + 8;
			card(g, new Box(x, by0 + 3, x + kw, by0 + 15), 0x00000000, 0xFF3F3F46);
			text(g, txt(p[0]), x + 4, by0 + 5, FG);
			x += kw + 4;
			text(g, txt(p[1]), x, by0 + 5, MUTED);
			x += font.width(txt(p[1])) + 12;
		}
	}

	// ------------------------------------------------------------------ input

	private void pick(WardrobeNet.Item item) {
		selected = item.id();
		trying.clear();
		trying.putAll(state.worn());
		trying.put(item.slot(), item.id());
	}

	private void selectSlot(Slot s) {
		if (slot != s) {
			slot = s;
			scroll = 0;
		}
		// Show what is worn there, if anything.
		String worn = s.id == null ? null : state.worn().get(s.id);
		WardrobeNet.Item item = worn == null ? null : state.item(worn);
		if (item != null) {
			pick(item);
		} else if (selectedItem() != null && !selectedItem().slot().equals(s.id)) {
			selected = null;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}

		// The slot points on the player.
		if (Math.abs(mx - headX) <= 6 && Math.abs(my - headY) <= 6) {
			selectSlot(Slot.HAT);
			return true;
		}
		if (backVisible && Math.abs(mx - backX) <= 6 && Math.abs(my - backY) <= 6) {
			selectSlot(Slot.BACKPACK);
			return true;
		}

		if (left.over(mx, my)) {
			for (Slot s : Slot.values()) {
				if (s.id != null && tab(s).over(mx, my)) {
					selectSlot(s);
					return true;
				}
			}
			List<WardrobeNet.Item> items = shown();
			for (int i = 0; i < items.size(); i++) {
				Box b = new Box(tileX(i), tileY(i), tileX(i) + TILE_W, tileY(i) + TILE_H);
				if (b.over(mx, my) && my >= gridY0() && my < gridY1()) {
					WardrobeNet.Item item = items.get(i);
					if (doubleClick && item.owned()) {
						WardrobeNet.send(worn(item) ? "remove" : "wear", worn(item) ? "slot" : "id", worn(item) ? item.slot() : item.id());
					}
					pick(item);
					return true;
				}
			}
			return true;
		}

		if (right.over(mx, my)) {
			WardrobeNet.Item sel = selectedItem();
			if (sel != null && sel.dyeable() && swatchY >= 0) {
				for (int i = 0; i < swatchCount(); i++) {
					if (swatch(i).over(mx, my)) {
						int rgb = swatchColor(sel, i);
						tint.put(sel.id(), rgb);
						if (sel.owned()) {
							WardrobeNet.dye(sel.id(), rgb);
						}
						return true;
					}
				}
			}
			if (actionButton().over(mx, my) && actionEnabled()) {
				if (worn(sel)) {
					WardrobeNet.send("remove", "slot", sel.slot());
				} else if (sel.owned()) {
					WardrobeNet.send("wear", "id", sel.id());
				} else {
					WardrobeNet.send("buy", "id", sel.id());
				}
				return true;
			}
			if (takeOffButton().over(mx, my) && !state.worn().isEmpty()) {
				for (String s : state.worn().keySet()) {
					WardrobeNet.send("remove", "slot", s);
				}
				selected = null;
				return true;
			}
			if (topUpButton().over(mx, my)) {
				ConfirmLinkScreen.confirmLinkNow(this, URI.create(state.store()));
				return true;
			}
			return true;
		}

		if (bar.over(mx, my)) {
			return true;
		}
		// Anywhere else: turn round.
		dragging = true;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			previewYaw = Mth.wrapDegrees(previewYaw - (float) dx * 2.5f);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (left.over(x, y)) {
			scroll = Mth.clamp(scroll - scrollY * 20, 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}
}
