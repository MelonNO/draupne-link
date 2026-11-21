package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Makes a flight controller on USB safe, whatever a test left behind: STOP to every motor, the motors read back at
 * rest, the armed flag read. For the end of every hardware test, and for when one died.
 * <pre>
 * gradlew boardSafe [-Pport=COM9]        exit code 0 only when the board is disarmed and every motor is at rest
 * </pre>
 * Betaflight keeps the last motor value it was sent over USB for good (MotorTest), so a test that died with a motor
 * on leaves it on: this sends STOP again - three times, as the motor test does - and looks whether it took. Nothing
 * is written to the board's settings and nothing restarts, with one exception: a board found in the CLI (a tool died
 * in there; it answers nothing else) is told "exit" first - Betaflight then restarts, which stops its motors too.
 * <p>
 * What it cannot do: disarm. An armed board ignores motor values from USB; it reads ARMED here and the exit code says
 * not safe - disarm at the radio or take the battery out. And a board that answers nothing at all (a tool died while
 * the port was with the ESCs) is not safe to this tool either: unplug its USB and battery.
 */
public final class BoardSafe {
    /** Looks at the motors after STOP: the flight controller takes a moment to pass a new value on. */
    private static final int LOOKS = 5;

    /**
     * The arming lock's place: the flight controller keeper brings one (a board that cannot be armed until the keeper
     * lets it). Called last, on a board that answers - stopped or not, armed or not; what it returns is said.
     */
    @FunctionalInterface
    public interface Lock {
        String engage(BetaflightUsb fc) throws IOException;
    }

    /**
     * @param motors  each motor output as read back (those the mixer has), or null when the board did not answer
     * @param atRest  every one of them reads stop
     * @param armed   Betaflight's armed flag
     * @param lock    what the arming lock said, or null (none yet)
     * @param problem why the board could not be made or seen safe, or null
     */
    public record Result(int[] motors, boolean atRest, boolean armed, String lock, String problem) {
        public boolean safe() {
            return problem == null && atRest && !armed;
        }

        /** One line for the log. */
        public String text() {
            if (problem != null) return "NOT SAFE: " + problem;
            String read = "motors read " + Arrays.toString(motors) + (atRest ? " (at rest)" : " (NOT AT REST)") + ", "
                    + (armed ? "ARMED" : "disarmed") + (lock == null ? "" : ", " + lock);
            if (safe()) return "SAFE: " + read + ".";
            return "NOT SAFE: " + read + "." + (armed ? " An armed board takes no motor values from USB: disarm at the radio, or take the battery out." : "");
        }
    }

    private BoardSafe() {
    }

    /** Stops the board on this port and says how it is. Never throws: what went wrong is the result's problem. */
    public static Result run(BetaflightUsb.Port port, BoardPort.Timing timing, Lock lock, Consumer<String> log) {
        try (BetaflightUsb fc = BoardPort.open(port, timing, log)) {
            // in 3D mode 1000 is full reverse: stop is the middle there
            int stop = (fc.featureMask() & Msp.FEATURE_3D) != 0 ? Msp.STOP_3D : MotorTest.STOP;
            IOException failed = null;
            for (int i = 0; i < 3; i++) {
                try {
                    fc.msp(Msp.SET_MOTOR, Msp.allMotors(stop));
                    failed = null;
                } catch (IOException e) {
                    failed = e;
                }
            }
            if (failed != null) log.accept("STOP was not taken (" + failed.getMessage() + ").");
            else log.accept("STOP (" + stop + ") sent to every motor.");
            int[] motors = null;
            boolean atRest = false;
            for (int look = 0; look < LOOKS && !atRest; look++) {
                if (look > 0) BoardPort.pause(timing.stepMs());
                motors = there(Msp.motors(fc.msp(Msp.MOTOR, null)));
                atRest = atRest(motors, stop);
            }
            boolean armed = Msp.armed(fc.msp(Msp.STATUS, null));
            // ---- the arming lock goes here (the flight controller keeper): after the stop, before the port is let go ----
            String locked = lock == null ? null : lock.engage(fc);
            return new Result(motors, atRest, armed, locked, null);
        } catch (IOException | RuntimeException e) {
            String why = String.valueOf(e.getMessage());
            return new Result(null, false, false, null, why.startsWith(port.systemName()) ? why : port.systemName() + ": " + why);
        }
    }

    /** The outputs the mixer has: one it doesn't have reads 0. */
    private static int[] there(int[] outputs) {
        return Arrays.stream(outputs).filter(v -> v > 0).toArray();
    }

    /** Every motor reads stop: 1000 or under it (Betaflight's mincommand may be lower); in 3D mode the middle, exactly. */
    static boolean atRest(int[] motors, int stop) {
        for (int v : motors) if (stop == Msp.STOP_3D ? v != stop : v > stop) return false;
        return true;
    }

    /** args: the port ("" = every flight controller found). Exit code 0 only when every one is disarmed and at rest. */
    public static void main(String[] args) {
        System.exit(byHand(args, System.out, BoardPort.Timing.REAL));
    }

    /** What main does, said to out: 0 only when there is a board, and every board found is disarmed and at rest. */
    static int byHand(String[] args, java.io.PrintStream out, BoardPort.Timing timing) {
        String wanted = args.length > 0 ? args[0] : "";
        List<BetaflightUsb.Port> ports = new ArrayList<>();
        try {
            ports.addAll(BoardPort.findAll(wanted, timing));
        } catch (IOException e) {
            out.println("NOT SAFE: " + e.getMessage());
            return 1;
        }
        if (ports.isEmpty()) {
            out.println("NOT SAFE: no flight controller found" + (wanted.isBlank() ? " on USB" : " on " + wanted.strip())
                    + " - nothing could be stopped or looked at.");
            return 1;
        }
        boolean safe = true;
        for (BetaflightUsb.Port port : ports) {
            out.println(port + ":");
            Result r = run(port, timing, null, s -> out.println("  " + s));
            out.println("  " + r.text());
            safe &= r.safe();
        }
        return safe ? 0 : 1;
    }
}
