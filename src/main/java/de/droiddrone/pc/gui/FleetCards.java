package de.droiddrone.pc.gui;

import java.util.Locale;

/**
 * FLEET MANAGEMENT (1.0.52): what one phone's card in the map window shows, worked out once a second by the main
 * window; and the pure rules behind it (its badge, the texts), for the tests. Unknown is unknown: NaN, -1, null.
 */
final class FleetCards {
    private FleetCards() {
    }

    /** "Not known" for temperatures (tenths of a degree, or whole degrees). */
    static final int NO_TEMP = Integer.MIN_VALUE;
    /** nextCheckMs: always listening (no countdown). */
    static final long LISTENING = Long.MAX_VALUE;

    /**
     * One card.
     * @param kind          the map's sign for it
     * @param badge         ACTIVE / CONNECTED / STANDBY / ASLEEP / OVERWATCH / LOST / UNKNOWN
     * @param cellVolts     the drone's battery per cell, NaN unknown
     * @param cellAgeMs     how old that is (0 live, -1 unknown)
     * @param phonePercent  the phone's battery, -1 unknown
     * @param nextCheckMs   time to its next check-in, -1 none, {@link #LISTENING}
     * @param intervalMin   its CHECK EVERY (SleepCheck; 255 always listening), 0 unknown
     * @param heardAgoMs    since it was last heard, -1 never
     * @param signal        mobile bars 0-4, -1 unknown
     * @param gpsFix        1 fix, 0 none, -1 unknown
     * @param events        overwatch events this PC has of it
     * @param lastEventWall the last one's time, 0 none
     * @param phoneTempDeci the phone's battery temperature, tenths of a degree, {@link #NO_TEMP}
     * @param fcTempC       the flight controller's core temperature, {@link #NO_TEMP}
     * @param queued        "DEEP SLEEP" and so on while a mode waits for the phone, else null
     * @param note          why its buttons can't be used now (short), else null
     * @param queuedAgoMs   since that mode was queued (2026-11-10, the pilot: "inform the user for how long since that
     *                      command was cued"), -1 none or unknown
     */
    record Card(String name, boolean virtual, MapView.Kind kind, String badge, double cellVolts, long cellAgeMs, int phonePercent,
                boolean charging, long nextCheckMs, int intervalMin, long heardAgoMs, int signal, int gpsFix, int sats,
                int events, long lastEventWall, int phoneTempDeci, int fcTempC, String queued, String note, long queuedAgoMs) {
        Card(String name, boolean virtual, MapView.Kind kind, String badge, double cellVolts, long cellAgeMs, int phonePercent,
             boolean charging, long nextCheckMs, int intervalMin, long heardAgoMs, int signal, int gpsFix, int sats,
             int events, long lastEventWall, int phoneTempDeci, int fcTempC, String queued, String note) {
            this(name, virtual, kind, badge, cellVolts, cellAgeMs, phonePercent, charging, nextCheckMs, intervalMin, heardAgoMs, signal,
                    gpsFix, sats, events, lastEventWall, phoneTempDeci, fcTempC, queued, note, -1);
        }

        /** The queued mode's sign, or null. */
        MapView.Kind queuedKind() {
            if (queued == null) return null;
            return switch (queued) {
                case "STANDBY" -> MapView.Kind.STANDBY;
                case "DEEP SLEEP" -> MapView.Kind.ASLEEP;
                case "OVERWATCH" -> MapView.Kind.OVERWATCH;
                default -> null;
            };
        }
    }

    /** A card's badge: the map's sign and its word. */
    record Badge(MapView.Kind kind, String text) {}

    /**
     * The badge, in this order: this PC's link to it; asleep here (late: unknown); in standby here; its own word
     * (pc.39 power byte or pc.40 status, while fresh); heard; else lost if it was flying, unknown if not.
     * @param overdueMs  for a sleeping phone: how late its check-in is (0 or less: on time)
     * @param saidMode   the mode it last said (0-3, -1 unknown), saidAgeMs how old that word is
     */
    static Badge badge(boolean connectedHere, boolean asleep, boolean overwatch, long overdueMs, boolean standby, int saidMode,
                       long saidAgeMs, boolean heardRecently, boolean wasFlying) {
        if (connectedHere) return new Badge(MapView.Kind.ACTIVE, "CONNECTED");
        if (asleep) {
            if (overdueMs > 0) return new Badge(MapView.Kind.UNKNOWN, "UNKNOWN");
            return overwatch ? new Badge(MapView.Kind.OVERWATCH, "OVERWATCH") : new Badge(MapView.Kind.ASLEEP, "ASLEEP");
        }
        if (standby) return new Badge(heardRecently ? MapView.Kind.STANDBY : MapView.Kind.UNKNOWN, heardRecently ? "STANDBY" : "UNKNOWN");
        if (saidMode >= 1 && saidMode <= 3 && saidAgeMs >= 0 && saidAgeMs <= 30_000) {
            return switch (saidMode) {
                case 1 -> new Badge(MapView.Kind.STANDBY, "STANDBY");
                case 2 -> new Badge(MapView.Kind.ASLEEP, "ASLEEP");
                default -> new Badge(MapView.Kind.OVERWATCH, "OVERWATCH");
            };
        }
        if (heardRecently) return new Badge(MapView.Kind.ACTIVE, "ACTIVE");
        return wasFlying ? new Badge(MapView.Kind.LOST, "LOST") : new Badge(MapView.Kind.UNKNOWN, "UNKNOWN");
    }

    /**
     * A queued mode never changes the sign (2026-11-10, the pilot: "The modes should only be as follows when confirmed
     * changed"; 1.0.56 showed it at once): it goes beside it, an arrow to its own sign ({@link MapView#paintQueued}).
     * Shown for a phone that can still take it: not over this PC's link, nor for a lost or crashed drone.
     */
    static boolean showsQueued(Badge b) {
        return b.kind() != MapView.Kind.LOST && b.kind() != MapView.Kind.CRASHED && !"CONNECTED".equals(b.text());
    }

    /** "now", "40s", "12m", "3h": since a mode was queued; "" unknown. */
    static String queuedAgo(long ms) {
        if (ms < 0) return "";
        long s = ms / 1000;
        if (s < 5) return "now";
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        return s / 3600 + "h";
    }

    /** "0:20 ago", "12 min ago", "3 h ago", or "-". */
    static String ago(long ms) {
        if (ms < 0) return "-";
        long s = ms / 1000;
        if (s < 600) return String.format(Locale.ROOT, "%d:%02d ago", s / 60, s % 60);
        if (s < 3 * 3600) return s / 60 + " min ago";
        if (s < 3 * 86_400) return s / 3600 + " h ago";
        return s / 86_400 + " d ago";
    }

    /** The countdown in a ring: "1:40", "LISTEN", or "-". */
    static String countdown(long ms) {
        if (ms == LISTENING) return "LISTEN";
        if (ms < 0) return "-";
        long s = (ms + 999) / 1000;
        return s >= 3600 ? s / 3600 + " h" : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** How full the ring is: the part of the interval still to go (0..1); listening full, unknown empty. */
    static double ringFill(long nextCheckMs, int intervalMin) {
        if (nextCheckMs == LISTENING) return 1;
        if (nextCheckMs < 0 || intervalMin <= 0 || de.droiddrone.common.SleepCheck.isAlways(intervalMin)) return 0;
        return Math.max(0, Math.min(1, nextCheckMs / (intervalMin * 60_000.0)));
    }

    /** "31°C", or "-". */
    static String tempDeci(int deci) {
        return deci == NO_TEMP ? "-" : Math.round(deci / 10.0) + "°C";
    }

    static String tempC(int c) {
        return c == NO_TEMP ? "-" : c + "°C";
    }

    /** "3.82 V", or "-". */
    static String cell(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f V", v);
    }

    /** A cell's charge for the gauge (0..1): 3.3 V empty, 4.2 V full; NaN unknown. */
    static double cellFill(double v) {
        if (Double.isNaN(v)) return Double.NaN;
        return Math.max(0, Math.min(1, (v - 3.3) / 0.9));
    }

    /** GPS as the card writes it: "12 SAT FIX", "6 SAT NO FIX", or "-". */
    static String gps(int fix, int sats) {
        if (fix < 0 && sats < 0) return "-";
        return (sats >= 0 ? sats + " SAT " : "") + (fix > 0 ? "FIX" : fix == 0 ? "NO FIX" : "");
    }
}
