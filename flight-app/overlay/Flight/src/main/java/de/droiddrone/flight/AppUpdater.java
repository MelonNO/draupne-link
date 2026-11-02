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
        File d = dir(c);
        cleanUp(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no room for the download");
        File apk = new File(d, "update.apk");
        try {
            download(found, apk, progress);
            if (DDService.isRunning) throw new IOException(c.getString(R.string.dd_update_connected)); // connected while it downloaded
            progress.update(c.getString(R.string.dd_update_installing));
            install(c, apk);
        } finally {
            if (apk.exists() && !apk.delete()) log("Could not delete " + apk);
        }
    }

    private static void download(UpdateCheck.Found found, File apk, Progress progress) throws IOException {
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
        long done = 0;
        int shown = -1;
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent / 5 != shown / 5) {
                    shown = percent;
                    progress.update(percent < 0 ? "Downloading the update..." : "Downloading the update... " + percent + "%");
                }
            }
        }
        String got = hex(sha.digest());
        if (found.sha256 != null && !found.sha256.equalsIgnoreCase(got)) {
            throw new IOException("the download doesn't match the release's SHA-256");
        }
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_STATUS).setClass(c, AppUpdateReceiver.class);
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
