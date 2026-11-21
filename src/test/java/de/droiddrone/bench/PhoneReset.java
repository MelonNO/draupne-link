package de.droiddrone.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * After a phone run that was killed or failed (its {@link PhoneRig#close} may never have run): what the bench may
 * have left pretended on the real phone is taken back - doze no longer forced, the battery as it really is. Then the
 * phone's state is read and printed, so that anything else left behind is seen (airplane mode on, a port still led
 * back to this PC); that is only said, not changed - it may be the pilot's own setting, or the bench build's flight
 * controller, which comes down such a port.
 * <pre>
 *   gradlew phoneReset [-Pserial=&lt;the phone's adb serial&gt;]      (.\bench.ps1 phone ends with it, however the run went)
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

    /** The orders sent, then the phone's state read; false when the phone was not there or an order failed. */
    static boolean run(PhoneAdb adb, Consumer<String> out) {
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
