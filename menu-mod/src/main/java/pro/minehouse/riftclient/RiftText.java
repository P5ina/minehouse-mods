package pro.minehouse.riftclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Texts of the mode come with {@code &} codes (colours, {@code &l} bold, {@code &r} reset). */
public final class RiftText {
	private static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55,
			0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	public static final int WHITE = 0xFFFFFF;

	private RiftText() {
	}

	/** A run of letters in one colour. */
	public record Span(String text, int color, boolean bold) {
	}

	public static List<Span> spans(String s) {
		List<Span> out = new ArrayList<>();
		StringBuilder run = new StringBuilder();
		int color = WHITE;
		boolean bold = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int code = c == '&' && i + 1 < s.length() ? "0123456789abcdeflr".indexOf(Character.toLowerCase(s.charAt(i + 1))) : -1;
			if (code < 0) {
				run.append(c);
				continue;
			}
			if (run.length() > 0) {
				out.add(new Span(run.toString(), color, bold));
				run.setLength(0);
			}
			if (code < 16) {
				color = COLORS[code];
				bold = false; // a colour resets the style, as in the game
			} else if (code == 16) {
				bold = true;
			} else {
				color = WHITE;
				bold = false;
			}
			i++;
		}
		if (run.length() > 0) {
			out.add(new Span(run.toString(), color, bold));
		}
		return out;
	}

	public static String plain(String s) {
		StringBuilder b = new StringBuilder();
		for (Span span : spans(s)) {
			b.append(span.text());
		}
		return b.toString();
	}

	public static Component component(String s) {
		MutableComponent out = Component.empty();
		for (Span span : spans(s)) {
			out.append(Component.literal(span.text()).withStyle(Style.EMPTY.withColor(span.color()).withBold(span.bold())));
		}
		return out;
	}
}
