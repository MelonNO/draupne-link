package de.droiddrone.pc.setup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub which DRAUPNE Link release is the latest (one anonymous request, no account, nothing sent but the
 * request itself). The app only tells the pilot; it downloads and installs only when the pilot presses UPDATE
 * ({@link SelfUpdate}).
 */
public final class UpdateCheck {
    public static final String LATEST_API = "https://api.github.com/repos/MelonNO/draupne-link/releases/latest";
    /** Installers are only ever downloaded from this project's own releases. */
    static final String DOWNLOADS = "https://github.com/MelonNO/draupne-link/releases/download/";
    /** The project's releases page: what UPDATE opens in the browser when a release's own page is not to be had. */
    public static final String RELEASES = "https://github.com/MelonNO/draupne-link/releases";
    /** A release's own page: https, under the project's releases, a tag's plain characters after it. */
    private static final Pattern RELEASE_PAGE = Pattern.compile(Pattern.quote(RELEASES + "/") + "[A-Za-z0-9._+/-]{1,200}");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /**
     * version without the leading "v", e.g. "1.0.6"; pageUrl is the release page ({@link #releasePage}: one of this
     * project's own, or the project's releases page). The installer's download address,
     * size and SHA-256 (as GitHub lists them) are null / -1 when the release has no installer to update with.
     */
    public record Release(String version, String pageUrl, String installerUrl, long installerSize, String installerSha256) {
        public boolean canInstall() {
            return installerUrl != null && installerSize > 0 && installerSha256 != null;
        }
    }

    private UpdateCheck() {
    }

    public static Release latest() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> r = de.droiddrone.pc.Net.send(client, HttpRequest.newBuilder(URI.create(de.droiddrone.pc.Outside.url(LATEST_API))).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString(), 25_000);
        if (r.statusCode() != 200) throw new IOException("GitHub answered HTTP " + r.statusCode());
        return parse(r.body());
    }

    static Release parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String tag = o.get("tag_name").getAsString();
        String url = null, sha = null;
        long size = -1;
        if (o.has("assets")) {
            for (JsonElement e : o.getAsJsonArray("assets")) {
                JsonObject a = e.getAsJsonObject();
                String name = a.get("name").getAsString();
                String u = text(a, "browser_download_url");
                if (!name.startsWith("DRAUPNE-Link-Setup-") || !name.endsWith(".exe") || u == null || !u.startsWith(DOWNLOADS)) continue;
                String digest = text(a, "digest");
                url = u;
                size = a.has("size") ? a.get("size").getAsLong() : -1;
                sha = digest != null && digest.matches("sha256:[0-9a-fA-F]{64}") ? digest.substring(7).toLowerCase() : null;
            }
        }
        return new Release(tag.startsWith("v") ? tag.substring(1) : tag, releasePage(text(o, "html_url")), url, size, sha);
    }

    /**
     * The page UPDATE may open in the browser: GitHub's address for the release when it is an https page under this
     * project's own releases (".../releases/tag/v1.0.72"), written with a tag's plain characters and no step up or
     * aside in it; with any other address, or none, the project's releases page.
     */
    static String releasePage(String fromGitHub) {
        boolean own = fromGitHub != null && RELEASE_PAGE.matcher(fromGitHub).matches()
                && !fromGitHub.contains("..") && !fromGitHub.substring(RELEASES.length()).contains("//");
        return own ? fromGitHub : RELEASES;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Is candidate a later version than current? Numbers compared in order: 1.0.10 > 1.0.9, 2.3.0-pc.4 > 2.3.0-pc.3. */
    public static boolean isNewer(String candidate, String current) {
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
