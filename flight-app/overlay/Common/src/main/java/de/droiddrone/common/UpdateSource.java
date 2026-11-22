/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.regex.Pattern;

/**
 * DRAUPNE Flight: where the phone app downloads a newer build of itself from, and which download it installs.
 * <ul>
 * <li>{@link #fromReleases}: only from this project's GitHub release downloads - where the PC's pinned phone app is
 * (ApkShare.APK_URL, the flight-&lt;version&gt; release) and where the phone's own update check finds it (an asset of
 * the latest DRAUPNE Link release). Before, the phone downloaded from whatever address a session's REMOTE_UPDATE named.</li>
 * <li>{@link #newer}: the downloaded APK is this app's package and its own version code - read from the file before it
 * goes to Android's installer - is newer than the installed one's. The version a PC's REMOTE_UPDATE names is not what
 * decides any more (it may still say "already that version" before anything is fetched).</li>
 * </ul>
 * The SHA-256 is checked as before; Android checks the signer as ever. Pure: the phone asks it, the PC's tests check it.
 */
public final class UpdateSource {
    /** This project's GitHub release downloads: {@code <prefix><tag>/<asset>.apk}. */
    public static final String RELEASE_DOWNLOADS = "https://github.com/MelonNO/draupne-link/releases/download/";
    /** A tag and an APK asset's name: letters, digits, dot, dash and underscore - one of each, nothing after them. */
    private static final Pattern TAG_AND_APK = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}/[A-Za-z0-9_][A-Za-z0-9._-]{0,199}\\.apk");

    private UpdateSource() {
    }

    /** Whether an APK may be fetched from this address: this project's release downloads, a tag and an APK, nothing more. */
    public static boolean fromReleases(String url) {
        if (url == null || !url.startsWith(RELEASE_DOWNLOADS)) return false;
        String rest = url.substring(RELEASE_DOWNLOADS.length());
        return TAG_AND_APK.matcher(rest).matches() && !rest.contains("..");
    }

    /**
     * Whether the downloaded APK may be installed.
     * @param apkPackage           the package the APK file says (null: not readable as an APK)
     * @param apkVersionCode       its version code, as the file says (negative: not read)
     * @param ownPackage           this app's package
     * @param installedVersionCode the installed app's version code
     */
    public static boolean newer(String apkPackage, long apkVersionCode, String ownPackage, long installedVersionCode) {
        return apkPackage != null && apkPackage.equals(ownPackage) && apkVersionCode >= 0 && apkVersionCode > installedVersionCode;
    }
}
