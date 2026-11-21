package de.droiddrone.pc.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drone setup's ESCS row (1.0.71): what the ESCs run and whether their own beacon will sound on a resting drone
 * ({@link EscFirmware}), read through the flight controller on USB ({@link EscLink}) - and that beacon switched off.
 * One call opens the port, does it and gives the port back; the row shows {@link Result#state} and {@link Result#text}.
 */
public final class EscCheck {
    /** Opens the flight controller's USB port. */
    public interface Opener {
        BetaflightUsb open() throws IOException;
    }

    /**
     * escs: each ESC as it is now (null when the flight controller could not be asked: problem says why).
     * failures: what went wrong switching a beacon off, an ESC each - said in full, one may ask for the motor test.
     */
    public record Result(List<EscFirmware.Info> escs, String problem, List<String> failures) {
        /** An ESC's own beacon is on and this app can switch it off. */
        public boolean canSilence() {
            return escs != null && escs.stream().anyMatch(EscFirmware.Info::canSilence);
        }

        /** The row's light: "OK", "WARN", "BAD" or "INFO" (CheckRow's states). */
        public String state() {
            if (!failures.isEmpty()) return "BAD";
            if (escs == null) return "WARN";
            boolean beeps = false, unanswered = false, unread = false;
            for (EscFirmware.Info i : escs) {
                beeps |= i.beeps();
                unanswered |= i.problem() != null;
                unread |= i.kind() == EscFirmware.Kind.ARM_OTHER;
            }
            return beeps || unanswered ? "WARN" : unread ? "INFO" : "OK";
        }

        /** The row's words (HTML, short). */
        public String text() {
            if (escs == null) return problem;
            StringBuilder b = new StringBuilder(EscFirmware.summary(escs)).append('.');
            if (!failures.isEmpty()) {
                for (String f : failures) b.append("<br>").append(f);
                return b.toString();
            }
            if (canSilence()) b.append("<br>Beeps at rest. <b>Beacon off</b> ends it.");
            else if (escs.stream().anyMatch(EscFirmware.Info::beeps)) b.append("<br>Beeps at rest: set Beacon Delay to Infinite in the ESC's configurator.");
            if (escs.stream().anyMatch(i -> i.kind() == EscFirmware.Kind.ARM_OTHER)) {
                b.append("<br>BLHeli_32: set Beacon Delay to Infinite in BLHeliSuite32.");
            }
            return b.toString();
        }
    }

    private EscCheck() {
    }

    /**
     * Reads every ESC; with silence, switches the beacon off on each that has it on and can be written
     * (the old settings kept in backupDir). The port is closed again whatever happens.
     */
    public static Result run(Opener opener, boolean silence, Path backupDir, Consumer<String> log) {
        List<String> failures = new ArrayList<>();
        try (BetaflightUsb fc = opener.open()) {
            if (!fc.firmware().startsWith("BTFL")) return new Result(null, "The flight controller doesn't run Betaflight.", failures);
            try { // said at once, instead of after every ESC was asked in vain
                if (!fc.batteryState().present()) return new Result(null, "No battery. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", failures);
            } catch (IOException e) {
                // a Betaflight that doesn't say: the ESCs will
            }
            List<EscFirmware.Info> all;
            try (EscLink escs = fc.escs(log)) {
                all = escs.infoAll();
                log.accept("ESCs: " + EscFirmware.summary(all) + ".");
                if (silence) {
                    for (int i = 0; i < all.size(); i++) {
                        EscFirmware.Info before = all.get(i);
                        if (!before.canSilence()) continue;
                        try {
                            all.set(i, escs.silence(i, backupDir));
                            log.accept("ESC " + (i + 1) + ": beacon " + before.beaconText() + " -> " + all.get(i).beaconText() + " (saved in the ESC).");
                        } catch (IOException e) {
                            failures.add(e.getMessage());
                            log.accept("ESC " + (i + 1) + ": beacon " + before.beaconText() + " -> off FAILED: " + e.getMessage());
                        }
                    }
                }
            }
            if (all.stream().allMatch(i -> i.problem() != null && i.kind() == EscFirmware.Kind.UNKNOWN && i.chip().isEmpty())) {
                return new Result(null, "No ESC answered. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", failures);
            }
            return new Result(all, null, failures);
        } catch (IOException e) {
            log.accept("ESCs: " + e.getMessage());
            return new Result(null, e.getMessage(), failures);
        }
    }
}
