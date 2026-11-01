package pro.minehouse.trailer;

/** What the mixins ask each frame; kept free of game classes so the mixins stay small. */
public final class CameraHook {

	private CameraHook() {
	}

	/** {x, y, z, yaw, pitch, fov} for this frame, or null when the trailer isn't playing. */
	public static double[] camera() {
		return Trailer.camera();
	}

	public static long dayTime() {
		return Trailer.dayTime();
	}
}
