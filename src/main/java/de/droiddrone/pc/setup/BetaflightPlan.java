package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which Betaflight CLI commands DroidDrone needs, and nothing else: the receiver must be "MSP RX input"
 * (the phone feeds the sticks over MSP), and the serial task rate is raised so stick updates don't queue up.
 */
public final class BetaflightPlan {
    public record Change(String description, List<String> commands) {}

    /** Receiver features that compete with RX_MSP (only one receiver type can be active). */
    private static final List<String> OTHER_RECEIVERS = List.of("RX_SERIAL", "RX_PPM", "RX_SPI", "RX_PARALLEL_PWM");
    private static final Pattern SERIAL_RATE = Pattern.compile("serial_update_rate_hz\\s*=\\s*(\\d+)");
    private static final Pattern VERSION = Pattern.compile("^(\\S+) / (\\S+) \\(\\S+\\) (\\d+\\.\\d+\\.\\d+)");

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

    /** From `feature`, `get serial_update_rate_hz` and the version line: the commands still needed (none = all set). */
    public static List<Change> plan(String featureOutput, String serialRateOutput, String versionLine) {
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
        int rate = serialRate(serialRateOutput);
        int target = targetSerialRate(versionLine);
        if (rate >= 0 && rate < target) {
            changes.add(new Change("Stick delay: serial_update_rate_hz " + rate + " -> " + target,
                    List.of("set serial_update_rate_hz = " + target)));
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

    /** "Betaflight 4.5.1 (STM32F7X2)" from the full version line; the line itself if it isn't understood. */
    public static String shortVersion(String versionLine) {
        Matcher m = VERSION.matcher(versionLine);
        return m.find() ? m.group(1) + " " + m.group(3) + " (" + m.group(2) + ")" : versionLine;
    }
}
