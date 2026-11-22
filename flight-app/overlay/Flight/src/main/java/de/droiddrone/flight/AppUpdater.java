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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import de.droiddrone.common.GuardedCopy;
import de.droiddrone.common.UpdateSource;
import de.droiddrone.common.UpdateWithdraw;

import static de.droiddrone.common.Logcat.log;

/**
 * Updates this app from inside it. The browser left every version's installer (DRAUPNE-Flight-x.apk) in the phone's
 * Downloads, where Android doesn't let an app delete another app's files. Here the APK goes into this app's own cache,
 * is checked against the SHA-256 GitHub publishes for it, handed to Android's installer (its usual "update this app?"
 * prompt) and deleted at once; anything left from an update cut short goes at the next start ({@link #cleanUp}).
 */
final class AppUpdater {
    /** The installer's answer comes to {@link AppUpdateReceiver}. */
    static final String ACTION_STATUS = "de.droiddrone.flight.pc.UPDATE_STATUS";

    interface Progress {
        void update(String text);
    }

    private AppUpdater() {
    }

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "updates");
    }

    /** Deletes downloaded installers this app left behind (an update cut short). Never touches Downloads. */
    static void cleanUp(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) log("Could not delete " + f);
        }
    }

    /**
     * Downloads the newer app, checks it and hands it to Android's installer, which asks the pilot. Blocking: not on
     * the UI thread. The downloaded file is gone when this returns (the installer keeps its own copy).
     * Never while connected: installing ends this app, and with it the sticks to the flight controller.
     */
    static void downloadAndInstall(Context c, UpdateCheck.Found found, Progress progress) throws IOException {
        // DRAUPNE Link (pc.19): nothing is installed that can't be checked - a release GitHub gave no SHA-256 for
        // (or not a sha256 one) isn't downloaded at all
        if (found.sha256 == null) throw new IOException(c.getString(R.string.dd_update_no_digest));
        if (!UpdateSource.fromReleases(found.url)) throw new IOException("not from the project's release downloads");
        File d = dir(c);
        cleanUp(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no room to download");
        File apk = new File(d, "update.apk");
        try {
            // pc.38: and stopped as soon as the PC connects (the download would share the link with the sticks)
            download(found, apk, progress, null, () -> !DDService.isRunning, c.getString(R.string.dd_update_connected));
            if (DDService.isRunning) throw new IOException(c.getString(R.string.dd_update_connected)); // connected while it downloaded
            progress.update(c.getString(R.string.dd_update_installing));
            install(c, apk, false, () -> true);
        } finally {
            if (apk.exists() && !apk.delete()) log("Could not delete " + apk);
        }
    }

    /**
     * pc.32, the PC's REMOTE_UPDATE ({@link RemoteUpdate}): the same, while connected - the PC asked, and the flight
     * controller is checked disarmed again just before the install, which ends this app and the link with it.
     * Android 12 on: no tap needed when this app installed its own last update.
     * pc.38 (H9): the flight controller is asked before every 64 KB of the download too, which stops
     * ({@link GuardedCopy.Stopped}) the moment it isn't known disarmed - 50 MB over 4G must not crowd the sticks.
     * @param installing told right before the APK goes to Android's installer (the PC refuses to arm from then on)
     */
    static void downloadAndInstallRemote(Context c, UpdateCheck.Found found, java.util.function.IntConsumer percent,
                                         java.util.function.BooleanSupplier disarmed, Runnable installing) throws IOException {
        if (found.sha256 == null) throw new IOException(c.getString(R.string.dd_update_no_digest));
        File d = dir(c);
        cleanUp(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no room to download");
        File apk = new File(d, "update.apk");
        try {
            download(found, apk, text -> { }, percent, disarmed, "FC not known disarmed: download stopped");
            checkNewerBuild(c, apk); // the APK's own word, not the version the PC named (UpdateSource)
            if (!disarmed.getAsBoolean()) throw new GuardedCopy.Stopped("FC not known disarmed: not installed");
            installing.run();
            RemoteUpdate.pending(c, true); // opens again by itself once updated
            install(c, apk, true, disarmed);
        } finally {
            if (apk.exists() && !apk.delete()) log("Could not delete " + apk);
        }
    }

    /**
     * The downloaded file is a newer build of this app: its package and its own version code, read from the APK
     * (Android's archive reader), against the installed app's (UpdateSource.newer). Otherwise it is not installed.
     */
    @SuppressWarnings("deprecation")
    private static void checkNewerBuild(Context c, File apk) throws IOException {
        android.content.pm.PackageManager pm = c.getPackageManager();
        android.content.pm.PackageInfo file = pm.getPackageArchiveInfo(apk.getPath(), 0);
        long fileCode = file == null ? -1 : Build.VERSION.SDK_INT >= 28 ? file.getLongVersionCode() : file.versionCode;
        long ownCode;
        try {
            android.content.pm.PackageInfo own = pm.getPackageInfo(c.getPackageName(), 0);
            ownCode = Build.VERSION.SDK_INT >= 28 ? own.getLongVersionCode() : own.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IOException("this app's own version unknown");
        }
        if (!de.droiddrone.common.UpdateSource.newer(file == null ? null : file.packageName, fileCode, c.getPackageName(), ownCode)) {
            throw new IOException(file == null ? "the download is no APK" : !c.getPackageName().equals(file.packageName) ? "the APK is another app"
                    : "the APK is not newer (build " + fileCode + ", installed " + ownCode + ")");
        }
    }

    private static void download(UpdateCheck.Found found, File apk, Progress progress, java.util.function.IntConsumer percentOut,
                                 java.util.function.BooleanSupplier mayGoOn, String stopped) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(found.url).openConnection();
        conn.setInstanceFollowRedirects(true); // GitHub sends the file from another https host
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (conn.getResponseCode() != 200) throw new IOException("download failed, HTTP " + conn.getResponseCode());
        long total = conn.getContentLengthLong();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            GuardedCopy.copy(in, out, sha, total, percent -> {
                if (percentOut != null) percentOut.accept(Math.max(0, percent));
                progress.update(percent < 0 ? "Downloading..." : "Downloading... " + percent + "%");
            }, mayGoOn, stopped);
        } finally {
            conn.disconnect(); // pc.38: a stopped download lets go of its connection at once
        }
        String got = hex(sha.digest());
        if (found.sha256 == null || !found.sha256.equalsIgnoreCase(got)) {
            throw new IOException("SHA-256 mismatch");
        }
    }

    /** Extra on the installer's answer: the PC asked for this update. */
    static final String EXTRA_REMOTE = "de.droiddrone.flight.pc.REMOTE";

    private static void install(Context c, File apk, boolean remote, java.util.function.BooleanSupplier disarmed) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        // Android 12 on: no "update this app?" when this app installed its own last update (else Android asks anyway)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        sessionCreated(c, id, remote); // F-070b: its id is kept - it is what is abandoned, should the install have to be taken back
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            // pc.55: asked once more right before the commit - copying the APK into the session takes seconds, and an
            // arm in that time had the install end the app (and the sticks) on an armed drone
            if (!disarmed.getAsBoolean()) throw new GuardedCopy.Stopped("FC not known disarmed: not installed");
            Intent status = new Intent(ACTION_STATUS).setClass(c, AppUpdateReceiver.class).putExtra(EXTRA_REMOTE, remote)
                    .putExtra(EXTRA_SESSION, id);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            committed = true; // F-070b: from here it waits - for Android, or for the tap on its prompt (RemoteUpdate.watch)
            session.commit(PendingIntent.getBroadcast(c, id, status, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            forget(c, id);
            try {
                installer.abandonSession(id);
            } catch (RuntimeException gone) {
                // F-070b: taken back in the moment of its commit (withdraw): there is no such session any more
            }
            throw e;
        }
    }

    // ---- F-070b: the install session that waits, and taking it back (the rule: UpdateWithdraw)

    /** Extra on the installer's answer: the session it is about. */
    static final String EXTRA_SESSION = "de.droiddrone.flight.pc.SESSION";
    private static final int NO_SESSION = -1;
    /** Where the session's id is kept for the app's next start (RemoteUpdate's own store). */
    private static final String SESSION_KEY = "session";
    /** The session this run of the app created and has not heard the end of. */
    private static volatile int sessionId = NO_SESSION;
    /** ... and it was committed: Android has it, and may be asking for the tap. */
    private static volatile boolean committed;
    private static volatile boolean sessionRemote;
    /** The session this app took back itself: Android's "aborted" for it is not a tap on Cancel. */
    private static volatile int withdrawnId = NO_SESSION;

    /** An install of this app's waits - for Android, or for the tap on "update this app?". */
    static boolean sessionWaits() {
        return committed && sessionId != NO_SESSION;
    }

    /** This run of the app has an install of its own under way: being copied, or waiting. */
    static boolean sessionUnderWay() {
        return sessionId != NO_SESSION;
    }

    /** The one that waits was asked for by the PC. */
    static boolean sessionIsRemote() {
        return sessionRemote;
    }

    private static synchronized void sessionCreated(Context c, int id, boolean remote) {
        if (sessionId != NO_SESSION && sessionId != id) withdraw(c, "another update's install begins");
        sessionId = id;
        committed = false;
        sessionRemote = remote;
        c.getSharedPreferences(RemoteUpdate.PREFS, Context.MODE_PRIVATE).edit().putInt(SESSION_KEY, id).commit();
    }

    private static synchronized void forget(Context c, int id) {
        if (sessionId == id) {
            sessionId = NO_SESSION;
            committed = false;
        }
        android.content.SharedPreferences p = c.getSharedPreferences(RemoteUpdate.PREFS, Context.MODE_PRIVATE);
        if (p.getInt(SESSION_KEY, NO_SESSION) == id) p.edit().remove(SESSION_KEY).commit();
    }

    /**
     * The install that waits is taken back: exactly the session this app created is abandoned - Android's prompt for
     * it is then void, a tap on it installs nothing. False when none waits.
     */
    static synchronized boolean withdraw(Context c, String why) {
        int id = sessionId;
        if (id == NO_SESSION) return false;
        withdrawnId = id; // before Android is asked: its answer may come at once
        forget(c, id); // asked once: an install Android has already begun cannot be taken back, and goes on
        try {
            c.getPackageManager().getPackageInstaller().abandonSession(id);
            log("Update taken back (" + why + "): its install session was abandoned");
        } catch (RuntimeException e) { // no such session any more: it was installed, or cancelled on the phone
            log("Update taken back (" + why + "): its install had ended already (" + e + ")");
        }
        return true;
    }

    /** The session Android answers about is one this app took back itself. */
    static boolean tookBack(Intent answer) {
        int id = answer.getIntExtra(EXTRA_SESSION, answer.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION));
        return id != NO_SESSION && id == withdrawnId;
    }

    /** Android's last word on a session came (AppUpdateReceiver): it waits no more. True when this app took it back itself. */
    static synchronized boolean sessionEnded(Context c, Intent answer) {
        int id = answer.getIntExtra(EXTRA_SESSION, answer.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION));
        if (id == NO_SESSION) return false;
        forget(c, id);
        return id == withdrawnId;
    }

    /**
     * At the app's start: an install an earlier run of the app left waiting is taken back - its prompt could still be
     * on the screen, or come up, with nobody watching for the arm any more.
     */
    static void withdrawLeftOver(Context c) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences(RemoteUpdate.PREFS, Context.MODE_PRIVATE);
            int kept = p.getInt(SESSION_KEY, NO_SESSION);
            if (!UpdateWithdraw.leftOver(kept, sessionId, NO_SESSION)) return;
            p.edit().remove(SESSION_KEY).commit();
            c.getPackageManager().getPackageInstaller().abandonSession(kept);
            log("An update's install left waiting by the app's last run was taken back");
        } catch (RuntimeException e) {
            // no such session any more (it was installed, or cancelled): nothing waits
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
