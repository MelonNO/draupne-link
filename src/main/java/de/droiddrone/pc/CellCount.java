package de.droiddrone.pc;

/**
 * The battery's cell count, checked. Betaflight counts the cells once, when it sees the battery arrive. With the flight
 * controller already running on the phone's USB power, it can count while its filtered voltage is still climbing
 * through 3-4.3 V, settle on 1S (or 2S) and keep that until the battery is unplugged - a 6S at 25 V then shows as
 * 25 V per cell. Such an impossible count is replaced by one made the way Betaflight makes it (pack voltage / max cell
 * voltage, plus one), but only once the voltage has held steady, and kept like Betaflight keeps its own.
 */
final class CellCount {
    /** A cell above max cell voltage + this cannot be: the count is too low. */
    static final double IMPOSSIBLE_MARGIN = 0.2;
    static final long STEADY_MS = 1500;
    private static final double STEADY_VOLTS = 0.15;
    /** Below this the battery is unplugged (USB power only): forget the count. */
    private static final double UNPLUGGED_VOLTS = 2.0;

    private int counted;
    private double steadyVolts = -1;
    private long steadySince;

    /**
     * The count to show for this pack voltage; Betaflight's own (fcCells, 0 = none yet) when it is possible, else this
     * class's count, or 0 while the voltage still has to settle before counting.
     */
    synchronized int resolve(double volts, int fcCells, double maxCell, long nowMs) {
        if (Math.abs(volts - steadyVolts) > STEADY_VOLTS) {
            steadyVolts = volts;
            steadySince = nowMs;
        }
        if (volts < UNPLUGGED_VOLTS) counted = 0;
        if (fcCells <= 0 || possible(volts, fcCells, maxCell)) return fcCells;
        if (counted > 0 && !possible(volts, counted, maxCell)) counted = 0; // a fuller pack plugged in: count again
        if (counted == 0 && nowMs - steadySince >= STEADY_MS) counted = (int) (volts / maxCell) + 1;
        return counted;
    }

    static boolean possible(double volts, int cells, double maxCell) {
        return volts / cells <= maxCell + IMPOSSIBLE_MARGIN;
    }
}
