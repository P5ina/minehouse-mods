package pro.minehouse.anvilclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnvilCostTest {
	@AfterEach
	void reset() {
		AnvilCost.clear();
	}

	@Test
	void realCostWhileTheSlotShowsItsClampedValue() {
		AnvilCost.set(3, 57);
		assertEquals(57, AnvilCost.real(3, 39));
	}

	@Test
	void cheapCostIsPassedAsIs() {
		AnvilCost.set(3, 12);
		assertEquals(12, AnvilCost.real(3, 12));
	}

	@Test
	void ignoredForAnotherWindow() {
		AnvilCost.set(3, 57);
		assertEquals(-1, AnvilCost.real(4, 39));
	}

	@Test
	void ignoredWhileTheClientShowsItsOwnRecomputedCost() {
		AnvilCost.set(3, 57);
		assertEquals(-1, AnvilCost.real(3, 21));
	}

	@Test
	void ignoredWhenThereIsNoCost() {
		AnvilCost.set(3, 0);
		assertEquals(-1, AnvilCost.real(3, 0));
	}

	@Test
	void nothingBeforeTheFirstPacket() {
		assertEquals(-1, AnvilCost.real(0, 39));
	}
}
