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

import android.content.Context;
import android.content.SharedPreferences;

import java.io.DataOutputStream;
import java.io.IOException;

import de.droiddrone.common.GuardedCopy;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.UpdateWithdraw;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.32: the update asked for by the connected PC (DRAUPNE Link 1.0.41, the pilot: "a remote update for connected
 * phones"). REMOTE_UPDATE [0x77][version UTF][APK url UTF][its SHA-256 UTF], unnumbered, sent again until the
 * phone's PHONE_UPDATE record answers. The phone takes it only for a newer version and only with the flight
 * controller known to be disarmed (installing ends this app, and the sticks with it); it downloads the APK, checks the
 * SHA-256 and installs it like the in-app update ({@link AppUpdater}) - without a tap on Android 12 and later once
 * this app installed its own last update, else Android's "update this app?" prompt on the phone. When it is done the
 * app opens again by itself ({@link AppReplacedReceiver}) and listens for the PC, which reconnects.
 */
final class RemoteUpdate {
    static final byte PACKET = PacketIds.REMOTE_UPDATE;
    /** What the record says (PHONE_UPDATE). */
    static final int IDLE = 0, DOWNLOADING = 1, INSTALLING = 2, TAP_NEEDED = 3, FAILED = 4, REFUSED_ARMED = 5, CURRENT = 6;
    /** F-058: what the PC shows after "update failed - " when the app may not install apps (the PC knows the words). */
    static final String INSTALLS_NOT_ALLOWED = "allow \"Install unknown apps\" for DRAUPNE Flight on the phone, once by hand";
    /** A state other than IDLE is reported this long after it last changed (the PC shows the last one). */
    private static final long REPORT_MS = 30_000;
    /**
     * pc.38 (H9): INSTALLING and TAP_NEEDED are reported as long as they last (the PC refuses to arm meanwhile: the
     * install ends this app and the sticks with it), but no longer than this - an installer that never answers mustn't
     * keep the drone on the ground for good. F-070b: when it is no longer reported the install is taken back
     * ({@link #watch}) - the PC may arm from then on, and the prompt must not still be there to be tapped.
     */
    private static final long INSTALL_REPORT_MS = 5 * 60_000;
    static final String PREFS = "dd_remote_update", PENDING_KEY = "pending";

    private static volatile int state = IDLE, percent;
    private static volatile String text = "";
    private static volatile long changedAt;
    private static volatile boolean busy;

    private RemoteUpdate() {
    }

    static void set(int s, int pct, String t) {
        state = s;
        percent = Math.max(0, Math.min(100, pct));
        text = t == null ? "" : t;
        changedAt = System.currentTimeMillis();
    }

    /** The PC's REMOTE_UPDATE. Never blocks: the download runs on a thread of its own. */
    static void request(Context c, String version, String url, String sha256, Msp msp, Mavlink mavlink) {
        if (busy || c == null || version == null || url == null || sha256 == null) return;
        String current = MainActivity.versionName;
        if (current != null && !UpdateCheck.isNewer(version, current)) {
            set(CURRENT, 100, current);
            return;
        }
        // F-058 (the bench's first remote update, 2026-11-21): without "Install unknown apps" for this app Android puts
        // its own question up after the download - on a phone nobody can reach. Said at once, before 23 MB are fetched
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            log("Remote update to " + version + " refused: \"Install unknown apps\" is not allowed for this app");
            set(FAILED, 0, INSTALLS_NOT_ALLOWED);
            return;
        }
        if (FcArmedState.of(msp, mavlink) != FcArmedState.DISARMED) {
            set(REFUSED_ARMED, 0, "FC not known disarmed");
            return;
        }
        busy = true;
        set(DOWNLOADING, 0, version);
        log("Remote update to " + version + " asked by the PC");
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                AppUpdater.downloadAndInstallRemote(app, new UpdateCheck.Found(version, url, sha256),
                        pct -> set(DOWNLOADING, pct, version),
                        () -> FcArmedState.of(msp, mavlink) == FcArmedState.DISARMED,
                        () -> set(INSTALLING, 100, version)); // pc.38: before the APK goes to the installer, not after
            } catch (GuardedCopy.Stopped e) { // pc.38 (H9): armed, or not known disarmed, mid-download
                log("Remote update stopped: " + e.getMessage());
                set(REFUSED_ARMED, 0, e.getMessage());
                pending(app, false);
            } catch (IOException | RuntimeException e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                log("Remote update failed: " + why);
                set(FAILED, 0, why);
                pending(app, false);
            } finally {
                busy = false;
            }
        }, "remoteUpdate");
        t.setDaemon(true);
        t.start();
    }

    /** The app is to open again by itself after this update (AppReplacedReceiver). */
    static void pending(Context c, boolean on) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(PENDING_KEY, on).commit(); // pc.55: on the disk now - the install ends this process next
    }

    /** A remote update is under way (its download, or the APK going to the installer). */
    static boolean busy() {
        return busy;
    }

    static boolean takePending(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean on = p.getBoolean(PENDING_KEY, false);
        if (on) p.edit().putBoolean(PENDING_KEY, false).apply();
        return on;
    }

    /** Something to tell the PC: a state other than IDLE, changed lately. */
    static boolean reportDue() {
        long age = System.currentTimeMillis() - changedAt;
        boolean installing = state == INSTALLING || state == TAP_NEEDED;
        return state != IDLE && (busy || age < REPORT_MS || installing && age < INSTALL_REPORT_MS);
    }

    /**
     * F-070b, the service's timer once a second: an install that waits - for Android, or for the tap on its "update
     * this app?" - is taken back (AppUpdater.withdraw abandons its session; the rule is UpdateWithdraw) the moment the
     * flight controller is not known disarmed, and a remote one when this phone no longer tells the PC that it waits
     * ({@link #reportDue}: the PC may arm from then on). It waited for good: armed and flying, one tap on the prompt
     * installed the update and ended the app, and the sticks with it. Costs one read of a flag while nothing waits.
     */
    static void watch(Context c, boolean fcKnownDisarmed) {
        if (!AppUpdater.sessionWaits()) return;
        boolean remote = AppUpdater.sessionIsRemote();
        boolean waiting = state == INSTALLING || state == TAP_NEEDED;
        boolean reported = waiting && reportDue();
        switch (UpdateWithdraw.now(fcKnownDisarmed, remote, reported)) {
            case UpdateWithdraw.NOT_DISARMED:
                tookBackState = REFUSED_ARMED;
                tookBackText = remote && waiting ? "FC not known disarmed: update taken back" : null;
                AppUpdater.withdraw(c, "FC not known disarmed");
                break;
            case UpdateWithdraw.NO_LONGER_REPORTED:
                tookBackState = FAILED;
                // waiting for the tap, or for an installer that never answered (no prompt then: nothing to tap)
                tookBackText = !waiting ? null : (state == TAP_NEEDED ? "no tap on the phone in " : "not installed in ")
                        + INSTALL_REPORT_MS / 60_000 + " min: taken back";
                AppUpdater.withdraw(c, "the PC is no longer told that it waits");
                break;
            default:
                break;
        }
    }

    /** What the PC is told once Android says the install that was taken back is gone ({@link #tookBack}); null: nothing. */
    private static volatile int tookBackState;
    private static volatile String tookBackText;

    /**
     * Android answered "aborted" for the install this app took back (AppUpdateReceiver): only now is the PC told - an
     * install Android had already begun cannot be taken back, goes on, and is "installing" to the PC until it is done.
     */
    static void tookBack(Context c) {
        String t = tookBackText;
        tookBackText = null;
        if (t != null) set(tookBackState, 0, t);
        // not when it only made way for a newer update's install, which is to open the app again when it is done
        if (!AppUpdater.sessionUnderWay()) pending(c, false);
    }

    /** The PHONE_UPDATE record, last in its packet. */
    static void writeRecordTo(DataOutputStream out) throws IOException {
        PcLinkStats.writeRemoteUpdateTo(out, state, percent, text);
    }
}
