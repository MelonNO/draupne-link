package de.droiddrone.pc.setup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Drives the Tailscale CLI: install (winget), status, sign-in, and path checks (direct vs relayed). */
public final class Tailscale {
    public static final String PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.tailscale.ipn";
    private static final Pattern PONG = Pattern.compile("pong from .* via (\\S+) in (\\d+)ms");
    private static volatile Process loginProcess;

    public record Peer(String hostName, String os, boolean online, String ip, String curAddr, String relay) {
        /** Direct = a peer-to-peer UDP path; otherwise traffic goes through Tailscale's DERP relays (TCP, slower). */
        public boolean isDirect() {
            return curAddr != null && !curAddr.isEmpty();
        }

        public boolean isAndroid() {
            return "android".equalsIgnoreCase(os);
        }
    }

    public record Status(boolean installed, boolean running, String backendState, String authUrl,
                         String selfIp, String selfName, List<Peer> peers) {
        public boolean signedIn() {
            return "Running".equals(backendState);
        }

        public List<Peer> androidPeers() {
            return peers.stream().filter(Peer::isAndroid).toList();
        }

        static Status notInstalled() {
            return new Status(false, false, "NotInstalled", "", "", "", List.of());
        }
    }

    /** bestMs / medianMs are over the direct pongs when there are any, otherwise over the relayed ones. */
    public record PingResult(boolean reached, boolean direct, String via, int bestMs, int medianMs, String output) {}

    private Tailscale() {
    }

    public static Path exe() {
        Path path = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Tailscale", "tailscale.exe");
        return Files.isRegularFile(path) ? path : null;
    }

    public static Status status() {
        Path exe = exe();
        if (exe == null) return Status.notInstalled();
        Shell.Result result = Shell.run(Duration.ofSeconds(10), exe.toString(), "status", "--json");
        return parseStatus(result.output());
    }

    static Status parseStatus(String output) {
        int start = output.indexOf('{');
        if (start < 0) return new Status(true, false, "Stopped", "", "", "", List.of());
        try {
            JsonObject root = JsonParser.parseString(output.substring(start)).getAsJsonObject();
            JsonObject self = root.has("Self") && root.get("Self").isJsonObject() ? root.getAsJsonObject("Self") : new JsonObject();
            List<Peer> peers = new ArrayList<>();
            if (root.has("Peer") && root.get("Peer").isJsonObject()) {
                for (var entry : root.getAsJsonObject("Peer").entrySet()) {
                    JsonObject p = entry.getValue().getAsJsonObject();
                    peers.add(new Peer(str(p, "HostName"), str(p, "OS"), bool(p, "Online"), firstIpv4(p),
                            str(p, "CurAddr"), str(p, "Relay")));
                }
            }
            return new Status(true, true, str(root, "BackendState"), str(root, "AuthURL"), firstIpv4(self),
                    str(self, "HostName"), peers);
        } catch (RuntimeException e) {
            return new Status(true, false, "Unknown", "", "", "", List.of());
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String firstIpv4(JsonObject o) {
        JsonElement e = o.get("TailscaleIPs");
        if (e == null || !e.isJsonArray()) return "";
        JsonArray ips = e.getAsJsonArray();
        for (JsonElement ip : ips) {
            if (ip.getAsString().contains(".")) return ip.getAsString();
        }
        return "";
    }

    /** Installs Tailscale with winget; Windows asks for admin approval. Blocks for up to 10 minutes. */
    public static Shell.Result install() {
        return Shell.run(Duration.ofMinutes(10), "winget", "install", "--id", "Tailscale.Tailscale", "-e",
                "--accept-source-agreements", "--accept-package-agreements", "--silent");
    }

    /**
     * Asks the Tailscale service for a sign-in link. `tailscale login` waits until the user finishes in the browser,
     * so it runs in the background; the link then appears as AuthURL in status().
     */
    public static void startLogin() {
        Path exe = exe();
        if (exe == null) return;
        Process old = loginProcess;
        if (old != null && old.isAlive()) return;
        try {
            Process p = new ProcessBuilder(exe.toString(), "login").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            loginProcess = p;
            Thread reaper = new Thread(() -> {
                try {
                    if (!p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly();
                } catch (InterruptedException ignored) {
                    p.destroyForcibly();
                }
            }, "tailscaleLogin");
            reaper.setDaemon(true);
            reaper.start();
        } catch (Exception ignored) {
        }
    }

    /**
     * `tailscale ping`: whether the path to a device is direct (UDP) or relayed (DERP), and how fast. Sends all 10 pings
     * (--until-direct=false): the first direct pongs are often slow while the path settles, so one sample misleads.
     */
    public static PingResult ping(String ip) {
        Path exe = exe();
        if (exe == null) return new PingResult(false, false, "", -1, -1, "Tailscale is not installed");
        Shell.Result result = Shell.run(Duration.ofSeconds(70), exe.toString(), "ping", "--c", "10",
                "--until-direct=false", "--timeout", "5s", ip);
        return parsePing(result.output());
    }

    static PingResult parsePing(String output) {
        Matcher m = PONG.matcher(output);
        List<Integer> all = new ArrayList<>();
        List<Integer> direct = new ArrayList<>();
        String via = "";
        String directVia = "";
        while (m.find()) {
            int ms = Integer.parseInt(m.group(2));
            via = m.group(1);
            all.add(ms);
            if (!via.toUpperCase(Locale.ROOT).startsWith("DERP")) {
                direct.add(ms);
                directVia = via;
            }
        }
        List<Integer> used = direct.isEmpty() ? all : direct;
        if (used.isEmpty()) return new PingResult(false, false, "", -1, -1, output.trim());
        List<Integer> sorted = used.stream().sorted().toList();
        return new PingResult(true, !direct.isEmpty(), direct.isEmpty() ? via : directVia,
                sorted.get(0), sorted.get(sorted.size() / 2), output.trim());
    }
}
