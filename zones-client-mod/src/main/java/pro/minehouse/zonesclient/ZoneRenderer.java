package pro.minehouse.zonesclient;

import java.util.List;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

/**
 * Draws zones with vanilla gizmos: a translucent wall along every edge from the zone's lowest to its
 * highest block, solid lines on the bottom and top, thin lines every 10 blocks, posts at the corners
 * and the zone's name in the middle. The admin's draft also gets numbered vertices.
 */
final class ZoneRenderer {
	/** Edges farther than this from the player are not drawn. */
	static final double RANGE = 160;
	private static final int WALL_ALPHA = 0x38;
	private static final int GRID_ALPHA = 0x70;

	private ZoneRenderer() {
	}

	static void draw(List<ZoneShapesPayload.Shape> shapes, Vec3 player) {
		for (ZoneShapesPayload.Shape s : shapes) {
			draw(s, player);
		}
	}

	private static void draw(ZoneShapesPayload.Shape s, Vec3 player) {
		int n = s.xs().length;
		if (n == 0) return;
		int line = 0xFF000000 | s.rgb();
		int grid = GRID_ALPHA << 24 | s.rgb();
		int wall = WALL_ALPHA << 24 | s.rgb();
		double lo = s.yMin(), hi = s.yMax() + 1;
		float width = s.draft() ? 4.0F : 3.0F;
		boolean closed = n >= 3;
		int edges = closed ? n : n - 1;

		for (int i = 0; i < edges; i++) {
			double ax = s.xs()[i] + 0.5, az = s.zs()[i] + 0.5;
			double bx = s.xs()[(i + 1) % n] + 0.5, bz = s.zs()[(i + 1) % n] + 0.5;
			if (distanceToSegment(player.x, player.z, ax, az, bx, bz) > RANGE) continue;
			Vec3 a0 = new Vec3(ax, lo, az), b0 = new Vec3(bx, lo, bz);
			Vec3 a1 = new Vec3(ax, hi, az), b1 = new Vec3(bx, hi, bz);
			Gizmos.rect(a0, b0, b1, a1, GizmoStyle.fill(wall));
			Gizmos.line(a0, b0, line, width);
			Gizmos.line(a1, b1, line, width);
			for (int y = ceilToTen(lo + 1); y < hi; y += 10) {
				Gizmos.line(new Vec3(ax, y, az), new Vec3(bx, y, bz), grid, 1.5F);
			}
			// The edge at the player's feet, so it is easy to see where the border runs on the ground.
			double feet = Math.floor(player.y) + 0.05;
			if (feet > lo && feet < hi) Gizmos.line(new Vec3(ax, feet, az), new Vec3(bx, feet, bz), line, width);
		}

		for (int i = 0; i < n; i++) {
			double x = s.xs()[i] + 0.5, z = s.zs()[i] + 0.5;
			double dx = x - player.x, dz = z - player.z;
			if (dx * dx + dz * dz > RANGE * RANGE) continue;
			Gizmos.line(new Vec3(x, lo, z), new Vec3(x, hi, z), line, s.draft() ? 5.0F : width);
			if (s.draft()) {
				double y = Math.max(lo, Math.min(hi, player.y + 1.6));
				Gizmos.point(new Vec3(x, y, z), 0xFFFFFFFF, 12.0F);
				Gizmos.billboardText(String.valueOf(i + 1), new Vec3(x, y + 0.8, z),
						TextGizmo.Style.forColorAndCentered(0xFFFFE066).withScale(1.6F));
			}
		}

		if (!s.label().isEmpty()) {
			double[] c = centre(s.xs(), s.zs());
			double dx = c[0] - player.x, dz = c[1] - player.z;
			if (dx * dx + dz * dz <= RANGE * RANGE) {
				double y = Math.max(lo, Math.min(hi, player.y + 4));
				Gizmos.billboardText(s.label(), new Vec3(c[0], y, c[1]), TextGizmo.Style.forColorAndCentered(line).withScale(3.0F));
			}
		}
	}

	static int ceilToTen(double y) {
		return (int) Math.ceil(y / 10.0) * 10;
	}

	/** Middle of the vertices (good enough for a label inside a convex-ish zone). */
	static double[] centre(int[] xs, int[] zs) {
		double x = 0, z = 0;
		for (int i = 0; i < xs.length; i++) {
			x += xs[i] + 0.5;
			z += zs[i] + 0.5;
		}
		return new double[] {x / xs.length, z / zs.length};
	}

	static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
		double vx = bx - ax, vz = bz - az;
		double len2 = vx * vx + vz * vz;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
		double dx = px - (ax + t * vx), dz = pz - (az + t * vz);
		return Math.sqrt(dx * dx + dz * dz);
	}
}
