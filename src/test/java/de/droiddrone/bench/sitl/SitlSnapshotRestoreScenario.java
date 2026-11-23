package de.droiddrone.bench.sitl;

import de.droiddrone.bench.Film;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.hw.BoardRestore;
import de.droiddrone.bench.hw.BoardSnapshot;
import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The proof the hardware plan wants before anything writes to the real board: on Betaflight itself, as a program
 * (SITL; 4.4.1, the drone's version, in bench.ps1 sitl), a snapshot is taken ({@code BoardSnapshot}, as {@code gradlew
 * boardSnapshot}), a few settings are changed and saved - in the master settings, a PID profile, a mode range, rxfail
 * and a feature - and the restore ({@code BoardRestore}, as {@code gradlew boardRestore}) puts the snapshot back: the
 * board's dump all and MSP facts are the snapshot's again, compared by the restore itself and once more by
 * compare-only, and each changed setting reads as it was.
 */
@Tag("sitl")
class SitlSnapshotRestoreScenario {
    /** What is changed, and the CLI question that reads it back. */
    private static final Map<String, String> CHANGES = new LinkedHashMap<>();

    static {
        CHANGES.put("set failsafe_delay = 37", "get failsafe_delay");
        CHANGES.put("set small_angle = 40", "get small_angle");
        CHANGES.put("aux 1 1 1 1300 1700 0 0", "aux");
        CHANGES.put("rxfail 5 s 1234", "rxfail");
        CHANGES.put("feature -AIRMODE", "feature");
    }

    private final List<String> wrong = new ArrayList<>();
    private Film film;

    private void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) wrong.add(what);
    }

    @Test
    void aSnapshotChangedSettingsAndTheRestoreLeaveBetaflightAsTheSnapshotHasIt() throws Exception {
        String missing = Sitl.missing();
        Assumptions.assumeTrue(missing == null, missing);
        film = new Film(Sandbox.runRoot().resolve("film").resolve("sitl-snapshot-restore"), "sitl-snapshot-restore");
        Path boardRoot = film.dir().resolve("board");
        try {
            film.add("a fresh board: " + Sitl.fresh(film::remark), null, "sitl", "");
            String version;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                version = Sitl.version(fc);
            }
            film.add("Betaflight " + version + " as a program on 127.0.0.1:" + Sitl.port(), null, "sitl", Sitl.folder());
            check(Sitl.startedVersion().isEmpty() || Sitl.startedVersion().equals(version), "the Betaflight that answers is the one the run started (" + Sitl.startedVersion() + "): " + version);

            // ---- the snapshot (diff all and dump all asked twice each in one CLI visit: taken only when both are the same)
            BoardSnapshot before = again("the snapshot", () -> BoardSnapshot.take(Sitl.BOARD, new BoardSnapshot.Options(false, "sitl-before", Sitl.TIMING), film::remark));
            Path snapshot = before.write(boardRoot, film.dir().resolve("backups"));
            film.add("snapshot: " + snapshot, null, "sitl", before.version());
            Map<String, String> was = readBack();

            // ---- a few settings changed and saved, as a test campaign would leave the board
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                fc.enterCli();
                fc.command("profile 1");
                Sitl.taken(fc, "set motor_output_limit = 42");
                fc.command("profile 0");
                for (String change : CHANGES.keySet()) Sitl.taken(fc, change);
                fc.saveAndReboot();
            }
            Map<String, String> changed = readBack();
            film.add("changed and saved: " + String.join("; ", CHANGES.keySet()) + "; motor_output_limit = 42 in PID profile 1", null, "sitl", "");
            for (String question : CHANGES.values()) check(!changed.get(question).equals(was.get(question)), "\"" + question + "\" reads otherwise after the change");

            BoardRestore.Report differs = again("compare-only", () -> BoardRestore.run(Sitl.BOARD, snapshot, new BoardRestore.Options(false, false, true, boardRoot, Sitl.TIMING), film::remark));
            check(differs.refused() == null && !differs.asSnapshot() && !differs.left().isEmpty(), "compare-only says the board is not the snapshot's now: "
                    + (differs.left() == null ? "?" : differs.left().size() + " difference(s)"));
            film.add("compare-only before the restore: " + (differs.left() == null ? "?" : String.join("; ", differs.left().subList(0, Math.min(8, differs.left().size())))), null, "sitl", "");

            // ---- the restore
            BoardRestore.Report restored = again("the restore", () -> BoardRestore.run(Sitl.BOARD, snapshot, new BoardRestore.Options(false, false, false, boardRoot, Sitl.TIMING), film::remark));
            film.add("restore: saved=" + restored.saved() + ", stopped=" + restored.stopped() + ", left=" + restored.left() + ", notes=" + restored.notes(), null, "sitl", "");
            check(restored.refused() == null, "the restore was not refused: " + restored.refused());
            check(restored.saved(), "every line of the snapshot's dump all was taken and saved");
            check(restored.asSnapshot(), "after the restore its dump all and MSP facts are the snapshot's: " + restored.left());

            BoardRestore.Report again = again("compare-only after the restore", () -> BoardRestore.run(Sitl.BOARD, snapshot, new BoardRestore.Options(false, false, true, boardRoot, Sitl.TIMING), film::remark));
            check(again.asSnapshot() && again.exitCode() == 0, "compare-only after the restore: as the snapshot (exit code " + again.exitCode() + ") " + again.left());
            Map<String, String> now = readBack();
            for (String question : CHANGES.values()) check(now.get(question).equals(was.get(question)), "\"" + question + "\" reads as before the change");
            check(now.get("profile 1 motor_output_limit").equals(was.get("profile 1 motor_output_limit")), "motor_output_limit in PID profile 1 as before: "
                    + now.get("profile 1 motor_output_limit"));
            for (String note : again.notes()) film.remark("restore's note: " + note);
        } catch (Exception e) {
            wrong.add("the scenario broke off: " + e);
            film.remark("BROKE OFF: " + e);
        } finally {
            BetaflightUsb.use(null);
            film.close(wrong.isEmpty(), wrong);
        }
        if (!wrong.isEmpty()) fail("sitl-snapshot-restore: " + wrong.size() + " problem(s):\n - " + String.join("\n - ", wrong));
    }

    /**
     * A read of the board's "diff all" and "dump all" (asked twice each, taken only when both are the same), again when
     * the two came back different: SITL's TCP stand-in for USB garbles a long answer now and then - a second "dump all"
     * of 32976 characters after a first of 22342, the same board, one CLI visit (2026-11-23, 4.4.1, by hand) - which a
     * board on USB does not. Three times at most; the film says so.
     */
    private <T> T again(String what, java.util.concurrent.Callable<T> read) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return read.call();
            } catch (java.io.IOException e) {
                if (attempt == 3 || !String.valueOf(e.getMessage()).contains("read differently twice")) throw e;
                film.remark(what + ": " + e.getMessage() + " - read again (" + attempt + " of 3): SITL's TCP stand-in garbles a long answer now and then");
            }
        }
    }

    /** Each changed setting as the CLI reads it now; the visit ends with "exit" (nothing saved). */
    private Map<String, String> readBack() throws Exception {
        Map<String, String> read = new LinkedHashMap<>();
        try (BetaflightUsb fc = Sitl.open(film::remark)) {
            fc.enterCli();
            for (String question : CHANGES.values()) read.put(question, fc.command(question));
            fc.command("profile 1");
            read.put("profile 1 motor_output_limit", fc.command("get motor_output_limit"));
            fc.command("profile 0");
        } // closed in the CLI: "exit", Betaflight restarts
        return read;
    }
}
