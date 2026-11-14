package pro.minehouse.riftclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

class RiftUiTest {
	@Test
	void codesBecomeSpans() {
		List<RiftText.Span> spans = RiftText.spans("&d&lРазлом &7открыт, &fNick&r!");
		assertEquals(List.of(new RiftText.Span("Разлом ", 0xFF55FF, true), new RiftText.Span("открыт, ", 0xAAAAAA, false),
				new RiftText.Span("Nick", 0xFFFFFF, false), new RiftText.Span("!", 0xFFFFFF, false)), spans);
	}

	@Test
	void strangeCodesStayText() {
		assertEquals("Tom & Jerry &", RiftText.plain("Tom & Jerry &"));
		assertEquals("a&zb", RiftText.plain("a&zb"));
		assertEquals("", RiftText.plain("&a&l"));
	}

	@Test
	void linesFadeAtTheEnd() {
		assertEquals(1f, RiftHud.alpha(0, 7000));
		assertEquals(1f, RiftHud.alpha(6000, 7000));
		assertEquals(0.5f, RiftHud.alpha(6750, 7000), 1e-6);
		assertEquals(0f, RiftHud.alpha(7000, 7000));
		assertEquals(0f, RiftHud.alpha(-1, 7000));
	}

	private static RiftUiPayloads.Lobby lobby(boolean ready, String note, int startIn) {
		return new RiftUiPayloads.Lobby(true, List.of(new RiftUiPayloads.Slot("Leader", true, true), new RiftUiPayloads.Slot("", false, false)), true, note,
				ready, true, false, true, "Friend", startIn);
	}

	@Test
	void lobbySurvivesTheWire() {
		for (RiftUiPayloads.Lobby l : List.of(lobby(true, "&7ждём", 3), lobby(false, "", -1), RiftUiPayloads.Lobby.NONE)) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			RiftUiPayloads.Lobby.CODEC.encode(buf, l);
			assertEquals(l, RiftUiPayloads.Lobby.CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		RiftUiPayloads.Note.CODEC.encode(buf, new RiftUiPayloads.Note(RiftUiPayloads.BANNER, "&dРазлом", "&7вперёд"));
		assertEquals(new RiftUiPayloads.Note(RiftUiPayloads.BANNER, "&dРазлом", "&7вперёд"), RiftUiPayloads.Note.CODEC.decode(buf));
		buf = new FriendlyByteBuf(Unpooled.buffer());
		RiftUiPayloads.Action.CODEC.encode(buf, new RiftUiPayloads.Action("invite", "Nick"));
		assertEquals(new RiftUiPayloads.Action("invite", "Nick"), RiftUiPayloads.Action.CODEC.decode(buf));
	}

	@Test
	void onlyButtonChangesRebuildTheScreen() {
		assertTrue(PartyScreen.sameButtons(lobby(true, "a", 3), lobby(true, "b", 2)));
		assertFalse(PartyScreen.sameButtons(lobby(true, "a", 3), lobby(false, "a", 3)));
	}

	private static RiftUiPayloads.Run run(int room, int wave, int waves) {
		return new RiftUiPayloads.Run(true, room, 3, wave, waves, "Страж Разлома", 620, 18, 30, 60, true, "Friend",
				List.of(new RiftUiPayloads.Mate("Friend", 18, 20, (byte) 0, -1), new RiftUiPayloads.Mate("James", 0, 20, (byte) 1, 22)));
	}

	@Test
	void runRosterAndResultSurviveTheWire() {
		for (RiftUiPayloads.Run r : List.of(run(2, 1, 2), RiftUiPayloads.NOT_IN_RUN)) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			RiftUiPayloads.Run.CODEC.encode(buf, r);
			assertEquals(r, RiftUiPayloads.Run.CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		}
		RiftUiPayloads.Roster roster = new RiftUiPayloads.Roster(List.of(new RiftUiPayloads.Person("Friend", (byte) 0), new RiftUiPayloads.Person("James", (byte) 2)));
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		RiftUiPayloads.Roster.CODEC.encode(buf, roster);
		assertEquals(roster, RiftUiPayloads.Roster.CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
		RiftUiPayloads.Result result = new RiftUiPayloads.Result(true, 462, 15, List.of(new RiftUiPayloads.Score("Friend", 31, 2, 0, false)));
		RiftUiPayloads.Result.CODEC.encode(buf, result);
		assertEquals(result, RiftUiPayloads.Result.CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void fightAndSceneSurviveTheWire() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		RiftUiPayloads.Cast cast = new RiftUiPayloads.Cast((byte) 0, 0.6f, -0.8f);
		RiftUiPayloads.Cast.CODEC.encode(buf, cast);
		assertEquals(cast, RiftUiPayloads.Cast.CODEC.decode(buf));
		RiftUiPayloads.Cooldown cool = new RiftUiPayloads.Cooldown((byte) -1, 0);
		RiftUiPayloads.Cooldown.CODEC.encode(buf, cool);
		assertEquals(cool, RiftUiPayloads.Cooldown.CODEC.decode(buf));
		RiftUiPayloads.Warn warn = new RiftUiPayloads.Warn(1024.5, 64, 80.25, 6f, 24);
		RiftUiPayloads.Warn.CODEC.encode(buf, warn);
		assertEquals(warn, RiftUiPayloads.Warn.CODEC.decode(buf));
		RiftUiPayloads.Scene scene = new RiftUiPayloads.Scene(true, 512.5, 102, -6.5, 0, 8);
		RiftUiPayloads.Scene.CODEC.encode(buf, scene);
		assertEquals(scene, RiftUiPayloads.Scene.CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void skillsCoolDown() {
		Combat.reset();
		assertEquals(0, Combat.left(Combat.WAVE, 1000));
		assertEquals(0f, Combat.covered(Combat.WAVE, 1000));
		Combat.cooldown(Combat.WAVE, 160, 1000);
		assertEquals(8000, Combat.left(Combat.WAVE, 1000));
		assertEquals(1f, Combat.covered(Combat.WAVE, 1000));
		assertEquals(0.5f, Combat.covered(Combat.WAVE, 5000), 1e-6);
		assertEquals(0, Combat.left(Combat.WAVE, 9000));
		assertEquals(0, Combat.left(Combat.ROLL, 1000)); // each skill on its own
		Combat.cooldown(-1, 0, 2000); // a new run: everything is ready
		assertEquals(0, Combat.left(Combat.WAVE, 2000));
	}

	@Test
	void slotsSpeakShort() {
		assertEquals("", Combat.countdown(0));
		assertEquals("1.2", Combat.countdown(1200));
		assertEquals("0.1", Combat.countdown(60));
		assertEquals("12", Combat.countdown(11200));
		assertEquals("ЛКМ", Combat.shortKey("Левая кнопка"));
		assertEquals("ЛКМ", Combat.shortKey("Left Button"));
		assertEquals("ПРОБЕЛ", Combat.shortKey("Пробел"));
		assertEquals("ПРОБЕЛ", Combat.shortKey("Space"));
		assertEquals("Q", Combat.shortKey("q"));
		assertEquals(0.5f, Combat.grown(600, 1200), 1e-6);
		assertEquals(1f, Combat.grown(5000, 1200));
	}

	@Test
	void theRunSaysWhereWeAre() {
		assertEquals("ЗАЛ 2 ИЗ 3 · ВОЛНА 1 ИЗ 2", RiftHud.objective(run(2, 1, 2)));
		assertEquals("ЗАЛ 3 ИЗ 3", RiftHud.objective(run(3, 0, 0)));
		assertEquals("ВХОД В РАЗЛОМ", RiftHud.objective(run(0, 0, 0)));
		assertEquals("18", RiftHud.mateNote(run(1, 0, 0).mates().get(0)));
		assertEquals("УПАЛ · 22 с", RiftHud.mateNote(run(1, 0, 0).mates().get(1)));
		assertEquals("ВЫБЫЛ", RiftHud.mateNote(new RiftUiPayloads.Mate("x", 0, 20, (byte) 2, -1)));
		assertEquals("7:42", Ui.clock(462));
	}

	@Test
	void theBestIsWhoKilledMostAndLasted() {
		RiftUiPayloads.Score a = new RiftUiPayloads.Score("a", 10, 0, 0, false);
		RiftUiPayloads.Score b = new RiftUiPayloads.Score("b", 10, 2, 1, false);
		RiftUiPayloads.Score gone = new RiftUiPayloads.Score("c", 40, 0, 3, true);
		assertEquals(1, RiftHud.best(List.of(a, b, gone)));
		assertEquals(-1, RiftHud.best(List.of(gone, new RiftUiPayloads.Score("d", 0, 0, 0, false))));
	}

	@Test
	void theLobbyNamesItsPlaces() {
		assertEquals("ЛИДЕР · ГОТОВ", PartyScreen.slotNote(new RiftUiPayloads.Slot("Leader", true, true), false));
		assertEquals("НЕ ГОТОВ", PartyScreen.slotNote(new RiftUiPayloads.Slot("Friend", false, false), true));
		assertEquals("ПОДБОР", PartyScreen.slotNote(new RiftUiPayloads.Slot("", false, false), true));
		assertEquals("СВОБОДНО", PartyScreen.slotNote(new RiftUiPayloads.Slot("", false, false), false));
		assertEquals("в забеге", PartyScreen.personNote(new RiftUiPayloads.Person("James", (byte) 2)));
		assertTrue(PartyScreen.rowsThatFit(240) >= 2 && PartyScreen.rowsThatFit(1080) == 8 && PartyScreen.rowsThatFit(100) == 1);
	}
}
