package de.droiddrone.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bench's internet: one small web server on loopback that answers for every site the app asks - map tiles
 * (drawn here: a grid with the tile's numbers, so a map picture shows which tiles it got), terrain heights, GitHub's
 * "latest release" and its downloads. {@link BenchDoors} sends the app's addresses here as
 * {@code http://127.0.0.1:port/<the real host>/<the real path>}. It can also be slow, answer errors, or be down.
 */
public final class FakeWeb implements AutoCloseable {
    /** STALL: the head of every answer and half its body, then nothing more on that connection, whatever the mood is later (a transfer stalled on a weak or changing link). */
    public enum Mood { OK, SLOW, ERRORS, DOWN, STALL }

    /** A request the app made: the real host and path it meant. */
    public record Request(long atMs, String host, String path) {
    }

    private static final Pattern ZXY = Pattern.compile("/(\\d{1,2})/(\\d+)/(\\d+)(?:\\.\\w+)?(?:\\?.*)?$");

    private HttpServer server;
    private final int port;
    private volatile Mood mood = Mood.OK;
    private volatile boolean closed;
    private volatile long slowMs = 3000;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** GitHub's answer to "the latest release"; null = HTTP 404 (no release yet). */
    private volatile String latestRelease;
    private volatile double groundM = 120;

    public FakeWeb() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        port = server.getAddress().getPort();
        start(server);
    }

    private void start(HttpServer s) {
        s.createContext("/", this::answer);
        s.setExecutor(Executors.newCachedThreadPool(r -> { // not a fixed few: a stalled answer holds its thread
            Thread t = new Thread(r, "benchWeb");
            t.setDaemon(true);
            return t;
        }));
        s.start();
    }

    /** The loopback address that stands in for this real one. */
    public String standInFor(String url) {
        URI u = URI.create(url);
        String rest = u.getRawPath() == null ? "" : u.getRawPath();
        if (u.getRawQuery() != null) rest += "?" + u.getRawQuery();
        return "http://127.0.0.1:" + port + "/" + u.getHost() + rest;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public long requestsTo(String hostPart) {
        return requests.stream().filter(r -> r.host().contains(hostPart)).count();
    }

    /** How the internet behaves from now on. DOWN: nothing listens (connection refused), as with no network. */
    public synchronized void mood(Mood m) throws IOException {
        if (m == Mood.DOWN && server != null) {
            server.stop(0);
            server = null;
        } else if (m != Mood.DOWN && server == null) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
            start(server);
        }
        mood = m;
    }

    public void slowMs(long ms) {
        slowMs = ms;
    }

    /** The ground's height everywhere, for the height services and the terrain tiles. */
    public void ground(double metres) {
        groundM = metres;
    }

    /**
     * GitHub has this release as its latest. installer null = a release without an installer.
     *
     * @return the address the app will download the installer from (to compare with what it asks)
     */
    public String release(String version, byte[] installer) {
        String page = "https://github.com/MelonNO/draupne-link/releases/tag/v" + version;
        StringBuilder json = new StringBuilder("{\"tag_name\":\"v").append(version).append("\",\"html_url\":\"").append(page).append("\",\"assets\":[");
        String address = null;
        if (installer != null) {
            String name = "DRAUPNE-Link-Setup-" + version + ".exe";
            address = "https://github.com/MelonNO/draupne-link/releases/download/v" + version + "/" + name;
            files.put("github.com/MelonNO/draupne-link/releases/download/v" + version + "/" + name, installer);
            json.append("{\"name\":\"").append(name).append("\",\"browser_download_url\":\"").append(address).append("\",\"size\":")
                    .append(installer.length).append(",\"digest\":\"sha256:").append(sha256(installer)).append("\"}");
        }
        latestRelease = json.append("]}").toString();
        return address;
    }

    /** Any other file the app may download: host + path as in the real address, without "https://". */
    public void file(String hostAndPath, byte[] content) {
        files.put(hostAndPath, content);
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void answer(HttpExchange x) throws IOException {
        try (x) {
            String full = x.getRequestURI().toString(); // /host/path?query
            int slash = full.indexOf('/', 1);
            String host = slash < 0 ? full.substring(1) : full.substring(1, slash);
            String path = slash < 0 ? "/" : full.substring(slash);
            requests.add(new Request(System.currentTimeMillis(), host, path));
            Mood m = mood;
            if (m == Mood.SLOW) {
                try {
                    Thread.sleep(slowMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (m == Mood.ERRORS) {
                send(x, 500, "text/plain", "bench: server error".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String noQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            byte[] file = files.get(host + noQuery);
            if (file != null) {
                send(x, 200, "application/octet-stream", file);
            } else if (host.equals("api.github.com") && path.endsWith("/releases/latest")) {
                String r = latestRelease;
                if (r == null) send(x, 404, "application/json", "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8));
                else send(x, 200, "application/json", r.getBytes(StandardCharsets.UTF_8));
            } else if (host.equals("ws.geonorge.no")) {
                send(x, 200, "application/json", ("{\"koordsys\":4258,\"punkter\":[{\"datakilde\":\"dtm1\",\"terreng\":\"bakke\",\"x\":0,\"y\":0,\"z\":"
                        + groundM + "}]}").getBytes(StandardCharsets.UTF_8));
            } else if (host.equals("api.open-meteo.com")) {
                send(x, 200, "application/json", ("{\"elevation\":[" + groundM + "]}").getBytes(StandardCharsets.UTF_8));
            } else if (host.equals("api.ipify.org")) {
                send(x, 200, "text/plain", "127.0.0.1".getBytes(StandardCharsets.UTF_8));
            } else {
                Matcher t = ZXY.matcher(path);
                if (t.find()) {
                    boolean terrain = host.contains("elevation-tiles") || path.contains("terrarium");
                    send(x, 200, "image/png", terrain ? terrainTile() : mapTile(host, t.group(1), t.group(2), t.group(3)));
                } else {
                    send(x, 404, "text/plain", ("bench: nothing at " + host + path).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, body.length);
        if (mood == Mood.STALL && body.length > 1) {
            x.getResponseBody().write(body, 0, body.length / 2);
            x.getResponseBody().flush();
            long end = System.currentTimeMillis() + 600_000;
            // held also after the mood is good again: a stalled transfer does not come to life because new ones work
            while (!closed && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            return; // the connection is closed with half an answer sent
        }
        x.getResponseBody().write(body);
    }

    /** A pale tile with a grid and its numbers: enough to see on a map picture which tiles came and where. */
    private static byte[] mapTile(String host, String a, String b, String c) throws IOException {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xDDE6D5));
        g.fillRect(0, 0, 256, 256);
        g.setColor(new Color(0xB9C7AD));
        for (int i = 0; i < 256; i += 64) {
            g.drawLine(i, 0, i, 255);
            g.drawLine(0, i, 255, i);
        }
        g.setColor(new Color(0x7C8A70));
        g.drawRect(0, 0, 255, 255);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString(a + " / " + b + " / " + c, 8, 18);
        g.drawString(host.length() > 30 ? host.substring(0, 30) : host, 8, 34);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Terrarium: height = R * 256 + G + B / 256 - 32768; flat ground at the set height. */
    private byte[] terrainTile() throws IOException {
        int v = (int) Math.round(groundM + 32768);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int rgb = ((v >> 8) & 0xFF) << 16 | (v & 0xFF) << 8;
        for (int y = 0; y < 256; y++) for (int xx = 0; xx < 256; xx++) img.setRGB(xx, y, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (server != null) server.stop(0);
        server = null;
    }
}
