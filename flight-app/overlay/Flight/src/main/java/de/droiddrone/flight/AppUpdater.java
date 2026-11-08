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
        File d = dir(c);
        cleanUp(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no room to download");
        File apk = new File(d, "update.apk");
        try {
            // pc.38: and stopped as soon as the PC connects (the download would share the link with the sticks)
            download(found, apk, progress, null, () -> !DDService.isRunning, c.getString(R.string.dd_update_connected));
            if (DDService.isRunning) throw new IOException(c.getString(R.string.dd_update_connected)); // connected while it downloaded
            progress.update(c.getString(R.string.dd_update_installing));
            install(c, apk, false);
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
            if (!disarmed.getAsBoolean()) throw new GuardedCopy.Stopped("FC not known disarmed: not installed");
            installing.run();
            RemoteUpdate.pending(c, true); // opens again by itself once updated
            install(c, apk, true);
        } finally {
            if (apk.exists() && !apk.delete()) log("Could not delete " + apk);
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

    private static void install(Context c, File apk, boolean remote) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        // Android 12 on: no "update this app?" when this app installed its own last update (else Android asks anyway)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_STATUS).setClass(c, AppUpdateReceiver.class).putExtra(EXTRA_REMOTE, remote);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, status, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(id);
            throw e;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
