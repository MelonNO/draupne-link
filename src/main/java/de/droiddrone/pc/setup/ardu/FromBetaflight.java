package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.ModeSwitch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fleet PRD AS-11: what carries over from a Betaflight drone to its ArduPilot setup - read from the board on USB
 * ("diff all" in the CLI, left without saving) before it is flashed:
 * <ul>
 *   <li>battery: the cells (force_battery_cell_count, else the pack on now) and the pack's mAh (bat_capacity);</li>
 *   <li>the ARM switch's AUX and the flight-mode switch's AUX (ANGLE / HORIZON);</li>
 *   <li>board turn (align_board_roll/pitch/yaw) as AHRS_ORIENTATION, when ArduPilot has the same turn;</li>
 *   <li>motor order and direction: Betaflight's quad X is ArduPilot's "BetaFlightX" frame (FRAME_TYPE 12), props out
 *       (yaw_motors_reversed) "BetaFlightXReversed" (18). Moved motor pins (resource MOTOR) are not carried: noted.</li>
 * </ul>
 * Anything not set or not understood stays at the setup's defaults.
 */
public final class FromBetaflight {
    public static final int FRAME_CLASS_QUAD = 1, FRAME_TYPE_BF_X = 12, FRAME_TYPE_BF_X_REVERSED = 18;
    static final int MODE_ANGLE = 1, MODE_HORIZON = 2;

    /** What was read; null = not known (the default stays). notes: what wasn't carried, short. */
    public record Carried(Integer cells, Integer capacityMah, Integer armAux, Integer modeAux, Integer ahrsOrientation,
                          Integer frameType, List<String> notes) {

        /** The setup's choices with what was read in place of the defaults. */
        public ArduPlan.Choices apply(ArduPlan.Choices c) {
            return new ArduPlan.Choices(c.sysId(), cells != null ? cells : c.cells(), capacityMah != null ? capacityMah : c.capacityMah(),
                    armAux != null ? armAux : c.armAux(), modeAux != null ? modeAux : c.modeAux(), c.flightModes(), c.rtlAltM(),
                    c.cruiseMs(), c.climbMs(), c.descentMs(), c.avoidance());
        }

        /** The parameters beyond the choices: frame and board turn. */
        public Map<String, ArduPlan.Wanted> extra() {
            Map<String, ArduPlan.Wanted> w = new LinkedHashMap<>();
            if (frameType != null) {
                w.put("FRAME_CLASS", new ArduPlan.Wanted(FRAME_CLASS_QUAD, "quad, as on Betaflight"));
                w.put("FRAME_TYPE", new ArduPlan.Wanted(frameType, frameType == FRAME_TYPE_BF_X_REVERSED
                        ? "Betaflight's motor order, props out" : "Betaflight's motor order"));
            }
            if (ahrsOrientation != null) w.put("AHRS_ORIENTATION", new ArduPlan.Wanted(ahrsOrientation, "board turn from Betaflight"));
            return w;
        }

        /** "4 cells, 1500 mAh, ARM AUX1, modes AUX2, props out" */
        public String summary() {
            List<String> s = new ArrayList<>();
            if (cells != null) s.add(cells + " cells");
            if (capacityMah != null) s.add(capacityMah + " mAh");
            if (armAux != null) s.add("ARM AUX" + armAux);
            if (modeAux != null) s.add("modes AUX" + modeAux);
            if (frameType != null) s.add(frameType == FRAME_TYPE_BF_X_REVERSED ? "props out" : "props in");
            if (ahrsOrientation != null && ahrsOrientation != 0) s.add("board turned");
            return s.isEmpty() ? "nothing" : String.join(", ", s);
        }
    }

    private FromBetaflight() {
    }

    /** From the CLI's "diff all" (defaults left out), and the pack's cells if one is on (0: none). */
    public static Carried parse(String diffAll, int liveCells) {
        List<String> notes = new ArrayList<>();
        Integer cells = intSetting(diffAll, "force_battery_cell_count");
        if (cells != null && cells <= 0) cells = null;
        if (cells == null && liveCells > 0) cells = liveCells;
        Integer capacity = intSetting(diffAll, "bat_capacity");
        if (capacity != null && capacity < 100) capacity = null;

        Integer arm = null, modes = null;
        java.util.Set<Integer> arms = ModeSwitch.armAuxes(diffAll);
        if (!arms.isEmpty()) arm = arms.iterator().next();
        for (String line : diffAll.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 6 || !p[0].equals("aux")) continue;
            try {
                int mode = Integer.parseInt(p[2]), channel = Integer.parseInt(p[3]);
                int start = Integer.parseInt(p[4]), end = Integer.parseInt(p[5]);
                if ((mode == MODE_ANGLE || mode == MODE_HORIZON) && end > start && modes == null) modes = channel + 1;
            } catch (NumberFormatException ignored) {
                // not an aux line
            }
        }
        if (arm != null && arm > 8) {
            notes.add("ARM on AUX" + arm + ": not carried (AUX1-8)");
            arm = null;
        }
        if (modes != null && (modes > 8 || modes.equals(arm))) modes = null;

        int roll = orZero(intSetting(diffAll, "align_board_roll"));
        int pitch = orZero(intSetting(diffAll, "align_board_pitch"));
        int yaw = orZero(intSetting(diffAll, "align_board_yaw"));
        Integer orientation = orientation(roll, pitch, yaw);
        if (orientation == null) notes.add("board turn " + roll + "/" + pitch + "/" + yaw + ": set it on ArduPilot");

        Integer frame = null;
        Integer mixer = null;
        Matcher mm = Pattern.compile("(?m)^\\s*mixer\\s+(\\S+)").matcher(diffAll);
        if (mm.find() && !mm.group(1).equalsIgnoreCase("QUADX")) notes.add("mixer " + mm.group(1) + ": not a quad X, frame not carried");
        else frame = "ON".equalsIgnoreCase(setting(diffAll, "yaw_motors_reversed")) ? FRAME_TYPE_BF_X_REVERSED : FRAME_TYPE_BF_X;
        if (Pattern.compile("(?mi)^\\s*resource\\s+MOTOR\\s+\\d").matcher(diffAll).find()) notes.add("motor pins moved on Betaflight: check the motor order");
        return new Carried(cells, capacity, arm, modes, orientation, frame, notes);
    }

    /** ArduPilot's AHRS_ORIENTATION for Betaflight's board turn: yaw in 45s, flat or upside down; null otherwise. */
    static Integer orientation(int roll, int pitch, int yaw) {
        int y = Math.floorMod(yaw, 360);
        if (y % 45 != 0 || pitch % 360 != 0) return null;
        int r = Math.floorMod(roll, 360);
        if (r == 0) return y / 45; // ROTATION_NONE, YAW_45 .. YAW_315
        if (r == 180) return switch (y) { // ROTATION_ROLL_180, _YAW_45, _YAW_90, _YAW_135, (12 = PITCH_180), _YAW_225, _YAW_270, _YAW_315
            case 0 -> 8;
            case 45 -> 9;
            case 90 -> 10;
            case 135 -> 11;
            case 180 -> 12;
            case 225 -> 13;
            case 270 -> 14;
            default -> 15;
        };
        return null;
    }

    /** Read from a Betaflight board on USB: the CLI's "diff all" and the pack on now; left without saving. */
    public static Carried read(BetaflightUsb fc) throws IOException {
        int cells = 0;
        try {
            cells = fc.batteryState().cells();
        } catch (IOException ignored) {
            // no pack on: the cells stay as chosen
        }
        fc.enterCli();
        try {
            return parse(fc.command("diff all", 20_000), cells);
        } finally {
            fc.exitCli();
        }
    }

    static String setting(String text, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*set\\s+" + Pattern.quote(name) + "\\s*=\\s*(\\S+)").matcher(text);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    static Integer intSetting(String text, String name) {
        String v = setting(text, name);
        if (v == null) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
