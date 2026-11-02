package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which Betaflight changes DRAUPNE Link needs, and nothing else: the receiver must be "MSP RX input"
 * (the phone feeds the sticks over MSP), the serial task rate is raised so stick updates don't queue up, and the
 * battery's cell count is fixed to the pilot's choice (Betaflight's own count goes wrong when the flight controller
 * already runs on the phone's USB power as the battery is plugged in).
 *
 * <p>The receiver is read and set through Betaflight's feature mask over MSP (MSP_FEATURE_CONFIG /
 * MSP_SET_FEATURE_CONFIG), as Betaflight Configurator does, never with the CLI's {@code feature} command:
 * Betaflight 2025.12's CLI has no name for RX_MSP ("feature RX_MSP" is an invalid name there, and its listing
 * can't show it), while the bit itself is the same in every Betaflight. The rest is CLI commands.
 */
public final class BetaflightPlan {
    public record Change(String description, List<String> commands) {}

    /** Bits of Betaflight's feature mask (features_e), unchanged since Cleanflight. */
    public static final int FEATURE_RX_PPM = 1, FEATURE_RX_SERIAL = 1 << 3, FEATURE_GPS = 1 << 7,
            FEATURE_RX_PARALLEL_PWM = 1 << 13, FEATURE_RX_MSP = 1 << 14, FEATURE_RX_SPI = 1 << 25;
    /** Receivers that compete with RX_MSP (Betaflight takes the first one on, in this order, before MSP). */
    private static final int[] OTHER_RECEIVERS = {FEATURE_RX_PARALLEL_PWM, FEATURE_RX_PPM, FEATURE_RX_SERIAL, FEATURE_RX_SPI};
    private static final String[] OTHER_RECEIVER_NAMES = {"RX_PARALLEL_PWM", "RX_PPM", "RX_SERIAL", "RX_SPI"};
    private static final Pattern SERIAL_RATE = Pattern.compile("serial_update_rate_hz\\s*=\\s*(\\d+)");
    private static final Pattern FORCED_CELLS = Pattern.compile("force_battery_cell_count\\s*=\\s*(\\d+)");
    /** Choices offered for the cell count. */
    public static final int MIN_CELLS = 1, MAX_CELLS = 12, DEFAULT_CELLS = 6;
    private static final Pattern VERSION = Pattern.compile("^(\\S+) / (\\S+) \\(\\S+\\) (\\d+\\.\\d+\\.\\d+)");

    private BetaflightPlan() {
    }

    // ------------------------------------------------------------------ the feature mask (over MSP)

    /** MSP RX input is the receiver: RX_MSP is on and no receiver Betaflight would take before it. */
    public static boolean isMspReceiver(int featureMask) {
        if ((featureMask & FEATURE_RX_MSP) == 0) return false;
        for (int rx : OTHER_RECEIVERS) if (rx != FEATURE_RX_SPI && (featureMask & rx) != 0) return false;
        return true;
    }

    public static boolean hasGps(int featureMask) {
        return (featureMask & FEATURE_GPS) != 0;
    }

    /** The mask with MSP RX input as the only receiver; everything else as it was. */
    public static int mspReceiverMask(int featureMask) {
        int mask = featureMask;
        for (int rx : OTHER_RECEIVERS) mask &= ~rx;
        return mask | FEATURE_RX_MSP;
    }

    /** The receivers on in the mask, for the log: "RX_SERIAL", "RX_SERIAL + RX_MSP", or "no receiver". */
    public static String receiverNames(int featureMask) {
        List<String> on = new ArrayList<>();
        for (int i = 0; i < OTHER_RECEIVERS.length; i++) if ((featureMask & OTHER_RECEIVERS[i]) != 0) on.add(OTHER_RECEIVER_NAMES[i]);
        if ((featureMask & FEATURE_RX_MSP) != 0) on.add("RX_MSP");
        return on.isEmpty() ? "no receiver" : String.join(" + ", on);
    }

    /**
     * The backup is Betaflight's "diff all", taken in the CLI after the receiver was already switched over MSP (the
     * CLI can't be open while MSP is used, and leaving it restarts Betaflight). This puts the receiver lines back
     * to what they were: a receiver that was on and is default-on had no line before (its "feature -X" goes), one
     * that is default-off had "feature X" (added), and "feature RX_MSP" (never a default) goes.
     */
    public static String restoreReceiver(String diffAll, int featureMaskBefore) {
        List<String> lines = new ArrayList<>(List.of(diffAll.split("\\R", -1)));
        if ((featureMaskBefore & FEATURE_RX_MSP) == 0) lines.removeIf(l -> l.trim().equals("feature RX_MSP"));
        List<String> add = new ArrayList<>();
        for (int i = 0; i < OTHER_RECEIVERS.length; i++) {
            if ((featureMaskBefore & OTHER_RECEIVERS[i]) == 0) continue;
            String name = OTHER_RECEIVER_NAMES[i];
            if (!lines.removeIf(l -> l.trim().equals("feature -" + name))) add.add("feature " + name);
        }
        if (!add.isEmpty()) {
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.equals("# feature") || l.startsWith("feature ")) at = i;
            }
            if (at < 0) {
                if (!lines.get(lines.size() - 1).isBlank()) lines.add("");
                lines.add("# feature");
                at = lines.size() - 1;
            }
            lines.addAll(at + 1, add);
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------ the CLI

    /** Betaflight's CLI didn't take the command: "###ERROR IN set: INVALID NAME ..." or a feature the build lacks. */
    public static boolean refused(String cliReply) {
        return cliReply.contains("###ERROR") || cliReply.startsWith("Unavailable");
    }

    /** The value from `get serial_update_rate_hz`, or -1 (not understood, or a Betaflight without the setting). */
    public static int serialRate(String getOutput) {
        Matcher m = SERIAL_RATE.matcher(getOutput);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The value from `get force_battery_cell_count` (0 = Betaflight counts by itself), or -1 if not understood. */
    public static int forcedCells(String getOutput) {
        Matcher m = FORCED_CELLS.matcher(getOutput);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Betaflight reads one MSP message per serial cycle, 100 times a second by default: too slow for 250 stick
     * updates a second plus OSD requests. 2000, or 1000 on the slower F411 processors.
     */
    public static int targetSerialRate(String versionLine) {
        return versionLine.contains("F411") ? 1000 : 2000;
    }

    /** From `get serial_update_rate_hz` and the version line: the CLI commands still needed (none = all set). */
    public static List<Change> plan(String serialRateOutput, String versionLine) {
        List<Change> changes = new ArrayList<>();
        int rate = serialRate(serialRateOutput);
        int target = targetSerialRate(versionLine);
        if (rate >= 0 && rate < target) {
            changes.add(new Change("Stick delay: serial_update_rate_hz " + rate + " -> " + target,
                    List.of("set serial_update_rate_hz = " + target)));
        }
        return changes;
    }

    /** As above, plus the cell count from `get force_battery_cell_count` set to cells. */
    public static List<Change> plan(String serialRateOutput, String versionLine, String cellsOutput, int cells) {
        List<Change> changes = new ArrayList<>(plan(serialRateOutput, versionLine));
        int forced = forcedCells(cellsOutput);
        if (forced >= 0 && forced != cells) {
            changes.add(new Change("Battery: force_battery_cell_count " + forced + " -> " + cells + " (" + cells + "S)",
                    List.of("set force_battery_cell_count = " + cells)));
        }
        return changes;
    }

    /** From `serial`: a port has Betaflight's GPS function (FUNCTION_GPS = 2, "Sensor input: GPS" in the Ports tab). */
    public static boolean hasGpsPort(String serialOutput) {
        for (String line : serialOutput.split("\\R")) {
            String[] p = line.replaceFirst("^#\\s*", "").trim().split("\\s+");
            if (p.length < 3 || !p[0].equals("serial")) continue;
            try {
                if ((Integer.parseInt(p[2]) & 2) != 0) return true;
            } catch (NumberFormatException ignored) {
                // not a port line
            }
        }
        return false;
    }

    /** {major, minor} from the version line ("4.5.1" -> 4,5; "2025.12.0" -> 2025,12), or null. */
    public static int[] versionNumber(String versionLine) {
        Matcher m = VERSION.matcher(versionLine);
        if (!m.find()) return null;
        String[] p = m.group(3).split("\\.");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    /** Betaflight's version line, e.g. "Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00". */
    public static String version(String versionOutput) {
        for (String line : versionOutput.split("\\R")) {
            String l = line.replaceFirst("^#\\s*", "").trim();
            if (l.startsWith("Betaflight") || l.startsWith("INAV")) return l;
        }
        return "";
    }

    /** "Betaflight 4.5.1 (STM32F7X2)" from the full version line; the line itself if it isn't understood. */
    public static String shortVersion(String versionLine) {
        Matcher m = VERSION.matcher(versionLine);
        return m.find() ? m.group(1) + " " + m.group(3) + " (" + m.group(2) + ")" : versionLine;
    }
}
