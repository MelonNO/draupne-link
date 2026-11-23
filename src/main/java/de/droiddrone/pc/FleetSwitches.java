package de.droiddrone.pc;

/**
 * Fleet PRD E4 / FA4 / GU-7: DISCARD LEAD and CALL RESERVE are switches on the pilot's radio, read here - not functions
 * of the flight controller. Each is an AUX channel of the stick frames (default AUX5 and AUX6); a flip from low to
 * high is one press. A switch must sit low for {@link #ARM_MS} before its flip counts (a channel that starts high, or
 * jitters round the middle, never fires), and a press is followed by {@link #HOLD_OFF_MS} in which it can't fire
 * again. Asked once a stick frame on the stick thread: a few compares, no allocation.
 */
public final class FleetSwitches {
    public static final String DISCARD_LEAD_KEY = "fleet.discardLeadAux", CALL_RESERVE_KEY = "fleet.callReserveAux";
    public static final int DEFAULT_DISCARD_LEAD_AUX = 5, DEFAULT_CALL_RESERVE_AUX = 6;
    /** Above this a switch is up, below LOW_US down; between them it keeps what it was. */
    static final int HIGH_US = 1700, LOW_US = 1300;
    static final long ARM_MS = 300, HOLD_OFF_MS = 1000;

    public static final int NONE = 0, DISCARD_LEAD = 1, CALL_RESERVE = 2;

    private final int discardChannel, reserveChannel;
    private final Edge discard = new Edge(), reserve = new Edge();

    /** @param discardAux AUX number (1 = AUX1, frame channel 4 + aux); 0: not on the radio */
    public FleetSwitches(int discardAux, int reserveAux) {
        discardChannel = discardAux <= 0 ? -1 : 3 + discardAux;
        reserveChannel = reserveAux <= 0 ? -1 : 3 + reserveAux;
    }

    public static FleetSwitches from(PcConfig config) {
        return new FleetSwitches(aux(config.getString(DISCARD_LEAD_KEY), DEFAULT_DISCARD_LEAD_AUX),
                aux(config.getString(CALL_RESERVE_KEY), DEFAULT_CALL_RESERVE_AUX));
    }

    static int aux(String saved, int fallback) {
        if (saved == null || saved.isBlank()) return fallback;
        try {
            int a = Integer.parseInt(saved.trim());
            return a >= 0 && a <= 12 ? a : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * One stick frame (the frame's channel values in microseconds, AETR then AUX1...). Returns DISCARD_LEAD or
     * CALL_RESERVE when that switch was flipped up just now (DISCARD LEAD first should both flip in one frame - the next
     * frame gives the other), else NONE.
     */
    public int onFrame(short[] channels, int count, long nowMs) {
        int out = NONE;
        if (discardChannel >= 0 && discardChannel < count && discard.flip(channels[discardChannel], nowMs)) out = DISCARD_LEAD;
        if (reserveChannel >= 0 && reserveChannel < count && reserve.flip(channels[reserveChannel], nowMs) && out == NONE) out = CALL_RESERVE;
        return out;
    }

    /** One switch: low long enough, then up = one flip. */
    private static final class Edge {
        private boolean up = true; // not known at first: taken as up, so a switch that starts up never fires
        private long lowSince = -1, firedAt = Long.MIN_VALUE / 2;
        boolean flip(int us, long now) {
            if (us <= LOW_US) {
                if (up || lowSince < 0) lowSince = now;
                up = false;
                return false;
            }
            if (us < HIGH_US) return false; // between: as it was
            boolean wasLow = !up;
            up = true;
            if (!wasLow) return false;
            boolean armed = lowSince >= 0 && now - lowSince >= ARM_MS;
            lowSince = -1;
            if (!armed || now - firedAt < HOLD_OFF_MS) return false;
            firedAt = now;
            return true;
        }
    }
}
