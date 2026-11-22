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
 * order ({@code PhoneRigEmulatorTest} holds it to that). Since Q-006 (2.3.0-pc.58: the phone answers over Tailscale
 * only, and the emulator has no Tailscale) the app flown there is the bench build ({@code flight-app\build.ps1
 * -Bench}, {@link BenchControl#PACKAGE}), whose link takes every address in the emulator alone; the released app's
 * package is put there for the update test only, whose builds do the same ({@link #installApp}, {@link #stopTheOther}).</li>
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
 * <b>A real phone's lock screen</b> ({@link Lock}, since the first run on the phone, 2026-11-22). The phones on the
 * drones have a PIN, and the app flies, sleeps and wakes behind that lock screen: a run behind it is the realistic
 * one. The rig never enters a PIN and never changes the lock; nothing it may send could (no text, no key but WAKEUP,
 * BACK and HOME, a finger only on the app's own controls). At each launch it says in the film which state the phone
 * is in. Behind a secure lock screen, what a scenario would read off the screen is taken the other way there is -
 * the app's own log, Android's word that its service lives, the bench build's answer ({@link PhoneWords}) - and the
 * film says so; what only the screen could show is skipped by name ({@link #skip}) and listed when the rig closes,
 * never passed over and never counted as passed. When the app itself comes in front of the lock screen (woken by a PC, opened again after an error) the
 * screen is the app's again and is read as ever. A phone found unlocked is kept awake on its cable for the run
 * ({@code svc power stayon usb}), its own setting put back exactly when the rig closes or by {@link PhoneReset}.
 * <b>A screen that cannot be read</b> ({@link #reading}, since the Android 11 emulator, 2026-11-22): uiautomator's
 * own words go into the film; on the emulator what can be had another way is taken so, as behind a lock screen (the
 * bench build's word, the app's process and window), and the film says which way.
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
     * @param app                     the package the scenarios fly: the bench build's (on the emulator since Q-006: the
     *                                released app answers over Tailscale only); on a real phone the bench build's
     *                                unless the run says otherwise
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

        /**
         * The bench's emulator, flying the bench build (Q-006: the released app answers over Tailscale only, and the
         * emulator has none; the bench build's link takes every address there, and only there).
         */
        public static Target emulator() {
            String local = local();
            return new Target(SERIAL, Kind.EMULATOR, BenchControl.PACKAGE, null, EnumSet.allOf(Capability.class), true,
                    local == null ? null : Path.of(local, "Android", "Sdk"), local == null ? null : Path.of(local, "DroidDroneBuild", "avd"), null);
        }

        /** The same phone, flying the other of DRAUPNE Flight's two packages (the update test on the emulator: the released app's). */
        public Target flying(String otherApp) {
            return new Target(serial, kind, otherApp, link, can, releasedAppMayBeChanged, sdk, avdHome, apk);
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

    /**
     * One thing on the phone's screen.
     *
     * @param app the package whose window it is in (the lock screen's and the system's own are not the app's)
     */
    public record Node(String text, String id, String description, Rectangle bounds, boolean clickable, boolean enabled, String app) {
        public String shortId() {
            return id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;
        }
    }

    /** How a real phone's screen stands when the bench comes to it. */
    public enum Lock {
        /** No lock screen showing: the screen is whatever app is in front. */
        UNLOCKED,
        /** A lock screen with nothing to enter (a swipe): adb sends it away as a hand's swipe does. */
        SWIPE,
        /** A secure lock screen - a PIN, a pattern, a password. Nobody enters it for the bench; the run goes on behind it. */
        SECURE,
        /** What the phone says of its lock screen was not understood: taken by what its screen then shows. */
        NOT_KNOWN
    }

    /**
     * What Android says of the lock screen at one moment.
     *
     * @param occluded an app has put itself in front of the lock screen (the app does, when a PC wakes it); null: not said
     * @param focus    the window that has the keys, as Android names it; "" when not said
     */
    public record Keyguard(Lock lock, Boolean occluded, String focus) {
        /**
         * From {@code dumpsys window} (whether a lock screen shows, whether something is in front of it, which window
         * has the focus) and {@code dumpsys lock_settings} (whether the phone has anything to enter: "CredentialType").
         * Android's versions and the phone makers word it differently, so each fact is looked for under every name it
         * is known by. A lock screen of which it is not known whether it is secure is taken as secure: nothing is
         * sent to it.
         */
        public static Keyguard read(String window, String lockSettings) {
            String w = window == null ? "" : window, l = lockSettings == null ? "" : lockSettings;
            Boolean showing = said(w, "\\bisKeyguardShowing=", "\\bmKeyguardShowing=", "(?m)^\\s*showing=", "\\bmShowingLockscreen=");
            Boolean occluded = said(w, "\\bmKeyguardOccluded=", "\\bisKeyguardOccluded=", "(?m)^\\s*occluded=");
            Matcher f = Pattern.compile("mCurrentFocus=Window\\{\\S+ \\S+ ([^}]+)}").matcher(w);
            String focus = f.find() ? f.group(1).trim() : "";
            if (showing == null && Boolean.TRUE.equals(said(w, "\\bmDreamingLockscreen=")) && focus.contains("NotificationShade")) showing = true;
            if (showing == null) return new Keyguard(Lock.NOT_KNOWN, occluded, focus);
            if (!showing) return new Keyguard(Lock.UNLOCKED, occluded, focus);
            Matcher c = Pattern.compile("(?i)CredentialType:\\s*(\\w+)").matcher(l);
            Boolean secure = c.find() ? Boolean.valueOf(!c.group(1).equalsIgnoreCase("NONE")) : said(w, "(?m)^\\s*secure=", "\\bisKeyguardSecure=");
            return new Keyguard(Boolean.FALSE.equals(secure) ? Lock.SWIPE : Lock.SECURE, occluded, focus);
        }

        /** The first of these names the text has with =true or =false after it; null when it has none. */
        private static Boolean said(String text, String... names) {
            for (String name : names) {
                Matcher m = Pattern.compile(name + "(true|false)\\b").matcher(text);
                if (m.find()) return Boolean.valueOf(m.group(1));
            }
            return null;
        }

        @Override
        public String toString() {
            String state = switch (lock) {
                case UNLOCKED -> "unlocked";
                case SWIPE -> "a lock screen with nothing to enter (a swipe)";
                case SECURE -> "A SECURE LOCK SCREEN (a PIN, a pattern or a password)";
                case NOT_KNOWN -> "not known (the phone's own words about its lock screen were not understood)";
            };
            return state + (Boolean.TRUE.equals(occluded) ? ", an app in front of it" : "") + (focus.isEmpty() ? "" : "; the window in front: " + focus);
        }
    }

    /** A scenario's own way of noting a check that goes on ({@code rig::check}). */
    public interface Checks {
        void check(boolean ok, String what);
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
    /**
     * Checks that need the screen and were not made behind a lock screen; facts taken another way than the screen there
     * - or on the emulator, when its screen could not be read ({@link #unread}).
     */
    private final List<String> skipped = new ArrayList<>(), otherWays = new ArrayList<>();
    private boolean pretending, standingStillSaid, linkSaid, closedOnce;
    /** What uiautomator said at the last read of the screen that failed, while reads go on failing (null once one is read); how many failed in a row. */
    private String screenError;
    private int screenFailures;
    /** A real phone's lock screen as last looked at; the kind of lock it has (asked once: the bench never changes it). */
    private Keyguard keyguard;
    private String lockSettings;
    /** The phone's clock at the last launch: what the app says in its log from then on is this start's. */
    private String launchedAt;
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
            Keyguard found = lookAtTheLock();
            say("The phone's screen as the run finds it: " + found + "." + (found.lock() == Lock.SECURE
                    ? " The run goes on behind the lock screen: what its screen would show is taken from the app's log, Android and the bench build where they "
                    + "know it, and what only the screen could show is skipped and listed at the end." : ""));
            if (found.lock() == Lock.UNLOCKED) keepAwake();
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

    /** The newest phone app this repo has built (flight-app\build.ps1): the released app's package. */
    public static Path builtApk() throws IOException {
        return newest("flight", "flight-app\\build.ps1");
    }

    /**
     * The newest bench build this repo has built (flight-app\build.ps1 -Bench): what the emulator flies since Q-006 -
     * the released app answers over Tailscale only, and the emulator has none.
     */
    public static Path benchApk() throws IOException {
        return newest("flight-bench", "flight-app\\build.ps1 -Bench");
    }

    private static Path newest(String folder, String build) throws IOException {
        Path dir = Path.of(System.getProperty("bench.build", "build")).resolve(folder);
        if (!Files.isDirectory(dir)) throw new IllegalStateException("No phone app built yet: run " + build + " (" + dir + ")");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".apk")).max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("No .apk in " + dir + ": run " + build));
        }
    }

    /** The build of the package this rig flies: the bench build's newest, or the released app's (build/flight). */
    public Path appApk() throws IOException {
        return target.benchBuild() ? benchApk() : builtApk();
    }

    /**
     * The APK over the app that is there. On a real phone only past the guard, which reads the APK's package first.
     * Test-only APKs too ("-t": the bench build is one, on the emulator as on a real phone).
     */
    public void install(Path apk) {
        String said = new String(adbBytes(180, "install", "-r", "-t", apk.toString()), StandardCharsets.UTF_8);
        if (!said.contains("Success")) throw new IllegalStateException("The phone app did not install: " + said);
    }

    /**
     * The emulator: the other of DRAUPNE Flight's two packages stopped, so that the one this rig flies has the link
     * port - both want it, and the emulator may have the released app's from an update test or an earlier run. A
     * real phone's other app is left alone (the guard's rule): there the run's file says which one is flown.
     */
    public void stopTheOther() {
        if (device()) return;
        shell("am force-stop " + (target.benchBuild() ? APP : BenchControl.PACKAGE));
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
     * The app a scenario flies, put on the phone. The emulator gets the newest build of this repo of the package it
     * flies - the bench build since Q-006 ({@link #appApk}) - and the other package is stopped ({@link #stopTheOther}).
     * A real phone keeps the app it has - the bench build is put there once by hand ({@code adb install -t}) - unless
     * the run names an APK ({@code -Pphone.apk=...}); an app that is not there at all stops the scenario.
     */
    public void installApp() throws IOException {
        if (!device()) {
            stopTheOther();
            install(appApk());
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
     * <p>
     * Storage (2026-11-22, the first run on Android 8): the app asks for it on Android 8 and 9 alone (its manifest's
     * maxSdkVersion 28), and until it is answered its first screen waits behind Android's question - the PC never saw
     * the phone. On a newer Android pm only says the app has not asked for it, as Android 8 says of the notifications'
     * permission, which it does not know: what pm answers is not read.
     */
    public void grantPermissions() {
        for (String p : List.of("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "WRITE_EXTERNAL_STORAGE")) {
            shell("pm grant " + target.app() + " android.permission." + p);
        }
        shell("dpm set-active-admin --user 0 " + target.app() + "/de.droiddrone.flight.PowerAdminReceiver");
        if (device()) say("The phone's own lock screen is left as it is (locksettings is never sent to a real phone).");
        else shell("locksettings set-disabled false");
    }

    /**
     * The screen on and its lock swiped away, as a pilot picking the phone up does. On a real phone the lock is looked
     * at first ({@link #lookAtTheLock}): only one with nothing to enter is sent away.
     */
    public void unlock() {
        if (device()) {
            lookAtTheLock();
            return;
        }
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        pause.accept(500);
    }

    /**
     * The app opened, as a tap on its icon does. A real phone's lock screen is looked at first and the film told
     * which state it is in; behind a secure one the app starts all the same - there it is not in front, as on a drone.
     */
    public void launch() {
        if (device()) {
            launchedAt = now();
            Keyguard k = lookAtTheLock();
            shell("am start -n " + target.app() + "/de.droiddrone.flight.MainActivity");
            pause.accept(2500);
            say("The app opened; the phone's screen at this launch: " + k + (k.lock() == Lock.SECURE ? " - the app is behind it." : "."));
            return;
        }
        unlock();
        shell("am start -n " + target.app() + "/de.droiddrone.flight.MainActivity");
        pause.accept(2500);
    }

    // ------------------------------------------------------------------ a real phone's lock screen

    /**
     * The screen woken and the lock screen looked at: unlocked, one that a swipe sends away (and adb then does), or a
     * secure one - to which nothing is sent: no PIN is ever entered and the lock is never changed. Its kind is asked of
     * the phone once ({@code dumpsys lock_settings}; only the word for the kind is kept).
     */
    public Keyguard lookAtTheLock() {
        if (!device()) return new Keyguard(Lock.UNLOCKED, null, "");
        shell("input keyevent KEYCODE_WAKEUP");
        pause.accept(500);
        Keyguard k = keyguardNow();
        if (k.lock() == Lock.SWIPE) {
            shell("wm dismiss-keyguard");
            pause.accept(500);
            Keyguard after = keyguardNow();
            if (after.lock() == Lock.UNLOCKED) say("A lock screen with nothing to enter was showing: swiped away, as a hand does.");
            k = after;
        }
        keyguard = k;
        return k;
    }

    private Keyguard keyguardNow() {
        String window = shell("dumpsys window");
        if (lockSettings == null) {
            // which kind of lock: the one line of it. The rest of what the phone says there stays on the phone
            Matcher kind = Pattern.compile("(?i)CredentialType:\\s*\\w+").matcher(shell("dumpsys lock_settings"));
            lockSettings = kind.find() ? kind.group() : "";
        }
        return Keyguard.read(window, lockSettings);
    }

    /** The lock screen as last looked at ({@link #launch}, {@link #lookAtTheLock}); unlocked on the emulator and before any look. */
    public Lock lock() {
        return keyguard == null ? Lock.UNLOCKED : keyguard.lock();
    }

    /**
     * Whether what Android describes of the screen is something other than the app with a lock screen in front: then
     * nothing a scenario looks for is on it, and nothing on it is the bench's to touch. Asked of the screen itself each
     * time, not remembered - the app locks the screen when it goes to deep sleep, and comes in front of the lock screen
     * by itself when a PC wakes it. Never on the emulator.
     */
    private boolean lockedAway(List<Node> nodes) {
        if (!device()) return false;
        for (Node n : nodes) if (target.app().equals(n.app())) return false; // the app's own screen: without a lock, or in front of it
        Keyguard k = keyguardNow();
        if (k.lock() == Lock.SWIPE) { // locked again meanwhile, with nothing to enter: away with it, and the screen is whatever is behind
            shell("wm dismiss-keyguard");
            pause.accept(500);
            k = keyguardNow();
        }
        keyguard = k;
        return k.lock() != Lock.UNLOCKED; // (not known: taken as locked away, and the film's line at the launch says that it was not known)
    }

    /** The app's screen can be read and pressed now: always on the emulator; on a real phone unless a lock screen is in front of the app. */
    public boolean screenIsTheApps() {
        return !device() || !lockedAway(screen());
    }

    /**
     * A check this run on a real phone does not make - it needs the screen and a lock screen is over the app, or it is
     * one for the emulator alone: said in the film now, and listed with the others when the rig closes. Never passed
     * over in silence, never counted as passed.
     */
    public void skip(String check) {
        skipped.add(check);
        say("SKIPPED in this run: " + check);
    }

    private void otherWay(String fact, String how) {
        otherWays.add(fact + " [" + how + "]");
        say("Behind the lock screen: " + fact + " - known by " + how + ", not by the screen's words.");
    }

    /** The checks skipped so far because a lock screen was over the app. */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** The facts taken another way than from the screen so far, each with the way. */
    public List<String> otherWays() {
        return List.copyOf(otherWays);
    }

    /** What the app (whichever of its processes) said in its log since that moment of the phone's clock ({@link #now}). */
    public String appSaidSince(String stamp) {
        return shell("logcat -d -v time -T '" + stamp + "' -s " + BenchControl.LOG_TAG + ":I");
    }

    /** What the app said in its log since it was last opened by {@link #launch}. */
    public String appSaidSinceLaunch() {
        return launchedAt == null || launchedAt.isBlank() ? "" : appSaidSince(launchedAt);
    }

    /**
     * The app's main screen is up ({@link PhoneWords#SCREEN_TITLE} on it) - or, behind a real phone's lock screen,
     * the app runs: its process is there. On the emulator, when its screen cannot be read (Android 11's, 2026-11-22):
     * the app's process is there and its window is the one in front, and the film says so.
     */
    public void untilAppIsUp(long ms) {
        if (!device()) {
            long end = clock.getAsLong() + ms;
            while (true) {
                Reading r = reading();
                if (r.says(PhoneWords.SCREEN_TITLE)) return;
                if (!r.read() && !pid().isEmpty()) {
                    String front = windowInFront();
                    if (front.startsWith(target.app() + "/")) {
                        unread(r, "the app is up", "its process in Android's list and its window in front (" + front + ")");
                        return;
                    }
                }
                if (clock.getAsLong() > end) {
                    shot("phone: TIMED OUT after " + ms + " ms waiting for \"" + PhoneWords.SCREEN_TITLE + "\"");
                    throw new AssertionError("Timed out after " + ms + " ms waiting for the phone to say \"" + PhoneWords.SCREEN_TITLE + "\". On it: " + r.texts()
                            + (r.read() ? "" : r.why() + "; nor were the app's process and its window in front both there"));
                }
                pause.accept(500);
            }
        }
        long end = clock.getAsLong() + ms;
        while (true) {
            List<Node> nodes = screen();
            for (Node n : nodes) if (n.text().toLowerCase().contains(PhoneWords.SCREEN_TITLE.toLowerCase())) return;
            if (lockedAway(nodes) && !pid().isEmpty()) {
                otherWay("the app is up", "its process in Android's list");
                return;
            }
            if (clock.getAsLong() > end) {
                shot("phone: TIMED OUT after " + ms + " ms waiting for \"" + PhoneWords.SCREEN_TITLE + "\"");
                throw new AssertionError("Timed out after " + ms + " ms waiting for the phone's app to be up (\"" + PhoneWords.SCREEN_TITLE + "\" on its screen, or behind "
                        + "a lock screen its process). The lock screen: " + keyguard + ". On the screen: " + texts());
            }
            pause.accept(500);
        }
    }

    /**
     * START pressed if the app shows it (a start without everything allowed waits for it). Behind a real phone's lock
     * screen nobody can look for the button or press it: there the service has to have started by itself, as it does
     * on a phone that is set up - known by Android's word that it lives and by the app's own log; if it has not, the
     * scenario stops here and says why. The same on the emulator when its screen cannot be read (Android 11's), by
     * Android's word alone.
     */
    public void startIfAsked() {
        if (!device()) {
            Reading r = reading();
            if (r.read()) {
                if (r.texts().contains(PhoneWords.SCREEN_START)) tap(PhoneWords.SCREEN_START);
                return;
            }
            if (!serviceRunsWithin(20_000)) {
                throw new AssertionError("The phone's screen could not be read (uiautomator says \"" + r.error() + "\"): START cannot be looked for or pressed, and "
                        + "the app's service has not started by itself within 20 s (it does when the camera is allowed and the app was started once before).");
            }
            unread(r, "the service runs and listens for a PC (no START to press)", "Android's list of living services");
            return;
        }
        List<Node> nodes = screen();
        if (!lockedAway(nodes)) {
            for (Node n : nodes) {
                if (n.text().equals(PhoneWords.SCREEN_START) && target.app().equals(n.app())) {
                    tap(PhoneWords.SCREEN_START);
                    return;
                }
            }
            return;
        }
        boolean runs = serviceRunsWithin(20_000);
        String log = appSaidSinceLaunch();
        if (log.contains(PhoneWords.LINK_PORT_TAKEN)) {
            throw new AssertionError("The phone app could not have its link port (its log: \"" + PhoneWords.LINK_PORT_TAKEN + "\"): is the other DRAUPNE Flight "
                    + "running on this phone? One of the two at a time - both want the same port.");
        }
        // Q-006: on a real phone the link is on Tailscale or silent
        if (log.contains(PhoneWords.LINK_NOT_BOUND)) {
            say("The phone app could not bind its link port on its Tailscale address (its log: \"" + PhoneWords.LINK_NOT_BOUND + "\"): is the other DRAUPNE "
                    + "Flight running on this phone? It stays silent and tries again.");
        } else if (log.contains(PhoneWords.NO_TAILSCALE) && !log.contains(PhoneWords.ON_TAILSCALE)) {
            say("The phone app says it is not on Tailscale (its log: \"" + PhoneWords.NO_TAILSCALE + "\"): the PC cannot reach it until Tailscale is on.");
        }
        if (!runs) {
            throw new AssertionError("Behind the lock screen START cannot be looked for or pressed, and the app's service has not started by itself within 20 s "
                    + "(it does when the camera is allowed and the app was started once before). The lock screen: " + keyguard);
        }
        otherWay("the service runs and listens for a PC (no START to press)", "Android's list of living services"
                + (log.contains(PhoneWords.RECEIVER_STARTED) ? " and the app's log: \"" + PhoneWords.RECEIVER_STARTED + "\"" : ""));
    }

    /** The app's service runs now, or does within so long: asked once a second. */
    private boolean serviceRunsWithin(long ms) {
        long end = clock.getAsLong() + ms;
        boolean runs = serviceRunning();
        while (!runs && clock.getAsLong() < end) {
            pause.accept(1000);
            runs = serviceRunning();
        }
        return runs;
    }

    /**
     * The phone says a PC is connected: on its screen ({@link PhoneWords#SCREEN_CONNECTED}); behind a real phone's
     * lock screen by the bench build's own word ({@link PhoneWords#STATS_PC_CONNECTED}). With neither to be had (the
     * released app behind a lock screen) the check is skipped by name. On the emulator, when its screen cannot be read
     * (Android 11's), by the bench build's word too; the released app there has none: the check fails, saying why.
     */
    public void checkConnectedToPc(Checks checks, String what) {
        if (!device()) {
            Reading r = reading();
            if (r.read()) {
                checks.check(r.says(PhoneWords.SCREEN_CONNECTED), what);
            } else if (target.benchBuild()) {
                String stats = benchControl(BenchControl.STATS);
                unread(r, "a PC is connected", "the bench build's own word (" + PhoneWords.STATS_PC_CONNECTED + ")");
                checks.check(stats.contains(PhoneWords.STATS_PC_CONNECTED), what + " - the screen could not be read, by the bench build's own word");
            } else {
                checks.check(false, what + r.why() + ", and the released app has no bench receiver to ask instead");
            }
            return;
        }
        List<Node> nodes = screen();
        if (!lockedAway(nodes)) {
            boolean on = false;
            for (Node n : nodes) on |= n.text().toLowerCase().contains(PhoneWords.SCREEN_CONNECTED.toLowerCase());
            checks.check(on, what);
        } else if (target.benchBuild()) {
            String stats = benchControl(BenchControl.STATS);
            otherWay("a PC is connected", "the bench build's own word (" + PhoneWords.STATS_PC_CONNECTED + ")");
            checks.check(stats.contains(PhoneWords.STATS_PC_CONNECTED), what + " - behind the lock screen, by the bench build's own word");
        } else {
            skip(what + " (a lock screen is over the app, and the released app has no bench receiver to ask instead)");
        }
    }

    /**
     * What the phone says of its flight controller, for the film: its screen's line; behind a lock screen (or on an
     * emulator whose screen cannot be read) the bench build's serial status.
     */
    public String flightControllerWord() {
        Reading r = reading();
        boolean unreadable = !device() && !r.read();
        if (!unreadable && !lockedAway(r.nodes())) {
            List<String> lines = new ArrayList<>();
            for (Node n : r.nodes()) if (n.text().toLowerCase().contains("flight controller")) lines.add(n.text());
            return lines.toString();
        }
        String where = unreadable ? "the screen could not be read" : "behind the lock screen";
        if (!target.benchBuild()) return "(not known: " + where + ", and the released app has no bench receiver)";
        Matcher m = Pattern.compile(PhoneWords.STATS_SERIAL + "(\\S+)").matcher(benchControl(BenchControl.STATS));
        return m.find() ? "(" + where + ", the bench build's serial status) " + m.group(1) + (m.group(1).equals("1") ? " = no flight controller" : "")
                : "(" + where + ": the bench build did not say)";
    }

    /**
     * The emulator's phone says there is no flight controller on its USB (the emulator has no USB): on its screen
     * ({@link PhoneWords#SCREEN_NO_FC}); when the screen cannot be read (Android 11's), by the bench build's serial
     * status - 0 or 1, the two for which the app writes those words. (A real phone may have one: the scenarios only
     * say there what it says, {@link #flightControllerWord}.)
     */
    public void checkNoFlightController(Checks checks, String what) {
        Reading r = reading();
        if (r.read() || !target.benchBuild()) {
            checks.check(r.says(PhoneWords.SCREEN_NO_FC), what + r.why());
            return;
        }
        Matcher m = Pattern.compile(PhoneWords.STATS_SERIAL + "(\\d+)").matcher(benchControl(BenchControl.STATS));
        boolean none = m.find() && (m.group(1).equals("0") || m.group(1).equals("1"));
        unread(r, "no flight controller on USB", "the bench build's serial status (" + PhoneWords.STATS_SERIAL + "0 or 1, for which the app says \""
                + PhoneWords.SCREEN_NO_FC + "\")");
        checks.check(none, what + " - the screen could not be read, by the bench build's serial status");
    }

    /**
     * For the film, never a failure: what can be seen of the app when it should have put itself in front of the lock
     * screen by itself (a PC woke it; it opened again after it was ended) - its own log's word, what Android says of
     * the lock screen and of the window in front, and whether the screen's description is the app's. Whether a phone
     * of this make and Android shows all of that to adb is not known until it is looked at: so this reports.
     */
    public void lookOverTheLock(String when) {
        if (!device()) return;
        List<Node> nodes = screen();
        boolean apps = false;
        List<String> words = new ArrayList<>();
        for (Node n : nodes) {
            apps |= target.app().equals(n.app());
            if (!n.text().isBlank() && words.size() < 6) words.add(n.text());
        }
        Keyguard k = keyguardNow();
        keyguard = k;
        String said = appSaidSinceLaunch();
        say("TO LOOK AT - the app in front of the lock screen, " + when + ": its log " + (said.contains(PhoneWords.OPENED_OVER_LOCK) ? "says" : "does not say")
                + " \"" + PhoneWords.OPENED_OVER_LOCK + "\" since its last launch" + (said.contains(PhoneWords.SLEEP_LOCKED_SCREEN)
                ? " (and \"" + PhoneWords.SLEEP_LOCKED_SCREEN + "\": going to sleep the app locked the screen itself)" : "") + "; the lock screen: " + k
                + "; the screen as Android describes it is " + (apps ? "the app's" : nodes.isEmpty() ? "not described (off, or moving)" : "not the app's") + " " + words + ".");
        shot("phone: " + when + " - what is in front of the lock screen");
    }

    // ------------------------------------------------------------------ a real phone kept awake (found unlocked)

    /**
     * A phone found unlocked stays so for the run: Android's "stay awake while plugged in" for USB, so that its screen
     * timeout does not lock it in the middle. It is a setting of the phone's: its own value is noted first - on the
     * phone itself ({@link PhoneGuard#STAY_ON_NOTE}), where {@link PhoneReset} finds it after a run that was killed -
     * and put back exactly at {@link #close}. A note still there from a killed run is the phone's own value: kept.
     * (It holds while the phone believes it is on its cable: not through {@link #onBattery}, and not past the app's own
     * lock of the screen at a deep sleep.)
     */
    private void keepAwake() {
        String noted = shell("settings get global " + PhoneGuard.STAY_ON_NOTE).trim();
        boolean hadNote = noted.equals("unset") || noted.matches("\\d{1,2}");
        String was;
        if (hadNote) {
            was = noted;
        } else {
            String now = shell("settings get global " + PhoneGuard.STAY_ON).trim();
            if (!now.equals("null") && !now.matches("\\d{1,2}")) { // not a value and not "never set": what would have to be put back is not known
                say("Found unlocked, but what the phone's \"stay awake while plugged in\" is set to could not be read (\"" + now + "\"): left alone - the screen's "
                        + "own timeout may lock the phone in the middle of the run.");
                return;
            }
            was = now.equals("null") ? "unset" : now;
            if (!was.equals("unset") && (Integer.parseInt(was) & 2) != 0) {
                say("Found unlocked, and the phone stays awake on USB already (its own setting: " + was + "): left as it is.");
                return;
            }
            shell("settings put global " + PhoneGuard.STAY_ON_NOTE + " " + was);
        }
        shell("svc power stayon usb");
        say("Found unlocked: kept awake while on USB for this run (svc power stayon usb). The phone's own setting (" + was + ") is put back when the run ends"
                + (hadNote ? " - noted by a run before this one, which did not get to put it back." : "."));
    }

    /** The orders that put the phone's own "stay awake while plugged in" back, from the bench's note of it; none when there is no note. */
    static List<List<String>> stayOnBack(String noted) {
        String n = noted == null ? "" : noted.trim();
        if (n.equals("unset")) {
            return List.of(List.of("shell", "settings delete global " + PhoneGuard.STAY_ON), List.of("shell", "settings delete global " + PhoneGuard.STAY_ON_NOTE));
        }
        if (n.matches("\\d{1,2}") && Integer.parseInt(n) <= 15) {
            return List.of(List.of("shell", "settings put global " + PhoneGuard.STAY_ON + " " + n), List.of("shell", "settings delete global " + PhoneGuard.STAY_ON_NOTE));
        }
        return List.of();
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

    /**
     * One look at the phone's screen: what is on it - or, when uiautomator could not describe it, nothing, and
     * uiautomator's own words why.
     *
     * @param error what uiautomator said instead of the screen, word for word; null when the screen was read
     */
    public record Reading(List<Node> nodes, String error) {
        public boolean read() {
            return error == null;
        }

        /** The words on it. */
        public List<String> texts() {
            List<String> out = new ArrayList<>();
            for (Node n : nodes) if (!n.text().isBlank()) out.add(n.text());
            return out;
        }

        /** Something on it says this (any case). */
        public boolean says(String part) {
            String p = part.toLowerCase();
            for (String t : texts()) if (t.toLowerCase().contains(p)) return true;
            return false;
        }

        /** For a message: nothing when the screen was read, else why not. */
        String why() {
            return read() ? "" : " (the phone's screen could not be read: uiautomator says \"" + error + "\")";
        }
    }

    /** Everything on the phone's screen now, as Android describes it (nothing when it could not be read: {@link #reading}). */
    public List<Node> screen() {
        return reading().nodes();
    }

    /**
     * The phone's screen now, as Android describes it ({@code uiautomator dump}, three tries) - or what uiautomator
     * said instead, word for word, and the film told (once while the same words come back, and when the screen can be
     * read again). Until 2026-11-22 a screen that could not be read was an empty one, said nowhere: on the Android 11
     * emulator (draupne-a30) every read of the app's own screen failed, ~36 s each, with "ERROR: could not get idle
     * state." - uiautomator first waits for one second in which nothing on the screen changes, 10 s at most, and the
     * app's status lines change every second. That wait is uiautomator's own (Android 11's DumpCommand:
     * waitForIdle(1000, 10000), with --compressed as without: that changes only what is dumped) - no option shortens
     * it. Where a scenario's check can be had another way, the emulator then takes it so ({@link #unread}).
     */
    public Reading reading() {
        String xml = "";
        for (int attempt = 0; attempt < 3; attempt++) { // "could not get idle state" while something animates (the STANDBY screen does, all the time)
            xml = new String(adbBytes(40, "exec-out", "uiautomator", "dump", "/dev/tty"), StandardCharsets.UTF_8);
            if (xml.contains("<hierarchy")) break;
            pause.accept(700);
        }
        if (!xml.contains("<hierarchy")) {
            String said = uiautomatorSaid(xml);
            screenFailures++;
            if (!said.equals(screenError)) say("The phone's screen could not be read (3 tries): uiautomator says \"" + said + "\".");
            screenError = said;
            return new Reading(List.of(), said);
        }
        if (screenError != null) {
            say("The phone's screen could be read again, after " + screenFailures + " read(s) that failed (uiautomator: \"" + screenError + "\").");
            screenError = null;
            screenFailures = 0;
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
                    attr(n, "clickable").equals("true"), attr(n, "enabled").equals("true"), attr(n, "package")));
        }
        return new Reading(nodes, null);
    }

    /** uiautomator's own words in what a dump printed that had no screen in it: on one line, 300 characters at most. */
    static String uiautomatorSaid(String output) {
        String s = output == null ? "" : output.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "(nothing)";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /**
     * A fact the emulator's rig took another way because the phone's screen could not be read: said in the film with
     * the way and uiautomator's words, and listed with the others when the rig closes.
     */
    private void unread(Reading r, String fact, String how) {
        otherWays.add(fact + " [" + how + "]");
        say("The phone's screen could not be read (uiautomator says \"" + r.error() + "\"): " + fact + " - known by " + how + ", not by the screen's words.");
    }

    /** The window that has the keys now, as Android names it ("package/activity"); "" when it does not say. */
    private String windowInFront() {
        return Keyguard.read(shell("dumpsys window"), "").focus();
    }

    /**
     * On a real phone a finger goes on the app's own controls and nowhere else: not on a phone maker's dialog, not on
     * the lock screen - whose number pad is made of buttons like any other. (The emulator is the bench's own.)
     */
    private void onlyOnTheApp(Node n, String words) {
        if (!device() || target.app().equals(n.app())) return;
        String text = "\"" + words + "\" on the phone's screen is not the app's own (it belongs to " + (n.app().isEmpty() ? "another window" : n.app())
                + "): on a real phone the bench presses nothing but the app's controls.";
        refusals.add(text);
        say("REFUSED: " + text);
        throw new Refused(text);
    }

    /** A finger at a fixed place only while the screen is the app's: never onto a lock screen. */
    private void onlyOnTheAppsScreen(String what) {
        if (!device() || screenIsTheApps()) return;
        String text = what + ": the phone's screen is not the app's now (the lock screen: " + keyguard + ") - on a real phone the bench touches nothing else.";
        refusals.add(text);
        say("REFUSED: " + text);
        throw new Refused(text);
    }

    /** The words on the screen. */
    public List<String> texts() {
        return reading().texts();
    }

    public boolean sees(String part) {
        return reading().says(part);
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
        onlyOnTheApp(n, words);
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
        onlyOnTheApp(n, words);
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
        onlyOnTheAppsScreen("A finger at a fixed place");
        Point p = place(x, y);
        shell("input tap " + p.x + " " + p.y);
        pause.accept(900);
    }

    /** A finger drawn between two places written for the reference screen. */
    public void swipe(int x1, int y1, int x2, int y2, int ms) {
        if (device()) {
            onlyOnTheAppsScreen("A finger drawn across the screen");
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
                Reading last = reading();
                throw new AssertionError("Timed out after " + ms + " ms waiting for the phone to say \"" + part + "\". On it: " + last.texts() + last.why());
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
        throughTheRouter(phonePort);
        return host;
    }

    /** Phone ports already led through the emulator's own router by {@link #throughTheRouter} (it keeps its rule until it is restarted). */
    private final List<Integer> ledThrough = new ArrayList<>();

    /**
     * The Android 8 to 10 images of the emulator (2026-11-22, draupne-a26: every scenario with the Windows app failed,
     * the PC never saw the phone): Android sits behind a router of the emulator's own, a network namespace "router" with
     * 10.0.2.15 on one side and the phone's Wi-Fi (192.168.232.2) and mobile data on the other. The console's forward
     * ends at 10.0.2.15, in that router, and a packet from this PC never reached the app; Android 11 on has no such
     * router. Where there is one, it is told (as root, which the emulator has) to pass the phone's port on to the
     * phone's Wi-Fi address - once, however many scenarios ask. Elsewhere the address the forward ends at is looked
     * for ({@link #ontoTheEmulatorsCard}) (without root the router cannot even be seen: the first scenario of a run,
     * before any other asked for root, was left out); an emulator held to a real phone's rules (no root) is not looked at.
     */
    private void throughTheRouter(int phonePort) {
        if (!can(Capability.ROOT) || ledThrough.contains(phonePort)) return;
        ledThrough.add(phonePort);
        root();
        if (shell("ip netns list").lines().noneMatch(l -> l.trim().startsWith("router"))) {
            ontoTheEmulatorsCard();
            return;
        }
        Matcher wifi = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)/").matcher(shell("ip -4 -o addr show wlan0"));
        if (!wifi.find()) {
            say("The emulator has a router of its own, but no Wi-Fi address to lead port " + phonePort + " to: the PC may not reach the phone.");
            return;
        }
        String rule = "PREROUTING -i eth0 -p udp --dport " + phonePort + " -j DNAT --to-destination " + wifi.group(1) + ":" + phonePort;
        if (!shell("ip netns exec router iptables -t nat -C " + rule + " 2>&1; echo rc=$?").contains("rc=0")) {
            shell("ip netns exec router iptables -t nat -A " + rule);
        }
        say("The emulator's own router (Android 8 to 10): its port " + phonePort + " led on to the phone at " + wifi.group(1) + ".");
    }

    /** Where the emulator's console forward ends: the address of the emulator's own network card. */
    static final String FORWARDED_TO = "10.0.2.15";

    /**
     * The Android 15 image of the emulator (2026-11-22, draupne-a35: every scenario failed, the PC never saw the phone).
     * The console's forward ends at {@value #FORWARDED_TO} on the emulator's own network card (eth0), which since
     * Android 11 is the phone's mobile data. On this image mobile data never comes up (no SIM chosen for data, no data
     * profile), so the card stays down without an address; the phone's only address is its Wi-Fi's, 10.0.2.16, on
     * the emulator's Wi-Fi network, where nothing from the console arrives. Where no card has the address, the card is
     * given it (as root, once; it lasts until the emulator restarts). The phone's answers still go out over its Wi-Fi,
     * as on the other images (F-039), and reach this PC from another port of its loopback: the {@code Wire} takes them
     * from any port. Android does not know of the card: aeroplane mode leaves it up.
     */
    private void ontoTheEmulatorsCard() {
        String addresses = shell("ip -4 -o addr show");
        if (addresses.isBlank() || addresses.contains("inet " + FORWARDED_TO + "/")) return;
        String said = shell("ip link set eth0 up 2>&1 && ip addr add " + FORWARDED_TO + "/24 dev eth0 2>&1; echo rc=$?");
        if (said.contains("rc=0")) {
            say("The emulator's own card (eth0) had no address - this Android's mobile data never comes up - and the PC's port is forwarded to "
                    + FORWARDED_TO + " on it: given that address, until the emulator restarts. The phone answers over its Wi-Fi.");
        } else {
            say("The emulator's own card (eth0) has no " + FORWARDED_TO + ", where the PC's port is forwarded, and could not be given it (" + said
                    + "): the PC may not reach the phone.");
        }
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
            // Q-006: on a real phone the app answers over Tailscale only - the bench build too (it widens in the emulator alone)
            if (!de.droiddrone.common.TailscaleOnly.peer(at.getAddress())) {
                say("WARNING: the hardware file's phone.ip is not a Tailscale address. Since 2.3.0-pc.58 (Q-006) the phone app - the bench build "
                        + "too, on a real phone - answers over Tailscale only: nothing will answer there. Put the phone's Tailscale address (100.x) in phone.ip.");
            }
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
     * it is ({@code dumpsys deviceidle unforce}, {@code dumpsys battery reset} - always), the phone's own "stay awake
     * while plugged in" back to what the bench's note on the phone says it was (this run's note or a killed run's),
     * the network switches this rig turned off turned on again, the reverse ports this rig asked adb for removed (no
     * others: the bench build's flight controller comes down such a port too), the log tap ended. And the film is told
     * what a lock screen made of the run: the facts taken another way than the screen, the checks skipped.
     */
    @Override
    public void close() {
        if (device() && !closedOnce) {
            closedOnce = true;
            if (!otherWays.isEmpty()) {
                say("BEHIND THE LOCK SCREEN " + otherWays.size() + " fact(s) were taken another way than from the screen's words: " + String.join("; ", otherWays) + ".");
            }
            if (!skipped.isEmpty()) {
                say("ON THE REAL PHONE " + skipped.size() + " CHECK(S) WERE SKIPPED in this run - not made, and not counted as passed: " + String.join("; ", skipped) + ".");
            }
        } else if (!device() && !closedOnce) {
            closedOnce = true;
            if (!otherWays.isEmpty()) {
                say("THE PHONE'S SCREEN COULD NOT BE READ: " + otherWays.size() + " fact(s) were taken another way than from the screen's words: "
                        + String.join("; ", otherWays) + ".");
            }
        }
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
        if (device()) {
            try {
                String noted = own("shell", "settings get global " + PhoneGuard.STAY_ON_NOTE);
                for (List<String> order : stayOnBack(noted)) quietly(order.toArray(new String[0]));
            } catch (RuntimeException e) {
                say("Whether the phone's \"stay awake while plugged in\" has to be put back could not be asked (" + e.getMessage() + "): gradlew phoneReset does it.");
            }
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
