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

    /**
     * True if this frame would arm (the switch is on), or might: judged on the frame as the flight controller gets it.
     * A channel this frame doesn't carry goes out as 1000 when it is one of the first 8 ({@link ControlLink#padForRxMap});
     * beyond that, and a channel sent as 0 (1.0.49: the gap before AUX14), it is not sent at all - Betaflight holds such a
     * channel at its last value or its centre (rx.c), which may well be inside an arm range. That is UNKNOWN, and
     * unknown counts as on: never taken as low or off. Betaflight's own check is value &lt; 900 + endStep * 25, the end
     * exclusive; this one includes the end, erring on the side of "on" (the sticks wait a little longer, never start
     * too early).
     */
    public boolean on(short[] channels) {
        if (channels == null) return false;
        if (isUnknown(channels)) return true;
        int v = channel < channels.length ? channels[channel] : RcMapper.MIN_CHANNEL_LEVEL;
        for (int[] r : ranges) if (v >= r[0] && v <= r[1]) return true;
        return false;
    }

    /** The arm channel's value in this frame is not known: not sent (beyond the padded 8), or sent as 0. */
    public boolean isUnknown(short[] channels) {
        if (channels == null) return true;
        if (channel >= channels.length) return channel >= ControlLink.RX_MAP_SIZE;
        return channels[channel] == 0;
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
