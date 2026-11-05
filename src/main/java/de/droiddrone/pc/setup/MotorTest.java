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
    static final int MSP_SET_MIXER_CONFIG = 43, MSP_ADVANCED_CONFIG = 90, MSP_REBOOT = 68, MSP_EEPROM_WRITE = 250;
    /** Betaflight's own MSP v2 commands (msp_protocol_v2_betaflight.h), as Configurator's motor wizards use them. */
    static final int MSP2_MOTOR_OUTPUT_REORDERING = 0x3001, MSP2_SET_MOTOR_OUTPUT_REORDERING = 0x3002,
            MSP2_SEND_DSHOT_COMMAND = 0x3003;
    /** DShot commands to the ESC: spin direction (absolute, kept by the ESC once saved) and save. */
    static final int DSHOT_SPIN_NORMAL = 20, DSHOT_SPIN_REVERSED = 21, DSHOT_SAVE_SETTINGS = 12;
    /** MSP2_SEND_DSHOT_COMMAND's type: blocking - Betaflight stops the motors while it sends. */
    static final int DSHOT_CMD_TYPE_BLOCKING = 1;
    /** Betaflight's motor protocols that take DShot commands: DSHOT150, 300, 600 and PROSHOT1000. */
    static final int PROTOCOL_DSHOT150 = 5, PROTOCOL_PROSHOT1000 = 8;

    /**
     * What the flight controller is: Betaflight version, mixer, motor direction setting (props out), how many motors,
     * and whether the motors run DShot (only then can the ESCs be told a spin direction).
     */
    public record Info(String port, String version, int mixer, boolean reversed, int motorCount, boolean dshot) {
        public boolean quadX() {
            return mixer == MIXER_QUADX;
        }
    }

    /** What it does now: each motor's output, battery voltage, Betaflight's cell count (0 = none seen), armed. */
    public record Live(int[] motors, double volts, int cells, boolean armed) {
        /** A battery is in: Betaflight counts its cells, or the voltage is above USB power's (a 1S pack reads 3-4 V). */
        public boolean batteryIn() {
            return cells > 0 || volts >= NO_BATTERY_VOLTS;
        }
    }

    /** Below this there is no battery, only USB power (a 1S pack reads well above it). */
    public static final double NO_BATTERY_VOLTS = 3;

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
            boolean dshot = false;
            try {
                dshot = isDshot(fc.msp(MSP_ADVANCED_CONFIG, null));
            } catch (IOException e) {
                // not known: no DShot commands offered
            }
            MotorTest test = new MotorTest(fc, new Info(port.systemName(), version, mixer.length > 0 ? mixer[0] & 0xff : 0,
                    mixer.length > 1 && mixer[1] != 0, count, dshot));
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
        int cells = 0;
        try {
            cells = fc.batteryState().cells();
        } catch (IOException e) {
            // an old Betaflight without it: the voltage decides
        }
        boolean armed = armed(fc.msp(FcCommon.MSP_STATUS, null));
        return new Live(Arrays.copyOf(motors, info.motorCount()), volts, cells, armed);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** Betaflight's motor output order: for each motor number (index 0 = motor 1), the output it drives. */
    public synchronized int[] outputOrder() throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        return parseOrder(fc.msp2(MSP2_MOTOR_OUTPUT_REORDERING, null));
    }

    /**
     * A new motor output order: stopped first, written, saved, and Betaflight restarts (the order is only taken at
     * start-up). The motor test is closed after it; open a new one once the flight controller is back.
     */
    public synchronized void setOutputOrderAndRestart(int[] order) throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        fc.msp(MSP_SET_MOTOR, setMotorPayload(new int[0]));
        fc.msp2(MSP2_SET_MOTOR_OUTPUT_REORDERING, orderPayload(order));
        saveAndRestart();
    }

    /** Props out (Betaflight's yaw_motors_reversed) on or off: written with the mixer kept, saved, Betaflight restarts. */
    public synchronized void setPropsOutAndRestart(boolean out) throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        fc.msp(MSP_SET_MOTOR, setMotorPayload(new int[0]));
        fc.msp(MSP_SET_MIXER_CONFIG, new byte[]{(byte) info.mixer(), (byte) (out ? 1 : 0)});
        saveAndRestart();
    }

    /**
     * Tells one ESC its spin direction (motor 1 first) and to save it: DShot commands, blocking, so Betaflight holds
     * the motors stopped while it sends. Needs DShot and the battery in (the ESCs powered).
     */
    public synchronized void setDirection(int motor, boolean reversed) throws IOException {
        if (closed) throw new IOException("The motor test is closed");
        if (!info.dshot()) throw new IOException("The motors don't run DShot: the ESCs can't be told a direction this way.");
        fc.msp(MSP_SET_MOTOR, setMotorPayload(new int[0]));
        fc.msp2(MSP2_SEND_DSHOT_COMMAND, dshotPayload(motor - 1, reversed ? DSHOT_SPIN_REVERSED : DSHOT_SPIN_NORMAL, DSHOT_SAVE_SETTINGS));
    }

    /** EEPROM write, then a restart; the port goes away, so the connection is let go of without the usual STOP. */
    private void saveAndRestart() throws IOException {
        fc.msp(MSP_EEPROM_WRITE, null);
        try {
            fc.msp(MSP_REBOOT, null);
        } catch (IOException ignored) {
            // it may restart before it answers
        }
        closed = true; // the motors were stopped before the write
        fc.close();
        try {
            Runtime.getRuntime().removeShutdownHook(stopOnExit);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
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

    /** MSP_ADVANCED_CONFIG: gyro denom, pid denom, unsynced PWM, then the motor protocol (DSHOT150 5 ... PROSHOT1000 8). */
    static boolean isDshot(byte[] advanced) {
        if (advanced.length < 4) return false;
        int protocol = advanced[3] & 0xff;
        return protocol >= PROTOCOL_DSHOT150 && protocol <= PROTOCOL_PROSHOT1000;
    }

    /** MSP2_MOTOR_OUTPUT_REORDERING: a count, then the output for each motor number. */
    static int[] parseOrder(byte[] p) throws IOException {
        if (p.length < 1 || p.length < 1 + (p[0] & 0xff)) throw new IOException("Short motor order answer");
        int[] order = new int[p[0] & 0xff];
        for (int i = 0; i < order.length; i++) order[i] = p[1 + i] & 0xff;
        return order;
    }

    static byte[] orderPayload(int[] order) {
        byte[] p = new byte[1 + order.length];
        p[0] = (byte) order.length;
        for (int i = 0; i < order.length; i++) p[1 + i] = (byte) order[i];
        return p;
    }

    /** MSP2_SEND_DSHOT_COMMAND: type, motor index (0 first; 255 = all), how many commands, the commands. */
    static byte[] dshotPayload(int motorIndex, int... commands) {
        byte[] p = new byte[3 + commands.length];
        p[0] = DSHOT_CMD_TYPE_BLOCKING;
        p[1] = (byte) motorIndex;
        p[2] = (byte) commands.length;
        for (int i = 0; i < commands.length; i++) p[3 + i] = (byte) commands[i];
        return p;
    }

    /**
     * The new output order from the reorder wizard: motor k (1 first) was spun and seen at the place that belongs to
     * motor positions[k-1]. That place's motor number must drive the output that spun, so new[positions[k-1]-1] =
     * current[k-1]; outputs past the motors keep theirs.
     * @throws IllegalArgumentException when the places aren't each motor number once
     */
    public static int[] reordered(int[] current, int[] positions) {
        int n = positions.length;
        if (current.length < n) throw new IllegalArgumentException("More places than motors in the order");
        boolean[] seen = new boolean[n];
        int[] next = current.clone();
        for (int k = 0; k < n; k++) {
            int p = positions[k];
            if (p < 1 || p > n || seen[p - 1]) throw new IllegalArgumentException("Each place must be picked once");
            seen[p - 1] = true;
            next[p - 1] = current[k];
        }
        return next;
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
