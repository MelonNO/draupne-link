/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * THE TEST RIG'S BUILD ONLY - NEVER RELEASED (flight-app/bench-overlay/README.md).
 * <p>
 * What the rig does to the app from the PC. The emulator's tests do these as root (delete CrashGuard's file, kill the
 * process); a real phone gives no root, and this build is not debuggable (it is the release build but for the rig's
 * door), so {@code run-as} is shut as well. Only a sender that holds android.permission.DUMP gets here (the manifest):
 * adb's shell does.
 * <pre>
 * adb shell am broadcast -n de.droiddrone.flight.pc.bench/de.droiddrone.flight.BenchControlReceiver -a &lt;action&gt;
 *   de.droiddrone.flight.bench.RESET_CRASH_GUARD   CrashGuard forgets its restarts and what ended the app last
 *   de.droiddrone.flight.bench.KILL                the app's process ended outright (as a battery saver does)
 *   de.droiddrone.flight.bench.CRASH               an error nobody catches, on a thread of its own
 *                                                  (--es thread main: on the main thread)
 *   de.droiddrone.flight.bench.STATS               the rig's connection in numbers
 * </pre>
 * Each says what it did in logcat (tag DDLog) and as the broadcast's result, which {@code am broadcast} prints.
 */
public class BenchControlReceiver extends BroadcastReceiver {
    private static final String PREFIX = "de.droiddrone.flight.bench.";
    static final String RESET_CRASH_GUARD = PREFIX + "RESET_CRASH_GUARD";
    static final String KILL = PREFIX + "KILL";
    static final String CRASH = PREFIX + "CRASH";
    static final String STATS = PREFIX + "STATS";
    /** CrashGuard's own store (its STORE; PC test ReleaseHasNoBenchCodeTest holds the two names together). */
    private static final String CRASH_GUARD_STORE = "crashGuard";
    /** The broadcast has its answer before the app ends. */
    private static final long ENDS_AFTER_MS = 300;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        String said;
        switch (action) {
            case RESET_CRASH_GUARD: {
                boolean done = context.getApplicationContext().getSharedPreferences(CRASH_GUARD_STORE, Context.MODE_PRIVATE)
                        .edit().clear().commit();
                said = done ? "CrashGuard's restarts and its last end are forgotten" : "CrashGuard's store could not be written";
                break;
            }
            case KILL: {
                said = "the app's process " + android.os.Process.myPid() + " is ended outright in " + ENDS_AFTER_MS
                        + " ms (service " + (DDService.isRunning ? "running" : "not running") + ")";
                Thread killer = new Thread(() -> {
                    pause();
                    android.os.Process.killProcess(android.os.Process.myPid());
                });
                killer.setName("benchKill");
                killer.start();
                break;
            }
            case CRASH: {
                boolean onMain = "main".equals(intent.getStringExtra("thread"));
                said = "an error nobody catches in " + ENDS_AFTER_MS + " ms, on " + (onMain ? "the main thread" : "a thread of its own")
                        + " (service " + (DDService.isRunning ? "running: CrashGuard opens the app again" : "not running: CrashGuard leaves the app closed") + ")";
                if (onMain) {
                    new Handler(Looper.getMainLooper()).postDelayed(BenchControlReceiver::fail, ENDS_AFTER_MS);
                } else {
                    Thread crasher = new Thread(() -> {
                        pause();
                        fail();
                    });
                    crasher.setName("benchCrash");
                    crasher.start();
                }
                break;
            }
            case STATS:
                said = BenchFc.stats() + " serialStatus=" + DDService.getSerialPortStatus()
                        + " service=" + (DDService.isRunning ? "running" : "stopped") + " pc=" + (DDService.isConnected ? "connected" : "none")
                        + " pid=" + android.os.Process.myPid();
                break;
            default:
                said = "not an action of the rig: " + action;
        }
        if (!STATS.equals(action)) said = BenchFc.MARKER + " control: " + said;
        log(said);
        if (isOrderedBroadcast()) {
            setResultCode(1);
            setResultData(said);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(ENDS_AFTER_MS);
        } catch (InterruptedException ignored) {
        }
    }

    private static void fail() {
        throw new IllegalStateException(BenchFc.MARKER + ": an error thrown on purpose (BenchControlReceiver)");
    }
}
