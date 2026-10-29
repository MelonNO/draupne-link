package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which Betaflight CLI commands DroidDrone needs, from the FC's own `feature` and `aux` output:
 * the receiver must be "MSP RX input" (the phone feeds the sticks over MSP), and ARM needs a switch - AUX1 high,
 * which is what the calibration wizard maps the arm switch to. Existing ARM / mode setups are left alone.
 * The serial task rate is raised so stick updates don't queue up in Betaflight.
 */
public final class BetaflightPlan {
    public record Change(String description, List<String> commands) {}

    /** Receiver features that compete with RX_MSP (only one receiver type can be active). */
    private static final List<String> OTHER_RECEIVERS = List.of("RX_SERIAL", "RX_PPM", "RX_SPI", "RX_PARALLEL_PWM");
    private static final Pattern AUX = Pattern.compile("^aux\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)", Pattern.MULTILINE);
    private static final Pattern SERIAL_RATE = Pattern.compile("serial_update_rate_hz\\s*=\\s*(\\d+)");
    private static final int ARM_MODE_ID = 0;
    private static final int AUX1 = 0;

    private BetaflightPlan() {
    }

    /** Features listed on the "Enabled:" line of Betaflight's `feature` output; null if the output is not understood. */
    static List<String> enabledFeatures(String featureOutput) {
        for (String line : featureOutput.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("Enabled:")) return Arrays.stream(l.substring(8).trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
        }
        return null;
    }

    public static boolean isMspReceiver(String featureOutput) {
        List<String> enabled = enabledFeatures(featureOutput);
        return enabled != null && enabled.contains("RX_MSP");
    }

    /** Is a mode with this id given a non-empty switch range? */
    static boolean hasMode(String auxOutput, int modeId) {
        Matcher m = AUX.matcher(auxOutput);
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) == modeId && Integer.parseInt(m.group(4)) < Integer.parseInt(m.group(5))) return true;
        }
        return false;
    }

    public static boolean hasArmSwitch(String auxOutput) {
        return hasMode(auxOutput, ARM_MODE_ID);
    }

    /** First aux slot with an empty range (start == end), or -1. */
    static int freeAuxSlot(String auxOutput) {
        Matcher m = AUX.matcher(auxOutput);
        while (m.find()) {
            if (Integer.parseInt(m.group(4)) >= Integer.parseInt(m.group(5))) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** The value from `get serial_update_rate_hz`, or -1 (not understood, or a Betaflight without the setting). */
    public static int serialRate(String getOutput) {
        Matcher m = SERIAL_RATE.matcher(getOutput);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Betaflight reads one MSP message per serial cycle, 100 times a second by default: too slow for 250 stick
     * updates a second plus OSD requests. 2000, or 1000 on the slower F411 processors.
     */
    public static int targetSerialRate(String versionLine) {
        return versionLine.contains("F411") ? 1000 : 2000;
    }

    /** {@link #plan(String, String)} plus the stick delay setting, from `get serial_update_rate_hz` and `version`. */
    public static List<Change> plan(String featureOutput, String auxOutput, String serialRateOutput, String versionLine) {
        List<Change> changes = new ArrayList<>(plan(featureOutput, auxOutput));
        int rate = serialRate(serialRateOutput);
        int target = targetSerialRate(versionLine);
        if (rate >= 0 && rate < target) {
            changes.add(new Change("Stick delay: serial_update_rate_hz " + rate + " -> " + target,
                    List.of("set serial_update_rate_hz = " + target)));
        }
        return changes;
    }

    public static List<Change> plan(String featureOutput, String auxOutput) {
        List<Change> changes = new ArrayList<>();
        List<String> enabled = enabledFeatures(featureOutput);
        if (enabled == null || !enabled.contains("RX_MSP")) {
            List<String> cmds = new ArrayList<>();
            for (String rx : OTHER_RECEIVERS) {
                if (enabled == null || enabled.contains(rx)) cmds.add("feature -" + rx);
            }
            cmds.add("feature RX_MSP");
            changes.add(new Change("Receiver mode: MSP RX input (the phone sends the sticks over MSP)", cmds));
        }
        if (!hasArmSwitch(auxOutput)) {
            int slot = freeAuxSlot(auxOutput);
            if (slot >= 0) {
                changes.add(new Change("ARM on the AUX1 switch (on = high)",
                        List.of("aux " + slot + " " + ARM_MODE_ID + " " + AUX1 + " 1700 2100 0 0")));
            }
        }
        return changes;
    }

    /** Betaflight's version line, e.g. "Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00". */
    public static String version(String versionOutput) {
        for (String line : versionOutput.split("\\R")) {
            String l = line.replaceFirst("^#\\s*", "").trim();
            if (l.startsWith("Betaflight") || l.startsWith("INAV")) return l;
        }
        return "";
    }
}
