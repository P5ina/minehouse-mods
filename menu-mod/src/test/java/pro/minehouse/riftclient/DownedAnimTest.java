package pro.minehouse.riftclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DownedAnimTest {
	@Test
	void armsAreFlungOutAtTheFallAndSettle() {
		DownedAnim.Limbs start = DownedAnim.limbs(0, 0, 0, 0);
		DownedAnim.Limbs lying = DownedAnim.limbs(DownedAnim.FALL_TICKS, 0, 0, 0);
		assertTrue(start.dragArmZ() < -1.2f && start.holdArmZ() > 1.2f);
		assertEquals(-0.18f, lying.dragArmZ(), 1e-5);
		assertEquals(0.45f, lying.holdArmZ(), 1e-5);
		assertEquals(lying, DownedAnim.limbs(DownedAnim.FALL_TICKS * 5, 0, 0, 0));
	}

	@Test
	void oneArmDragsOnlyWhileMoving() {
		float still = DownedAnim.limbs(40, 0, 0, 0).dragArmX();
		float alsoStill = DownedAnim.limbs(40, 3, 0, 0).dragArmX();
		assertEquals(still, alsoStill, 1e-5);
		float reach = DownedAnim.limbs(40, 0, 1, 0).dragArmX();
		float pulled = DownedAnim.limbs(40, (float) (Math.PI / 0.55), 1, 0).dragArmX();
		assertEquals(-2.95f, reach, 1e-4);
		assertEquals(-1.7f, pulled, 1e-4);
		// The hand on the wound does not take part.
		assertEquals(DownedAnim.limbs(40, 0, 1, 0).holdArmX(), DownedAnim.limbs(40, 2, 1, 0).holdArmX(), 1e-5);
	}

	@Test
	void headLiftsToCrawl() {
		assertTrue(DownedAnim.limbs(40, 0, 1, 0).headX() < DownedAnim.limbs(40, 0, 0, 0).headX() - 0.4f);
	}
}
