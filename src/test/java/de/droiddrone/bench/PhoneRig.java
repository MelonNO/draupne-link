package de.droiddrone.bench;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The real phone app in the Android emulator, driven from the PC's test: the very APK that would be put on a phone
 * is installed, its buttons are pressed where they are on its screen (found by their words, through Android's own
 * screen description), its screen is photographed (the emulator's screen - nothing of this PC's), its log is read.
 * The emulator's controls stand in for the world: GPS, battery level, the network going away, the phone dozing, the
 * app killed. UDP from this PC reaches the phone's link port through a forwarded port, so the PC's link - or the
 * whole Windows app - can connect to it, with a bad network in between if a scenario wants one.
 * <p>
 * The emulator has no USB: without the bench's flight-controller door (see docs/TESTING.md) the phone has no flight
 * controller, and what is tested here is everything else - the link, the camera's picture, sleep and wake, updates.
 */
public final class PhoneRig implements AutoCloseable {
    public static final String SERIAL = "emulator-5584";
    public static final String APP = "de.droiddrone.flight.pc";
    public static final String AVD = "draupne-bench";
    /** The link port inside the emulated phone (the phone app's default): never opened on this PC - a free port of this PC is forwarded to it. */
    public static int linkOnThePhone() {
        return de.droiddrone.common.UdpCommon.defaultPort;
    }

    /** One thing on the phone's screen. */
    public record Node(String text, String id, String description, Rectangle bounds, boolean clickable, boolean enabled) {
        public String shortId() {
            return id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;
        }
    }

    private final Path sdk, avdHome;
    private final Film film;
    private final List<Integer> forwarded = new ArrayList<>();

    public PhoneRig(Film film) {
        String local = System.getProperty("bench.realLocalAppData", System.getenv("LOCALAPPDATA"));
        sdk = Path.of(local, "Android", "Sdk");
        avdHome = Path.of(local, "DroidDroneBuild", "avd");
        this.film = film;
    }

    /** The emulator and its Android device are set up on this PC. */
    public static boolean setUp() {
        String local = System.getProperty("bench.realLocalAppData", System.getenv("LOCALAPPDATA"));
        return local != null && Files.exists(Path.of(local, "Android", "Sdk", "emulator", "emulator.exe"))
                && Files.exists(Path.of(local, "DroidDroneBuild", "avd", AVD + ".avd"));
    }

    // ------------------------------------------------------------------ adb

    private List<String> adbCommand(String... args) {
        List<String> cmd = new ArrayList<>(List.of(sdk.resolve("platform-tools").resolve("adb.exe").toString(), "-s", SERIAL));
        cmd.addAll(List.of(args));
        return cmd;
    }

    public byte[] adbBytes(long timeoutS, String... args) {
        try {
            ProcessBuilder b = new ProcessBuilder(adbCommand(args)).redirectErrorStream(true);
            realEnvironment(b);
            Process p = b.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                }
            }, "benchAdb");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("adb " + String.join(" ", args) + " did not end within " + timeoutS + " s");
            }
            reader.join(5000);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("adb could not be started: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String adb(String... args) {
        return new String(adbBytes(60, args), StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    public String shell(String command) {
        return adb("shell", command);
    }

    /** The programs the bench starts live outside the test JVM's sealed folders: they get this user's real ones. */
    private void realEnvironment(ProcessBuilder b) {
        String local = System.getProperty("bench.realLocalAppData");
        String roaming = System.getProperty("bench.realAppData");
        if (local != null && !local.isBlank()) {
            b.environment().put("LOCALAPPDATA", local);
            b.environment().put("TEMP", local + "\\Temp");
            b.environment().put("TMP", local + "\\Temp");
        }
        if (roaming != null && !roaming.isBlank()) b.environment().put("APPDATA", roaming);
        b.environment().put("ADB_MDNS", "0"); // no network discovery: nothing for Windows' firewall to ask about
        b.environment().put("ADB_MDNS_OPENSCREEN", "0");
        b.environment().put("ANDROID_SDK_ROOT", sdk.toString());
        b.environment().put("ANDROID_HOME", sdk.toString());
        b.environment().put("ANDROID_AVD_HOME", avdHome.toString());
    }

    // ------------------------------------------------------------------ the emulator

    public boolean running() {
        try {
            return "1".equals(shell("getprop sys.boot_completed"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The emulated phone up and started through; starts the emulator (no window) when it is not running. */
    public void ensureRunning() throws IOException {
        if (running()) return;
        ProcessBuilder b = new ProcessBuilder(sdk.resolve("emulator").resolve("emulator.exe").toString(), "-avd", AVD, "-no-window", "-no-audio",
                "-no-boot-anim", "-gpu", "swiftshader_indirect", "-camera-back", "emulated", "-camera-front", "emulated", "-no-snapshot", "-port", "5584");
        realEnvironment(b);
        Path log = Sandbox.runRoot().resolve("emulator.log");
        b.redirectErrorStream(true).redirectOutput(log.toFile());
        b.start();
        long end = System.currentTimeMillis() + 240_000;
        while (!running()) {
            if (System.currentTimeMillis() > end) throw new IllegalStateException("The emulator did not start within 4 minutes: see " + log);
            Ui.pause(3000);
        }
        Ui.pause(4000); // Android's own first seconds
    }

    // ------------------------------------------------------------------ the app

    /** The newest phone app this repo has built (flight-app\build.ps1). */
    public static Path builtApk() throws IOException {
        Path dir = Path.of(System.getProperty("bench.build", "build")).resolve("flight");
        if (!Files.isDirectory(dir)) throw new IllegalStateException("No phone app built yet: run flight-app\\build.ps1 (" + dir + ")");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".apk")).max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("No .apk in " + dir + ": run flight-app\\build.ps1"));
        }
    }

    public void install(Path apk) {
        String said = new String(adbBytes(180, "install", "-r", apk.toString()), StandardCharsets.UTF_8);
        if (!said.contains("Success")) throw new IllegalStateException("The phone app did not install: " + said);
    }

    /** The app as on a phone that never had it: its settings gone, its permissions asked again. */
    public void clearData() {
        shell("pm clear " + APP);
    }

    /**
     * What a pilot grants by tapping the permission questions, granted at once - and the phone set up as a drone's
     * phone is (2026-11-20): the app allowed to lock the screen (the status card's "Allow screen lock for deep
     * sleep"), and a lock screen to swipe away, as every real phone has one. Until then the emulator had neither: a
     * deep sleep left its screen lit, and what the app does behind a locked screen - open itself over it when the PC
     * wakes it, or after an error ended it - was never run.
     */
    public void grantPermissions() {
        for (String p : List.of("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
            shell("pm grant " + APP + " android.permission." + p);
        }
        shell("dpm set-active-admin --user 0 " + APP + "/de.droiddrone.flight.PowerAdminReceiver");
        shell("locksettings set-disabled false");
    }

    /** The screen on and its lock swiped away, as a pilot picking the phone up does. */
    public void unlock() {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        Ui.pause(500);
    }

    public void launch() {
        unlock();
        shell("am start -n " + APP + "/de.droiddrone.flight.MainActivity");
        Ui.pause(2500);
    }

    /** The app's process number; "" when it has none. */
    public String pid() {
        try {
            return shell("pidof " + APP).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** As swiping the app away and Android ending it. */
    public void forceStop() {
        shell("am force-stop " + APP);
    }

    public String version() {
        Matcher m = Pattern.compile("versionName=(\\S+)").matcher(shell("dumpsys package " + APP));
        return m.find() ? m.group(1) : "";
    }

    /**
     * The app's service runs in a living process. (Until 2026-11-20 this asked only whether Android knows the
     * service: it does, too, for one that crashed and waits to be restarted - in the night's phone part the app was
     * gone for a minute and "the service is running" stayed green.)
     */
    public boolean serviceRunning() {
        if (pid().isEmpty()) return false;
        String services = shell("dumpsys activity services " + APP);
        return services.contains("DDService") && services.contains("app=ProcessRecord");
    }

    /** The app's own log lines so far. */
    public String log() {
        String pid = shell("pidof " + APP);
        if (pid.isBlank()) return "";
        return new String(adbBytes(60, "logcat", "-d", "--pid=" + pid.split("\\s+")[0], "-v", "time"), StandardCharsets.UTF_8).replace("\r", "");
    }

    /** The phone's own clock now, as its log stamps lines (for {@link #logSince}). */
    public String now() {
        return shell("date '+%m-%d %H:%M:%S.000'").trim();
    }

    /** The app's own log lines since that moment of the phone's clock ({@link #now}): a night's log does not fit the buffer. */
    public String logSince(String stamp) {
        String pid = shell("pidof " + APP);
        if (pid.isBlank()) return "";
        return shell("logcat -d --pid=" + pid.trim().split("\\s+")[0] + " -v time -T '" + stamp + "'");
    }

    // ------------------------------------------------------------------ its screen

    private static final Pattern NODE = Pattern.compile("<node [^>]*>");

    private static String attr(String node, String name) {
        Matcher m = Pattern.compile(" " + name + "=\"([^\"]*)\"").matcher(node);
        return m.find() ? m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#10;", " ").replace("&lt;", "<").replace("&gt;", ">") : "";
    }

    /** Everything on the phone's screen now, as Android describes it. */
    public List<Node> screen() {
        String xml = "";
        for (int attempt = 0; attempt < 3; attempt++) { // "could not get idle state" while something animates (the STANDBY screen does, all the time)
            xml = new String(adbBytes(40, "exec-out", "uiautomator", "dump", "/dev/tty"), StandardCharsets.UTF_8);
            if (xml.contains("<hierarchy")) break;
            Ui.pause(700);
        }
        List<Node> nodes = new ArrayList<>();
        Matcher m = NODE.matcher(xml);
        while (m.find()) {
            String n = m.group();
            Matcher b = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matcher(attr(n, "bounds"));
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            nodes.add(new Node(attr(n, "text"), attr(n, "resource-id"), attr(n, "content-desc"),
                    new Rectangle(x1, y1, Integer.parseInt(b.group(3)) - x1, Integer.parseInt(b.group(4)) - y1),
                    attr(n, "clickable").equals("true"), attr(n, "enabled").equals("true")));
        }
        return nodes;
    }

    /** The words on the screen. */
    public List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Node n : screen()) if (!n.text().isBlank()) out.add(n.text());
        return out;
    }

    public boolean sees(String part) {
        String p = part.toLowerCase();
        for (String t : texts()) if (t.toLowerCase().contains(p)) return true;
        return false;
    }

    public Node find(String words) {
        List<Node> all = screen();
        for (Node n : all) if (n.text().equalsIgnoreCase(words)) return n;
        for (Node n : all) if (n.shortId().equals(words)) return n;
        for (Node n : all) if (n.text().toLowerCase().startsWith(words.toLowerCase())) return n;
        List<String> there = new ArrayList<>();
        for (Node n : all) if (!n.text().isBlank()) there.add(n.text());
        throw new AssertionError("Nothing on the phone's screen says \"" + words + "\". On it: " + there);
    }

    /** A finger on the control with these words (or this id), in its middle. */
    public void tap(String words) {
        Node n = find(words);
        if (!n.enabled()) {
            shot("NOT PRESSABLE on the phone: \"" + words + "\" is greyed");
            throw new AssertionError("\"" + words + "\" on the phone is greyed");
        }
        shell("input tap " + (n.bounds().x + n.bounds().width / 2) + " " + (n.bounds().y + n.bounds().height / 2));
        Ui.pause(900);
        shot("phone: tap \"" + words + "\"");
    }

    /** A finger held on it for so long. */
    public void hold(String words, int ms) {
        Node n = find(words);
        int x = n.bounds().x + n.bounds().width / 2, y = n.bounds().y + n.bounds().height / 2;
        shell("input swipe " + x + " " + y + " " + x + " " + y + " " + ms);
        Ui.pause(900);
        shot("phone: hold \"" + words + "\" " + ms + " ms");
    }

    public void back() {
        shell("input keyevent KEYCODE_BACK");
        Ui.pause(700);
    }

    public void scrollDown() {
        shell("input swipe 540 1700 540 700 300");
        Ui.pause(700);
    }

    /** Waits until the phone's screen says so; fails with a picture of how it was instead. */
    public void untilSees(String part, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!sees(part)) {
            if (System.currentTimeMillis() > end) {
                shot("phone: TIMED OUT after " + ms + " ms waiting for \"" + part + "\"");
                throw new AssertionError("Timed out after " + ms + " ms waiting for the phone to say \"" + part + "\". On it: " + texts());
            }
            Ui.pause(500);
        }
    }

    /** The phone's screen as a picture, a step in the film. */
    public BufferedImage shot(String action) {
        BufferedImage img = null;
        try {
            img = ImageIO.read(new ByteArrayInputStream(adbBytes(40, "exec-out", "screencap", "-p")));
        } catch (IOException | RuntimeException ignored) {
        }
        if (film != null) film.add(action, img, "emulator", img == null ? "no picture of the phone's screen" : null);
        return img;
    }

    // ------------------------------------------------------------------ the world around the phone

    /** A port of this PC that leads to the phone's UDP port. */
    public int forwardUdp(int phonePort) {
        int host = de.droiddrone.testing.Ports.free();
        String said = adb("emu", "redir", "add", "udp:" + host + ":" + phonePort);
        if (said.toLowerCase().contains("ko") || said.toLowerCase().contains("error")) throw new IllegalStateException("The emulator did not forward the port: " + said);
        forwarded.add(host);
        return host;
    }

    public void gps(double lat, double lon) {
        adb("emu", "geo", "fix", String.valueOf(lon), String.valueOf(lat));
    }

    /** Battery per cent, on the charger or not. */
    public void battery(int percent, boolean charging) {
        adb("emu", "power", "capacity", Integer.toString(percent));
        adb("emu", "power", "ac", charging ? "on" : "off");
        adb("emu", "power", "status", charging ? "charging" : "discharging");
    }

    /** The phone's own network gone (aeroplane mode on) or back. */
    public void aeroplane(boolean on) {
        shell("cmd connectivity airplane-mode " + (on ? "enable" : "disable"));
    }

    /** Android's deep doze forced on, or the phone woken. */
    public void doze(boolean on) {
        if (on) {
            shell("dumpsys battery unplug");
            shell("dumpsys deviceidle force-idle");
        } else {
            shell("dumpsys deviceidle unforce");
            shell("dumpsys battery reset");
            shell("input keyevent KEYCODE_WAKEUP");
        }
    }

    @Override
    public void close() {
        for (int host : forwarded) {
            try {
                adb("emu", "redir", "del", "udp:" + host);
            } catch (RuntimeException ignored) {
            }
        }
        forwarded.clear();
    }
}
