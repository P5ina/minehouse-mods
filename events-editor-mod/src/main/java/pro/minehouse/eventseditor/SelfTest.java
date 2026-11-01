package pro.minehouse.eventseditor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Screenshot;

/**
 * CI check (xvfb-run ./gradlew runSelftestClient): feeds the client a made-up editing session (the
 * bundled meteor show), opens the panel, selects a cue, takes a screenshot of the editor and prints
 * MINEHOUSE_EVENTS_EDITOR_OK if everything held together.
 */
final class SelfTest {
	private static int ticks;
	private static String problem;

	private SelfTest() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ticks++;
			try {
				if (ticks == 60) {
					EditorClient.receive("{\"type\":\"hello\",\"shows\":[\"meteor\",\"meteor_shower\"]}");
					check(EditorClient.admin && EditorClient.shows.size() == 2, "hello");
					EditorClient.receive(state());
					check(client.gui.screen() instanceof EditorScreen, "state opens the editor");
				} else if (ticks == 70) {
					EditorClient.selTrack = "fx";
					EditorClient.selIndex = 0;
					EditorClient.version++;
					EditorClient.receive("{\"type\":\"tick\",\"tick\":250,\"playing\":false}");
					check(EditorClient.tick == 250, "tick");
				} else if (ticks == 90) {
					Screenshot.grab(client.gameDirectory, "editor.png", client.gameRenderer.mainRenderTarget(), 1,
							msg -> MinehouseEventsEditor.LOG.info("Screenshot: {}", msg.getString()));
				} else if (ticks == 110) {
					EditorClient.receive("{\"type\":\"closed\"}");
					check(!(client.gui.screen() instanceof EditorScreen) && !EditorClient.editing(), "closed");
					boolean receiver = ClientPlayNetworking.getGlobalReceivers().contains(EditorPayload.TYPE.id());
					check(receiver, "receiver");
					check(CueSchema.duration(com.google.gson.JsonParser.parseString(
							"{\"do\":\"meteors\",\"over\":\"9s\",\"fall\":\"2s\"}").getAsJsonObject()) == 220, "meteors duration");
					MinehouseEventsEditor.LOG.info(problem == null ? "MINEHOUSE_EVENTS_EDITOR_OK" : "MINEHOUSE_EVENTS_EDITOR_FAIL: " + problem);
					client.stop();
				}
			} catch (RuntimeException e) {
				MinehouseEventsEditor.LOG.error("MINEHOUSE_EVENTS_EDITOR_FAIL", e);
				client.stop();
			}
		});
	}

	private static void check(boolean ok, String what) {
		if (!ok && problem == null) {
			problem = what;
		}
	}

	private static String state() {
		String doc;
		try (InputStream in = SelfTest.class.getResourceAsStream("/selftest/meteor.json")) {
			doc = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException | NullPointerException e) {
			throw new IllegalStateException("no selftest/meteor.json", e);
		}
		return "{\"type\":\"state\",\"show\":\"meteor\",\"doc\":" + doc + ",\"length\":900,\"tick\":0,\"playing\":false,"
				+ "\"follow\":true,\"dirty\":true,\"undo\":2,\"redo\":0,\"spots\":{},\"geo\":{},"
				+ "\"stage\":{\"world\":\"minecraft:overworld\",\"pos\":[0,64,0]}}";
	}
}
