package pro.minehouse.menu.intro;

import java.util.stream.IntStream;

/**
 * YUV 4:2:0 (BT.709, limited range, as encoded by intro/encode.sh) to RGBA.
 * jcodec stores samples as signed bytes shifted by -128.
 */
public final class Yuv {
	// 16.16 fixed point, limited range: Y' = 1.164 (Y - 16), BT.709 coefficients.
	private static final int CY = 76309;   // 1.164383 * 65536
	private static final int CRV = 117489; // 1.792741 * 65536
	private static final int CGU = 13975;  // 0.213249 * 65536
	private static final int CGV = 34925;  // 0.532909 * 65536
	private static final int CBU = 138438; // 2.112402 * 65536
	private static final int BAND = 36;    // rows per parallel task

	private Yuv() {
	}

	/**
	 * Writes the cropped frame as packed pixels with R in the lowest byte (NativeImage RGBA,
	 * little endian: R, G, B, A in memory). {@code out} has width * height ints. Rows are
	 * converted in parallel bands, so this costs the render thread about a millisecond.
	 */
	public static void toAbgr(IntroDecoder.Frame f, int[] out) {
		int bands = (f.height + BAND - 1) / BAND;
		IntStream.range(0, bands).parallel().forEach(b -> rows(f, out, b * BAND, Math.min(f.height, (b + 1) * BAND)));
	}

	private static void rows(IntroDecoder.Frame f, int[] out, int y0, int y1) {
		int w = f.width;
		int sx = f.startX;
		int sy = f.startY;
		byte[] yp = f.planes[0];
		byte[] up = f.planes[1];
		byte[] vp = f.planes[2];
		int ys = f.strideY;
		int cs = f.strideC;
		for (int y = y0; y < y1; y++) {
			int yRow = (y + sy) * ys + sx;
			int cRow = ((y + sy) >> 1) * cs;
			int o = y * w;
			for (int x = 0; x < w; x++) {
				int ci = cRow + ((x + sx) >> 1);
				int u = up[ci];          // already U - 128
				int v = vp[ci];          // already V - 128
				int yy = (yp[yRow + x] + 128 - 16) * CY;
				int r = (yy + CRV * v + 32768) >> 16;
				int g = (yy - CGU * u - CGV * v + 32768) >> 16;
				int b = (yy + CBU * u + 32768) >> 16;
				r = r < 0 ? 0 : (r > 255 ? 255 : r);
				g = g < 0 ? 0 : (g > 255 ? 255 : g);
				b = b < 0 ? 0 : (b > 255 ? 255 : b);
				out[o + x] = 0xFF000000 | (b << 16) | (g << 8) | r;
			}
		}
	}
}
