package de.droiddrone.pc.setup;

import de.droiddrone.common.CellVolts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drone setup's battery limits (1.0.71; the pilot, 2026-11-18, flying Li-ion packs: "lower the low battery warning to
 * be empty at 2,8 and low at 3,2"): Betaflight's own warning and minimum cell voltage, written to match the app's
 * ({@link CellVolts}) so its figures - the telemetry's battery state, its own OSD if one is fitted - agree with what
 * the app shows. Nothing in Betaflight stops arming for either: they are warnings.
 * Read and written in the CLI: {@code vbat_warning_cell_voltage} and {@code vbat_min_cell_voltage}, in 0.01 V since
 * Betaflight 4.0 (350 = 3.5 V); a value under 100 is an older one's 0.1 V and is written in that unit.
 */
public final class BatterySetup {
    static final String WARNING = "vbat_warning_cell_voltage", MIN = "vbat_min_cell_voltage";

    private BatterySetup() {
    }

    /** The value from `get name` ("name = 350"), or -1 when the reply has none. */
    static int value(String reply, String name) {
        if (reply == null) return -1;
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*(\\d+)").matcher(reply);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** What to write for these volts in the unit the value read is in: 0.01 V, or 0.1 V for a value under 100. */
    static int inUnitOf(int read, int centivolts) {
        return read < 100 ? Math.round(centivolts / 10f) : centivolts;
    }

    /**
     * The commands that set both limits; null when they are so already or a reply isn't understood (nothing guessed).
     * @param warningReply `get vbat_warning_cell_voltage`
     * @param minReply     `get vbat_min_cell_voltage`
     */
    public static BetaflightPlan.Change plan(String warningReply, String minReply) {
        int warning = value(warningReply, WARNING), min = value(minReply, MIN);
        if (warning < 0 || min < 0) return null;
        int warningWanted = inUnitOf(warning, CellVolts.LOW_CENTIVOLTS), minWanted = inUnitOf(min, CellVolts.EMPTY_CENTIVOLTS);
        List<String> commands = new ArrayList<>();
        // the minimum first: Betaflight wants it under the warning, and both come down
        if (min != minWanted) commands.add("set " + MIN + " = " + minWanted);
        if (warning != warningWanted) commands.add("set " + WARNING + " = " + warningWanted);
        if (commands.isEmpty()) return null;
        return new BetaflightPlan.Change("Battery: low at 3.2 V a cell, empty at 2.8 V (warnings only)", List.copyOf(commands));
    }
}
