package pro.minehouse.eventseditor;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** K when not editing: pick a show to edit, or name a new one. */
public final class ShowPickerScreen extends Screen {
	private EditBox name;

	public ShowPickerScreen() {
		super(Component.literal("Шоу"));
	}

	@Override
	protected void init() {
		int w = Math.min(320, width - 20);
		int left = (width - w) / 2;
		int y = 36;
		int cols = 3;
		int bw = (w - (cols - 1) * 4) / cols;
		int i = 0;
		for (String show : EditorClient.shows) {
			int bx = left + (i % cols) * (bw + 4);
			int by = y + (i / cols) * 22;
			if (by > height - 70) {
				break;
			}
			addRenderableWidget(Button.builder(Component.literal(show), b -> open(show)).bounds(bx, by, bw, 20).build());
			i++;
		}
		int bottom = height - 40;
		name = addRenderableWidget(new EditBox(font, left, bottom, w - 84, 20, Component.literal("имя")));
		name.setHint(Component.literal("новое шоу: латиница, цифры, _ и -"));
		name.setMaxLength(48);
		addRenderableWidget(Button.builder(Component.literal("Создать"), b -> open(name.getValue().trim())).bounds(left + w - 80, bottom, 80, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Обновить"), b -> {
			EditorClient.simple("hello");
		}).bounds(left, bottom + 22, 80, 18).build());
	}

	private void open(String show) {
		if (!show.matches("[a-z0-9_-]+")) {
			EditorClient.toast("§cИмя шоу: строчная латиница, цифры, _ и -");
			return;
		}
		JsonObject o = EditorClient.op("open");
		o.addProperty("show", show);
		EditorClient.send(o);
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() == name && event.isConfirmation()) {
			open(name.getValue().trim());
			return true;
		}
		if (getFocused() != name && MinehouseEventsEditor.panelKey().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.centeredText(font, "Какое шоу редактировать?", width / 2, 14, 0xFFFFFFFF);
		if (EditorClient.shows.isEmpty()) {
			g.centeredText(font, "§7Шоу пока нет: впишите имя нового внизу", width / 2, 50, 0xFFFFFFFF);
		}
		if (EditorClient.toast != null && System.currentTimeMillis() < EditorClient.toastUntil) {
			g.centeredText(font, EditorClient.toast, width / 2, height - 54, 0xFFFFFFFF);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
