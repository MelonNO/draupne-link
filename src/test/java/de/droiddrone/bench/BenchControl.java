package de.droiddrone.bench;

import java.util.List;

/**
 * The bench-only build of the phone app (flight-app/bench-overlay) and the receiver in it that does, on a phone
 * without root, what the emulator scenarios do as root: forget the crash guard's count, end the app's process
 * outright, throw an error nobody catches - and say how the build's own connections stand. Every name of it the PC's
 * bench uses is here and nowhere else; {@code PhoneRigBenchControlTest} holds them to the build's own sources.
 * <p>
 * The receiver is exported and asks its sender for {@code android.permission.DUMP}, which adb's shell holds and no
 * app from a store can get; it answers in the broadcast's result - {@code am broadcast} prints
 * {@code Broadcast completed: result=1, data="..."} - and in the app's log.
 */
public final class BenchControl {
    private BenchControl() {
    }

    /** The bench-only build's package: beside the released app on the phone, installed with {@code adb install -t}. */
    public static final String PACKAGE = "de.droiddrone.flight.pc.bench";
    /** The receiver's class (the app's code keeps its namespace whatever the package is called). */
    public static final String RECEIVER = "de.droiddrone.flight.BenchControlReceiver";

    private static final String PREFIX = "de.droiddrone.flight.bench.";
    /** What the crash guard kept of earlier restarts, and of what ended the app last, is forgotten (the emulator: rm of its settings file, as root). */
    public static final String RESET_CRASH_GUARD = PREFIX + "RESET_CRASH_GUARD";
    /** The app's process ends outright, 0.3 s after the answer, as killed from outside (the emulator: kill -9, as root). */
    public static final String KILL = PREFIX + "KILL";
    /** An error nobody catches, 0.3 s after the answer, on a thread of the app's own (the emulator: am crash). */
    public static final String CRASH = PREFIX + "CRASH";
    /** The build's own figures in one line: its flight controller's connection, its service, whether a PC is connected, its process. */
    public static final String STATS = PREFIX + "STATS";

    /** Every action the rig may send; the guard lets no other broadcast through to a real phone. */
    public static final List<String> ACTIONS = List.of(RESET_CRASH_GUARD, KILL, CRASH, STATS);

    /** What {@code am broadcast} prints when the receiver took the order (it sets the result to 1; 0 is "nobody was there"). */
    public static final String TAKEN = "Broadcast completed: result=1";

    /** The app's own tag in Android's log. */
    public static final String LOG_TAG = "DDLog";

    /** The receiver as adb names it: package/class. */
    public static String component(String app) {
        return app + "/" + RECEIVER;
    }
}
