package pro.minehouse.menu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * The small MineHouse window that downloads a pack update before the game starts.
 *
 * Runs as its own Java process (see {@link #runFor}) so the game's JVM never
 * starts AWT, which would fight GLFW on macOS. Uses only the JDK: the process
 * has nothing but our jar on its classpath. The job file is written by
 * {@link PackUpdater#preLaunch}: a header line `from \t to \t mode` (mode:
 * none, relaunch or restart) and one line per file `url \t sha512 \t size \t
 * tmp path \t name`. The exit code tells the game what the player chose.
 */
public final class UpdateWindow extends JPanel {
	/** Downloaded; close the game now (mode relaunch/restart) or just go on (mode none). */
	static final int DONE = 0;
	static final int FAILED = 1;
	/** The player skipped the update. */
	static final int SKIPPED = 2;
	/** Downloaded, but the player keeps playing: the update lands when the game exits. */
	static final int LATER = 4;

	private static final Color BG = new Color(0x0E0D12);
	private static final Color BORDER = new Color(0x27242F);
	private static final Color TEXT = new Color(0xF4F4F5);
	private static final Color MUTED = new Color(0xA1A1AA);
	private static final Color TRACK = new Color(0x221F29);
	private static final Color ACCENT = new Color(0xD8B4FE);
	private static final Color ACCENT_DEEP = new Color(0x9F67E8);
	private static final Color ERROR = new Color(0xFCA5A5);
	private static final int W = 480;
	private static final int H = 230;

	private record Job(String url, String sha512, long size, Path tmp, String name) {
	}

	private record Btn(String label, boolean primary, int code) {
	}

	private final String from;
	private final String to;
	private final String mode;
	private final List<Job> jobs;
	private final long totalBytes;
	private final Font titleFont;
	private final Font bodyFont;
	private final Font smallFont;
	private Image logo;
	private JFrame frame;

	private volatile long doneBytes;
	private volatile String status = "Подключаюсь к сайту…";
	private volatile String title = "Обновляем MineHouse";
	private volatile boolean error;
	private volatile boolean finished;
	private volatile List<Btn> buttons = List.of(new Btn("Пропустить", false, SKIPPED));
	private final List<java.awt.Rectangle> buttonRects = new ArrayList<>();
	private int hover = -1;
	private volatile int exitCode = SKIPPED;
	private final Object done = new Object();

	private UpdateWindow(String from, String to, String mode, List<Job> jobs) {
		this.from = from;
		this.to = to;
		this.mode = mode;
		this.jobs = jobs;
		this.totalBytes = Math.max(1, jobs.stream().mapToLong(Job::size).sum());
		String family = family();
		this.titleFont = new Font(family, Font.BOLD, 19);
		this.bodyFont = new Font(family, Font.PLAIN, 13);
		this.smallFont = new Font(family, Font.PLAIN, 12);
		this.setPreferredSize(new Dimension(W, H));
		this.setOpaque(false);
		try (InputStream in = UpdateWindow.class.getResourceAsStream("/assets/minehouse-menu/icon.png")) {
			if (in != null) {
				this.logo = ImageIO.read(in);
			}
		} catch (IOException ignored) {
			// no logo, just text
		}
		MouseAdapter mouse = new MouseAdapter() {
			private Point drag;

			@Override
			public void mousePressed(MouseEvent e) {
				int b = UpdateWindow.this.buttonAt(e.getPoint());
				if (b >= 0) {
					UpdateWindow.this.finish(UpdateWindow.this.buttons.get(b).code());
				} else {
					this.drag = e.getPoint();
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (this.drag != null && UpdateWindow.this.frame != null) {
					Point p = UpdateWindow.this.frame.getLocation();
					UpdateWindow.this.frame.setLocation(p.x + e.getX() - this.drag.x, p.y + e.getY() - this.drag.y);
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				int b = UpdateWindow.this.buttonAt(e.getPoint());
				if (b != UpdateWindow.this.hover) {
					UpdateWindow.this.hover = b;
					UpdateWindow.this.setCursor(Cursor.getPredefinedCursor(b >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
					UpdateWindow.this.repaint();
				}
			}
		};
		this.addMouseListener(mouse);
		this.addMouseMotionListener(mouse);
	}

	private static String family() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.startsWith("windows")) {
			return "Segoe UI";
		}
		if (os.contains("mac")) {
			return "Helvetica Neue";
		}
		return Font.SANS_SERIF;
	}

	// ---- game side -------------------------------------------------------

	/** Starts the window process for a job file and waits for the player; FAILED if it can't run. */
	static int runFor(Path job) {
		try {
			Path self = Path.of(UpdateWindow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			String java = ProcessHandle.current().info().command().orElse("java");
			List<String> cmd = new ArrayList<>(List.of(java, "-Djava.awt.headless=false", "-Dapple.awt.application.name=MineHouse",
				"-Xmx128m", "-cp", self.toString(), UpdateWindow.class.getName(), job.toString()));
			Process p = new ProcessBuilder(cmd)
				.redirectErrorStream(true)
				.redirectOutput(job.resolveSibling("window.log").toFile())
				.start();
			if (!p.waitFor(30, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				return FAILED;
			}
			return p.exitValue();
		} catch (Exception e) {
			MinehouseMenu.LOG.warn("Can't open the update window", e);
			return FAILED;
		}
	}

	// ---- window process ----------------------------------------------------

	public static void main(String[] args) throws Exception {
		List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
		String[] head = lines.get(0).split("\t", -1);
		List<Job> jobs = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			String[] f = line.split("\t", -1);
			jobs.add(new Job(f[0], f[1], Long.parseLong(f[2]), Path.of(f[3]), f[4]));
		}
		UpdateWindow w = new UpdateWindow(head[0], head[1], head[2], jobs);
		SwingUtilities.invokeAndWait(w::open);
		Thread worker = new Thread(w::download, "MineHouse update download");
		worker.setDaemon(true);
		worker.start();
		synchronized (w.done) {
			while (!w.finished) {
				w.done.wait();
			}
		}
		System.exit(w.exitCode);
	}

	/** Shown by {@link UpdateApplier} when the game can't be started again by itself. */
	static void tellRestart() {
		try {
			UpdateWindow w = new UpdateWindow("", "", "restart", List.of());
			w.title = "Обновление установлено";
			w.status = "Запусти игру снова, чтобы играть на новой версии.";
			w.doneBytes = w.totalBytes;
			w.buttons = List.of(new Btn("Хорошо", true, DONE));
			SwingUtilities.invokeAndWait(w::open);
			synchronized (w.done) {
				if (!w.finished) {
					w.done.wait(120_000);
				}
			}
		} catch (Throwable t) {
			System.out.println("Can't show the restart note: " + t);
		}
	}

	private void open() {
		this.frame = new JFrame("Обновление MineHouse");
		this.frame.setUndecorated(true);
		this.frame.setBackground(new Color(0, 0, 0, 0));
		this.frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		this.frame.setContentPane(this);
		if (this.logo != null) {
			this.frame.setIconImage(this.logo);
		}
		this.frame.pack();
		this.frame.setLocationRelativeTo(null);
		this.frame.setAlwaysOnTop(true);
		this.frame.setVisible(true);
		this.frame.setAlwaysOnTop(false);
		this.frame.toFront();
		new Timer(50, e -> this.repaint()).start();
	}

	private void finish(int code) {
		synchronized (this.done) {
			if (this.finished) {
				return;
			}
			this.exitCode = code;
			this.finished = true;
			this.done.notifyAll();
		}
	}

	private void download() {
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		long before = 0;
		try {
			for (Job j : this.jobs) {
				if (this.finished) {
					return;
				}
				this.status = j.name();
				Files.createDirectories(j.tmp().getParent());
				HttpResponse<InputStream> res = http.send(
					HttpRequest.newBuilder(URI.create(j.url())).timeout(Duration.ofMinutes(10)).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
				if (res.statusCode() != 200) {
					throw new IOException(j.name() + ": HTTP " + res.statusCode());
				}
				MessageDigest md = MessageDigest.getInstance("SHA-512");
				long got = 0;
				try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(j.tmp())) {
					byte[] buf = new byte[65536];
					int n;
					while ((n = in.read(buf)) > 0) {
						if (this.finished) {
							return;
						}
						out.write(buf, 0, n);
						md.update(buf, 0, n);
						got += n;
						this.doneBytes = before + got;
					}
				}
				if (got != j.size() || !HexFormat.of().formatHex(md.digest()).equals(j.sha512())) {
					Files.deleteIfExists(j.tmp());
					throw new IOException(j.name() + ": файл повреждён при скачивании");
				}
				before += j.size();
			}
			this.doneBytes = this.totalBytes;
			this.done();
		} catch (Exception e) {
			System.out.println("Download failed: " + e);
			this.error = true;
			this.title = "Не получилось обновить";
			this.status = "Нет связи с сайтом MineHouse. Игра запустится как есть, обновим позже.";
			this.buttons = List.of(new Btn("Продолжить", true, FAILED));
			Timer t = new Timer(8000, ev -> this.finish(FAILED));
			t.setRepeats(false);
			t.start();
		}
	}

	private void done() {
		switch (this.mode) {
			case "relaunch" -> {
				this.title = "Готово, перезапускаем игру";
				this.status = "Сборка " + this.to + " установится за пару секунд.";
				this.buttons = List.of();
				Timer t = new Timer(1200, e -> this.finish(DONE));
				t.setRepeats(false);
				t.start();
			}
			case "restart" -> {
				this.title = "Обновление скачано";
				this.status = "Закрой игру и запусти её снова: сборка " + this.to + " встанет при закрытии.";
				this.buttons = List.of(new Btn("Играть без него", false, LATER), new Btn("Закрыть игру", true, DONE));
			}
			default -> {
				this.title = "Готово";
				this.status = "Сборка " + this.to + " установлена, запускаем игру.";
				this.buttons = List.of();
				Timer t = new Timer(700, e -> this.finish(DONE));
				t.setRepeats(false);
				t.start();
			}
		}
	}

	// ---- drawing -------------------------------------------------------------

	private int buttonAt(Point p) {
		for (int i = 0; i < this.buttonRects.size(); i++) {
			if (this.buttonRects.get(i).contains(p)) {
				return i;
			}
		}
		return -1;
	}

	private static String mb(long bytes) {
		return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0).replace('.', ',');
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		g.setColor(BG);
		g.fill(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 18, 18));
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, W - 2, H - 2, 18, 18));

		int pad = 26;
		int x = pad;
		if (this.logo != null) {
			g.drawImage(this.logo, pad, 24, 48, 48, null);
			x = pad + 48 + 16;
		}
		g.setFont(this.smallFont);
		g.setColor(ACCENT);
		g.drawString("MINEHOUSE", x, 38);
		g.setFont(this.titleFont);
		g.setColor(this.error ? ERROR : TEXT);
		g.drawString(this.title, x, 64);

		g.setFont(this.bodyFont);
		g.setColor(MUTED);
		String sub = this.from.isEmpty() || this.to.isEmpty() ? "" : "Сборка " + this.from + "  →  " + this.to;
		if (!sub.isEmpty()) {
			g.drawString(sub, pad, 106);
		}

		// Progress bar.
		int barY = 120;
		int barW = W - pad * 2;
		g.setColor(TRACK);
		g.fill(new RoundRectangle2D.Float(pad, barY, barW, 8, 8, 8));
		float frac = Math.min(1f, this.doneBytes / (float) this.totalBytes);
		if (frac > 0) {
			g.setPaint(new java.awt.GradientPaint(pad, 0, ACCENT_DEEP, pad + barW, 0, ACCENT));
			g.fill(new RoundRectangle2D.Float(pad, barY, Math.max(8, barW * frac), 8, 8, 8));
		}

		g.setFont(this.smallFont);
		g.setColor(this.error ? ERROR : MUTED);
		String line = this.status;
		boolean downloading = !this.error && this.doneBytes < this.totalBytes && !this.jobs.isEmpty();
		if (downloading) {
			line = this.status + "   " + mb(this.doneBytes) + " из " + mb(this.totalBytes) + " МБ";
		}
		this.drawWrapped(g, line, pad, 148, barW, 2);

		// Buttons, right-aligned.
		this.buttonRects.clear();
		List<Btn> bs = this.buttons;
		g.setFont(this.bodyFont);
		FontMetrics fm = g.getFontMetrics();
		int bx = W - pad;
		int by = H - pad - 30;
		List<java.awt.Rectangle> rects = new ArrayList<>();
		for (int i = bs.size() - 1; i >= 0; i--) {
			int bw = fm.stringWidth(bs.get(i).label()) + 28;
			bx -= bw;
			rects.addFirst(new java.awt.Rectangle(bx, by, bw, 30));
			bx -= 8;
		}
		for (int i = 0; i < bs.size(); i++) {
			Btn b = bs.get(i);
			java.awt.Rectangle r = rects.get(i);
			boolean hot = i == this.hover;
			if (b.primary()) {
				g.setColor(hot ? TEXT : ACCENT);
				g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 10, 10));
				g.setColor(BG);
			} else {
				g.setColor(hot ? TRACK.brighter() : TRACK);
				g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 10, 10));
				g.setColor(TEXT);
			}
			g.drawString(b.label(), r.x + 14, r.y + 20);
		}
		this.buttonRects.addAll(rects);
		g.dispose();
	}

	private void drawWrapped(Graphics2D g, String text, int x, int y, int width, int maxLines) {
		FontMetrics fm = g.getFontMetrics();
		String[] words = text.split(" ");
		StringBuilder cur = new StringBuilder();
		int lines = 0;
		for (String w : words) {
			String next = cur.isEmpty() ? w : cur + " " + w;
			if (fm.stringWidth(next) > width && !cur.isEmpty()) {
				g.drawString(cur.toString(), x, y + lines * (fm.getHeight()));
				lines++;
				if (lines >= maxLines) {
					return;
				}
				cur = new StringBuilder(w);
			} else {
				cur = new StringBuilder(next);
			}
		}
		if (!cur.isEmpty()) {
			g.drawString(cur.toString(), x, y + lines * fm.getHeight());
		}
	}
}
