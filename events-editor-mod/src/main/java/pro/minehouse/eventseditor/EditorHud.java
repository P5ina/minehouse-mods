package pro.minehouse.eventseditor;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** While editing with the panel hidden: a strip at the top with the show, its time and the key. */
final class EditorHud {
	private EditorHud() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) {
			return;
		}
		if (!EditorClient.editing()) {
			if (EditorClient.toast != null && System.currentTimeMillis() < EditorClient.toastUntil) {
				int tw = mc.font.width(EditorClient.toast);
				g.fill((g.guiWidth() - tw) / 2 - 4, 16, (g.guiWidth() + tw) / 2 + 4, 28, 0xA0000000);
				g.centeredText(mc.font, EditorClient.toast, g.guiWidth() / 2, 18, 0xFFFFFFFF);
			}
			return;
		}
		String key = MinehouseEventsEditor.panelKey().getTranslatedKeyMessage().getString();
		String text = "Шоу " + EditorClient.show() + "  " + Time.format(EditorClient.tick) + " / " + Time.format(EditorClient.length())
				+ (EditorClient.playing ? "  ▶" : "  ❚❚") + (EditorClient.flag("dirty") ? "  §e не сохранено§r" : "")
				+ "   §7" + key + " — панель";
		int w = mc.font.width(text);
		int x = (g.guiWidth() - w) / 2;
		g.fill(x - 4, 2, x + w + 4, 14, 0xA0000000);
		g.text(mc.font, text, x, 4, 0xFFFFFFFF);
		if (EditorClient.toast != null && System.currentTimeMillis() < EditorClient.toastUntil) {
			int tw = mc.font.width(EditorClient.toast);
			g.fill((g.guiWidth() - tw) / 2 - 4, 16, (g.guiWidth() + tw) / 2 + 4, 28, 0xA0000000);
			g.centeredText(mc.font, EditorClient.toast, g.guiWidth() / 2, 18, 0xFFFFFFFF);
		}
	}
}
