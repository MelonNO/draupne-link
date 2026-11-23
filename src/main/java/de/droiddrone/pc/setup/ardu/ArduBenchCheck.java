package de.droiddrone.pc.setup.ardu;

import com.MAVLink.common.msg_rc_channels_override;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Fleet PRD AS-10, the part USB can check on the bench (props off): do RC overrides arm this ArduPilot drone the way
 * the phone's will? This PC sends the sticks as the phone does - centred, throttle down, the ARM switch's channel up -
 * ten times a second and waits up to {@link #ARM_WAIT_MS} for the heartbeat's ARMED; then ARM down until DISARMED, then
 * every override released. Refused when the drone is armed before the test.
 */
public final class ArduBenchCheck {
    static final long ARM_WAIT_MS = 4000, DISARM_WAIT_MS = 3000;
    static final int LOW = 1000, MID = 1500, HIGH = 2000;
    /** An override field of channels 1-8 that changes nothing; 0 gives the channel back. */
    static final int IGNORE = 65535, RELEASE = 0;

    private ArduBenchCheck() {
    }

    /** The AUX the board's ARM switch is on (the first RCn_OPTION with ARM/DISARM, n 5-16), or -1 when it has none. */
    public static int armAux(java.util.Map<String, Float> params) {
        for (int n = 5; n <= 16; n++) {
            Float o = params.get("RC" + n + "_OPTION");
            if (o != null && Math.round(o) == ArduPlan.RC_OPTION_ARM_DISARM) return n - 4;
        }
        return -1;
    }

    /** @param armAux the AUX the ARM switch is on (frame channel 4 + armAux) */
    public static String armTest(MavSession fc, int armAux, Consumer<String> log) throws IOException, InterruptedException {
        if (fc.armed()) return "Armed already, or not known: not tested.";
        int throttleCh = channel(fc, "RCMAP_THROTTLE", 3);
        int rollCh = channel(fc, "RCMAP_ROLL", 1), pitchCh = channel(fc, "RCMAP_PITCH", 2), yawCh = channel(fc, "RCMAP_YAW", 4);
        int armCh = 4 + armAux;
        int[] ch = new int[18];
        java.util.Arrays.fill(ch, IGNORE);
        ch[rollCh - 1] = MID;
        ch[pitchCh - 1] = MID;
        ch[yawCh - 1] = MID;
        ch[throttleCh - 1] = LOW;
        ch[armCh - 1] = LOW;
        // a second with ARM down first: ArduPilot arms on the switch's change, not on its position
        send(fc, ch, 1000);
        ch[armCh - 1] = HIGH;
        log.accept("ARM switch up (channel " + armCh + "), throttle down ...");
        boolean armed = sendUntil(fc, ch, ARM_WAIT_MS, true);
        ch[armCh - 1] = LOW;
        boolean disarmed = sendUntil(fc, ch, DISARM_WAIT_MS, false);
        java.util.Arrays.fill(ch, 0, 8, RELEASE);
        send(fc, ch, 200);
        if (!armed) return "Did NOT arm on the overrides - see ArduPilot's reason below.";
        return disarmed ? "Armed on the overrides, and disarmed again: OK." : "Armed, but did NOT disarm on the switch - check RC" + armCh + "_OPTION.";
    }

    private static int channel(MavSession fc, String param, int fallback) throws IOException, InterruptedException {
        float v = fc.readParam(param, 1500);
        int c = Float.isNaN(v) ? fallback : Math.round(v);
        return c >= 1 && c <= 18 ? c : fallback;
    }

    private static void send(MavSession fc, int[] ch, long forMs) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + forMs;
        do {
            one(fc, ch);
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
    }

    /** Overrides until the heartbeat's armed flag is as wanted (a fresh heartbeat), or the time is up. */
    private static boolean sendUntil(MavSession fc, int[] ch, long forMs, boolean wantArmed) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + forMs;
        while (System.currentTimeMillis() < end) {
            one(fc, ch);
            Thread.sleep(100);
            boolean a = fc.armed();
            if (a == wantArmed && fc.heartbeatFresh()) return true;
        }
        return false;
    }

    private static void one(MavSession fc, int[] c) throws IOException {
        int target = Math.max(1, fc.fcSysId());
        // channels 9-18: 0 and 65535 both mean "not used" there (65534 gives one back)
        int[] hi = new int[10];
        for (int i = 0; i < 10; i++) hi[i] = c[8 + i] == IGNORE ? 0 : c[8 + i];
        fc.send(new msg_rc_channels_override(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], (short) target, (short) 1,
                hi[0], hi[1], hi[2], hi[3], hi[4], hi[5], hi[6], hi[7], hi[8], hi[9], MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
    }
}
