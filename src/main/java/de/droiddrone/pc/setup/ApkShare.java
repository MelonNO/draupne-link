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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Gets the DroidDrone Flight app onto the phone: downloads the official release zip from GitHub, checks its SHA-256,
 * unpacks the APK and serves it from a tiny web server that only answers the local network and Tailscale.
 */
public class ApkShare implements AutoCloseable {
    public static final String VERSION = "2.3.0";
    public static final String ZIP_URL = "https://github.com/DroidDrone/DroidDrone/releases/download/v2.3.0/Flight-release_v2.3.0.zip";
    /** SHA-256 of Flight-release_v2.3.0.zip as published on 2024-12-08. */
    public static final String ZIP_SHA256 = "80198775a4f64bc81bd158ae66cbaba0678ba715336264f436168e3a4029f003";
    public static final String APK_ENTRY = "Flight-release.apk";
    public static final String APK_NAME = "DroidDrone-Flight-" + VERSION + ".apk";
    public static final int PORT = 8765;

    private HttpServer server;

    /** Downloads and verifies the APK once; later calls return the cached file. */
    public static Path prepareApk(Path downloadsDir) throws IOException, InterruptedException {
        Path apk = downloadsDir.resolve(APK_NAME);
        if (Files.isRegularFile(apk) && Files.size(apk) > 0) return apk;
        Path zip = downloadsDir.resolve("Flight-release_v" + VERSION + ".zip.part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(ZIP_URL)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(zip));
        if (response.statusCode() != 200) throw new IOException("GitHub answered HTTP " + response.statusCode());
        String hash = sha256(zip);
        if (!ZIP_SHA256.equalsIgnoreCase(hash)) {
            Files.deleteIfExists(zip);
            throw new IOException("Downloaded file failed its integrity check (SHA-256 " + hash + ") - not using it");
        }
        Path tmp = downloadsDir.resolve(APK_NAME + ".part");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(APK_ENTRY)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(zip);
                    return apk;
                }
            }
        }
        throw new IOException(APK_ENTRY + " not found in the release zip");
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
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "apkShare");
            t.setDaemon(true);
            return t;
        }));
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
                        + "<title>DroidDrone Flight</title></head><body style='font-family:sans-serif;padding:24px'>"
                        + "<h2>DroidDrone Flight " + VERSION + "</h2><p><a style='font-size:22px' href='/" + APK_NAME
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
    }
}
