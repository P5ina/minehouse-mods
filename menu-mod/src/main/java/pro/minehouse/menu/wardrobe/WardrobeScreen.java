package pro.minehouse.menu.wardrobe;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The wardrobe, «mannequin» layout: the player in the middle with the slots around them (head,
 * back; more later), the cosmetics for the picked slot on the left, and the picked one with what
 * to do with it on the right. Clicking a tile tries it on, even one you don't own yet; drag the
 * player to turn them round.
 */
public final class WardrobeScreen extends Screen {
	// Colours (ARGB).
	private static final int SHADE = 0xC0080A0E;
	private static final int BG = 0xF214121A;
	private static final int SIDE = 0xF21B1823;
	private static final int STAGE = 0xFF1F1B29;
	private static final int TILE = 0xFF252130;
	private static final int TILE_HOVER = 0xFF2E2940;
	private static final int ACCENT = 0xFFB58CFF;
	private static final int ACCENT_HOVER = 0xFFC9A8FF;
	private static final int ON_ACCENT = 0xFF14121A;
	private static final int GREEN = 0xFF6FD08C;
	private static final int TEXT = 0xFFEEEAF5;
	private static final int MUTED = 0xFFA59FB3;
	private static final int DIM = 0xFF6F687E;
	private static final int LINE = 0xFF2F2A3B;
	private static final int BUTTON = 0xFF2A2437;
	private static final int BUTTON_HOVER = 0xFF352E45;
	private static final int DISABLED = 0xFF2A2533;
	private static final int RED = 0xFFE5484D;

	private static final int LIST_W = 148;
	private static final int INFO_W = 124;
	private static final int TILE_W = 40;
	private static final int TILE_H = 50;
	private static final int GAP = 4;
	private static final int LIST_TOP = 34;

	/** The mannequin's slots; the ones without a server slot are shown as coming later. */
	private enum Slot {
		HAT("Голова", "hat"),
		BACKPACK("Спина", "backpack"),
		CAPE("Плащ", null),
		PET("Питомец", null);

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
	/** Slot → what the preview wears: what is worn, plus what is being tried on. */
	private final Map<String, String> trying = new HashMap<>();
	private float previewYaw = 25;
	private boolean dragging;
	private double scroll;
	private final Map<String, ItemStackRenderState> models = new HashMap<>();

	// Layout, set in init().
	private int x0;
	private int y0;
	private int x1;
	private int y1;

	WardrobeScreen(WardrobeNet.State state) {
		super(Component.literal("Гардероб"));
		this.state = state;
		trying.putAll(state.worn());
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
	protected void init() {
		int w = Math.min(width - 16, 560);
		int h = Math.min(height - 16, 300);
		x0 = (width - w) / 2;
		y0 = (height - h) / 2;
		x1 = x0 + w;
		y1 = y0 + h;
	}

	/** The HUD is hidden while you look at yourself; put back as it was. */
	private Boolean hudWasHidden;

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
			stack.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(rgb));
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

	// ------------------------------------------------------------------ colours

	/** Default colour first, then the 16 dyes. */
	private static final int[] DYES;
	static {
		net.minecraft.world.item.DyeColor[] all = net.minecraft.world.item.DyeColor.values();
		DYES = new int[all.length];
		for (int i = 0; i < all.length; i++) {
			DYES[i] = all[i].getTextureDiffuseColor() & 0xFFFFFF;
		}
	}
	private static final int SWATCH = 14;
	private static final int SWATCH_GAP = 4;
	private static final int SWATCH_COLS = 6;

	/** id → colour being tried on (sent to the server at once if the player owns it). */
	private final Map<String, Integer> tint = new HashMap<>();
	/** Where the colour swatches start; set while drawing the info panel. */
	private int swatchY = -1;

	private int swatchCount() {
		return DYES.length + 1;
	}

	private int swatchColor(WardrobeNet.Item item, int i) {
		return i == 0 ? item.defaultColor() : DYES[i - 1];
	}

	private Button swatch(int i) {
		int sx = x1 - INFO_W + 10 + (i % SWATCH_COLS) * (SWATCH + SWATCH_GAP);
		int sy = swatchY + (i / SWATCH_COLS) * (SWATCH + SWATCH_GAP);
		return new Button(sx, sy, sx + SWATCH, sy + SWATCH, swatchY >= 0);
	}

	private static final String[] DYE_NAMES = {"Белый", "Оранжевый", "Сиреневый", "Голубой", "Жёлтый", "Лаймовый", "Розовый",
			"Серый", "Светло-серый", "Бирюзовый", "Фиолетовый", "Синий", "Коричневый", "Зелёный", "Красный", "Чёрный"};

	private static String dyeName(int i) {
		return i < DYE_NAMES.length ? DYE_NAMES[i] : "";
	}

	private boolean worn(WardrobeNet.Item item) {
		return item.id().equals(state.worn().get(item.slot()));
	}

	private String nameOf(String id) {
		WardrobeNet.Item item = id == null ? null : state.item(id);
		return item == null ? null : item.name();
	}

	// ------------------------------------------------------------------ layout helpers

	private int listX0() {
		return x0 + 8;
	}

	private int listY0() {
		return y0 + LIST_TOP;
	}

	private int columns() {
		return Math.max(1, (LIST_W - 16 + GAP) / (TILE_W + GAP));
	}

	private int tileX(int i) {
		return listX0() + (i % columns()) * (TILE_W + GAP);
	}

	private int tileY(int i) {
		return listY0() + (i / columns()) * (TILE_H + GAP) - (int) scroll;
	}

	private int maxScroll() {
		int rows = (shown().size() + columns() - 1) / columns();
		return Math.max(0, rows * (TILE_H + GAP) - (y1 - 8 - listY0()));
	}

	private int stageX0() {
		return x0 + LIST_W;
	}

	private int stageX1() {
		return x1 - INFO_W;
	}

	private record Button(int x0, int y0, int x1, int y1, boolean enabled) {
		boolean over(double mx, double my) {
			return mx >= x0 && mx < x1 && my >= y0 && my < y1;
		}
	}

	/** Wide enough for the chips beside the mannequin (with the later slots on the right)? */
	private boolean wideStage() {
		return stageX1() - stageX0() >= 300;
	}

	/**
	 * The slot chips: beside the mannequin when there is room (head and back on the left, the later
	 * ones on the right), otherwise a row of the real slots along the top of the stage.
	 */
	private Button slotButton(Slot s) {
		if (wideStage()) {
			int w = 92;
			int h = 22;
			boolean left = s.id != null;
			int index = s.ordinal() % 2;
			int bx = left ? stageX0() + 8 : stageX1() - 8 - w;
			int by = y0 + 30 + index * (h + 8);
			return new Button(bx, by, bx + w, by + h, s.id != null);
		}
		if (s.id == null) {
			return null;
		}
		int gap = 4;
		int w = (stageX1() - stageX0() - 16 - gap) / 2;
		int bx = stageX0() + 8 + s.ordinal() * (w + gap);
		return new Button(bx, y0 + 8, bx + w, y0 + 26, true);
	}

	private Button topUpButton() {
		return new Button(x1 - 64, y0 + 8, x1 - 8, y0 + 22, true);
	}

	private Button actionButton() {
		return new Button(x1 - INFO_W + 10, y1 - 50, x1 - 10, y1 - 30, actionEnabled());
	}

	private Button takeOffButton() {
		return new Button(x1 - INFO_W + 10, y1 - 26, x1 - 10, y1 - 8, !state.worn().isEmpty());
	}

	private WardrobeNet.Item selectedItem() {
		return selected == null ? null : state.item(selected);
	}

	private String actionLabel() {
		WardrobeNet.Item item = selectedItem();
		if (item == null) {
			return "Выбери предмет";
		}
		if (worn(item)) {
			return "Снять";
		}
		if (item.owned()) {
			return "Надеть";
		}
		return "Купить · " + item.price() + " MX";
	}

	private boolean actionEnabled() {
		WardrobeNet.Item item = selectedItem();
		return item != null && (item.owned() || state.coins() >= item.price());
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// No shade: the player stands in the world behind the window, the camera in front of them.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);

		g.fill(x0, y0, x0 + LIST_W, y1, BG);
		g.fill(x1 - INFO_W, y0, x1, y1, BG);

		list(g, mouseX, mouseY, a);
		stage(g, mouseX, mouseY);
		info(g, mouseX, mouseY);
	}

	private void list(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(x0, y0, x0 + LIST_W, y1, SIDE);
		g.text(font, Component.literal(slot.title).withStyle(s -> s.withBold(true)), x0 + 8, y0 + 9, TEXT, false);
		List<WardrobeNet.Item> items = shown();
		g.text(font, items.size() + " шт.", x0 + LIST_W - 8 - font.width(items.size() + " шт."), y0 + 9, DIM, false);
		g.fill(x0 + 8, y0 + 24, x0 + LIST_W - 8, y0 + 25, LINE);

		g.enableScissor(x0, listY0() - 2, x0 + LIST_W, y1 - 4);
		if (items.isEmpty()) {
			g.centeredText(font, "Пока пусто", x0 + LIST_W / 2, listY0() + 30, MUTED);
		}
		float time = (minecraft.level != null ? minecraft.level.getGameTime() : 0) + a;
		for (int i = 0; i < items.size(); i++) {
			WardrobeNet.Item item = items.get(i);
			int x = tileX(i);
			int y = tileY(i);
			if (y > y1 || y + TILE_H < listY0()) {
				continue;
			}
			boolean hover = mouseX >= x && mouseX < x + TILE_W && mouseY >= y && mouseY < y + TILE_H && mouseY >= listY0();
			boolean sel = item.id().equals(selected);
			g.fill(x, y, x + TILE_W, y + TILE_H, hover ? TILE_HOVER : TILE);
			if (sel || worn(item)) {
				g.outline(x, y, TILE_W, TILE_H, sel ? ACCENT : 0xFF4A4060);
			}
			float spin = hover || sel ? time * 4 : 35;
			g.guiRenderState.addPicturesInPictureState(new WardrobePreview.State(null, 0, model(item.id()), spin,
					x + 3, y + 2, x + TILE_W - 3, y + TILE_H - 13, 30, g.scissorStack.peek()));
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
			g.centeredText(font, tag, x + TILE_W / 2, y + TILE_H - 11, color);
			if (hover) {
				g.setTooltipForNextFrame(font, Component.literal(item.name()), mouseX, mouseY);
			}
		}
		g.disableScissor();
	}

	private void stage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int sx0 = stageX0();
		int sx1 = stageX1();
		int cx = (sx0 + sx1) / 2;
		int cy = (y0 + y1) / 2 + 10;

		boolean wide = wideStage();
		for (Slot s : Slot.values()) {
			Button b = slotButton(s);
			if (b == null) {
				continue;
			}
			boolean on = s == slot;
			boolean hover = b.enabled() && b.over(mouseX, mouseY);
			if (!b.enabled()) {
				g.fill(b.x0(), b.y0(), b.x1(), b.y1(), 0xFF1C1925);
				dashed(g, b, 0xFF3A3448);
				g.text(font, s.title + " · скоро", b.x0() + 7, b.y0() + 7, DIM, false);
				continue;
			}
			g.fill(b.x0(), b.y0(), b.x1(), b.y1(), hover ? BUTTON_HOVER : BUTTON);
			g.outline(b.x0(), b.y0(), b.x1() - b.x0(), b.y1() - b.y0(), on ? ACCENT : LINE);
			if (wide) {
				String what = nameOf(trying.get(s.id));
				String label = s.title + " · " + (what == null ? "пусто" : what);
				g.text(font, fit(label, b.x1() - b.x0() - 12), b.x0() + 7, b.y0() + 7, on ? TEXT : 0xFFC7C0D4, false);
			} else {
				g.centeredText(font, s.title, (b.x0() + b.x1()) / 2, b.y0() + 5, on ? TEXT : 0xFFC7C0D4);
				if (trying.get(s.id) != null) {
					g.fill(b.x1() - 6, b.y0() + 3, b.x1() - 3, b.y0() + 6, on ? ACCENT : MUTED);
				}
			}
		}

		g.centeredText(font, "Тяни, чтобы повернуться", cx, y1 - 16, DIM);
	}

	private void dashed(GuiGraphicsExtractor g, Button b, int color) {
		for (int x = b.x0(); x < b.x1(); x += 4) {
			g.fill(x, b.y0(), Math.min(x + 2, b.x1()), b.y0() + 1, color);
			g.fill(x, b.y1() - 1, Math.min(x + 2, b.x1()), b.y1(), color);
		}
		for (int y = b.y0(); y < b.y1(); y += 4) {
			g.fill(b.x0(), y, b.x0() + 1, Math.min(y + 2, b.y1()), color);
			g.fill(b.x1() - 1, y, b.x1(), Math.min(y + 2, b.y1()), color);
		}
	}

	private void info(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int ix0 = x1 - INFO_W;
		g.fill(ix0, y0, x1, y1, SIDE);
		g.text(font, Component.literal(state.coins() + " MX").withStyle(s -> s.withBold(true)), ix0 + 10, y0 + 11, TEXT, false);
		Button top = topUpButton();
		boolean topHover = top.over(mouseX, mouseY);
		g.outline(top.x0(), top.y0(), top.x1() - top.x0(), top.y1() - top.y0(), topHover ? ACCENT_HOVER : ACCENT);
		g.centeredText(font, "Пополнить", (top.x0() + top.x1()) / 2, top.y0() + 3, topHover ? ACCENT_HOVER : ACCENT);
		g.fill(ix0 + 10, y0 + 30, x1 - 10, y0 + 31, LINE);

		WardrobeNet.Item item = selectedItem();
		int ty = y0 + 40;
		g.text(font, slot.title.toUpperCase(java.util.Locale.ROOT), ix0 + 10, ty, MUTED, false);
		if (item != null) {
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(
					Component.literal(item.name()).withStyle(s -> s.withBold(true)), INFO_W - 20);
			int ly = ty + 14;
			for (net.minecraft.util.FormattedCharSequence line : lines) {
				g.text(font, line, ix0 + 10, ly, TEXT, false);
				ly += 11;
			}
			String status = worn(item) ? "Надето" : (item.owned() ? "Есть в гардеробе" : item.price() + " MX");
			g.text(font, status, ix0 + 10, ly + 4, worn(item) ? ACCENT : (item.owned() ? GREEN : MUTED), false);
			swatchY = -1;
			if (item.dyeable()) {
				g.text(font, "ЦВЕТ", ix0 + 10, ly + 20, MUTED, false);
				swatchY = ly + 32;
				int current = colorOf(item.id()) == null ? item.defaultColor() : colorOf(item.id());
				for (int i = 0; i < swatchCount(); i++) {
					Button b = swatch(i);
					int rgb = swatchColor(item, i);
					boolean on = rgb == current;
					boolean hover = b.over(mouseX, mouseY);
					g.fill(b.x0() - 1, b.y0() - 1, b.x1() + 1, b.y1() + 1, on ? ACCENT : (hover ? TEXT : LINE));
					g.fill(b.x0(), b.y0(), b.x1(), b.y1(), 0xFF000000 | rgb);
					if (i == 0) {
						g.centeredText(font, "↺", (b.x0() + b.x1()) / 2, b.y0() + 3, 0xFFFFFFFF);
					}
					if (hover) {
						g.setTooltipForNextFrame(font, Component.literal(i == 0 ? "Как было" : dyeName(i - 1)), mouseX, mouseY);
					}
				}
			}
		} else {
			swatchY = -1;
			g.text(font, "Выбери предмет слева,", ix0 + 10, ty + 14, DIM, false);
			g.text(font, "чтобы примерить", ix0 + 10, ty + 25, DIM, false);
		}

		Button act = actionButton();
		boolean actHover = act.over(mouseX, mouseY);
		int col;
		int textCol = ON_ACCENT;
		if (!act.enabled()) {
			col = DISABLED;
			textCol = DIM;
		} else if (item != null && worn(item)) {
			col = actHover ? 0xFFF06A6E : RED;
			textCol = TEXT;
		} else {
			col = actHover ? ACCENT_HOVER : ACCENT;
		}
		g.fill(act.x0(), act.y0(), act.x1(), act.y1(), col);
		g.centeredText(font, actionLabel(), (act.x0() + act.x1()) / 2, act.y0() + 6, textCol);

		Button off = takeOffButton();
		if (off.enabled()) {
			g.fill(off.x0(), off.y0(), off.x1(), off.y1(), off.over(mouseX, mouseY) ? BUTTON_HOVER : BUTTON);
			g.centeredText(font, "Снять всё", (off.x0() + off.x1()) / 2, off.y0() + 5, TEXT);
		}
	}

	private String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, width - font.width("…")) + "…";
	}

	/** The player's render state, standing, turned by the drag, wearing what is tried on. */
	@SuppressWarnings("unchecked")
	private EntityRenderState playerState() {
		Entity player = minecraft.player;
		EntityRenderer<? super Entity, ?> renderer = (EntityRenderer<? super Entity, ?>) minecraft.getEntityRenderDispatcher().getRenderer(player);
		WardrobeSelf.suppress = true;
		EntityRenderState rs;
		try {
			rs = renderer.createRenderState(player, 1.0F);
		} finally {
			WardrobeSelf.suppress = false;
		}
		rs.shadowPieces.clear();
		rs.outlineColor = 0;
		rs.lightCoords = 15728880;
		if (rs instanceof LivingEntityRenderState living) {
			living.bodyRot = 180 + previewYaw;
			living.yRot = 0;
			living.xRot = 0;
			living.pose = Pose.STANDING;
			living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
			living.boundingBoxHeight = 1.8f;
			living.scale = 1.0F;
			living.walkAnimationSpeed = 0;
			String hat = trying.get("hat");
			if (hat != null) {
				minecraft.getItemModelResolver().updateForTopItem(living.headItem, stack(hat), ItemDisplayContext.HEAD, minecraft.level, null, 0);
				living.wornHeadType = null;
				// Like on the server: the hat takes the helmet's place, so no armour is drawn there.
				if (living instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
					humanoid.headEquipment = stack(hat);
				}
			}
		}
		return rs;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}

		// Colours.
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

		// Slots.
		for (Slot s : Slot.values()) {
			Button b = slotButton(s);
			if (b != null && b.enabled() && b.over(mx, my)) {
				if (slot != s) {
					slot = s;
					scroll = 0;
				}
				return true;
			}
		}

		// Tiles.
		List<WardrobeNet.Item> items = shown();
		for (int i = 0; i < items.size(); i++) {
			int x = tileX(i);
			int ty = tileY(i);
			if (mx >= x && mx < x + TILE_W && my >= ty && my < ty + TILE_H && my >= listY0() && my < y1 - 4) {
				WardrobeNet.Item item = items.get(i);
				if (doubleClick && item.owned()) {
					WardrobeNet.send(worn(item) ? "remove" : "wear", worn(item) ? "slot" : "id", worn(item) ? item.slot() : item.id());
				}
				selected = item.id();
				trying.clear();
				trying.putAll(state.worn());
				trying.put(item.slot(), item.id());
				return true;
			}
		}

		if (topUpButton().over(mx, my)) {
			ConfirmLinkScreen.confirmLinkNow(this, URI.create(state.store()));
			return true;
		}
		Button off = takeOffButton();
		if (off.enabled() && off.over(mx, my)) {
			for (String s : state.worn().keySet()) {
				WardrobeNet.send("remove", "slot", s);
			}
			selected = null;
			return true;
		}
		Button act = actionButton();
		if (act.over(mx, my) && act.enabled()) {
			WardrobeNet.Item item = selectedItem();
			if (worn(item)) {
				WardrobeNet.send("remove", "slot", item.slot());
			} else if (item.owned()) {
				WardrobeNet.send("wear", "id", item.id());
			} else {
				WardrobeNet.send("buy", "id", item.id());
			}
			return true;
		}

		// Drag the player round.
		if (mx >= stageX0() && mx < stageX1() && my >= y0 && my < y1) {
			dragging = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			previewYaw = Mth.wrapDegrees(previewYaw + (float) dx * 2.5f);
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
		if (x >= x0 && x < x0 + LIST_W) {
			scroll = Mth.clamp(scroll - scrollY * 20, 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}
}
