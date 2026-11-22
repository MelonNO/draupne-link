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

package de.droiddrone.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE UPDATE TEST'S BUILDS ONLY - NEVER RELEASED (flight-app/bench-overlay/README.md, "The update test's builds").
 * <p>
 * A released app takes a remote update only from the project's release downloads ({@link UpdateSource#fromReleases}).
 * The bench's update test (PhoneUpdateScenario) serves its newer test build from a web server on the PC that runs it,
 * over plain http: the emulator reaches that PC's loopback as 10.0.2.2, a real phone as its own 127.0.0.1 through
 * {@code adb reverse}. {@code flight-app\build.ps1 -UpdateTest} lays this file over a source copy of its own and has
 * UpdateSource ask it first, in that copy alone. What it takes: http, one of those two addresses, a port, one APK's
 * name - nothing more.
 * <p>
 * It carries {@link #MARKER} in code that runs: flight-app/scan-apk.ps1 refuses an APK that has it unless it is asked
 * for an update test's build, and PC test ReleaseHasNoBenchCodeTest fails when anything of it is in flight-app/overlay.
 */
public final class UpdateTestSource {
    /** In the code of every app that has this class; an APK is scanned for it (flight-app/scan-apk.ps1). */
    public static final String MARKER = "DRAUPNE-UPDATE-TEST";
    /** The bench's own server: plain http, the PC as the emulator or a real phone reaches it, a port, an APK's name. */
    private static final Pattern BENCH_SERVER = Pattern.compile("http://(?:10\\.0\\.2\\.2|127\\.0\\.0\\.1):([1-9][0-9]{0,4})/[A-Za-z0-9_][A-Za-z0-9._-]{0,199}\\.apk");

    private UpdateTestSource() {
    }

    /** Whether a remote update may be fetched from this address as well: the bench's own server, nothing else. */
    public static boolean takes(String url) {
        if (url == null) return false;
        Matcher m = BENCH_SERVER.matcher(url);
        if (!m.matches() || url.contains("..") || Integer.parseInt(m.group(1)) > 65535) return false;
        Log.log(MARKER + ": the update is taken from the bench's own server, " + url + " (a test build: a released app takes it from the project's release downloads only)");
        return true;
    }
}
