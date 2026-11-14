package pro.minehouse.riftclient;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The party screen of the Rift lobby: who is in the party, ready, call a friend, find players.
 * It shows what the server sent last ({@link RiftHud#lobby()}) and rebuilds itself when that
 * changes; a button only sends an action.
 */
public final class PartyScreen extends Screen {
	private static final int W = 220;

	private RiftUiPayloads.Lobby shown;
	private String nick = "";
	private EditBox nickBox;

	private PartyScreen() {
		super(Component.literal("Отряд Разлома"));
	}

	static void open() {
		Minecraft mc = Minecraft.getInstance();
		if (RiftHud.lobby().inLobby() && !(mc.gui.screen() instanceof PartyScreen)) {
			mc.gui.setScreen(new PartyScreen());
		}
	}

	static void close() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof PartyScreen) {
			mc.gui.setScreen(null);
		}
	}

	private static void send(String id, String arg) {
		if (ClientPlayNetworking.canSend(RiftUiPayloads.Action.TYPE)) {
			ClientPlayNetworking.send(new RiftUiPayloads.Action(id, arg));
		}
	}

	private int top() {
		return Math.max(8, this.height / 2 - 110);
	}

	@Override
	protected void init() {
		RiftUiPayloads.Lobby l = RiftHud.lobby();
		this.shown = l;
		int x = this.width / 2 - W / 2;
		int y = this.top() + 34 + l.slots().size() * 12 + 22;

		Button ready = Button.builder(RiftText.component(l.ready() ? "&7Не готов" : "&a&lГотов"), b -> {
			send("ready", "");
			if (!l.ready()) {
				this.onClose(); // back to the scene, to see the platform light up
			}
		}).bounds(x, y, W, 20).build();
		ready.setTooltip(Tooltip.create(Component.literal(l.ready() ? "Подождать" : "Когда готовы все, отряд заходит в Разлом")));
		this.addRenderableWidget(ready);
		y += 24;

		if (!l.invitedBy().isEmpty()) {
			this.addRenderableWidget(Button.builder(RiftText.component("&dВступить к " + l.invitedBy()), b -> send("accept", "")).bounds(x, y, W, 20).build());
			y += 24;
		}
		if (l.canInvite()) {
			this.nickBox = new EditBox(this.font, x, y, W - 74, 20, Component.literal("Ник"));
			this.nickBox.setMaxLength(16);
			this.nickBox.setHint(Component.literal("Ник друга"));
			this.nickBox.setValue(this.nick);
			this.nickBox.setResponder(v -> this.nick = v);
			this.addRenderableWidget(this.nickBox);
			this.addRenderableWidget(Button.builder(Component.literal("Позвать"), b -> this.invite()).bounds(x + W - 70, y, 70, 20).build());
			y += 24;
		} else {
			this.nickBox = null;
		}
		if (l.canSearch()) {
			Button search = Button.builder(RiftText.component(l.searching() ? "&dИщем игроков: вкл" : "Найти игроков"), b -> send("search", ""))
					.bounds(x, y, W, 20).build();
			search.setTooltip(Tooltip.create(Component.literal(l.searching() ? "Перестать искать" : "Добрать отряд случайными игроками")));
			this.addRenderableWidget(search);
			y += 24;
		}
		int half = (W - 4) / 2;
		if (l.canLeave()) {
			this.addRenderableWidget(Button.builder(RiftText.component("&cПокинуть отряд"), b -> send("leave", "")).bounds(x, y, half, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("На выживание"), b -> send("hub", "")).bounds(x + half + 4, y, half, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(Component.literal("На выживание"), b -> send("hub", "")).bounds(x, y, W, 20).build());
		}
		y += 24;
		this.addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> this.onClose()).bounds(x, y, W, 20).build());
	}

	private void invite() {
		String who = this.nick.trim();
		if (!who.isEmpty()) {
			send("invite", who);
			this.nick = "";
			if (this.nickBox != null) {
				this.nickBox.setValue("");
			}
		}
	}

	@Override
	public void tick() {
		if (!RiftHud.lobby().inLobby()) {
			this.onClose();
		} else if (!sameButtons(RiftHud.lobby(), this.shown)) {
			this.rebuildWidgets();
		}
	}

	/** The list and the note are drawn every frame; only these change the buttons. */
	static boolean sameButtons(RiftUiPayloads.Lobby a, RiftUiPayloads.Lobby b) {
		return a.ready() == b.ready() && a.canInvite() == b.canInvite() && a.canSearch() == b.canSearch() && a.canLeave() == b.canLeave()
				&& a.searching() == b.searching() && a.invitedBy().equals(b.invitedBy()) && a.slots().size() == b.slots().size();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		RiftUiPayloads.Lobby l = RiftHud.lobby();
		int cx = this.width / 2;
		int y = this.top();
		g.pose().pushMatrix();
		g.pose().translate(cx, y);
		g.pose().scale(1.5f, 1.5f);
		g.centeredText(this.font, RiftText.component("&d&lОтряд Разлома"), 0, 0, 0xFFFFFFFF);
		g.pose().popMatrix();
		y += 26;
		int x = cx - W / 2;
		g.fill(x, y - 4, x + W, y + l.slots().size() * 12 + 2, 0x900B0714);
		for (RiftUiPayloads.Slot s : l.slots()) {
			g.text(this.font, RiftHud.slotLine(s, l.searching()), x + 8, y, 0xFFFFFFFF, true);
			y += 12;
		}
		String note = l.startIn() >= 0 ? "&dВсе готовы, заходим через &f" + l.startIn() + " с" : l.note();
		g.centeredText(this.font, RiftText.component(note), cx, y + 8, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
