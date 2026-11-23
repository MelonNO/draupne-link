package de.droiddrone.pc.setup.ardu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fleet PRD AP-7 / AS-2: Drone setup's one way of writing an ArduPilot flight controller - the same steps as for
 * Betaflight: the whole parameter list read, saved as a backup (.param) BEFORE anything is written, the plan the pilot
 * confirmed written one parameter at a time and each read back, a stop at the first that doesn't read back as
 * written. Refused while the flight controller says armed or hasn't said (a drone on the bench only). RESTORE writes
 * a backup back the same way. Runs on the caller's worker thread.
 */
public final class ArduSetupWriter {
    static final long PARAM_TIMEOUT_MS = 1500;

    /** What a write did: the backup's file, the parameters written, and the failure that stopped it (null: none). */
    public record Result(Path backup, List<String> written, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final MavSession fc;
    private final Consumer<String> log;

    public ArduSetupWriter(MavSession fc, Consumer<String> log) {
        this.fc = fc;
        this.log = log == null ? s -> { } : log;
    }

    /**
     * Backs up, then writes each change of the plan (missing parameters skipped) and reads it back.
     * @param backupDir where the backup goes (AppPaths.arduPilotBackupsDir())
     */
    public Result write(List<ArduPlan.Change> plan, Path backupDir, java.time.LocalDateTime now) throws InterruptedException {
        if (fc.armed()) return new Result(null, List.of(), "The drone is armed, or hasn't said: setup only on the bench, disarmed.");
        Map<String, Float> all;
        try {
            all = fc.readAllParams(30_000);
        } catch (IOException e) {
            return new Result(null, List.of(), "Couldn't read its parameters: " + e.getMessage());
        }
        Path backup = ParamFile.backupName(backupDir, fc.fcSysId(), now);
        try {
            ParamFile.write(backup, all, "DRAUPNE Link backup before setup\nArduPilot system id " + fc.fcSysId());
        } catch (IOException e) {
            return new Result(null, List.of(), "Couldn't save the backup, nothing written: " + e.getMessage());
        }
        log.accept("Backup: " + backup.getFileName());
        List<String> written = new ArrayList<>();
        for (ArduPlan.Change c : plan) {
            if (!c.writable()) continue; // the other version's name, or not on this board (the plan said NOT SET)
            if (c.missing()) { // shown by an enable written (or on) before it: looked for again now
                float have;
                try {
                    have = fc.readParam(c.name(), PARAM_TIMEOUT_MS);
                } catch (IOException e) {
                    have = Float.NaN;
                }
                if (Float.isNaN(have)) return new Result(backup, written, c.name() + " didn't show with " + c.shownBy() + " on: stopped.");
                if (ArduPlan.same(have, c.to())) continue;
            }
            String failure = writeOne(c.name(), c.to());
            if (failure != null) return new Result(backup, written, failure);
            written.add(c.name());
        }
        return new Result(backup, written, null);
    }

    /** RESTORE: every parameter of a backup that differs from the FC's now, written and read back. */
    public Result restore(Path backupFile) throws InterruptedException {
        if (fc.armed()) return new Result(null, List.of(), "The drone is armed, or hasn't said: restore only on the bench, disarmed.");
        Map<String, Float> want, now;
        try {
            want = ParamFile.read(backupFile);
            now = fc.readAllParams(30_000);
        } catch (IOException e) {
            return new Result(null, List.of(), "Couldn't read: " + e.getMessage());
        }
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, Float> e : want.entrySet()) {
            Float have = now.get(e.getKey());
            if (have == null || ArduPlan.same(have, e.getValue())) continue; // a parameter this firmware lacks is left out
            String failure = writeOne(e.getKey(), e.getValue());
            if (failure != null) return new Result(backupFile, written, failure);
            written.add(e.getKey());
        }
        return new Result(backupFile, written, null);
    }

    private String writeOne(String name, float to) throws InterruptedException {
        if (fc.armed()) return "The drone armed during setup: stopped before " + name + ".";
        try {
            float back = fc.setParam(name, to, PARAM_TIMEOUT_MS);
            if (!ArduPlan.same(back, to)) return name + " reads back " + ArduPlan.num(back) + ", not " + ArduPlan.num(to) + ": stopped.";
            log.accept(name + " = " + ArduPlan.num(back));
            return null;
        } catch (IOException e) {
            return name + ": " + e.getMessage() + " - stopped.";
        }
    }
}
