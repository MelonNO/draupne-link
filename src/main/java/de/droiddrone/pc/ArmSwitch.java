package de.droiddrone.pc;

import java.util.ArrayList;
import java.util.List;

/**
 * Where Betaflight's ARM mode sits: the RC channel (index into the frame this app sends: 0-3 sticks, 4 = AUX1, ...)
 * and the ranges (in us) in which it arms. Read from the CLI's "aux" lines by Drone setup and remembered, so the sticks
 * only start once the arm switch is off - as well as the throttle down. Never guessed: without it only the throttle
 * rule applies.
 */
public record ArmSwitch(int channel, List<int[]> ranges) {
    public static final String KEY = "drone.armSwitch";
    /** Betaflight's ARM mode (permanent id 0). */
    private static final int MODE_ARM = 0;
    private static final int FIRST_AUX = 4;

    /** True if this frame would arm (the switch is on). */
    public boolean on(short[] channels) {
        if (channels == null || channel >= channels.length) return false;
        int v = channels[channel];
        for (int[] r : ranges) if (v >= r[0] && v <= r[1]) return true;
        return false;
    }

    /**
     * From the CLI's "aux" output: "aux &lt;slot&gt; &lt;mode&gt; &lt;aux channel&gt; &lt;start&gt; &lt;end&gt; ...", ranges in
     * us. A slot whose start equals its end is unused. null if ARM isn't on a switch (or the output isn't understood);
     * also when it spans more than one channel, which isn't covered.
     */
    public static ArmSwitch fromCli(String auxOutput) {
        Integer aux = null;
        List<int[]> ranges = new ArrayList<>();
        for (String line : auxOutput.split("\\R")) {
            String[] p = line.replaceFirst("^#\\s*", "").trim().split("\\s+");
            if (p.length < 6 || !p[0].equals("aux")) continue;
            try {
                int mode = Integer.parseInt(p[2]), channel = Integer.parseInt(p[3]);
                int start = Integer.parseInt(p[4]), end = Integer.parseInt(p[5]);
                if (mode != MODE_ARM || start >= end) continue;
                if (aux != null && aux != channel) return null;
                aux = channel;
                ranges.add(new int[]{start, end});
            } catch (NumberFormatException ignored) {
                // not an aux line after all
            }
        }
        return aux == null ? null : new ArmSwitch(FIRST_AUX + aux, List.copyOf(ranges));
    }

    /** As saved: "5:1700-2100,900-1100". */
    public String format() {
        StringBuilder sb = new StringBuilder().append(channel).append(':');
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ranges.get(i)[0]).append('-').append(ranges.get(i)[1]);
        }
        return sb.toString();
    }

    /** The saved form back; null if there is none or it is broken. */
    public static ArmSwitch parse(String saved) {
        if (saved == null || saved.isBlank()) return null;
        try {
            String[] parts = saved.trim().split(":");
            int channel = Integer.parseInt(parts[0]);
            List<int[]> ranges = new ArrayList<>();
            for (String r : parts[1].split(",")) {
                String[] se = r.split("-");
                ranges.add(new int[]{Integer.parseInt(se[0]), Integer.parseInt(se[1])});
            }
            return channel >= FIRST_AUX && !ranges.isEmpty() ? new ArmSwitch(channel, List.copyOf(ranges)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
