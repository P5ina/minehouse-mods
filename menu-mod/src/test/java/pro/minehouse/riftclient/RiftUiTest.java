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
}
