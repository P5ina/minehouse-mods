package pro.minehouse.anvilclient;

/**
 * The last real cost the server sent for an anvil window. It is trusted only while the window's
 * data slot still shows what the server clamped it to: right after an input changes, the client
 * recomputes the cost by vanilla rules, and until the server's answer arrives the vanilla number
 * is what the screen shows.
 */
public final class AnvilCost {
	/** Vanilla says "Too Expensive!" from 40, the server mod never sends the client more than 39. */
	public static final int CLIENT_CAP = 40;

	private static int containerId = -1;
	private static int realCost;

	private AnvilCost() {
	}

	public static synchronized void set(int container, int cost) {
		containerId = container;
		realCost = cost;
	}

	public static synchronized void clear() {
		containerId = -1;
		realCost = 0;
	}

	/** What the server mod puts in the data slot for a real cost. */
	public static int clamp(int cost) {
		return Math.min(cost, CLIENT_CAP - 1);
	}

	/** The real cost for this window, or -1 when the server hasn't sent one for what it shows now. */
	public static synchronized int real(int container, int slotCost) {
		if (container != containerId || slotCost <= 0 || clamp(realCost) != slotCost) {
			return -1;
		}

		return realCost;
	}
}
