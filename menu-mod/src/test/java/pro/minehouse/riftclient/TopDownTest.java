package pro.minehouse.riftclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

class TopDownTest {
	private static final Vec3 FEET = new Vec3(10, 64, 20);
	private static final double PLANE = FEET.y + TopDown.LOOK_AT;

	private static Vec3 aim(float yaw, double cx, double cy) {
		Vec3 camera = TopDown.cameraPosition(FEET, yaw, 56f, 17f);
		return TopDown.aimPoint(camera, yaw, 56f, TopDown.FOV, cx, cy, PLANE);
	}

	@Test
	void cameraIsBehindAndAbove() {
		Vec3 camera = TopDown.cameraPosition(FEET, 0f, 56f, 17f);
		assertEquals(FEET.x, camera.x, 1e-9);
		assertTrue(camera.y > FEET.y + 10, "above");
		assertTrue(camera.z < FEET.z - 5, "looking along +z from behind");
	}

	@Test
	void middleOfTheScreenIsThePlayer() {
		Vec3 aim = aim(0f, 0, 0);
		assertNotNull(aim);
		assertEquals(0, aim.distanceTo(FEET.add(0, TopDown.LOOK_AT, 0)), 1e-9);
	}

	@Test
	void cursorUpIsFurtherAlongTheView() {
		Vec3 up = aim(0f, 0, 0.5);
		assertTrue(up.z > FEET.z + 1, "up the screen is +z at yaw 0");
		assertEquals(FEET.x, up.x, 1e-9);
		assertEquals(0f, TopDown.yawTowards(FEET, up), 1e-4);
	}

	@Test
	void cursorRightIsScreenRight() {
		// Looking along +z, the right of the screen is -x (west): yaw 90.
		Vec3 right = aim(0f, 0.5, 0);
		assertTrue(right.x < FEET.x - 1);
		assertEquals(90f, TopDown.yawTowards(FEET, right), 1.5);
	}

	@Test
	void turnedCameraTurnsTheGround() {
		Vec3 up = aim(90f, 0, 0.5);
		assertTrue(up.x < FEET.x - 1, "yaw 90 looks along -x");
		assertEquals(FEET.z, up.z, 1e-6);
	}

	@Test
	void rayAboveTheHorizonHitsNothing() {
		Vec3 camera = TopDown.cameraPosition(FEET, 0f, 5f, 17f);
		assertNull(TopDown.aimPoint(camera, 0f, 5f, 90, 0, 0.9, PLANE));
	}

	@Test
	void keysWalkOnTheScreen() {
		// Facing the same way as the camera: nothing changes.
		Vec2 same = TopDown.screenToPlayer(new Vec2(0, 1), 0f, 0f);
		assertEquals(0, same.x, 1e-6);
		assertEquals(1, same.y, 1e-6);
		// Every way the player may face, W still moves them along the camera's view (+z at yaw 0).
		for (float playerYaw = -180; playerYaw < 180; playerYaw += 30) {
			Vec2 m = TopDown.screenToPlayer(new Vec2(0, 1), 0f, playerYaw);
			double rad = Math.toRadians(playerYaw);
			double worldX = m.x * Math.cos(rad) - m.y * Math.sin(rad);
			double worldZ = m.y * Math.cos(rad) + m.x * Math.sin(rad);
			assertEquals(0, worldX, 1e-5, "yaw " + playerYaw);
			assertEquals(1, worldZ, 1e-5, "yaw " + playerYaw);
		}
		// A is the left of the screen: +x when the camera looks along +z.
		Vec2 a = TopDown.screenToPlayer(new Vec2(1, 0), 0f, 120f);
		double rad = Math.toRadians(120);
		assertEquals(1, a.x * Math.cos(rad) - a.y * Math.sin(rad), 1e-5);
	}

	@Test
	void strikeArc() {
		Vec3 me = new Vec3(0, 64, 0);
		assertTrue(TopDown.inArc(me, 0f, new Vec3(0, 64, 2), TopDown.STRIKE_ARC), "straight ahead");
		assertTrue(TopDown.inArc(me, 0f, new Vec3(-1.5, 64, 1.5), TopDown.STRIKE_ARC), "45 degrees to the side");
		assertTrue(!TopDown.inArc(me, 0f, new Vec3(0, 64, -2), TopDown.STRIKE_ARC), "behind");
		assertTrue(!TopDown.inArc(me, 0f, new Vec3(-2, 64, 0), TopDown.STRIKE_ARC), "straight to the side");
		assertTrue(TopDown.inArc(me, 170f, new Vec3(0.3, 64, -2), TopDown.STRIKE_ARC), "across the -180/180 seam");
		assertTrue(TopDown.inArc(me, 0f, new Vec3(0.2, 64, -0.2), TopDown.STRIKE_ARC), "on top of the character");
	}
}
