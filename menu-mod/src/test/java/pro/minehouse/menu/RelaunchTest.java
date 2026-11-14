package pro.minehouse.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelaunchTest {
	@Test
	void parsesWindowsCommandLines() {
		assertEquals(
			List.of("C:\\Program Files\\Java\\bin\\javaw.exe", "-Xmx4G", "-cp", "C:\\a b\\x.jar;C:\\c.jar",
				"net.fabricmc.loader.impl.launch.knot.KnotClient", "--gameDir", "C:\\Users\\Тимур\\AppData\\Roaming\\minehouse\\game"),
			Relaunch.parseWindows("\"C:\\Program Files\\Java\\bin\\javaw.exe\"  -Xmx4G -cp \"C:\\a b\\x.jar;C:\\c.jar\" "
				+ "net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir \"C:\\Users\\Тимур\\AppData\\Roaming\\minehouse\\game\""));
		assertEquals(List.of("java", "a\\\\b", "say \"hi\"", "c:\\dir\\", "x\"y"),
			Relaunch.parseWindows("java a\\\\b \"say \\\"hi\\\"\" \"c:\\dir\\\\\" x\\\"y"));
		assertEquals(List.of("java", ""), Relaunch.parseWindows("java \"\""));
	}
}
