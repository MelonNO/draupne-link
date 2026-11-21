package de.droiddrone.pc.setup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Gets the DRAUPNE Flight app onto the phone: downloads this project's build of it (upstream 2.3.0 plus more
 * flight controllers and the PC app's look, source in flight-app/) from this project's GitHub release, checks its
 * SHA-256, and serves it from a tiny web server that only answers the local network and Tailscale.
 */
public class ApkShare implements AutoCloseable {
    // ---- RELEASE PIN: the phone app this PC app hands out. At every release that ships a new phone app, set both
    // ---- to it: its versionName and the SHA-256 flight-app/build.ps1 printed (the flight-VERSION release's APK).
    public static final String VERSION = bench(0, "2.3.0-pc.55");
    /** SHA-256 of the APK built by flight-app/build.ps1 and attached to the flight-VERSION release. */
    public static final String APK_SHA256 = bench(1, "9ee585c10b39d753879076c77ca61185ef45b0e3fbedadfef0137ca76a0076b7");
    // ---- end of the pin
    public static final String APK_NAME = "DRAUPNE-Flight-" + VERSION + ".apk";
    public static final String APK_URL = bench(2, "https://github.com/MelonNO/draupne-link/releases/download/flight-" + VERSION + "/" + APK_NAME);

    /**
     * The test bench's own phone app (F-054's emulator test, 2026-11-21): {@code -Ddraupne.bench.phoneApp=version|sha256|url}
     * puts a test build in the pin's place, so UPDATE PHONE APP can be run end to end. Unset - always, outside the
     * bench - the pin above as it stands. The phone checks the SHA-256 it is sent and Android the signature, as ever.
     */
    private static String bench(int part, String pinned) {
        return bench(System.getProperty("draupne.bench.phoneApp"), part, pinned);
    }

    static String bench(String s, int part, String pinned) {
        if (s == null || s.isBlank()) return pinned;
        String[] p = s.split("\\|");
        return p.length == 3 && !p[part].isBlank() ? p[part].trim() : pinned;
    }
    public static final int PORT = 8765;

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;

    /** A download that stands still this long is given up; one that keeps coming may take this long (tests make them short). */
    static long DOWNLOAD_STALL_MS = 60_000, DOWNLOAD_WHOLE_MS = 3 * 3_600_000L;

    /** Downloads and verifies the APK once; later calls return the cached file. */
    public static Path prepareApk(Path downloadsDir) throws IOException, InterruptedException {
        Path apk = downloadsDir.resolve(APK_NAME);
        if (Files.isRegularFile(apk) && APK_SHA256.equalsIgnoreCase(sha256(apk))) return apk;
        Files.createDirectories(downloadsDir);
        Path tmp = downloadsDir.resolve(APK_NAME + ".part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        Files.deleteIfExists(tmp); // what an earlier try left: never the head of a file under a new one's tail
        // as long as it takes while it keeps coming; given up when it stands still for a minute (15 min for all of it
        // was too short for 23 MB on a weak link, and each new try began at nothing)
        HttpResponse<Path> response = de.droiddrone.pc.Net.sendMoving(client, HttpRequest.newBuilder(URI.create(de.droiddrone.pc.Outside.url(APK_URL))).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp), DOWNLOAD_STALL_MS, DOWNLOAD_WHOLE_MS);
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("GitHub answered HTTP " + response.statusCode());
        }
        String hash = sha256(tmp);
        if (!APK_SHA256.equalsIgnoreCase(hash)) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed its SHA-256 check (" + hash + ")");
        }
        Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        return apk;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void start(Path apk) throws IOException {
        start(apk, PORT);
    }

    /**
     * The same on a port of the caller's choosing: the tests', which must never bind {@link #PORT} - a DRAUPNE Link
     * serving the phone app on this PC holds it. The app itself always serves on PORT (its firewall rule and the
     * addresses it shows name that one).
     */
    void start(Path apk, int port) throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "apkShare");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, apk));
        server.start();
    }

    private void handle(HttpExchange exchange, Path apk) throws IOException {
        try (exchange) {
            if (!isAllowed(exchange.getRemoteAddress().getAddress())) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] page = ("<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                        + "<title>DRAUPNE Flight</title></head><body style='font-family:sans-serif;padding:24px'>"
                        + "<h2>DRAUPNE Flight " + VERSION + "</h2><p><a style='font-size:22px' href='/" + APK_NAME
                        + "'>Download the app</a></p><p>Then open the downloaded file and allow installing.</p></body></html>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                exchange.getResponseBody().write(page);
            } else if (path.equals("/" + APK_NAME)) {
                exchange.getResponseHeaders().add("Content-Type", "application/vnd.android.package-archive");
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + APK_NAME + "\"");
                exchange.sendResponseHeaders(200, Files.size(apk));
                try (OutputStream out = exchange.getResponseBody()) {
                    Files.copy(apk, out);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    /** Only the local network and Tailscale (100.64.0.0/10) may download. */
    static boolean isAllowed(InetAddress a) {
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || NetInfo.isTailscale(a);
    }

    /** Download URLs for every address the phone could reach this PC on; Tailscale addresses first. */
    public static List<String> urls() {
        List<String> urls = new ArrayList<>();
        for (String ip : NetInfo.tailscaleAddresses()) urls.add("http://" + ip + ":" + PORT + "/");
        for (String ip : NetInfo.lanAddresses()) urls.add("http://" + ip + ":" + PORT + "/");
        return urls;
    }

    public boolean isRunning() {
        return server != null;
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        server = null;
        if (executor != null) executor.shutdownNow(); // or two threads stay behind per start
        executor = null;
    }
}
