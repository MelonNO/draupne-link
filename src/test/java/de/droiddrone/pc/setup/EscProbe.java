package de.droiddrone.pc.setup;

import de.droiddrone.common.BeeperQuiet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * By hand, on a real drone on USB (battery in, props off) - as quiet as each step can be:
 * <pre>
 * gradlew escProbe -Pesc=check     SILENT. Asks the flight controller only: Betaflight, battery, armed, whether each
 *                                  ESC answers it (DShot telemetry), the beeper settings. Nothing restarts.
 * gradlew escProbe -Pesc=beeper    SILENT. The check, then the phone app's beeper silence (MSP 185) written to the board's
 *                                  memory, read back, and put back as it was. Nothing is saved.
 * gradlew escProbe                 (read) The check, then every ESC's firmware and beacon through the ESC passthrough,
 *                                  then the check again. SOUND: the ESCs' start-up tones, once, at the end - an ESC
 *                                  that was in its bootloader starts again, and says so. No motor turns.
 * gradlew escProbe -Pesc=row       The same visit as Drone setup's Read ESCs button makes (EscCheck), and the line its
 *                                  row then shows. One round of start-up tones.
 * gradlew escProbe -Pesc=silence   As read, and the beacon switched off on the ESCs that have it on, in the same
 *                                  visit: still one round of start-up tones.
 * gradlew escProbe -Pesc=spin      NOISE, only when asked for: after the read, each motor in turn for 0.8 s at a
 *                                  low value. For when the silent check can't say whether the ESCs work again.
 * gradlew escProbe -Pesc=stop      STOP to every motor, and what they do now.
 * gradlew escProbe -Pesc=spinonly  each motor turning for a moment with no visit to the ESCs before it.
 * </pre>
 * The port is the first flight controller found, or {@code -Pport=COM9}. One visit to the ESCs a run, whatever is
 * done there: every visit ends in their start-up tones.
 */
public final class EscProbe {
    private static final int MSP_MOTOR_CONFIG = 131, MSP_MOTOR_TELEMETRY = 139, MSP_ANALOG = 110, MSP_STATUS = 101;
    /** A spin, when asked for: as low and short as shows a motor turning. */
    private static final int SPIN_VALUE = 1060;
    private static final long SPIN_MS = 800;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 && !args[0].isEmpty() ? args[0] : "read";
        String wanted = args.length > 1 ? args[1] : "";
        if (!List.of("check", "beeper", "read", "row", "silence", "spin", "spinonly", "stop").contains(mode)) {
            System.out.println("Unknown: " + mode + " (check, beeper, read, row, silence, spin, spinonly, stop)");
            return;
        }
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        System.out.println("Flight controllers: " + ports);
        BetaflightUsb.Port port = ports.stream().filter(p -> wanted.isEmpty() || p.systemName().equalsIgnoreCase(wanted)).findFirst().orElse(null);
        if (port == null) {
            System.out.println("None" + (wanted.isEmpty() ? "" : " on " + wanted) + ": USB in?");
            return;
        }
        System.out.println("Sound to expect: " + switch (mode) {
            case "check", "stop", "beeper" -> "none.";
            case "spin" -> "the ESCs' start-up tones, then each motor turning for " + SPIN_MS / 1000.0 + " s.";
            case "spinonly" -> "each motor turning for " + SPIN_MS / 1000.0 + " s (no visit to the ESCs, no start-up tones).";
            default -> "the ESCs' start-up tones, once, at the end. No motor turns.";
        });
        if (mode.equals("row")) { // what Drone setup's Read ESCs button runs, and the line its row then shows
            EscCheck.Result r = EscCheck.run(() -> BetaflightUsb.open(port), false, Path.of(System.getProperty("java.io.tmpdir"), "draupne-esc-backup"), s -> System.out.println("  " + s));
            System.out.println("The ESCS row: [" + r.state() + "] " + r.text() + (r.canSilence() ? "   (Beacon off is offered)" : "   (no Beacon off button)"));
            System.out.println("The ESCs start again now (their tones). Waiting 7 s...");
            Thread.sleep(7000);
            try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                check(fc, "After");
            }
            return;
        }
        try (BetaflightUsb fc = BetaflightUsb.open(port)) {
            if (!fc.firmware().startsWith("BTFL")) {
                System.out.println(port.systemName() + ": not Betaflight (" + fc.firmware() + ")");
                return;
            }
            if (mode.equals("stop")) {
                fc.msp(MotorTest.MSP_SET_MOTOR, MotorTest.setMotorPayload(new int[0]));
                Thread.sleep(500);
            }
            boolean battery = check(fc, "Now");
            if (mode.equals("beeper")) {
                beeper(fc);
                return;
            }
            if (mode.equals("check") || mode.equals("stop")) return;
            if (!battery) {
                System.out.println("No battery: an ESC without power doesn't answer. Nothing more done.");
                return;
            }
            if (mode.equals("spinonly")) { // the spin alone: no ESC visit before it, so a visit cannot be what changed an ESC
                spin(fc);
                return;
            }
            long t0 = System.currentTimeMillis();
            try (EscLink escs = fc.escs(s -> System.out.println("  " + s))) {
                List<EscFirmware.Info> all = escs.infoAll();
                for (EscFirmware.Info i : all) System.out.println("  " + i);
                System.out.println("ESCs: " + EscFirmware.summary(all) + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (mode.equals("silence")) {
                    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "draupne-esc-backup");
                    boolean any = false;
                    for (EscFirmware.Info i : all) {
                        if (!i.canSilence()) continue;
                        any = true;
                        try {
                            System.out.println("  after: " + escs.silence(i.index(), dir));
                        } catch (IOException e) {
                            System.out.println("  FAILED: " + e.getMessage());
                        }
                    }
                    System.out.println(any ? "ESCs now: " + EscFirmware.summary(escs.infoAll()) + "; the old settings are in " + dir
                            : "No ESC here has a beacon this app can switch off: nothing written.");
                }
            }
            System.out.println("The ESCs start again now (their tones). Waiting 7 s...");
            Thread.sleep(7000);
            check(fc, "After");
            if (mode.equals("spin")) spin(fc);
        }
    }

    /**
     * What the flight controller says, without a sound: the battery, armed or not, and of each ESC whether it answers
     * (bidirectional DShot: the share of its telemetry frames that did not come - a running ESC answers nearly all,
     * one that is off or in its bootloader none), and the beeper settings pc.54's phone app changes around a sleep.
     * @return whether a battery is in
     */
    private static boolean check(BetaflightUsb fc, String when) throws Exception {
        byte[] v = fc.msp(de.droiddrone.common.FcCommon.MSP_FC_VERSION, null);
        boolean armed = MotorTest.armed(fc.msp(MSP_STATUS, null));
        BetaflightUsb.BatteryState b = null;
        try {
            b = fc.batteryState();
        } catch (IOException e) {
            // an old Betaflight: no word
        }
        System.out.println(when + ": Betaflight " + (v.length >= 3 ? (v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff) : "?")
                + ", " + (armed ? "ARMED" : "disarmed") + ", battery " + (b == null ? "?" : b.present() ? b.volts() + " V, " + b.cells() + " cells" : "NOT IN (" + b.volts() + " V)")
                + ", motor outputs " + java.util.Arrays.toString(java.util.Arrays.copyOf(MotorTest.motors(fc.msp(MotorTest.MSP_MOTOR, null)), 4)));
        System.out.println("  ESCs answering the flight controller: " + answering(fc));
        try {
            byte[] beeper = fc.msp(BeeperQuiet.MSP_BEEPER_CONFIG, null);
            System.out.println("  Beeper settings (MSP 184): " + beeper.length + " bytes " + BeeperQuiet.hex(beeper)
                    + (BeeperQuiet.understood(beeper) ? " - lost-receiver sounds " + (BeeperQuiet.isQuiet(beeper) ? "off" : "on (or partly)") : " - not understood"));
        } catch (IOException e) {
            System.out.println("  Beeper settings (MSP 184): " + e.getMessage());
        }
        return b == null || b.present();
    }

    /**
     * SILENT. What the phone app does around a deep sleep (pc.54, BeeperQuiet), tried on a real board: the lost-receiver
     * sounds switched off in the flight controller's memory, read back, and put back as they were, read back. Nothing
     * is saved: a power cycle would bring the board's own settings back anyway.
     */
    private static void beeper(BetaflightUsb fc) throws Exception {
        byte[] read = fc.msp(BeeperQuiet.MSP_BEEPER_CONFIG, null);
        byte[] before = BeeperQuiet.asRead(read), quiet = BeeperQuiet.quiet(read);
        if (quiet == null) {
            System.out.println("Not understood (" + read.length + " bytes): nothing written.");
            return;
        }
        try {
            fc.msp(BeeperQuiet.MSP_SET_BEEPER_CONFIG, quiet);
            byte[] now = fc.msp(BeeperQuiet.MSP_BEEPER_CONFIG, null);
            System.out.println("  written " + BeeperQuiet.hex(quiet) + ", read back " + BeeperQuiet.hex(now) + ": "
                    + (java.util.Arrays.equals(BeeperQuiet.asRead(now), quiet) && BeeperQuiet.isQuiet(now) ? "QUIET, as written" : "NOT as written"));
        } finally {
            fc.msp(BeeperQuiet.MSP_SET_BEEPER_CONFIG, before);
            byte[] after = fc.msp(BeeperQuiet.MSP_BEEPER_CONFIG, null);
            System.out.println("  put back " + BeeperQuiet.hex(before) + ", read back " + BeeperQuiet.hex(after) + ": "
                    + (java.util.Arrays.equals(BeeperQuiet.asRead(after), before) ? "as it was" : "NOT AS IT WAS - unplug the battery and USB to get the board's own settings back"));
        }
    }

    /** "1: yes (0 % missing, 0 rpm)  2: NO (100 % missing) ..." or why it can't be said. */
    private static String answering(BetaflightUsb fc) {
        try {
            byte[] c = fc.msp(MSP_MOTOR_CONFIG, null);
            if (c.length < 9 || c[8] == 0) return "not known - bidirectional DShot is off on this board";
            byte[] t = fc.msp(MSP_MOTOR_TELEMETRY, null);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < (t.length > 0 ? t[0] : 0) && 1 + i * 13 + 6 <= t.length; i++) {
                int at = 1 + i * 13;
                long rpm = (t[at] & 0xffL) | (t[at + 1] & 0xffL) << 8 | (t[at + 2] & 0xffL) << 16 | (t[at + 3] & 0xffL) << 24;
                int missing = (t[at + 4] & 0xff) | (t[at + 5] & 0xff) << 8; // 0.01 %
                s.append(i + 1).append(": ").append(missing < 5000 ? "yes" : "NO").append(" (").append(missing / 100).append(" % missing, ").append(rpm).append(" rpm)  ");
            }
            return s.length() == 0 ? "no telemetry in the answer" : s.toString().trim();
        } catch (IOException e) {
            return "not known (" + e.getMessage() + ")";
        }
    }

    /** NOISE, asked for: each motor in turn, low and short, with what its ESC says meanwhile. PROPS OFF. */
    private static void spin(BetaflightUsb fc) throws Exception {
        if (MotorTest.is3d(fc.msp(MotorTest.MSP_FEATURE_CONFIG, null)) || MotorTest.armed(fc.msp(MSP_STATUS, null))) {
            System.out.println("3D mode or armed: no spin.");
            return;
        }
        try {
            for (int m = 0; m < 4; m++) {
                int[] values = {1000, 1000, 1000, 1000};
                values[m] = SPIN_VALUE;
                fc.msp(MotorTest.MSP_SET_MOTOR, MotorTest.setMotorPayload(values));
                Thread.sleep(SPIN_MS);
                System.out.println("  motor " + (m + 1) + " at " + SPIN_VALUE + ": " + answering(fc) + ", " + amps(fc) + " A");
                fc.msp(MotorTest.MSP_SET_MOTOR, MotorTest.setMotorPayload(new int[0]));
                Thread.sleep(400);
            }
        } finally {
            fc.msp(MotorTest.MSP_SET_MOTOR, MotorTest.setMotorPayload(new int[0]));
        }
    }

    private static double amps(BetaflightUsb fc) throws Exception {
        byte[] a = fc.msp(MSP_ANALOG, null);
        return a.length >= 7 ? (short) ((a[5] & 0xff) | (a[6] & 0xff) << 8) / 100.0 : Double.NaN;
    }
}
