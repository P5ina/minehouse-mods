package pro.minehouse.riftclient;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The lobby of the Rift: it is on the screen all the time the player is in the lobby, over the
 * scene with the platforms (which stays in the world behind it, undimmed). On the left who else
 * is on the server, to call them with one button; on the right the party, the mode, the search
 * for players and the big Ready. It shows what the server sent last ({@link RiftHud#lobby()},
 * {@link RiftHud#roster()}) and rebuilds itself when that changes; a button only sends an action.
 * Esc opens the game menu, and the lobby is back when that closes.
 */
public final class PartyScreen extends Screen {
	private static final int BAR = 22;
	private static final int LEFT_W = 124;
	private static final int RIGHT_W = 128;
	private static final int ROW = 16;

	private RiftUiPayloads.Lobby shown;
	private List<RiftUiPayloads.Person> shownPeople = List.of();
	private String nick = "";
	private EditBox nickBox;
	private int peopleRows;
	private int rightTop;

	private PartyScreen() {
		super(Component.literal("Разлом"));
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

	/** How many rows of players fit between the top bar and the nick field (which leaves room for the feed). */
	static int rowsThatFit(int height) {
		return Math.max(1, Math.min(8, (height - BAR - 16 - 40 - 84) / (ROW + 2)));
	}

	/** Only the free ones can be called; the rest of the list says why not. */
	static String personNote(RiftUiPayloads.Person p) {
		return p.state() == 1 ? "приглашён" : p.state() == 2 ? "в забеге" : "";
	}

	@Override
	protected void init() {
		RiftUiPayloads.Lobby l = RiftHud.lobby();
		this.shown = l;
		this.shownPeople = RiftHud.roster().people();

		// The top bar: back to survival, and out of the party when there is one.
		int bx = this.width - 8;
		bx -= 74;
		this.addRenderableWidget(new Ui.Flat(bx, 4, 74, 14, Component.literal("На выживание"), Ui.INK, 0xE9D5FF, Ui.EDGE, 1f, () -> send("hub", "")));
		if (l.canLeave()) {
			bx -= 82;
			this.addRenderableWidget(new Ui.Flat(bx, 4, 78, 14, Component.literal("Покинуть отряд"), Ui.INK, 0xFECDD3, 0x9F1239, 1f, () -> send("leave", "")));
		}

		// Left: who is on the server.
		int x = 8;
		int y = BAR + 18;
		this.peopleRows = Math.min(this.shownPeople.size(), rowsThatFit(this.height));
		for (int i = 0; i < this.peopleRows; i++) {
			RiftUiPayloads.Person p = this.shownPeople.get(i);
			if (p.state() == 0 && l.canInvite()) {
				this.addRenderableWidget(new Ui.Flat(x + LEFT_W - 46, y + 1, 44, ROW - 2, Component.literal("ПОЗВАТЬ"), Ui.ACCENT, 0x14092A, -1, 0.75f,
						() -> send("invite", p.name())));
			}
			y += ROW + 2;
		}
		if (this.shownPeople.isEmpty()) {
			y += 12;
		}
		if (l.canInvite()) {
			y += 12;
			this.nickBox = new EditBox(this.font, x, y, LEFT_W - 48, 16, Component.literal("Ник"));
			this.nickBox.setMaxLength(16);
			this.nickBox.setHint(Component.literal("Ник друга"));
			this.nickBox.setValue(this.nick);
			this.nickBox.setResponder(v -> this.nick = v);
			this.addRenderableWidget(this.nickBox);
			this.addRenderableWidget(new Ui.Flat(x + LEFT_W - 46, y, 46, 16, Component.literal("ПОЗВАТЬ"), Ui.ACCENT, 0x14092A, -1, 0.75f, this::invite));
		} else {
			this.nickBox = null;
		}

		// Right, from the bottom up: the note, Ready, the search, the mode, the party.
		int rx = this.width - RIGHT_W - 8;
		int ry = this.height - 6 - 28;
		ry -= 34;
		this.addRenderableWidget(new Ui.Flat(rx, ry, RIGHT_W, 30, Component.literal(l.ready() ? "НЕ ГОТОВ" : "ГОТОВ"), l.ready() ? 0x6B5A8F : Ui.YELLOW,
				l.ready() ? Ui.WHITE : 0x1C1300, -1, 2f, () -> send("ready", "")));
		if (l.canSearch()) {
			ry -= 20;
			this.addRenderableWidget(new Ui.Flat(rx, ry, RIGHT_W, 16, Component.literal("ПОДБОР ИГРОКОВ: " + (l.searching() ? "ВКЛ" : "ВЫКЛ")), Ui.PANEL_DIM,
					Ui.WHITE, l.searching() ? Ui.ACCENT : Ui.EDGE, 0.75f, () -> send("search", "")));
		}
		if (!l.invitedBy().isEmpty()) {
			ry -= 20;
			this.addRenderableWidget(new Ui.Flat(rx, ry, RIGHT_W, 16, Component.literal("ВСТУПИТЬ К " + l.invitedBy().toUpperCase(java.util.Locale.ROOT)),
					Ui.GREEN, 0x052E16, -1, 0.75f, () -> send("accept", "")));
		}
		this.rightTop = ry;
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
			this.minecraft.gui.setScreen(null);
		} else if (!sameButtons(RiftHud.lobby(), this.shown) || !RiftHud.roster().people().equals(this.shownPeople)) {
			this.rebuildWidgets();
		}
	}

	/** The party and the note are drawn every frame; only these change the buttons. */
	static boolean sameButtons(RiftUiPayloads.Lobby a, RiftUiPayloads.Lobby b) {
		return a.ready() == b.ready() && a.canInvite() == b.canInvite() && a.canSearch() == b.canSearch() && a.canLeave() == b.canLeave()
				&& a.searching() == b.searching() && a.invitedBy().equals(b.invitedBy()) && a.slots().size() == b.slots().size();
	}

	/** What a place of the party says under the name. */
	static String slotNote(RiftUiPayloads.Slot s, boolean searching) {
		if (s.name().isEmpty()) {
			return searching ? "ПОДБОР" : "СВОБОДНО";
		}
		return (s.leader() ? "ЛИДЕР · " : "") + (s.ready() ? "ГОТОВ" : "НЕ ГОТОВ");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Nothing: the scene with the platforms is the background.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		RiftUiPayloads.Lobby l = RiftHud.lobby();
		// The top bar.
		g.fill(0, 0, this.width, BAR, Ui.a(Ui.INK, 0.92f));
		g.fill(0, BAR, this.width, BAR + 1, Ui.a(0x2A1A4A, 1));
		Ui.big(g, this.font, "РАЗЛОМ", 10, 5, 1.5f, Ui.a(Ui.ACCENT, 1), 0);
		int tab = 10 + (int) (this.font.width("РАЗЛОМ") * 1.5f) + 12;
		int tabW = this.font.width("ОТРЯД") + 16;
		g.fill(tab, 0, tab + tabW, BAR, Ui.a(Ui.PANEL, 1));
		g.fill(tab, BAR - 2, tab + tabW, BAR, Ui.a(Ui.ACCENT, 1));
		g.text(this.font, "ОТРЯД", tab + 8, 7, 0xFFFFFFFF, false);
		String me = this.minecraft.player == null ? "" : this.minecraft.player.getGameProfile().name();
		int right = this.width - 8 - 74 - (l.canLeave() ? 82 : 0) - 8;
		int nameW = this.font.width(me);
		if (right - nameW - 18 > tab + tabW + 6) {
			g.text(this.font, me, right - nameW, 7, 0xFFFFFFFF, false);
			Ui.face(g, me, right - nameW - 16, 5, 12);
		}

		// Left: players on the server.
		int x = 8;
		int y = BAR + 8;
		Ui.label(g, this.font, "ИГРОКИ НА СЕРВЕРЕ", x, y, 0xB9A9D6);
		y += 10;
		List<RiftUiPayloads.Person> people = this.shownPeople;
		for (int i = 0; i < this.peopleRows; i++) {
			RiftUiPayloads.Person p = people.get(i);
			boolean free = p.state() == 0;
			Ui.panel(g, x, y, LEFT_W, ROW, p.state() == 2 ? Ui.PANEL_DIM : Ui.PANEL, 0.9f);
			g.fill(x, y, x + 2, y + ROW, Ui.a(p.state() == 2 ? 0x6B5A8F : Ui.GREEN, 1));
			Ui.face(g, p.name(), x + 5, y + 3, 10);
			g.text(this.font, this.font.plainSubstrByWidth(p.name(), LEFT_W - 66), x + 18, y + 4, Ui.a(p.state() == 2 ? Ui.MUTED : Ui.WHITE, 1), false);
			if (!free || !l.canInvite()) {
				String note = personNote(p);
				Ui.big(g, this.font, note, x + LEFT_W - 4, y + 5, 0.75f, Ui.a(Ui.MUTED, 1), 2);
			}
			y += ROW + 2;
		}
		if (people.isEmpty()) {
			g.text(this.font, "Пока никого больше нет", x, y + 1, Ui.a(Ui.MUTED, 1), false);
			y += 12;
		} else if (people.size() > this.peopleRows) {
			Ui.label(g, this.font, "и ещё " + (people.size() - this.peopleRows), x, y, 0xB9A9D6);
		}
		if (l.canInvite()) {
			Ui.label(g, this.font, "ПОЗВАТЬ ПО НИКУ", x, y + 3, 0xB9A9D6);
		}

		// Right: the note, then (above the buttons) the mode and the party.
		int rx = this.width - RIGHT_W - 8;
		String note = l.startIn() >= 0 ? "&dВсе готовы, заходим через &f" + l.startIn() + " с"
				: l.note().isEmpty() ? "&7Заходим, когда готовы все." : l.note();
		g.textWithWordWrap(this.font, RiftText.component(note), rx, this.height - 6 - 27, RIGHT_W, 0xFFFFFFFF);
		int ry = this.rightTop - 4;
		ry -= 44;
		Ui.panel(g, rx, ry, RIGHT_W, 44, Ui.PANEL, 0.9f);
		g.fill(rx, ry, rx + RIGHT_W, ry + 2, Ui.a(Ui.ACCENT, 1));
		Ui.label(g, this.font, "РЕЖИМ", rx + 6, ry + 6, 0xB9A9D6);
		g.text(this.font, "Данж на отряд", rx + 6, ry + 14, 0xFFFFFFFF, false);
		Ui.big(g, this.font, "Залы с волнами и Страж.", rx + 6, ry + 26, 0.75f, Ui.a(Ui.MUTED, 1), 0);
		Ui.big(g, this.font, "До " + Math.max(1, l.slots().size()) + " игроков, вещи на входе.", rx + 6, ry + 34, 0.75f, Ui.a(Ui.MUTED, 1), 0);
		ry -= 4;
		for (int i = l.slots().size() - 1; i >= 0; i--) {
			RiftUiPayloads.Slot s = l.slots().get(i);
			boolean empty = s.name().isEmpty();
			ry -= 20;
			Ui.panel(g, rx, ry, RIGHT_W, 18, empty ? Ui.PANEL_DIM : s.ready() ? 0x14532D : Ui.PANEL, 0.9f);
			g.fill(rx, ry + 16, rx + RIGHT_W, ry + 18, Ui.a(empty ? (l.searching() ? Ui.ACCENT : 0x3A2A5C) : s.ready() ? Ui.GREEN : 0x6B5A8F, 1));
			if (empty) {
				g.text(this.font, l.searching() ? "Ищем игрока" : "Свободно", rx + 6, ry + 4, Ui.a(l.searching() ? Ui.ACCENT : 0x8A7BA8, 1), false);
			} else {
				Ui.face(g, s.name(), rx + 4, ry + 3, 10);
				g.text(this.font, this.font.plainSubstrByWidth(s.name(), RIGHT_W - 70), rx + 18, ry + 4, 0xFFFFFFFF, false);
			}
			if (!empty) {
				Ui.big(g, this.font, slotNote(s, l.searching()), rx + RIGHT_W - 4, ry + 5, 0.75f, Ui.a(s.ready() ? 0xBBF7D0 : Ui.MUTED, 1), 2);
			}
		}
		Ui.label(g, this.font, "ОТРЯД", rx, ry - 9, 0xB9A9D6);

		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.minecraft.pauseGame(false); // the game menu; the lobby is back when it closes
			return true;
		}
		boolean typing = this.nickBox != null && this.nickBox.isFocused();
		if (!typing && this.minecraft.options.keyChat.matches(event)) {
			this.minecraft.gui.setScreen(new ChatScreen("", false));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
