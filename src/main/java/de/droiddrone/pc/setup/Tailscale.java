package de.droiddrone.pc.setup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Drives the Tailscale CLI: install, status, sign-in, and path checks (direct vs relayed). */
public final class Tailscale {
    public static final String PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.tailscale.ipn";
    /** Tailscale's own Windows package, always the current version, signed by Tailscale Inc. */
    static final String MSI_URL = "https://pkgs.tailscale.com/stable/tailscale-setup-latest-%s.msi";
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

    /**
     * Installs Tailscale without any windows: downloads Tailscale's package straight from Tailscale, checks it is
     * signed by Tailscale, and installs it silently. Windows' admin prompt is the only thing the user sees. Needs
     * nothing on the PC beyond Windows itself (not winget, which a new or older Windows may not have yet).
     * Blocks for up to 15 minutes.
     */
    public static Shell.Result install() {
        return downloadAndInstall(true);
    }

    /** Windows Installer's answer while another install is running (e.g. DroidDrone PC's own installer). */
    public static final int ERROR_INSTALL_ALREADY_RUNNING = 1618;

    /**
     * @param elevate true from the app (Windows asks for approval); false when already running as administrator,
     *                as DroidDrone PC's installer does
     */
    public static Shell.Result downloadAndInstall(boolean elevate) {
        Path msi;
        try {
            msi = download();
        } catch (Exception e) {
            return new Shell.Result(-1, "Download failed: " + e.getMessage());
        }
        try {
            return installMsi(msi, elevate);
        } finally {
            try {
                Files.deleteIfExists(msi);
                Files.deleteIfExists(msi.getParent());
            } catch (Exception ignored) {
            }
        }
    }

    /** Downloads Tailscale's package for this PC into a new temporary folder. */
    public static Path download() throws Exception {
        Path msi = Files.createTempDirectory("droiddrone-tailscale").resolve("tailscale-setup.msi");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(msiUrl()))
                .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(msi));
        if (response.statusCode() != 200) throw new java.io.IOException("HTTP " + response.statusCode());
        return msi;
    }

    /**
     * Checks the package is signed by Tailscale and installs it silently. Exit code 0 when installed (also when
     * Windows wants a restart some time), {@link #ERROR_INSTALL_ALREADY_RUNNING} while another install runs.
     */
    public static Shell.Result installMsi(Path msi, boolean elevate) {
        String script = "$f = " + Shell.psQuote(msi.toString()) + "\n"
                + "$s = Get-AuthenticodeSignature -LiteralPath $f\n"
                + "if ($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch 'O=Tailscale Inc\\.') {\n"
                + "  'The download is not signed by Tailscale, so it was not installed.'; exit 2 }\n"
                + "$msiexec = Join-Path $env:SystemRoot 'System32\\msiexec.exe'\n"
                + "try { $p = Start-Process -FilePath $msiexec -ArgumentList '/i', ('\"' + $f + '\"'), '/qn', '/norestart' "
                + (elevate ? "-Verb RunAs " : "") + "-Wait -PassThru -ErrorAction Stop }\n"
                + "catch { 'The Windows prompt was declined.'; exit 1223 }\n"
                + "if ($p.ExitCode -eq 3010) { exit 0 }\n"
                + "if ($p.ExitCode -ne 0) { 'Windows Installer error ' + $p.ExitCode }\n"
                + "exit $p.ExitCode";
        return Shell.powershell(Duration.ofMinutes(15), script);
    }

    /** The package for this PC's processor: Intel/AMD or ARM. */
    static String msiUrl() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return String.format(MSI_URL, arch.contains("aarch64") || arch.contains("arm") ? "arm64" : "amd64");
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
