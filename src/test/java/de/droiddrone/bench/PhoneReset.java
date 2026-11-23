package de.droiddrone.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * After a phone run that was killed or failed (its {@link PhoneRig#close} may never have run): what the bench may
 * have left pretended on the real phone is taken back - doze no longer forced, the battery as it really is, the
 * phone's own "stay awake while plugged in" where a run kept an unlocked phone awake, and the phone's own network
 * (airplane mode, Wi-Fi, mobile data) where a network-switch run switched it - each by the note that run left on the
 * phone; without a note it is not touched. Then the
 * phone's state is read and printed, so that anything else left behind is seen (airplane mode on, a port still led
 * back to this PC); that is only said, not changed - it may be the pilot's own setting, or the bench build's flight
 * controller, which comes down such a port.
 * <pre>
 *   gradlew phoneReset [-Pserial=&lt;the phone's adb serial&gt;]      (.\bench.ps1 phone ends with it, however the run went;
 *                                                                 .\bench.ps1 hw too, when a scenario switched the phone's network)
 * </pre>
 * Without {@code -Pserial} the phone of this bench's hardware file is taken ({@link Hardware}: phone.adb); with
 * neither nothing is done - a phone is never picked by itself. Safe to run at any time, as often as wanted: both
 * orders do nothing on a phone that needs none.
 */
public final class PhoneReset {
    private PhoneReset() {
    }

    /** What is sent, in this order: the two {@link PhoneRig#close} always sends to a real phone. */
    static final List<List<String>> ORDERS = List.of(
            List.of("shell", "dumpsys deviceidle unforce"),
            List.of("shell", "dumpsys battery reset"));

    public static void main(String[] args) {
        String serial = args.length > 0 ? args[0].trim() : "";
        if (serial.isEmpty() && Hardware.present()) {
            try {
                serial = Hardware.load().phoneAdb();
            } catch (RuntimeException e) {
                System.out.println(e.getMessage());
            }
        }
        if (serial.isEmpty()) {
            System.out.println("Nothing done: no phone named. gradlew phoneReset -Pserial=<the phone's adb serial>  (or phone.adb in " + Hardware.FILE + ")");
            System.exit(2);
            return;
        }
        Path sdk = sdk();
        if (sdk == null) {
            System.out.println("Nothing done: no Android SDK found (ANDROID_SDK_ROOT, or %LOCALAPPDATA%\\Android\\Sdk).");
            System.exit(2);
            return;
        }
        boolean ok = run(new PhoneAdb.Sdk(sdk, null, serial), System.out::println);
        System.exit(ok ? 0 : 1);
    }

    private static Path sdk() {
        for (String v : new String[]{System.getProperty("bench.android.sdk"), System.getenv("ANDROID_SDK_ROOT"), System.getenv("ANDROID_HOME")}) {
            if (v != null && !v.isBlank()) return Path.of(v.trim());
        }
        String local = System.getenv("LOCALAPPDATA");
        return local == null ? null : Path.of(local, "Android", "Sdk");
    }

    /** How long the phone's network gets to be back as a killed run noted it. */
    static final long NETWORK_BACK_MS = 60_000;

    /** The orders sent, then the phone's state read; false when the phone was not there or an order failed. */
    static boolean run(PhoneAdb adb, Consumer<String> out) {
        return run(adb, out, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, System::currentTimeMillis);
    }

    /** {@link #run(PhoneAdb, Consumer)} with its waiting and its clock handed in. */
    static boolean run(PhoneAdb adb, Consumer<String> out, LongConsumer pause, LongSupplier clock) {
        String state;
        try {
            state = said(adb, List.of("get-state"));
        } catch (RuntimeException e) {
            state = String.valueOf(e.getMessage());
        }
        if (!state.equals("device")) {
            out.accept("Nothing done: no phone on adb under that serial (adb says: " + state + ").");
            return false;
        }
        boolean ok = true;
        for (List<String> order : ORDERS) {
            try {
                String said = said(adb, order);
                boolean failed = said.toLowerCase().contains("error") || said.toLowerCase().contains("exception");
                ok &= !failed;
                out.accept((failed ? "FAILED  " : "done    ") + String.join(" ", order) + (said.isBlank() ? "" : "   -> " + said.lines().findFirst().orElse("")));
            } catch (RuntimeException e) {
                ok = false;
                out.accept("FAILED  " + String.join(" ", order) + "   -> " + e.getMessage());
            }
        }
        // "stay awake while plugged in": a run that found the phone unlocked set it for USB and noted the phone's own
        // value on the phone. If the note is still there, that value goes back, exactly, and the note goes
        try {
            String noted = said(adb, List.of("shell", "settings get global " + PhoneGuard.STAY_ON_NOTE));
            List<List<String>> back = PhoneRig.stayOnBack(noted);
            for (List<String> order : back) {
                String said = said(adb, order);
                boolean failed = said.toLowerCase().contains("error") || said.toLowerCase().contains("exception");
                ok &= !failed;
                out.accept((failed ? "FAILED  " : "done    ") + String.join(" ", order) + (said.isBlank() ? "" : "   -> " + said.lines().findFirst().orElse("")));
            }
            if (back.isEmpty()) out.accept("stay awake while plugged in: the bench has no note of having changed it - left as it is");
        } catch (RuntimeException e) {
            ok = false;
            out.accept("FAILED  stay awake while plugged in: its note could not be read or its value put back   -> " + e.getMessage());
        }
        // the phone's own network: a network-switch run notes on the phone what it is to be put back to before its first
        // switch (PhoneNet.NOTE) and takes the note away once it is back. Still there: that run did not get so far - the
        // network goes back as noted (airplane mode off, mobile data on, Wi-Fi as it was), and then the note
        try {
            PhoneNet.Normal was = PhoneNet.Normal.ofNote(said(adb, List.of("shell", PhoneNet.NOTE_READ)));
            if (was == null) {
                out.accept("network: the bench has no note of having switched it - left as it is");
            } else {
                PhoneNet.Back back = PhoneNet.putBack(c -> said(adb, List.of("shell", c)), was, pause, clock, NETWORK_BACK_MS, null);
                for (String o : back.sent()) out.accept("done    shell " + o);
                if (back.switches()) {
                    said(adb, List.of("shell", PhoneNet.NOTE_DELETE));
                    out.accept("done    shell " + PhoneNet.NOTE_DELETE);
                } else {
                    ok = false;
                }
                out.accept("network: as a killed run of the bench left it noted (" + was.words() + "): " + back.words());
            }
        } catch (RuntimeException e) {
            ok = false;
            out.accept("FAILED  the phone's network: its note could not be read or the network put back   -> " + e.getMessage());
        }
        // read only, from here on
        List<String> seen = new ArrayList<>();
        try {
            PhoneRig.Battery b = PhoneRig.Battery.parse(said(adb, List.of("shell", "dumpsys battery")));
            seen.add("battery: " + (b == null ? "not readable" : b.toString()));
            if (b != null && b.pretended()) {
                ok = false;
                seen.add("STILL PRETENDED off the cable: dumpsys battery reset did not take");
            }
            String airplane = said(adb, List.of("shell", "cmd connectivity airplane-mode"));
            seen.add("airplane mode: " + (airplane.isBlank() ? "not readable" : airplane) + (airplane.contains("enabled") ? "   <- ON: switch it off by hand if the bench left it" : ""));
            String idle = said(adb, List.of("shell", "dumpsys deviceidle get deep"));
            seen.add("deep doze: " + (idle.isBlank() ? "not readable" : idle));
            String stayOn = said(adb, List.of("shell", "settings get global " + PhoneGuard.STAY_ON));
            seen.add("stay awake while plugged in: " + (stayOn.isBlank() ? "not readable" : stayOn.equals("null") ? "unset" : stayOn)
                    + "   (0 or unset: never; 2: on USB; 3, 7, 15: on a charger of any kind - the phone's own, as far as the bench knows)");
            String reversed = said(adb, List.of("reverse", "--list"));
            seen.add("ports led back to this PC (adb reverse): " + (reversed.isBlank() ? "none" : reversed.replace("\n", "; ")
                    + "   <- left as they are: the bench build's flight controller comes down one; a scenario's own would be gone with a finished run"));
        } catch (RuntimeException e) {
            seen.add("the phone's state could not be read: " + e.getMessage());
        }
        seen.forEach(out);
        out.accept(ok ? "What the bench pretends is taken back." : "NOT everything was put back: see above.");
        return ok;
    }

    private static String said(PhoneAdb adb, List<String> args) {
        return new String(adb.run(30, args), StandardCharsets.UTF_8).replace("\r", "").trim();
    }
}
