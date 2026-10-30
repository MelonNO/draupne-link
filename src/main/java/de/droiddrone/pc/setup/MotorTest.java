package de.droiddrone.pc.setup;

import de.droiddrone.common.FcCommon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Betaflight's motor test (Configurator's Motors tab) over this PC's USB: MSP_SET_MOTOR sets each motor's output while
 * the drone is disarmed. Betaflight keeps the last value it was sent - there is no timeout in the firmware - so every
 * way out of here sends STOP first: close(), an error, and the app being shut down. Values are capped at {@link #MAX}:
 * enough to see each motor spin and which way, never enough to lift a drone held down.
 */
public final class MotorTest implements AutoCloseable {
    /** Motor output in Betaflight's external range: 1000 stops (DShot "motor stop", or mincommand), 2000 is full. */
    public static final int STOP = 1000;
    public static final int MAX = 1300; // 30 %
    public static final int MAX_MOTORS = 8;
    public static final int MIXER_QUADX = 3;
    static final int MSP_FEATURE_CONFIG = 36;
    static final int MSP_MOTOR = 104;
    static final int MSP_SET_MOTOR = 214;
    static final int FEATURE_3D = 1 << 12;

    /** What the flight controller is: Betaflight version, mixer, motor direction setting and how many motors. */
    public record Info(String port, String version, int mixer, boolean reversed, int motorCount) {
        public boolean quadX() {
            return mixer == MIXER_QUADX;
        }
    }

    /** What it does now: each motor's output, battery voltage (0 = no battery), armed. */
    public record Live(int[] motors, double volts, boolean armed) {}

    private final BetaflightUsb fc;
    private final Info info;
    /** Closes (STOP, then nothing more can be sent): a STOP alone could be overtaken by the motor thread's next send. */
    private final Thread stopOnExit = new Thread(this::close, "motor-test-stop");
    private boolean closed;

    private MotorTest(BetaflightUsb fc, Info info) {
        this.fc = fc;
        this.info = info;
    }

    /**
     * Opens the flight controller for a motor test, after checking it can be done safely: Betaflight, not armed, no 3D
     * mode (where 1000 is full reverse, not stop). Sends STOP to every motor first.
     */
    public static MotorTest open(BetaflightUsb.Port port) throws IOException {
        BetaflightUsb fc = BetaflightUsb.open(port);
        try {
            String variant = new String(fc.msp(FcCommon.MSP_FC_VARIANT, null), StandardCharsets.US_ASCII);
            if (!variant.startsWith("BTFL")) throw new IOException("This flight controller doesn't run Betaflight (" + variant.trim() + ").");
            byte[] v = fc.msp(FcCommon.MSP_FC_VERSION, null);
            String version = v.length >= 3 ? (v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff) : "";
            if (is3d(fc.msp(MSP_FEATURE_CONFIG, null))) {
                throw new IOException("3D mode is on: stop isn't the lowest value. Use Betaflight Configurator's Motors tab for this drone.");
            }
            byte[] mixer = fc.msp(FcCommon.MSP_MIXER_CONFIG, null);
            int count = motorCount(fc.msp(MSP_MOTOR, null));
            if (armed(fc.msp(FcCommon.MSP_STATUS, null))) throw new IOException("The drone is armed. Disarm it first.");
            MotorTest test = new MotorTest(fc, new Info(port.systemName(), version, mixer.length > 0 ? mixer[0] & 0xff : 0,
                    mixer.length > 1 && mixer[1] != 0, count));
            test.set(new int[0]);
            Runtime.getRuntime().addShutdownHook(test.stopOnExit);
            return test;
        } catch (IOException | RuntimeException e) {
            fc.close();
            throw e;
        }
    }

    public Info info() {
        return info;
    }

    /** Sets the motors (1 first); missing ones stop, every value is kept within STOP..MAX. */
    public synchronized void set(int[] values) throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        fc.msp(MSP_SET_MOTOR, setMotorPayload(values));
    }

    public synchronized Live poll() throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        int[] motors = motors(fc.msp(MSP_MOTOR, null));
        double volts = volts(fc.msp(FcCommon.MSP_ANALOG, null));
        boolean armed = armed(fc.msp(FcCommon.MSP_STATUS, null));
        return new Live(Arrays.copyOf(motors, info.motorCount()), volts, armed);
    }

    /** Every motor to STOP, trying a few times; never throws. */
    public synchronized void stop() {
        if (closed) return;
        for (int i = 0; i < 3; i++) {
            try {
                fc.msp(MSP_SET_MOTOR, setMotorPayload(new int[0]));
                return;
            } catch (IOException | RuntimeException ignored) {
                // the cable may be out: nothing more can be done from here
            }
        }
    }

    /** Stops every motor, then lets go of the flight controller. */
    @Override
    public synchronized void close() {
        if (closed) return;
        stop();
        closed = true;
        fc.close();
        try {
            Runtime.getRuntime().removeShutdownHook(stopOnExit);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
    }

    // ------------------------------------------------------------------ MSP payloads (little-endian)

    static byte[] setMotorPayload(int[] values) {
        byte[] p = new byte[MAX_MOTORS * 2];
        for (int i = 0; i < MAX_MOTORS; i++) {
            int v = i < values.length ? Math.max(STOP, Math.min(MAX, values[i])) : STOP;
            p[2 * i] = (byte) v;
            p[2 * i + 1] = (byte) (v >> 8);
        }
        return p;
    }

    /** MSP_MOTOR: eight outputs; motors the mixer doesn't have read 0. */
    static int[] motors(byte[] p) {
        int[] m = new int[Math.min(MAX_MOTORS, p.length / 2)];
        for (int i = 0; i < m.length; i++) m[i] = (p[2 * i] & 0xff) | (p[2 * i + 1] & 0xff) << 8;
        return m;
    }

    static int motorCount(byte[] motorPayload) {
        int n = 0;
        for (int v : motors(motorPayload)) if (v > 0) n++;
        return n;
    }

    /** MSP_STATUS: the first flight mode flag is ARM (box 0 comes first). */
    static boolean armed(byte[] status) {
        return status.length > 6 && (status[6] & 1) != 0;
    }

    /** MSP_ANALOG: the 0.01 V field from Betaflight 4.0 on, else the old 0.1 V one. */
    static double volts(byte[] analog) {
        if (analog.length >= 9) return ((analog[7] & 0xff) | (analog[8] & 0xff) << 8) / 100.0;
        return analog.length > 0 ? (analog[0] & 0xff) / 10.0 : 0;
    }

    static boolean is3d(byte[] features) {
        if (features.length < 4) return false;
        int mask = (features[0] & 0xff) | (features[1] & 0xff) << 8 | (features[2] & 0xff) << 16 | (features[3] & 0xff) << 24;
        return (mask & FEATURE_3D) != 0;
    }

    // ------------------------------------------------------------------ Quad X

    /**
     * Betaflight's Quad X, seen from above with the front up: motor 1 rear right, 2 front right, 3 rear left, 4 front
     * left. Props in (yaw_motors_reversed off): 1 and 4 clockwise, 2 and 3 counter-clockwise; props out: the other way.
     */
    public static boolean clockwise(int motor, boolean reversed) {
        boolean cw = motor == 1 || motor == 4;
        return cw != reversed;
    }

    /** Where motor 1..4 sits: x -1 left / +1 right, y -1 front / +1 rear. */
    public static int[] quadXPosition(int motor) {
        return switch (motor) {
            case 1 -> new int[]{1, 1};
            case 2 -> new int[]{1, -1};
            case 3 -> new int[]{-1, 1};
            case 4 -> new int[]{-1, -1};
            default -> throw new IllegalArgumentException("Quad X has motors 1 to 4");
        };
    }
}
