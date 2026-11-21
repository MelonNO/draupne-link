package de.droiddrone.bench;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The real phone app driven from the PC's test, on one of two phones ({@link Target}):
 * <ul>
 * <li><b>the Android emulator</b>, as since 2026-11-19: the very APK that would be put on a phone is installed, its
 * buttons are pressed where they are on its screen (found by their words, through Android's own screen description),
 * its screen is photographed (the emulator's screen - nothing of this PC's), its log is read. The emulator's controls
 * stand in for the world: GPS, battery level, the network going away, the phone dozing, the app killed. UDP from this
 * PC reaches the phone's link port through a forwarded port, so the PC's link - or the whole Windows app - can
 * connect to it, with a bad network in between if a scenario wants one. The emulator has no USB: the phone has no
 * flight controller there, and what is tested is everything else - the link, the camera's picture, sleep and wake,
 * updates. Nothing of this path changed when the second one came: the same serial, the same commands in the same
 * order ({@code PhoneRigEmulatorTest} holds it to that).</li>
 * <li><b>one real phone on adb</b> (2026-11-21, the hardware week): a production build, so no root; the model that
 * flies, with a SIM. The PC reaches its link port at the address of this bench's hardware file ({@link Hardware}),
 * through the fence's one way out ({@link Fence.Reach}), in a hardware run's JVM only. What the emulator's console
 * did has no stand-in ({@link Capability}); what root did goes through the bench-only build's receiver
 * ({@link BenchControl}); and every command is first held against {@link PhoneGuard}: the released app is not
 * changed, the phone's lock and versions are left alone, its network is not switched off under adb's own feet, and
 * whatever was pretended about its battery is taken back when the rig closes - or by {@link PhoneReset} after a run
 * that was killed. Its log is followed into the run's folder from the scenario's start ({@code phone-logcat.txt}),
 * so what ended the app is never lost; the bench pauses while its battery is hotter than {@value #HOT_C} degrees and
 * stops below {@value #LOW_PERCENT} per cent.</li>
 * </ul>
 * <b>Sleep on a cable</b> ({@link #onBattery}, {@link #plugged}). Android does not doze while it charges, and a
 * phone on adb's cable charges. {@code dumpsys battery unplug} makes Android believe the cable is gone (the app sees
 * "not charging"; the level and temperature Android reports stand still from then on) and
 * {@code dumpsys deviceidle force-idle} puts it into deep doze at once: the app's network closed unless it is exempt
 * from battery saving, its ordinary alarms held back, its jobs stopped. That is the doze the app's own sleep check
 * has to get through, and it can be had every round, on the bench. What it does NOT prove - those go to one attended
 * night off the cable:
 * <ul>
 * <li><b>real suspend</b>: with USB attached the phone's processor never sleeps (the cable, and adb, hold it awake);
 * forced doze is Android's bookkeeping, not the chip powered down;</li>
 * <li><b>exact-alarm timing after suspend</b>: an alarm here fires from a running processor; whether the phone's
 * clock chip wakes a sleeping one on time, and how late, is not in it;</li>
 * <li><b>the modem and the network's NAT going idle</b>: forced doze does not put the modem to sleep, and the cable
 * keeps the phone busy enough that the operator's address translation may never time out as it does in the field;</li>
 * <li><b>the phone maker's own app killers</b>: they act on the real battery state, screen-off time and their own
 * lists - none of which {@code unplug} or {@code force-idle} touch;</li>
 * <li>and forced doze <b>stays on when the screen lights</b>: a real phone leaves doze the moment the app wakes its
 * screen, here it stays dozing until {@link #plugged} - harder than the field in that one respect.</li>
 * </ul>
 */
public final class PhoneRig implements AutoCloseable {
    public static final String SERIAL = "emulator-5584";
    public static final String APP = "de.droiddrone.flight.pc";
    public static final String AVD = "draupne-bench";
    /** The bench pauses while the phone's battery is hotter than this, and goes on at {@link #COOL_C}. */
    public static final double HOT_C = 42.0, COOL_C = 40.0;
    /** Below this the bench stops: the phone is left with enough to be put on its charger by hand. */
    public static final int LOW_PERCENT = 15;
    /** The screen the scenarios' fixed places were written on (the bench emulator's): a real phone's are scaled from it. */
    public static final int REF_WIDTH = 1080, REF_HEIGHT = 2400;
    private static final long BATTERY_EVERY_MS = 60_000, COOL_DOWN_MAX_MS = 30 * 60_000L;
    /** What the tap follows: the app's own lines, Android's word on what ended it. */
    static final List<String> TAP = List.of("logcat", "-v", "threadtime", "-s", "DDLog:I", "DD:I", "AndroidRuntime:E", "ActivityManager:I");

    /** The link port inside the phone (the phone app's default). On the emulator it is never opened on this PC - a free port of this PC is forwarded to it. */
    public static int linkOnThePhone() {
        return de.droiddrone.common.UdpCommon.defaultPort;
    }

    public enum Kind {
        /** The bench's own Android emulator. */
        EMULATOR,
        /** A real phone on adb - or anything held to a real phone's rules. */
        DEVICE
    }

    /** What a phone lets a scenario do beyond adb itself. A real phone has none of these. */
    public enum Capability {
        /** {@code adb root}: another app's files and processes. */
        ROOT,
        /** A made-up place ({@code emu geo fix}). */
        EMU_GPS,
        /** A made-up battery level and charger ({@code emu power}). */
        EMU_BATTERY,
        /** A port of this PC forwarded to a UDP port of the phone ({@code emu redir}). */
        UDP_REDIR
    }

    /**
     * The phone a rig drives.
     *
     * @param serial                  as adb names it
     * @param app                     the package the scenarios fly: the released app's on the emulator; on a real
     *                                phone the bench build's unless the run says otherwise
     * @param link                    where this PC reaches the phone's link port; null when a port is forwarded ({@link Capability#UDP_REDIR})
     * @param releasedAppMayBeChanged the run's own word that the released app on a real phone may be cleared, stopped, installed over
     * @param sdk                     the Android SDK with adb in it
     * @param avdHome                 where the emulator's devices are; null for a real phone
     * @param apk                     an APK the run wants on a real phone first; null: the app there is left as it is
     */
    public record Target(String serial, Kind kind, String app, InetSocketAddress link, Set<Capability> can, boolean releasedAppMayBeChanged,
                         Path sdk, Path avdHome, Path apk) {
        public boolean can(Capability c) {
            return can.contains(c);
        }

        /** adb itself comes over the network (a serial like 192.0.2.7:5555, or wireless debugging's own name): then the network is not switched off. */
        public boolean overTcp() {
            return serial.matches(".+:\\d+") || serial.contains("._tcp") || serial.contains("_adb-tls-");
        }

        /** The bench-only build is what this run flies: its receiver does root's work. */
        public boolean benchBuild() {
            return BenchControl.PACKAGE.equals(app);
        }

        /** The emulator and its Android device are set up on this PC (as {@link PhoneRig#setUp()} asks); for a real phone: adb is there. */
        public boolean setUp() {
            if (sdk == null) return false;
            if (kind == Kind.DEVICE) return Files.exists(sdk.resolve("platform-tools").resolve("adb.exe"));
            return Files.exists(sdk.resolve("emulator").resolve("emulator.exe")) && avdHome != null && Files.exists(avdHome.resolve(AVD + ".avd"));
        }

        /** Under which address the phone reaches a TCP port of this PC's own loopback (on a real phone after {@link PhoneRig#reverseTcp}). */
        public String thisPc(int port) {
            return kind == Kind.EMULATOR ? "http://10.0.2.2:" + port : "http://127.0.0.1:" + port;
        }

        private static String local() {
            return System.getProperty("bench.realLocalAppData", System.getenv("LOCALAPPDATA"));
        }

        /** The bench's emulator, as ever. */
        public static Target emulator() {
            String local = local();
            return new Target(SERIAL, Kind.EMULATOR, APP, null, EnumSet.allOf(Capability.class), true,
                    local == null ? null : Path.of(local, "Android", "Sdk"), local == null ? null : Path.of(local, "DroidDroneBuild", "avd"), null);
        }

        /**
         * The phone this run was started for: the emulator's - unless this JVM is a hardware run's (gradlew
         * benchHwPhone: {@code -Dbench.hardware=true}, the one switch without which the fence lets nothing out of
         * this PC), and then this bench's one real phone ({@link #device()}). A hardware run on a PC without the
         * hardware file skips the scenario, as every hardware scenario skips itself; one whose file is there but does
         * not say enough, or that finds no adb, fails here with the reason. It is never turned into an emulator run.
         */
        public static Target ofRun() {
            if (!Fence.Reach.hardwareRun()) return emulator();
            org.junit.jupiter.api.Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                    + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
            Target t = device();
            if (!t.setUp()) throw new IllegalStateException("A run on a real phone needs adb, and there is none in the Android SDK at " + t.sdk()
                    + " (platform-tools\\adb.exe): -Pandroid.sdk=... (bench.android.sdk) or ANDROID_SDK_ROOT says where it is");
            return t;
        }

        /**
         * This bench's real phone, from {@link Hardware} - the hardware file, and before it the run's own word and the
         * environment's: its adb serial (phone.adb), where this PC reaches its link port (phone.ip, phone.port), and
         * which DRAUPNE Flight is flown (phone.package: the bench build unless it names the released app). The
         * Android SDK: {@code bench.android.sdk}, ANDROID_SDK_ROOT, ANDROID_HOME, else this user's. "The released app
         * may be changed" ({@code bench.phone.releasedAppMayBeChanged=true}, BENCH_PHONE_RELEASED_APP_MAY_BE_CHANGED)
         * and an APK to install first ({@code bench.phone.apk}) are a run's own word alone: no file says them.
         */
        public static Target device() {
            return device(Hardware.load(), System::getProperty, System::getenv, local());
        }

        /** {@link #device()} with its sources handed in. */
        static Target device(Hardware bench, UnaryOperator<String> property, UnaryOperator<String> environment, String localAppData) {
            String serial = bench.phoneAdb(); // asked for by name when nobody says it: a phone is never picked by itself
            InetSocketAddress link = null;
            Set<Capability> can = EnumSet.noneOf(Capability.class);
            if (serial.startsWith("emulator-")) {
                // a rehearsal: the emulator held to a real phone's rules. Its link port is reached as an emulator's is,
                // on this PC's loopback - the file's address is not used, and the fence needs no way through
                can.add(Capability.UDP_REDIR);
            } else {
                link = new InetSocketAddress(numbers(bench.phoneIp()), bench.phonePort());
            }
            String sdk = first(property.apply("bench.android.sdk"), environment.apply("ANDROID_SDK_ROOT"), environment.apply("ANDROID_HOME"));
            Path sdkPath = sdk != null ? Path.of(sdk) : localAppData == null ? null : Path.of(localAppData, "Android", "Sdk");
            boolean released = truth(first(property.apply("bench.phone.releasedAppMayBeChanged"), environment.apply("BENCH_PHONE_RELEASED_APP_MAY_BE_CHANGED")));
            String apk = first(property.apply("bench.phone.apk"), environment.apply("BENCH_PHONE_APK"));
            return new Target(serial, Kind.DEVICE, bench.phonePackage(), link, can, released, sdkPath, null, apk == null ? null : Path.of(apk));
        }

        /**
         * What the fence has to let through for this phone - the one way there is ({@link Fence.Reach}): a real
         * phone's address at its link port; nothing for the emulator, whose port is forwarded on this PC's loopback.
         * A scenario that starts the Windows app hands it to its plan ({@link AppRig.Plan#reach}). For a real phone
         * this throws in a JVM that is no hardware run - the fence's own rule, kept here too.
         */
        public Fence.Reach reach() {
            if (kind != Kind.DEVICE || link == null) return Fence.Reach.none();
            return Fence.Reach.of(link.getAddress().getHostAddress() + ":" + link.getPort());
        }

        private static String first(String... values) {
            for (String v : values) if (v != null && !v.isBlank()) return v.trim();
            return null;
        }

        private static boolean truth(String v) {
            return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
        }

        /** The address {@link Hardware} has made sure is four numbers written out: taken as it is, nothing looked up. */
        private static InetAddress numbers(String ip) {
            String[] part = ip.split("\\.");
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(part[i]);
            try {
                return InetAddress.getByAddress(b);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(ip + " is no address", e);
            }
        }
    }

    /** The bench will not send this to a real phone ({@link PhoneGuard}). An error no scenario's "try again" swallows. */
    public static final class Refused extends AssertionError {
        public Refused(String message) {
            super(message);
        }
    }

    /** The run ends here for the phone's sake: its battery is too low, or stays too hot. */
    public static final class Stop extends AssertionError {
        public Stop(String message) {
            super(message);
        }
    }

    /**
     * The phone's battery as Android reports it.
     *
     * @param status    CHARGING, DISCHARGING, NOT_CHARGING, FULL or UNKNOWN
     * @param plugged   a charger of any kind is seen (cable, wireless, dock)
     * @param pretended {@code dumpsys battery unplug} is in force: Android's own figures stand still
     * @param live      the level and temperature are the real ones of this moment (not so while pretended, unless
     *                  the phone lets adb read them from the battery's own files)
     */
    public record Battery(int percent, double celsius, String status, boolean plugged, boolean pretended, boolean live) {
        public boolean charging() {
            return status.equals("CHARGING") || status.equals("FULL");
        }

        /** {@code dumpsys battery}'s text; null when it has no level in it. */
        public static Battery parse(String dumpsys) {
            if (dumpsys == null) return null;
            Integer level = number(dumpsys, "level"), scale = number(dumpsys, "scale"), temperature = number(dumpsys, "temperature"), status = number(dumpsys, "status");
            if (level == null) return null;
            int of = scale == null || scale <= 0 ? 100 : scale;
            boolean plugged = false;
            for (String source : List.of("AC", "USB", "Wireless", "Dock")) {
                plugged |= Pattern.compile("(?m)^\\s*" + source + " powered:\\s*true\\s*$").matcher(dumpsys).find();
            }
            String state = status == null ? "UNKNOWN" : switch (status) {
                case 2 -> "CHARGING";
                case 3 -> "DISCHARGING";
                case 4 -> "NOT_CHARGING";
                case 5 -> "FULL";
                default -> "UNKNOWN";
            };
            boolean pretended = dumpsys.contains("UPDATES STOPPED");
            return new Battery(Math.round(level * 100f / of), temperature == null ? Double.NaN : temperature / 10.0, state, plugged, pretended, !pretended);
        }

        private static Integer number(String text, String key) {
            Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*(-?\\d+)\\s*$").matcher(text);
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        }

        /** With the battery's own files' word (per cent, then tenths of a degree) in place of figures that stand still; unchanged when that is no such word. */
        Battery withReal(String capacityAndTemp) {
            Matcher m = Pattern.compile("^\\s*(\\d{1,3})\\s+(-?\\d{1,4})\\s*$").matcher(capacityAndTemp == null ? "" : capacityAndTemp);
            if (!m.matches()) return this;
            int p = Integer.parseInt(m.group(1)), t = Integer.parseInt(m.group(2));
            if (p > 100 || t < -400 || t > 1000) return this;
            return new Battery(p, t / 10.0, status, plugged, pretended, true);
        }

        @Override
        public String toString() {
            return percent + " %, " + (Double.isNaN(celsius) ? "? " : String.format(Locale.ROOT, "%.1f ", celsius)) + "C, " + status.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (pretended ? " (pretended off the cable" + (live ? "" : "; these figures stand still") + ")" : plugged ? ", on a charger" : "");
        }
    }

    /** The phone's screen as it lies now, in pixels. */
    public record Screen(int width, int height) {
        /**
         * From {@code wm size} ("Physical size: 1080x2400", an "Override size:" line wins) and, when given,
         * {@code dumpsys window displays}, whose {@code cur=2400x1080} is the screen as it is turned now. Null when
         * neither says a size.
         */
        public static Screen parse(String wmSize, String displays) {
            Matcher cur = Pattern.compile("\\bcur=(\\d+)x(\\d+)").matcher(displays == null ? "" : displays);
            if (cur.find()) return new Screen(Integer.parseInt(cur.group(1)), Integer.parseInt(cur.group(2)));
            String text = wmSize == null ? "" : wmSize;
            Matcher m = Pattern.compile("Override size:\\s*(\\d+)x(\\d+)").matcher(text);
            if (!m.find()) {
                m = Pattern.compile("Physical size:\\s*(\\d+)x(\\d+)").matcher(text);
                if (!m.find()) return null;
            }
            return new Screen(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }

        /** A place written for the reference screen ({@link #REF_WIDTH} x {@link #REF_HEIGHT}), on this one: the same share of the way across and down. */
        public Point scaled(int x, int y) {
            return new Point(Math.min(width - 1, Math.round(x * (float) width / REF_WIDTH)), Math.min(height - 1, Math.round(y * (float) height / REF_HEIGHT)));
        }
    }

    /** One thing on the phone's screen. */
    public record Node(String text, String id, String description, Rectangle bounds, boolean clickable, boolean enabled) {
        public String shortId() {
            return id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;
        }
    }

    private final Target target;
    private final PhoneAdb adb;
    private final Film film;
    private final LongConsumer pause;
    private final LongSupplier clock;
    private final Path folder;
    private final List<Integer> forwarded = new ArrayList<>();
    private final List<Integer> reversed = new ArrayList<>();
    private final Set<PhoneGuard.Undo> switchedOff = EnumSet.noneOf(PhoneGuard.Undo.class);
    private final List<String> refusals = new ArrayList<>();
    private boolean pretending, standingStillSaid, linkSaid;
    private LogTap tap;
    private long batteryCheckedMs;
    private String stopped;

    /** The bench's emulator. */
    public PhoneRig(Film film) {
        this(film, Target.emulator());
    }

    public PhoneRig(Film film, Target target) {
        this(film, target, new PhoneAdb.Sdk(target.sdk(), target.avdHome(), target.serial()), Ui::pause, System::currentTimeMillis, null);
    }

    /**
     * @param adb    the door to the phone (a recorder in the fast tests)
     * @param pause  how the rig waits
     * @param clock  the time in ms
     * @param folder where the log tap writes; null: the film's folder, or the run's
     */
    PhoneRig(Film film, Target target, PhoneAdb adb, LongConsumer pause, LongSupplier clock, Path folder) {
        this.film = film;
        this.target = target;
        this.adb = adb;
        this.pause = pause;
        this.clock = clock;
        this.folder = folder;
        batteryCheckedMs = clock.getAsLong();
    }

    /** The emulator and its Android device are set up on this PC. */
    public static boolean setUp() {
        String local = System.getProperty("bench.realLocalAppData", System.getenv("LOCALAPPDATA"));
        return local != null && Files.exists(Path.of(local, "Android", "Sdk", "emulator", "emulator.exe"))
                && Files.exists(Path.of(local, "DroidDroneBuild", "avd", AVD + ".avd"));
    }

    public Target target() {
        return target;
    }

    /** The package this rig flies. */
    public String app() {
        return target.app();
    }

    public boolean can(Capability c) {
        return target.can(c);
    }

    /** A real phone (or the emulator held to its rules): no console, no root, the guard before every command. */
    public boolean device() {
        return target.kind() == Kind.DEVICE;
    }

    /** What the guard refused so far (each also stopped the scenario where it was asked). */
    public List<String> refusals() {
        return List.copyOf(refusals);
    }

    private void say(String text) {
        if (film != null) film.remark(text);
    }

    // ------------------------------------------------------------------ adb

    /** A command for the phone. To a real phone it goes only past the guard ({@link PhoneGuard}); what the guard refuses is a {@link Refused}. */
    public byte[] adbBytes(long timeoutS, String... args) {
        List<String> command = List.of(args);
        if (device()) {
            if (stopped != null) throw new Stop(stopped);
            PhoneGuard.Verdict v = PhoneGuard.judge(target, command, ApkPackage::read);
            if (!v.allowed()) {
                String text = "Not sent to the real phone: adb " + String.join(" ", command) + " - " + v.refusal() + ".";
                refusals.add(text);
                say("REFUSED: " + text);
                throw new Refused(text);
            }
            switchedOff.addAll(v.sets());
            switchedOff.removeAll(v.clears());
            if (v.pretends()) pretending = true;
            if (clock.getAsLong() - batteryCheckedMs >= BATTERY_EVERY_MS) checkBattery();
        }
        return adb.run(timeoutS, command);
    }

    public String adb(String... args) {
        return new String(adbBytes(60, args), StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    public String shell(String command) {
        return adb("shell", command);
    }

    /** The rig's own reading and putting back: straight to adb, past the battery watch (which reads through here itself). Only commands written in this class. */
    private String own(String... args) {
        return new String(adb.run(60, List.of(args)), StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    private void quietly(String... args) {
        try {
            own(args);
        } catch (RuntimeException e) {
            say("Not put back (" + String.join(" ", args) + "): " + e.getMessage() + " - gradlew phoneReset -Pserial=... does it.");
        }
    }

    // ------------------------------------------------------------------ the phone itself

    public boolean running() {
        try {
            return "1".equals(shell("getprop sys.boot_completed"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The phone there and started through. The emulator is started (no window) when it is not running. A real phone
     * must be on adb already - then its log is tapped from here on and its battery looked at.
     */
    public void ensureRunning() throws IOException {
        if (device()) {
            String state;
            try {
                state = own("get-state");
            } catch (RuntimeException e) {
                state = String.valueOf(e.getMessage());
            }
            if (!"device".equals(state)) {
                throw new IllegalStateException("No phone on adb under the serial given (adb says: " + state + "). The cable, USB debugging switched on, "
                        + "and adb's question on the phone's screen answered?");
            }
            if (!running()) throw new IllegalStateException("The phone on adb has not started through yet (sys.boot_completed)");
            startLogTap();
            Battery b = checkBattery();
            say("A real phone on adb (" + (target.overTcp() ? "over the network" : "on its cable") + "), held to PhoneGuard's list; the app flown: " + target.app()
                    + (target.releasedAppMayBeChanged() ? "; THE RELEASED APP MAY BE CHANGED by this run" : "") + (b == null ? "" : "; battery " + b));
            return;
        }
        if (running()) return;
        Path log = Sandbox.runRoot().resolve("emulator.log");
        adb.startEmulator(List.of("-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect", "-camera-back", "emulated",
                "-camera-front", "emulated", "-no-snapshot", "-port", "5584"), log);
        long end = clock.getAsLong() + 240_000;
        while (!running()) {
            if (clock.getAsLong() > end) throw new IllegalStateException("The emulator did not start within 4 minutes: see " + log);
            pause.accept(3000);
        }
        pause.accept(4000); // Android's own first seconds
    }

    /** {@code adb root} where the phone has it (the emulator, as before: asked, then 3 s for adb to come back). A real phone has none: said, nothing sent. */
    public void root() {
        if (can(Capability.ROOT)) {
            adb("root");
            pause.accept(3000);
        } else {
            say("No root on this phone: what the emulator does as root goes through the bench build's receiver (" + BenchControl.RECEIVER + ").");
        }
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

    /** The APK over the app that is there. On a real phone only past the guard, which reads the APK's package first. */
    public void install(Path apk) {
        String said = device() ? new String(adbBytes(180, "install", "-r", "-t", apk.toString()), StandardCharsets.UTF_8)
                : new String(adbBytes(180, "install", "-r", apk.toString()), StandardCharsets.UTF_8);
        if (!said.contains("Success")) throw new IllegalStateException("The phone app did not install: " + said);
    }

    /**
     * The APK over whatever is there; what adb said. The emulator also takes a version below the one it has
     * ("-d": the update scenario goes down again after it went up). A real phone is never taken down a version:
     * there an older APK is refused by Android itself, and that is what comes back.
     */
    public String installAnyVersion(Path apk) {
        return device() ? new String(adbBytes(180, "install", "-r", "-t", apk.toString()), StandardCharsets.UTF_8)
                : new String(adbBytes(180, "install", "-r", "-d", apk.toString()), StandardCharsets.UTF_8);
    }

    /**
     * The app a scenario flies, put on the phone. The emulator gets the newest build of this repo, as ever. A real
     * phone keeps the app it has - the bench build is put there once by hand ({@code adb install -t}) - unless the
     * run names an APK ({@code -Pphone.apk=...}); an app that is not there at all stops the scenario.
     */
    public void installApp() throws IOException {
        if (!device()) {
            install(builtApk());
            return;
        }
        if (target.apk() != null) {
            install(target.apk());
            say("Installed " + target.apk().getFileName() + " on the phone: " + target.app() + " " + version());
            return;
        }
        if (!shell("pm path " + target.app()).contains("package:")) {
            throw new IllegalStateException(target.app() + " is not on the phone: put it there by hand (the bench build: adb install -t), or name its APK with -Pphone.apk=...");
        }
        say("The app on the phone is left as it is: " + target.app() + " " + version());
    }

    /** The app as on a phone that never had it: its settings gone, its permissions asked again. */
    public void clearData() {
        shell("pm clear " + target.app());
    }

    /**
     * What a pilot grants by tapping the permission questions, granted at once - and the phone set up as a drone's
     * phone is (2026-11-20): the app allowed to lock the screen (the status card's "Allow screen lock for deep
     * sleep"), and a lock screen to swipe away, as every real phone has one. Until then the emulator had neither: a
     * deep sleep left its screen lit, and what the app does behind a locked screen - open itself over it when the PC
     * wakes it, or after an error ended it - was never run.
     * <p>
     * On a real phone the same grants (no phone maker's dialog is tapped through) and the screen lock's permission,
     * for the app the guard lets be changed; its own lock screen is left exactly as it is.
     */
    public void grantPermissions() {
        for (String p : List.of("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
            shell("pm grant " + target.app() + " android.permission." + p);
        }
        shell("dpm set-active-admin --user 0 " + target.app() + "/de.droiddrone.flight.PowerAdminReceiver");
        if (device()) say("The phone's own lock screen is left as it is (locksettings is never sent to a real phone).");
        else shell("locksettings set-disabled false");
    }

    /** The screen on and its lock swiped away, as a pilot picking the phone up does. */
    public void unlock() {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        pause.accept(500);
    }

    public void launch() {
        unlock();
        shell("am start -n " + target.app() + "/de.droiddrone.flight.MainActivity");
        pause.accept(2500);
    }

    /** The app's process number; "" when it has none. */
    public String pid() {
        try {
            return shell("pidof " + target.app()).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** As swiping the app away and Android ending it. */
    public void forceStop() {
        shell("am force-stop " + target.app());
    }

    /**
     * The app closed and what its crash guard kept of earlier restarts gone: no restart of an earlier run counts.
     * As root (the emulator, as before): closed, then the guard's file removed. Without root the bench build's
     * receiver forgets it - asked first (a closed app is opened for the question: Android hands a stopped app no
     * broadcast unless told to, {@code --include-stopped-packages}), and its answer is there before the app is closed.
     */
    public void stopForgettingRestarts() {
        if (can(Capability.ROOT)) {
            forceStop();
            shell("rm -f /data/data/" + target.app() + "/shared_prefs/crashGuard.xml");
        } else {
            benchControl(BenchControl.RESET_CRASH_GUARD, "--include-stopped-packages");
            forceStop();
        }
    }

    /**
     * The app's process ended outright, as a phone maker's battery saver does: kill -9 as root; else the bench build
     * ends its own process, a third of a second after it has answered.
     */
    public void endProcess(String pid) {
        if (can(Capability.ROOT)) shell("kill -9 " + pid);
        else benchControl(BenchControl.KILL);
    }

    /**
     * The app ended by an error nobody caught. The emulator, as before, and a real phone's released app: Android's
     * own {@code am crash}. The bench build on a real phone throws one itself, on a thread of its own, a third of a
     * second after it has answered.
     */
    public void crash() {
        if (device() && target.benchBuild()) benchControl(BenchControl.CRASH);
        else shell("am crash " + target.app());
    }

    /**
     * For a round that went wrong: where the phone's sending stands. As root Android's own {@code ss} - what waits
     * to leave each UDP socket. Without root that cannot be read; the bench build then says what it knows of itself
     * in one line (its flight controller's connection, its service, whether a PC is connected, its process).
     */
    public String socketStats() {
        if (can(Capability.ROOT)) return shell("ss -u -a -m 2>&1");
        if (!target.benchBuild()) return "(not readable: no root on this phone, and the released app has no bench receiver)";
        return "(no root on this phone: the sockets' queues cannot be read. The bench build's own figures:)\n" + benchControl(BenchControl.STATS);
    }

    /**
     * An order to the bench build's receiver ({@link BenchControl}), and its answer as {@code am broadcast} prints it.
     * The released app has no such receiver, so there this is refused; a receiver that does not answer (another
     * build under the bench's name) stops the scenario.
     *
     * @param options what else {@code am broadcast} is told, before the receiver's name
     */
    public String benchControl(String action, String... options) {
        if (!target.benchBuild()) {
            String text = "This step needs root or the bench build's receiver, and this run flies " + target.app() + " on a phone without root: not done.";
            refusals.add(text);
            say("REFUSED: " + text);
            throw new Refused(text);
        }
        String said = shell("am broadcast " + (options.length == 0 ? "" : String.join(" ", options) + " ") + "-n " + BenchControl.component(target.app()) + " -a " + action);
        if (!said.contains(BenchControl.TAKEN)) throw new IllegalStateException("The bench build's receiver did not take " + action + ": " + said);
        return said;
    }

    public String version() {
        Matcher m = Pattern.compile("versionName=(\\S+)").matcher(shell("dumpsys package " + target.app()));
        return m.find() ? m.group(1) : "";
    }

    /**
     * The app's service runs in a living process. (Until 2026-11-20 this asked only whether Android knows the
     * service: it does, too, for one that crashed and waits to be restarted - in the night's phone part the app was
     * gone for a minute and "the service is running" stayed green.)
     */
    public boolean serviceRunning() {
        if (pid().isEmpty()) return false;
        String services = shell("dumpsys activity services " + target.app());
        return services.contains("DDService") && services.contains("app=ProcessRecord");
    }

    /** The app's own log lines so far. */
    public String log() {
        String pid = shell("pidof " + target.app());
        if (pid.isBlank()) return "";
        return new String(adbBytes(60, "logcat", "-d", "--pid=" + pid.split("\\s+")[0], "-v", "time"), StandardCharsets.UTF_8).replace("\r", "");
    }

    /** The phone's own clock now, as its log stamps lines (for {@link #logSince}). */
    public String now() {
        return shell("date '+%m-%d %H:%M:%S.000'").trim();
    }

    /** The app's own log lines since that moment of the phone's clock ({@link #now}): a night's log does not fit the buffer. */
    public String logSince(String stamp) {
        String pid = shell("pidof " + target.app());
        if (pid.isBlank()) return "";
        return shell("logcat -d --pid=" + pid.trim().split("\\s+")[0] + " -v time -T '" + stamp + "'");
    }

    // ------------------------------------------------------------------ its log, followed (a real phone)

    /** Android's log followed into the run's folder; started again by itself when adb loses the phone for a moment (the cable). */
    private final class LogTap implements AutoCloseable {
        private final Path file;
        private volatile boolean closed;
        private volatile PhoneAdb.Running running;
        private Thread watcher;

        LogTap(Path file) {
            this.file = file;
        }

        void start() {
            note("---- the phone's log (" + String.join(" ", TAP) + "), followed from " + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS) + " ----");
            follow();
            watcher = new Thread(() -> {
                while (!closed) {
                    PhoneAdb.Running r = running;
                    if (r == null || !r.alive()) {
                        if (!sleep(3000)) return;
                        if (closed) return;
                        note("---- the tap had ended (the cable? adb?); followed again from " + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                + " - lines from before the gap may come twice ----");
                        follow();
                    }
                    if (!sleep(1000)) return;
                }
            }, "benchLogTap");
            watcher.setDaemon(true);
            watcher.start();
        }

        private boolean sleep(long ms) {
            try {
                Thread.sleep(ms);
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        }

        private synchronized void follow() {
            if (closed) return; // never one more after the rig closed: nobody would end it
            try {
                running = adb.follow(TAP, file);
            } catch (IOException | RuntimeException e) {
                running = null;
                note("---- the tap could not be started: " + e.getMessage() + " ----");
            }
        }

        private void note(String line) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (watcher != null) watcher.interrupt();
            PhoneAdb.Running r = running;
            if (r != null) r.close();
        }
    }

    private void startLogTap() throws IOException {
        if (tap != null) return;
        Path dir = folder != null ? folder : film != null ? film.dir() : Sandbox.runRoot();
        Files.createDirectories(dir);
        tap = new LogTap(dir.resolve("phone-logcat.txt"));
        tap.start();
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
            pause.accept(700);
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

    /** A finger on the control with these words (or this id), in its middle: found on the screen as it is, so on any phone's. */
    public void tap(String words) {
        Node n = find(words);
        if (!n.enabled()) {
            shot("NOT PRESSABLE on the phone: \"" + words + "\" is greyed");
            throw new AssertionError("\"" + words + "\" on the phone is greyed");
        }
        shell("input tap " + (n.bounds().x + n.bounds().width / 2) + " " + (n.bounds().y + n.bounds().height / 2));
        pause.accept(900);
        shot("phone: tap \"" + words + "\"");
    }

    /** A finger held on it for so long. */
    public void hold(String words, int ms) {
        Node n = find(words);
        int x = n.bounds().x + n.bounds().width / 2, y = n.bounds().y + n.bounds().height / 2;
        shell("input swipe " + x + " " + y + " " + x + " " + y + " " + ms);
        pause.accept(900);
        shot("phone: hold \"" + words + "\" " + ms + " ms");
    }

    public void back() {
        shell("input keyevent KEYCODE_BACK");
        pause.accept(700);
    }

    /** A real phone's screen as it lies now ({@code wm size}, and which way it is turned); null on the emulator, whose screen is the reference. */
    public Screen screenSize() {
        if (!device()) return null;
        Screen s = Screen.parse(shell("wm size"), shell("dumpsys window displays"));
        if (s == null) throw new IllegalStateException("The phone does not say its screen's size (wm size)");
        return s;
    }

    /** A place written for the reference screen, as a place on this phone's: the emulator's own (nothing asked), a real phone's scaled by its size. */
    public Point place(int x, int y) {
        return device() ? screenSize().scaled(x, y) : new Point(x, y);
    }

    /** A finger at a place written for the reference screen ({@link #REF_WIDTH} x {@link #REF_HEIGHT}). */
    public void tapAt(int x, int y) {
        Point p = place(x, y);
        shell("input tap " + p.x + " " + p.y);
        pause.accept(900);
    }

    /** A finger drawn between two places written for the reference screen. */
    public void swipe(int x1, int y1, int x2, int y2, int ms) {
        if (device()) {
            Screen s = screenSize();
            Point a = s.scaled(x1, y1), b = s.scaled(x2, y2);
            shell("input swipe " + a.x + " " + a.y + " " + b.x + " " + b.y + " " + ms);
        } else {
            shell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
        }
    }

    public void scrollDown() {
        swipe(540, 1700, 540, 700, 300);
        pause.accept(700);
    }

    /** Waits until the phone's screen says so; fails with a picture of how it was instead. */
    public void untilSees(String part, long ms) {
        long end = clock.getAsLong() + ms;
        while (!sees(part)) {
            if (clock.getAsLong() > end) {
                shot("phone: TIMED OUT after " + ms + " ms waiting for \"" + part + "\"");
                throw new AssertionError("Timed out after " + ms + " ms waiting for the phone to say \"" + part + "\". On it: " + texts());
            }
            pause.accept(500);
        }
    }

    /** The phone's screen as a picture, a step in the film. */
    public BufferedImage shot(String action) {
        BufferedImage img = null;
        try {
            img = ImageIO.read(new ByteArrayInputStream(adbBytes(40, "exec-out", "screencap", "-p")));
        } catch (IOException | RuntimeException ignored) {
        }
        if (film != null) film.add(action, img, device() ? "phone" : "emulator", img == null ? "no picture of the phone's screen" : null);
        return img;
    }

    // ------------------------------------------------------------------ the world around the phone

    /** What only some phones can do, asked for on one that cannot: said, never passed over. */
    private void need(Capability c, String what) {
        if (can(c)) return;
        String text = what + " needs " + c + ", which this phone does not have (a real phone has no emulator console and no root): not done.";
        refusals.add(text);
        say("REFUSED: " + text);
        throw new Refused(text);
    }

    /** A port of this PC that leads to the phone's UDP port (the emulator's console; a real phone is reached at its address: {@link #linkAddress}). */
    public int forwardUdp(int phonePort) {
        need(Capability.UDP_REDIR, "A forwarded UDP port");
        int host = de.droiddrone.testing.Ports.free();
        String said = adb("emu", "redir", "add", "udp:" + host + ":" + phonePort);
        if (said.toLowerCase().contains("ko") || said.toLowerCase().contains("error")) throw new IllegalStateException("The emulator did not forward the port: " + said);
        forwarded.add(host);
        return host;
    }

    /**
     * Where this PC reaches the phone's link port: on the emulator a freshly forwarded port on loopback
     * ({@link #forwardUdp}); for a real phone the address and port of the hardware file. The way there through the
     * fence is the fence's own, a {@link Fence.Reach}, and this rig opens nothing: with a fence up (a scenario with
     * the Windows app) it must be one that went up with this phone's reach ({@link AppRig.Plan#reach},
     * {@link Target#reach}) - asked here, before the first packet would be refused; with none up (a scenario of the
     * PC's link alone) the same rule holds all the same: only in a hardware run's JVM.
     */
    public InetSocketAddress linkAddress() {
        if (can(Capability.UDP_REDIR)) return new InetSocketAddress(InetAddress.getLoopbackAddress(), forwardUdp(linkOnThePhone()));
        InetSocketAddress at = target.link();
        if (Fence.isUp()) {
            if (!Fence.reach().allows(at.getAddress().getHostAddress(), at.getPort())) {
                throw new IllegalStateException("The fence that is up lets out " + Fence.reach() + " - not this phone's link port. A phone scenario starts "
                        + "its app with AppRig.scenario(...).reach(target.reach())");
            }
        } else {
            Fence.Reach.requireHardwareRun();
        }
        if (!linkSaid) {
            linkSaid = true;
            say("The phone's link port is reached at the hardware file's address, port " + at.getPort() + (Fence.isUp() ? ": the one way the fence has through it." : "."));
        }
        return at;
    }

    /**
     * A TCP port of the phone's own loopback led to the same port of this PC's ({@code adb reverse}): how a real
     * phone fetches from a web server a scenario has made up on this PC (the emulator has 10.0.2.2 for that). Sent
     * again whenever asked - the cable pulled for a moment loses it; taken away when the rig closes.
     */
    public void reverseTcp(int port) {
        String said = adb("reverse", "tcp:" + port, "tcp:" + port);
        if (said.toLowerCase().contains("error")) throw new IllegalStateException("adb did not lead the phone's port " + port + " to this PC: " + said);
        if (!reversed.contains(port)) reversed.add(port);
    }

    /** Every {@link #reverseTcp} sent again (after the cable was out, before a step that needs them). */
    public void reverseAgain() {
        for (int port : List.copyOf(reversed)) reverseTcp(port);
    }

    /** The phone can fetch from this PC's loopback port under {@link Target#thisPc}: nothing to do on the emulator, {@link #reverseTcp} on a real phone. */
    public void reachesThisPc(int port) {
        if (device()) reverseTcp(port);
    }

    public void gps(double lat, double lon) {
        need(Capability.EMU_GPS, "A made-up place");
        adb("emu", "geo", "fix", String.valueOf(lon), String.valueOf(lat));
    }

    /** Battery per cent, on the charger or not (the emulator's made-up battery; a real phone's is read: {@link #readBattery}). */
    public void battery(int percent, boolean charging) {
        need(Capability.EMU_BATTERY, "A made-up battery level");
        adb("emu", "power", "capacity", Integer.toString(percent));
        adb("emu", "power", "ac", charging ? "on" : "off");
        adb("emu", "power", "status", charging ? "charging" : "discharging");
    }

    /** The phone's own network gone (aeroplane mode on) or back. On a real phone only while adb comes over the cable, and back on when the rig closes. */
    public void aeroplane(boolean on) {
        shell("cmd connectivity airplane-mode " + (on ? "enable" : "disable"));
    }

    /** The phone's Wi-Fi off or on. On a real phone off only while adb comes over the cable, and on again when the rig closes. */
    public void wifi(boolean on) {
        shell("svc wifi " + (on ? "enable" : "disable"));
    }

    /** Android's deep doze forced on, or the phone woken. */
    public void doze(boolean on) {
        if (on) {
            onBattery();
        } else {
            plugged();
            shell("input keyevent KEYCODE_WAKEUP");
        }
    }

    /**
     * The phone made to believe it is off its cable, and put into deep doze at once - what a phone on a drone's
     * battery does by itself and one on adb's cable never does. Read the class comment for what this does not prove.
     * Taken back by {@link #plugged}, by {@link #close} whatever happens, and by {@link PhoneReset} after a killed run.
     *
     * @return whether Android says it is in deep doze now
     */
    public boolean onBattery() {
        pretending = true;
        shell("dumpsys battery unplug");
        String said = shell("dumpsys deviceidle force-idle");
        boolean idle = !said.toLowerCase().contains("unable");
        if (device()) {
            say("Pretended off the cable (dumpsys battery unplug), deep doze forced: " + (said.isBlank() ? "(nothing said)" : said)
                    + ". Android's battery figures stand still until plugged().");
        }
        return idle;
    }

    /** The pretence of {@link #onBattery} taken back: doze no longer forced, the battery as it really is. */
    public void plugged() {
        shell("dumpsys deviceidle unforce");
        shell("dumpsys battery reset");
        pretending = false;
    }

    /** A real phone sleeps "on battery" in the scenarios that put it to sleep - unless the run says not to ({@code -Dbench.phone.onBattery=false}). The emulator never: as before. */
    public boolean sleepsOnBattery() {
        return device() && !"false".equalsIgnoreCase(System.getProperty("bench.phone.onBattery", "true"));
    }

    // ------------------------------------------------------------------ a real phone's battery

    /**
     * The battery's level, temperature and charging state now ({@code dumpsys battery}). While {@link #onBattery}
     * is in force Android's figures stand still; the real ones are then asked of the battery's own files, which
     * some phones let adb read and some do not ({@link Battery#live}).
     */
    public Battery readBattery() {
        Battery b = Battery.parse(own("shell", "dumpsys battery"));
        if (b != null && b.pretended()) {
            try {
                b = b.withReal(own("shell", "cat /sys/class/power_supply/battery/capacity /sys/class/power_supply/battery/temp 2>/dev/null"));
            } catch (RuntimeException ignored) {
            }
        }
        return b;
    }

    /**
     * A real phone's battery looked at, and the bench's two rules for it kept: hotter than {@value #HOT_C} degrees
     * the scenario waits here until it is {@value #COOL_C} or cooler (half an hour at most, then it stops); below
     * {@value #LOW_PERCENT} per cent it stops ({@link Stop}), and so does every command after. Done by itself once
     * a minute as commands go to the phone, and whenever a scenario asks - between rounds, where a wait costs no
     * measurement. Nothing on the emulator.
     */
    public synchronized Battery checkBattery() {
        if (!device()) return null;
        batteryCheckedMs = clock.getAsLong();
        Battery b;
        try {
            b = readBattery();
        } catch (RuntimeException e) {
            b = null;
        }
        if (b == null) {
            say("The phone's battery could not be read (dumpsys battery).");
            return null;
        }
        if (!b.live()) {
            if (!standingStillSaid) {
                standingStillSaid = true;
                say("While the phone is pretended off its cable Android's battery figures stand still, and this phone does not let adb read the real ones: "
                        + "its level and heat are not watched until plugged() (last seen: " + b + ").");
            }
            return b;
        }
        low(b);
        if (b.celsius() > HOT_C) {
            long since = clock.getAsLong();
            say("PAUSED: the phone's battery is " + b + " - hotter than " + HOT_C + ": waiting until it is " + COOL_C + " or cooler.");
            while (b.celsius() > COOL_C) {
                if (clock.getAsLong() - since > COOL_DOWN_MAX_MS) {
                    stopped = "The phone's battery is still " + b + " after " + COOL_DOWN_MAX_MS / 60_000 + " minutes of waiting (the bench pauses above " + HOT_C
                            + "): the run stops here. Take the load off the phone and let it cool.";
                    say("STOPPED: " + stopped);
                    throw new Stop(stopped);
                }
                pause.accept(30_000);
                Battery next = readBattery();
                if (next == null || !next.live()) break;
                b = next;
                low(b);
            }
            say("Went on after " + (clock.getAsLong() - since) / 60_000 + " min: the phone's battery is " + b + ".");
            batteryCheckedMs = clock.getAsLong();
        }
        return b;
    }

    private void low(Battery b) {
        if (b.percent() >= LOW_PERCENT) return;
        stopped = "The phone's battery is at " + b + " (the bench stops below " + LOW_PERCENT + " %): the run stops here. Charge the phone and start again.";
        say("STOPPED: " + stopped);
        throw new Stop(stopped);
    }

    // ------------------------------------------------------------------ the end

    /**
     * Everything given back. The emulator: its forwarded ports, as before. A real phone, whatever happened in the
     * scenario and whether this rig pretended or a killed run before it did: doze no longer forced and the battery as
     * it is ({@code dumpsys deviceidle unforce}, {@code dumpsys battery reset} - always), the network switches this
     * rig turned off turned on again, the reverse ports this rig asked adb for removed (no others: the bench build's
     * flight controller comes down such a port too), the log tap ended.
     */
    @Override
    public void close() {
        for (int host : forwarded) {
            try {
                own("emu", "redir", "del", "udp:" + host);
            } catch (RuntimeException ignored) {
            }
        }
        forwarded.clear();
        if (device() || pretending) {
            quietly("shell", "dumpsys deviceidle unforce");
            quietly("shell", "dumpsys battery reset");
            pretending = false;
        }
        if (switchedOff.contains(PhoneGuard.Undo.AIRPLANE)) quietly("shell", "cmd connectivity airplane-mode disable");
        if (switchedOff.contains(PhoneGuard.Undo.WIFI)) quietly("shell", "svc wifi enable");
        if (switchedOff.contains(PhoneGuard.Undo.DATA)) quietly("shell", "svc data enable");
        switchedOff.clear();
        for (int port : reversed) quietly("reverse", "--remove", "tcp:" + port);
        reversed.clear();
        if (tap != null) {
            tap.close();
            tap = null;
        }
    }
}
