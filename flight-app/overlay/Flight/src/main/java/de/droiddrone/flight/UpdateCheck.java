/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
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

package de.droiddrone.flight;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is there a newer build of this app? Every DRAUPNE Link release on GitHub carries the phone app it installs
 * (DRAUPNE-Flight-&lt;version&gt;.apk), so one anonymous request for the latest release answers it. Only tells the
 * pilot; installing stays a tap (Android asks).
 */
final class UpdateCheck {
    static final String LATEST_API = "https://api.github.com/repos/MelonNO/draupne-link/releases/latest";
    // pc.19: only this app's own name - an asset called DroidDrone-Flight-*.apk would be another app's build
    private static final Pattern APK = Pattern.compile("^DRAUPNE-Flight-(.+)\\.apk$");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** The newer phone app found: its version, where to download it and its SHA-256 (null if GitHub gave none). */
    static final class Found {
        final String version;
        final String url;
        final String sha256;

        Found(String version, String url, String sha256) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    private UpdateCheck() {
    }

    /** null when there is nothing newer than current, or GitHub can't be reached. Blocking: not on the UI thread. */
    static Found newerThan(String current) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(LATEST_API).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
            }
            JSONArray assets = new JSONObject(body.toString("UTF-8")).getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                Matcher m = APK.matcher(a.getString("name"));
                if (m.matches() && isNewer(m.group(1), current)) {
                    return new Found(m.group(1), a.getString("browser_download_url"), sha256(a.optString("digest")));
                }
            }
        } catch (Exception e) {
            // offline or GitHub unreachable: nothing to show
        }
        return null;
    }

    /** GitHub gives each release asset a "digest" of "sha256:&lt;hex&gt;"; null when it is missing or another kind. */
    static String sha256(String digest) {
        if (digest == null || !digest.startsWith("sha256:")) return null;
        String hex = digest.substring("sha256:".length());
        return hex.matches("[0-9a-fA-F]{64}") ? hex : null;
    }

    /** Numbers compared in order: 2.3.0-pc.4 is newer than 2.3.0-pc.3, 2.3.1 than 2.3.0-pc.9. */
    static boolean isNewer(String candidate, String current) {
        List<Integer> a = numbers(candidate), b = numbers(current);
        if (a.isEmpty() || b.isEmpty()) return false;
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0, y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(v == null ? "" : v);
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }
}
