package pro.minehouse.menu.wardrobe;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
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
 * The wardrobe: categories on the left, the cosmetics as cards with their 3D model turning, the
 * player on the right wearing what is picked (drag to turn them round), and what to do with it
 * under the player. Clicking a card tries it on, even one you don't own yet.
 */
public final class WardrobeScreen extends Screen {
	// Colours (ARGB).
	private static final int SHADE = 0xC0080A0E;
	private static final int PANEL = 0xF0171A21;
	private static final int SIDEBAR = 0xF0121419;
	private static final int HEADER = 0xF01D212A;
	private static final int CARD = 0xFF222733;
	private static final int CARD_HOVER = 0xFF2A3140;
	private static final int ACCENT = 0xFF3B82F6;
	private static final int ACCENT_HOVER = 0xFF5B9BFF;
	private static final int GREEN = 0xFF4ADE80;
	private static final int GOLD = 0xFFFBBF24;
	private static final int TEXT = 0xFFF1F5F9;
	private static final int MUTED = 0xFF94A3B8;
	private static final int LINE = 0xFF2A2F3A;
	private static final int DISABLED = 0xFF3A404C;

	private static final int HEADER_H = 22;
	private static final int SIDEBAR_W = 96;
	private static final int PREVIEW_W = 136;
	private static final int CARD_W = 70;
	private static final int CARD_H = 84;
	private static final int GAP = 6;

	private enum Category {
		FEATURED("Витрина", "★", GOLD, null),
		HATS("Шляпы", "▲", 0xFF60A5FA, "hat"),
		BACKPACKS("Рюкзаки", "■", 0xFFF59E0B, "backpack"),
		OWNED("Моё", "♥", 0xFFF472B6, null);

		final String title;
		final String icon;
		final int color;
		final String slot;

		Category(String title, String icon, int color, String slot) {
			this.title = title;
			this.icon = icon;
			this.color = color;
			this.slot = slot;
		}
	}

	private WardrobeNet.State state;
	private Category category = Category.FEATURED;
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
		int w = Math.min(width - 16, 520);
		int h = Math.min(height - 16, 300);
		x0 = (width - w) / 2;
		y0 = (height - h) / 2;
		x1 = x0 + w;
		y1 = y0 + h;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ data

	private List<WardrobeNet.Item> shown() {
		List<WardrobeNet.Item> out = new ArrayList<>();
		for (WardrobeNet.Item item : state.items()) {
			boolean fits = switch (category) {
				case FEATURED -> true;
				case OWNED -> item.owned();
				default -> item.slot().equals(category.slot);
			};
			if (fits) {
				out.add(item);
			}
		}
		return out;
	}

	private static ItemStack stack(String id) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(WardrobeNet.NS, id));
		return stack;
	}

	private ItemStackRenderState model(String id) {
		return models.computeIfAbsent(id, k -> {
			ItemStackRenderState out = new ItemStackRenderState();
			minecraft.getItemModelResolver().updateForTopItem(out, stack(k), ItemDisplayContext.HEAD, minecraft.level, null, 0);
			return out;
		});
	}

	private boolean worn(WardrobeNet.Item item) {
		return item.id().equals(state.worn().get(item.slot()));
	}

	// ------------------------------------------------------------------ layout helpers

	private int gridX0() {
		return x0 + SIDEBAR_W;
	}

	private int gridX1() {
		return x1 - PREVIEW_W;
	}

	private int gridY0() {
		return y0 + HEADER_H;
	}

	private int columns() {
		return Math.max(1, (gridX1() - gridX0() - GAP) / (CARD_W + GAP));
	}

	private int cardX(int i) {
		int cols = columns();
		int used = cols * CARD_W + (cols - 1) * GAP;
		int left = gridX0() + (gridX1() - gridX0() - used) / 2;
		return left + (i % cols) * (CARD_W + GAP);
	}

	private int cardY(int i) {
		return gridY0() + 18 + (i / columns()) * (CARD_H + GAP) - (int) scroll;
	}

	private int maxScroll() {
		int rows = (shown().size() + columns() - 1) / columns();
		int content = 18 + rows * (CARD_H + GAP);
		return Math.max(0, content - (y1 - gridY0()));
	}

	private record Button(int x0, int y0, int x1, int y1, boolean enabled) {
		boolean over(double mx, double my) {
			return mx >= x0 && mx < x1 && my >= y0 && my < y1;
		}
	}

	private Button actionButton() {
		int px0 = x1 - PREVIEW_W + 10;
		return new Button(px0, y1 - 28, x1 - 10, y1 - 10, actionEnabled());
	}

	private Button topUpButton() {
		return new Button(x1 - 62, y0 + 4, x1 - 6, y0 + HEADER_H - 4, true);
	}

	private Button takeOffButton() {
		int px0 = x1 - PREVIEW_W + 10;
		return new Button(px0, y1 - 46, x1 - 10, y1 - 32, true);
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
		g.fill(0, 0, width, height, SHADE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);

		g.fill(x0, y0, x1, y1, PANEL);
		g.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, LINE);

		sidebar(g, mouseX, mouseY);
		grid(g, mouseX, mouseY, a);
		preview(g, mouseX, mouseY, a);
	}

	private void sidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.fill(x0, y0, x0 + SIDEBAR_W, y1, SIDEBAR);
		g.fill(x0, y0, x0 + SIDEBAR_W, y0 + HEADER_H, HEADER);
		g.text(font, Component.literal("Гардероб").withStyle(s -> s.withBold(true)), x0 + 8, y0 + 7, TEXT, false);
		g.fill(x0, y0 + HEADER_H, x1, y0 + HEADER_H + 1, LINE);

		int y = y0 + HEADER_H + 6;
		for (Category c : Category.values()) {
			boolean on = c == category;
			boolean hover = mouseX >= x0 && mouseX < x0 + SIDEBAR_W && mouseY >= y && mouseY < y + 20;
			if (on || hover) {
				g.fill(x0, y, x0 + SIDEBAR_W, y + 20, on ? 0xFF1F2530 : 0xFF1A1E26);
			}
			if (on) {
				g.fill(x0, y, x0 + 2, y + 20, c.color);
			}
			g.text(font, c.icon, x0 + 9, y + 6, c.color, false);
			g.text(font, c.title, x0 + 21, y + 6, on ? TEXT : (hover ? 0xFFCBD5E1 : c.color), false);
			y += 22;
		}

		g.text(font, "MineHouse", x0 + 8, y1 - 14, 0xFF475569, false);
	}

	private void grid(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int gx0 = gridX0();
		int gx1 = gridX1();
		g.fill(gx0, y0, gx1, y0 + HEADER_H, HEADER);
		g.text(font, category.title, gx0 + 8, y0 + 7, TEXT, false);

		List<WardrobeNet.Item> items = shown();
		g.enableScissor(gx0, gridY0() + 1, gx1, y1);
		String section = category == Category.FEATURED ? "Все предметы" : items.size() + " шт.";
		int sw = font.width(section);
		int cx = (gx0 + gx1) / 2;
		int sy = gridY0() + 7 - (int) scroll;
		g.fill(gx0 + 10, sy + 4, cx - sw / 2 - 6, sy + 5, LINE);
		g.fill(cx + sw / 2 + 6, sy + 4, gx1 - 10, sy + 5, LINE);
		g.centeredText(font, section, cx, sy, MUTED);

		if (items.isEmpty()) {
			g.centeredText(font, category == Category.OWNED ? "Пока пусто" : "Скоро здесь что-то будет", cx, gridY0() + 50, MUTED);
		}

		float time = (minecraft.level != null ? minecraft.level.getGameTime() : 0) + a;
		for (int i = 0; i < items.size(); i++) {
			WardrobeNet.Item item = items.get(i);
			int x = cardX(i);
			int y = cardY(i);
			if (y > y1 || y + CARD_H < gridY0()) {
				continue;
			}
			boolean hover = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H && mouseY > gridY0();
			boolean sel = item.id().equals(selected);
			g.fill(x, y, x + CARD_W, y + CARD_H, hover ? CARD_HOVER : CARD);
			// A soft colour behind the model, per slot.
			int tint = "hat".equals(item.slot()) ? 0x2260A5FA : 0x22F59E0B;
			g.fill(x, y, x + CARD_W, y + 56, tint);
			int bar = worn(item) ? GREEN : (item.owned() ? 0xFF64748B : ACCENT);
			g.fill(x, y + CARD_H - 2, x + CARD_W, y + CARD_H, bar);
			if (sel) {
				g.outline(x - 1, y - 1, CARD_W + 2, CARD_H + 2, ACCENT_HOVER);
			}

			float spin = hover || sel ? time * 4 : 35;
			g.guiRenderState.addPicturesInPictureState(new WardrobePreview.State(null, 0, model(item.id()), spin,
					x + 6, y + 4, x + CARD_W - 6, y + 54, 40, g.scissorStack.peek()));

			if (worn(item)) {
				badge(g, x + 3, y + 3, "НАДЕТО", 0xFF166534);
			}
			String name = fit(item.name(), CARD_W - 8);
			g.text(font, name, x + 4, y + 59, TEXT, false);
			if (item.owned()) {
				g.text(font, "Есть", x + 4, y + 70, GREEN, false);
			} else {
				g.text(font, item.price() + " ◆", x + 4, y + 70, 0xFF93C5FD, false);
			}
		}
		g.disableScissor();
	}

	private String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, width - font.width("…")) + "…";
	}

	private void badge(GuiGraphicsExtractor g, int x, int y, String text, int color) {
		int w = font.width(text) + 6;
		g.fill(x, y, x + w, y + 11, color);
		g.text(font, text, x + 3, y + 2, TEXT, false);
	}

	private void preview(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int px0 = x1 - PREVIEW_W;
		g.fill(px0, y0, x1, y1, SIDEBAR);
		g.fill(px0, y0, px0 + 1, y1, LINE);
		g.fill(px0, y0, x1, y0 + HEADER_H, HEADER);

		// Balance + top up.
		String coins = state.coins() + " MX";
		g.text(font, coins, px0 + 8, y0 + 7, 0xFF93C5FD, false);
		Button top = topUpButton();
		button(g, top, "Пополнить", top.over(mouseX, mouseY) ? ACCENT_HOVER : ACCENT);

		// Player.
		int boxY0 = y0 + HEADER_H + 4;
		int boxY1 = y1 - 80;
		if (minecraft.player != null) {
			EntityRenderState player = playerState();
			ItemStackRenderState back = trying.containsKey("backpack") ? model(trying.get("backpack")) : null;
			int size = Math.min((boxY1 - boxY0) * 36 / 100, 80);
			g.guiRenderState.addPicturesInPictureState(new WardrobePreview.State(player, 180 + previewYaw, back, 0,
					px0 + 4, boxY0, x1 - 4, boxY1, size, g.scissorStack.peek()));
		}
		g.centeredText(font, "Тяни, чтобы повернуть", (px0 + x1) / 2, boxY1 + 2, 0xFF475569);

		// What is picked and what to do with it.
		WardrobeNet.Item item = selectedItem();
		int infoY = y1 - 60;
		if (item != null) {
			g.text(font, fit(item.name(), PREVIEW_W - 16), px0 + 10, infoY, TEXT, false);
		}
		boolean anyWorn = !state.worn().isEmpty();
		Button off = takeOffButton();
		if (anyWorn) {
			button(g, off, "Снять всё", off.over(mouseX, mouseY) ? 0xFF4B5563 : 0xFF374151);
		}
		Button act = actionButton();
		int col = !act.enabled() ? DISABLED : (act.over(mouseX, mouseY) ? ACCENT_HOVER : ACCENT);
		if (item != null && worn(item)) {
			col = act.over(mouseX, mouseY) ? 0xFFF87171 : 0xFFDC2626;
		}
		button(g, act, actionLabel(), col);
	}

	private void button(GuiGraphicsExtractor g, Button b, String label, int color) {
		g.fill(b.x0(), b.y0(), b.x1(), b.y1(), color);
		g.fill(b.x0(), b.y1() - 1, b.x1(), b.y1(), 0x40000000);
		g.centeredText(font, label, (b.x0() + b.x1()) / 2, (b.y0() + b.y1()) / 2 - 4, b.enabled() ? TEXT : MUTED);
	}

	/** The player's render state, standing, turned by the drag, wearing what is tried on. */
	@SuppressWarnings("unchecked")
	private EntityRenderState playerState() {
		Entity player = minecraft.player;
		EntityRenderer<? super Entity, ?> renderer = (EntityRenderer<? super Entity, ?>) minecraft.getEntityRenderDispatcher().getRenderer(player);
		EntityRenderState rs = renderer.createRenderState(player, 1.0F);
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

		// Categories.
		int y = y0 + HEADER_H + 6;
		for (Category c : Category.values()) {
			if (mx >= x0 && mx < x0 + SIDEBAR_W && my >= y && my < y + 20) {
				category = c;
				scroll = 0;
				return true;
			}
			y += 22;
		}

		// Cards.
		List<WardrobeNet.Item> items = shown();
		for (int i = 0; i < items.size(); i++) {
			int x = cardX(i);
			int cy = cardY(i);
			if (mx >= x && mx < x + CARD_W && my >= cy && my < cy + CARD_H && my > gridY0()) {
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
		if (!state.worn().isEmpty() && takeOffButton().over(mx, my)) {
			for (String slot : state.worn().keySet()) {
				WardrobeNet.send("remove", "slot", slot);
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
		if (mx >= x1 - PREVIEW_W && mx < x1 && my >= y0 + HEADER_H && my < y1 - 80) {
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
		if (x >= gridX0() && x < gridX1()) {
			scroll = Mth.clamp(scroll - scrollY * 20, 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}
}
