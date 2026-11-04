package pro.minehouse.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/**
 * «Моды сборки»: the player turns optional pack mods on and off. Required mods
 * (ours, Polymer, voice chat…) are not listed. Done downloads what is needed
 * now and swaps the files after the game exits (see {@link PackUpdater#choose}).
 */
public final class PackModsScreen extends Screen {
	private static final int ROW = 24;

	private final Screen parent;
	private final Set<String> disabled;
	private final Set<String> initial;
	private int page;

	public PackModsScreen(Screen parent) {
		super(Component.literal("Моды сборки"));
		this.parent = parent;
		this.initial = new TreeSet<>(PackUpdater.disabled());
		this.disabled = new TreeSet<>(this.initial);
	}

	private List<PackUpdater.Option> options() {
		PackUpdater.Manifest m = PackUpdater.manifest();
		return m == null ? List.of() : m.optionList();
	}

	private int perPage() {
		return Math.max(2, (this.height - 100) / ROW * 2);
	}

	@Override
	protected void init() {
		List<PackUpdater.Option> options = this.options();
		int per = this.perPage();
		int pages = Math.max(1, (options.size() + per - 1) / per);
		this.page = Math.min(this.page, pages - 1);
		Map<String, String> names = new HashMap<>();
		for (PackUpdater.Option o : options) {
			names.put(o.id(), o.name());
		}
		Set<String> on = PackUpdater.manifest() == null ? Set.of() : PackUpdater.enabled(PackUpdater.manifest(), this.disabled);

		int left = this.width / 2 - 155;
		int top = 44;
		List<PackUpdater.Option> shown = options.subList(Math.min(options.size(), this.page * per), Math.min(options.size(), (this.page + 1) * per));
		for (int i = 0; i < shown.size(); i++) {
			PackUpdater.Option o = shown.get(i);
			boolean off = this.disabled.contains(o.id());
			List<String> missing = new ArrayList<>();
			if (o.requires() != null) {
				for (String r : o.requires()) {
					if (!on.contains(r)) {
						missing.add(names.getOrDefault(r, r));
					}
				}
			}
			String label = (off ? "§7✘ " : "§a✔ ") + o.name();
			if (!off && !missing.isEmpty()) {
				label = "§e✘ " + o.name();
			}
			Button b = Button.builder(Component.literal(label), btn -> {
				if (!this.disabled.remove(o.id())) {
					this.disabled.add(o.id());
				}
				this.rebuildWidgets();
			}).bounds(left + (i % 2) * 160, top + (i / 2) * ROW, 150, 20).build();
			String tip = o.why() == null ? "" : o.why();
			if (!missing.isEmpty()) {
				tip += "\n§eНе работает без: " + String.join(", ", missing);
			}
			if (!tip.isEmpty()) {
				b.setTooltip(Tooltip.create(Component.literal(tip)));
			}
			this.addRenderableWidget(b);
		}

		int bottom = this.height - 28;
		if (pages > 1) {
			Button prev = Button.builder(Component.literal("<"), b -> {
				this.page--;
				this.rebuildWidgets();
			}).bounds(this.width / 2 - 155, bottom - 24, 20, 20).build();
			prev.active = this.page > 0;
			Button next = Button.builder(Component.literal(">"), b -> {
				this.page++;
				this.rebuildWidgets();
			}).bounds(this.width / 2 + 135, bottom - 24, 20, 20).build();
			next.active = this.page < pages - 1;
			this.addRenderableWidget(prev);
			this.addRenderableWidget(next);
		}
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			if (!this.disabled.equals(this.initial)) {
				PackUpdater.choose(this.disabled);
			}
			this.onClose();
		}).bounds(this.width / 2 - 155, bottom, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
			this.disabled.clear();
			this.disabled.addAll(this.initial);
			this.onClose();
		}).bounds(this.width / 2 + 5, bottom, 150, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		String title = "Моды сборки";
		graphics.text(this.font, title, (this.width - this.font.width(title)) / 2, 12, ARGB.color(255, 0xFFFFFF), true);
		String hint;
		if (PackUpdater.manifest() == null) {
			hint = PackUpdater.phase() == PackUpdater.Phase.FAILED ? "Не удалось загрузить список модов с сайта" : "Загружаю список модов…";
		} else if (!this.disabled.equals(this.initial)) {
			hint = "Изменения применятся после перезапуска игры";
		} else if (PackUpdater.changed()) {
			hint = "Перезапусти игру, чтобы применить изменения";
		} else {
			hint = "Обязательные моды здесь не показаны. Выключенный мод можно заменить своей версией.";
		}
		graphics.text(this.font, hint, (this.width - this.font.width(hint)) / 2, 26, ARGB.color(255, 0xA0A0A0), true);
	}

	@Override
	public void tick() {
		SelfTest.onPackModsTick(this.minecraft);
		// The manifest may arrive while the screen is open.
		if (PackUpdater.manifest() != null && this.children().size() <= 2) {
			this.rebuildWidgets();
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
