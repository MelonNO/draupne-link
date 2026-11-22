package de.droiddrone.bench;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recorder that stands in adb's door in the fast tests of the phone rig: it writes down every command it is
 * handed, starts nothing, and says what a test told it a phone would say. With it the rig's rules for a real phone
 * are tested on any PC - no phone, no emulator, no adb.
 */
final class RecordingAdb implements PhoneAdb {
    /** Every command run, its words joined by a space (what follows {@code adb -s <serial>}). */
    final List<String> sent = new ArrayList<>();
    /** The time each of them was given, in seconds. */
    final List<Long> timeouts = new ArrayList<>();
    /** Every command followed, and the file its output goes to. */
    final List<String> followed = new ArrayList<>();
    final List<Path> followedInto = new ArrayList<>();
    final List<Boolean> followClosed = new ArrayList<>();
    /** The emulator's arguments, each time it was started. */
    final List<String> emulatorStarts = new ArrayList<>();
    /** A followed command that ends by itself at once (the cable pulled) while this is set. */
    volatile boolean followDies;
    private final Map<String, Deque<String>> answers = new LinkedHashMap<>();
    private final List<String> failing = new ArrayList<>();

    /**
     * A phone that is there and well: on adb, started through, a cool battery at 80 %, the app installed, the bench
     * receiver answering - and unlocked, the bench build's main screen in front, "stay awake" never set.
     */
    static RecordingAdb aPhone() {
        return new RecordingAdb()
                .says("get-state", "device")
                .says("shell getprop sys.boot_completed", "1")
                .says("shell dumpsys battery", battery(80, 301, 2, true))
                .says("shell pm path", "package:/data/app/x/base.apk")
                .says("shell wm size", "Physical size: 1080x2400")
                .says("shell am broadcast", "Broadcasting: Intent { }\nBroadcast completed: result=1, data=\"DRAUPNE-BENCH-FC control: done\"")
                .says("install", "Performing Streamed Install\nSuccess")
                .says("shell dumpsys window", window(false, null, BenchControl.PACKAGE + "/de.droiddrone.flight.MainActivity"))
                .says("shell dumpsys lock_settings", LOCK_SETTINGS_PIN)
                .says("shell settings get global", "null")
                .says("exec-out uiautomator", screen(BenchControl.PACKAGE, "DRAUPNE Flight BENCH", "2.3.0-pc.55-bench · for DRAUPNE Link", "No flight controller on USB"));
    }

    /** The same phone with its secure lock screen showing and the app behind it: as the bench's phone was on its first run. */
    static RecordingAdb aLockedPhone() {
        return aPhone().says("shell dumpsys window", WINDOW_SECURE_LOCK).says("exec-out uiautomator", LOCK_SCREEN);
    }

    /**
     * {@code dumpsys window}, the lines of it the rig reads. With a secure lock screen showing the first run on the
     * phone had "isKeyguardShowing=true" and "mDreamingLockscreen=true" in it, and the focus on the NotificationShade.
     */
    static String window(boolean lockScreenShowing, Boolean occluded, String focus) {
        return "WINDOW MANAGER POLICY STATE (dumpsys window policy)\n    mShowingDream=false mDreamingLockscreen=" + lockScreenShowing + " mDreamingSleepToken=null\n"
                + "    isKeyguardShowing=" + lockScreenShowing + "\n" + (occluded == null ? "" : "    mKeyguardOccluded=" + occluded + " mKeyguardOccludedChanged=false\n")
                + "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #7 Window{9c1e0aa u0 NotificationShade}:\n"
                + "  mCurrentFocus=Window{4d2f1a7 u0 " + focus + "}\n  mFocusedApp=ActivityRecord{71b9c3e u0 " + BenchControl.PACKAGE + "/de.droiddrone.flight.MainActivity t412}\n";
    }

    static final String WINDOW_SECURE_LOCK = window(true, false, "NotificationShade");
    /** {@code dumpsys lock_settings}: what kind of lock the phone has. (Nothing else of it is kept by the rig.) */
    static final String LOCK_SETTINGS_PIN = "Current lock settings service state:\n\nUser State:\n  User 0\n    SP Handle: 4f1a2b3c4d5e6f70\n    Quality: 0\n"
            + "    CredentialType: PIN\n    SeparateChallenge: true\n    Metrics: known\n";
    static final String LOCK_SETTINGS_NONE = "Current lock settings service state:\n\nUser State:\n  User 0\n    Quality: 0\n    CredentialType: NONE\n";
    /** What Android describes of a lock screen: the clock and the date, the system's own window. */
    static final String LOCK_SCREEN = screen("com.android.systemui", "00", "51", "Sunday 22 November");

    /** {@code uiautomator dump}: a screen of that package with these words on it, one under the other. */
    static String screen(String app, String... words) {
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        for (int i = 0; i < words.length; i++) {
            xml.append("<node index=\"").append(i).append("\" text=\"").append(words[i]).append("\" resource-id=\"").append(app).append(":id/t").append(i)
                    .append("\" class=\"android.widget.TextView\" package=\"").append(app).append("\" content-desc=\"\" clickable=\"true\" enabled=\"true\" bounds=\"[60,")
                    .append(200 + 100 * i).append("][660,").append(280 + 100 * i).append("]\" />");
        }
        return xml.append("</hierarchy>").toString();
    }

    /** What {@code dumpsys battery} prints. */
    static String battery(int level, int tenthsC, int status, boolean usb) {
        return "Current Battery Service state:\n  AC powered: false\n  USB powered: " + usb + "\n  Wireless powered: false\n  Dock powered: false\n"
                + "  Max charging current: 500000\n  Max charging voltage: 5000000\n  Charge counter: 2985000\n  status: " + status + "\n  health: 2\n  present: true\n"
                + "  level: " + level + "\n  scale: 100\n  voltage: 4281\n  temperature: " + tenthsC + "\n  technology: Li-ion\n";
    }

    /**
     * What the phone says to a command that starts so - one answer after the other, the last one from then on. The
     * longest start that fits a command wins.
     */
    RecordingAdb says(String commandStart, String... answer) {
        answers.put(commandStart, new ArrayDeque<>(List.of(answer)));
        return this;
    }

    /** adb fails (the phone gone) on commands that start so. */
    RecordingAdb fails(String commandStart) {
        failing.add(commandStart);
        return this;
    }

    @Override
    public synchronized byte[] run(long timeoutS, List<String> args) {
        String command = String.join(" ", args);
        sent.add(command);
        timeouts.add(timeoutS);
        for (String f : failing) if (command.startsWith(f)) throw new IllegalStateException("adb " + command + " did not end within " + timeoutS + " s");
        String best = null;
        for (String start : answers.keySet()) if (command.startsWith(start) && (best == null || start.length() > best.length())) best = start;
        if (best == null) return new byte[0];
        Deque<String> q = answers.get(best);
        String a = q.size() > 1 ? q.poll() : q.peek();
        return a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public synchronized Running follow(List<String> args, Path into) {
        followed.add(String.join(" ", args));
        followedInto.add(into);
        int n = followClosed.size();
        followClosed.add(false);
        boolean dies = followDies;
        return new Running() {
            @Override
            public boolean alive() {
                synchronized (RecordingAdb.this) {
                    return !dies && !followClosed.get(n);
                }
            }

            @Override
            public void close() {
                synchronized (RecordingAdb.this) {
                    followClosed.set(n, true);
                }
            }
        };
    }

    @Override
    public synchronized void startEmulator(List<String> args, Path log) {
        emulatorStarts.add(String.join(" ", args));
    }

    /** The commands sent since that many. */
    synchronized List<String> since(int count) {
        return List.copyOf(sent.subList(count, sent.size()));
    }

    synchronized int count() {
        return sent.size();
    }

    synchronized int followedCount() {
        return followed.size();
    }
}
