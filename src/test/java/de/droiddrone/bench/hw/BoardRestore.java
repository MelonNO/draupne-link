package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A flight controller's settings put back as a {@link BoardSnapshot} has them, after a test campaign that wrote to it.
 * <pre>
 * gradlew boardRestore -Psnapshot=&lt;folder&gt; [-Pport=COM9] [-Pescs=true] [-Preplay=always]
 * </pre>
 * <b>Refused</b>, with nothing sent to the board: an armed board; another board than the snapshot's (MSP_UID);
 * another firmware (version, build, the CLI's version line); a snapshot whose files are not what their SHA-256 say.
 * <p>
 * <b>What it does.</b> The board as it is now is read and filed first (a snapshot of its own, "before-restore"). Then
 * the snapshot's "diff all" is replayed in the CLI as Betaflight Configurator's paste would do it - "defaults nosave"
 * first (sent before the rest if the diff has none), then every line - through Drone setup's all-or-nothing write
 * ({@link CliWrite}): the first line Betaflight refuses, or a broken wire, ends it with "exit", and Betaflight drops
 * every change since the CLI opened. "save" is said last, and only after every line was taken. Then the board is read
 * again and "diff all", "dump all" and the MSP facts are compared with the snapshot, exactly; every difference is in
 * the report. A board that is as in the snapshot already is not written to (-Preplay=always replays all the same).
 * <p>
 * The feature mask is the one thing written over MSP, and only when it is not as in the snapshot after the replay:
 * Betaflight 2025.12's CLI has no name for RX_MSP, so its diff cannot say it (BetaflightPlan).
 * <p>
 * <b>Never put back:</b> see {@link #NOT_PUT_BACK}. With -Pescs=true (battery in; the ESCs' start-up tones once) the
 * ESCs' settings pages are read and compared with the snapshot's, and a change is reported.
 */
public final class BoardRestore {
    /** Said with every report. */
    public static final List<String> NOT_PUT_BACK = List.of(
            "ESC settings (beacon, timing, anything in the ESC's own memory): compared with -Pescs=true, never written.",
            "A spin direction saved inside an ESC (the motor test's REVERSE): it shows as a changed ESC settings page.",
            "The GPS module's own memory (what Betaflight or another tool saved in the module).",
            "The firmware: a board with another Betaflight than the snapshot's is refused, not flashed back.");
    /** Lines no "diff all" has, and that would do something else than set a setting: a diff with one is not replayed. */
    private static final Set<String> NEVER_REPLAYED = Set.of("exit", "bl", "dfu", "msc", "flash_erase", "flash_write", "escprog");

    /**
     * @param escs      also read the ESCs' settings pages and compare them with the snapshot's
     * @param always    replay even when the board is as in the snapshot already
     * @param boardRoot where the snapshots taken before and after are filed ({@link BoardSnapshot#boardRoot})
     * @param timing    how long to wait for a board that restarts
     */
    public record Options(boolean escs, boolean always, Path boardRoot, BoardPort.Timing timing) {
    }

    /**
     * @param refused      why nothing was sent to the board, or null
     * @param saved        every line was taken and "save" was said
     * @param stopped      why the write ended without a save (the board is then as it was before), or null
     * @param before       what differed from the snapshot before the restore
     * @param left         what differs from the snapshot now (empty: as in the snapshot); null: it could not be read again
     * @param escs         what differs in the ESCs' settings (never put back); empty when the same or not compared
     * @param notes        whatever else there is to say
     * @param beforeFolder the board as it was before the restore, filed
     * @param afterFolder  the board as it is after it, filed
     */
    public record Report(String refused, boolean saved, String stopped, List<String> before, List<String> left, List<String> escs,
                         List<String> notes, Path beforeFolder, Path afterFolder) {
        static Report refused(String why) {
            return new Report(why, false, null, List.of(), null, List.of(), List.of(), null, null);
        }

        /** Betaflight's settings are as in the snapshot: read back and compared, no difference. */
        public boolean asSnapshot() {
            return refused == null && stopped == null && left != null && left.isEmpty();
        }

        /** 0: as in the snapshot, the ESCs too where compared. 2: refused. 1: anything else. */
        public int exitCode() {
            return refused != null ? 2 : asSnapshot() && escs.isEmpty() ? 0 : 1;
        }
    }

    private BoardRestore() {
    }

    /**
     * The CLI lines that put a "diff all" back: its own lines without the comments, with "defaults nosave" first if it
     * has none, and without its "save" - that is said apart, last, once every line was taken.
     */
    static List<String> replay(String diffAll) throws IOException {
        if (!CliWrite.usableBackup(diffAll)) throw new IOException("The snapshot's \"diff all\" is not whole: not replayed.");
        List<String> commands = new ArrayList<>();
        boolean defaults = false;
        for (String raw : diffAll.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String word = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (word.equals("save")) continue;
            if (word.equals("defaults")) {
                // alone, "defaults" saves and restarts at once
                if (!line.equalsIgnoreCase("defaults nosave")) throw new IOException("The snapshot's diff has the line \"" + line + "\": not replayed.");
                defaults = true;
            }
            if (NEVER_REPLAYED.contains(word)) throw new IOException("The snapshot's diff has the line \"" + line + "\": not replayed.");
            commands.add(line);
        }
        if (!defaults) commands.add(!commands.isEmpty() && commands.get(0).equalsIgnoreCase("batch start") ? 1 : 0, "defaults nosave");
        return commands;
    }

    /** Why this board must not be written to from this snapshot, or null: armed, another board, another firmware. */
    private static String refusal(BetaflightUsb fc, BoardSnapshot wanted) throws IOException {
        if (Msp.armed(fc.msp(Msp.STATUS, null))) return "The board is armed. Disarm it first.";
        Map<String, String> now = BoardSnapshot.facts(fc);
        String uid = BoardSnapshot.boardId(now);
        if (wanted.uid().isEmpty() || uid.isEmpty()) return "The board's id is not known (MSP_UID): it can't be said to be the snapshot's board.";
        if (!wanted.uid().equals(uid)) return "Another board: the snapshot is of " + wanted.uid() + ", this one is " + uid + ".";
        for (String name : BoardSnapshot.FIRMWARE) {
            if (!Objects.equals(wanted.msp().get(name), now.get(name))) {
                return "Another firmware than the snapshot's (" + name + " " + wanted.msp().get(name) + " -> " + now.get(name) + "): not written to.";
            }
        }
        return null;
    }

    /**
     * Puts the snapshot in this folder back on the board on this port.
     * @throws IOException the board could not be read before anything was written to it
     */
    public static Report run(BetaflightUsb.Port port, Path snapshotDir, Options o, Consumer<String> log) throws IOException {
        BoardSnapshot wanted;
        List<String> commands;
        try {
            wanted = BoardSnapshot.load(snapshotDir);
            commands = replay(wanted.diffAll());
        } catch (IOException e) {
            return Report.refused(e.getMessage());
        }
        // the board itself, before even its CLI is opened
        try (BetaflightUsb fc = BoardPort.open(port, o.timing(), log)) {
            String no = refusal(fc, wanted);
            if (no != null) return Report.refused(no);
        }
        // as it is now, filed: what this restore replaces
        BoardSnapshot before = BoardSnapshot.take(port, new BoardSnapshot.Options(o.escs(), "before-restore", o.timing()), log);
        Path beforeDir = before.write(o.boardRoot(), null);
        log.accept("The board as it is now: " + beforeDir);
        List<String> firmware = wanted.firmwareDifferences(before);
        if (!firmware.isEmpty()) { // what MSP does not say of a build, the CLI's version line does
            return new Report("Another firmware than the snapshot's (" + String.join("; ", firmware) + "): not written to.", false, null,
                    List.of(), null, List.of(), List.of(), beforeDir, null);
        }
        List<String> notes = new ArrayList<>();
        List<String> escs = o.escs() ? wanted.escDifferences(before) : List.of();
        if (o.escs() && wanted.escs() == null) notes.add("The snapshot has no ESC settings: nothing to compare the ESCs with.");
        if (!o.escs() && wanted.escs() != null) notes.add("The snapshot has the ESCs' settings: -Pescs=true (battery in) compares them.");
        List<String> was = wanted.differences(before);
        if (was.isEmpty() && !o.always()) {
            log.accept("The board is as in the snapshot already: nothing written.");
            return new Report(null, false, null, was, was, escs, notes, beforeDir, null);
        }
        log.accept(was.size() + " difference(s) from the snapshot. Replaying its diff all: " + commands.size() + " lines, then save.");

        // the write: every line, or nothing
        boolean saved = false;
        String stopped = null;
        BetaflightUsb fc = BoardPort.open(port, o.timing(), log); // reading it restarted Betaflight
        try {
            String no = refusal(fc, wanted); // looked at once more: it was away meanwhile
            if (no != null) return new Report(no, false, null, was, was, escs, notes, beforeDir, null);
            fc.enterCli();
            CliWrite.Result written = CliWrite.run(fc::command, commands, log);
            if (written.complete()) {
                fc.saveAndReboot();
                saved = true;
            } else {
                stopped = written.stopped();
            }
        } catch (IOException e) {
            stopped = "The write broke off (" + e.getMessage() + ")";
        } finally {
            fc.close(); // the CLI still open: "exit" - Betaflight drops every change since it opened
        }
        if (!saved) log.accept("Write stopped, nothing saved - " + stopped);

        // read again and compare
        BoardSnapshot after;
        Path afterDir;
        try {
            after = BoardSnapshot.take(port, new BoardSnapshot.Options(false, saved ? "after-restore" : "after-stopped-restore", o.timing()), log);
            afterDir = after.write(o.boardRoot(), null);
            if (saved && !Objects.equals(wanted.msp().get("features"), after.msp().get("features")) && featuresOverMsp(port, wanted, o, log)) {
                notes.add("The feature mask was written over MSP: the CLI's lines alone left it at " + after.msp().get("features") + ".");
                after = BoardSnapshot.take(port, new BoardSnapshot.Options(false, "after-restore", o.timing()), log);
                afterDir = after.write(o.boardRoot(), null);
            }
        } catch (IOException e) {
            notes.add("The board could not be read again (" + e.getMessage() + "): what it holds now is not known.");
            return new Report(null, saved, stopped, was, null, escs, notes, beforeDir, null);
        }
        if (!saved) {
            List<String> moved = before.differences(after);
            notes.add(moved.isEmpty() ? "Read again after the stopped write: the board is as it was before the restore."
                    : "After the stopped write the board is NOT as it was before the restore: " + String.join("; ", moved));
        }
        return new Report(null, saved, stopped, was, wanted.differences(after), escs, notes, beforeDir, afterDir);
    }

    /**
     * The snapshot's feature mask written over MSP and saved (MSP_EEPROM_WRITE, then a restart), as Betaflight
     * Configurator sets features: for the bits the CLI's lines could not name. False when it could not be done.
     */
    private static boolean featuresOverMsp(BetaflightUsb.Port port, BoardSnapshot wanted, Options o, Consumer<String> log) {
        String hex = wanted.msp().get("features");
        if (hex == null || hex.length() != 8) return false; // the snapshot's board did not say: nothing to write
        try (BetaflightUsb fc = BoardPort.open(port, o.timing(), log)) {
            if (refusal(fc, wanted) != null) return false;
            log.accept("Feature mask: not as in the snapshot after the CLI's lines - written over MSP (" + hex + ") and saved.");
            fc.setFeatureMask(Msp.u32(Msp.unhex(hex), 0));
            fc.msp(Msp.EEPROM_WRITE, null);
            try {
                fc.msp(Msp.REBOOT, null);
            } catch (IOException e) {
                // it may restart before it answers
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.accept("Feature mask: could not be written over MSP (" + e.getMessage() + ").");
            return false;
        }
    }

    // ------------------------------------------------------------------ by hand

    /** args: the port ("" = the one flight controller there is), the snapshot's folder, "true" to compare the ESCs, "always". */
    public static void main(String[] args) {
        System.exit(byHand(args, System.out, BoardSnapshot.boardRoot(), BoardPort.Timing.REAL));
    }

    /** What main does, said to out; the result is {@link Report#exitCode}, and 1 when the board could not be read at all. */
    static int byHand(String[] args, java.io.PrintStream out, Path boardRoot, BoardPort.Timing timing) {
        String wanted = args.length > 0 ? args[0] : "", folder = args.length > 1 ? args[1] : "";
        boolean escs = args.length > 2 && args[2].equalsIgnoreCase("true"), always = args.length > 3 && args[3].equalsIgnoreCase("always");
        if (folder.isBlank()) {
            out.println("Which snapshot? gradlew boardRestore -Psnapshot=<folder> (they are under " + boardRoot + ")");
            return 2;
        }
        Report r;
        BetaflightUsb.Port port;
        try {
            port = BoardPort.find(wanted, timing);
            out.println("Restore of the flight controller on " + port + " from " + folder);
            out.println("Sound to expect: " + (escs ? "the ESCs' start-up tones, once. No motor turns." : "none."));
            r = run(port, Path.of(folder), new Options(escs, always, boardRoot, timing), s -> out.println("  " + s));
        } catch (IOException e) {
            out.println("NOT RESTORED, and nothing written: " + e.getMessage());
            return 1;
        }
        if (r.refused() != null) {
            out.println("REFUSED, nothing written: " + r.refused());
        } else if (r.left() == null) {
            out.println((r.saved() ? "SAVED, BUT NOT CHECKED" : "NOTHING SAVED (" + r.stopped() + "), AND NOT CHECKED") + ": see below.");
        } else if (r.stopped() != null) {
            out.println("NOTHING SAVED: " + r.stopped() + ". " + r.left().size() + " difference(s) from the snapshot remain:");
            r.left().forEach(d -> out.println("  " + d));
        } else if (r.left().isEmpty()) {
            out.println(r.saved() ? "RESTORED: diff all, dump all and the MSP facts read back - no difference from the snapshot. "
                    + r.before().size() + " difference(s) before." : "AS IN THE SNAPSHOT ALREADY: nothing written.");
        } else {
            out.println("SAVED, BUT NOT AS IN THE SNAPSHOT - " + r.left().size() + " difference(s):");
            r.left().forEach(d -> out.println("  " + d));
        }
        r.notes().forEach(n -> out.println("Note: " + n));
        if (!r.escs().isEmpty()) {
            out.println("THE ESCs ARE NOT AS IN THE SNAPSHOT (never put back by this tool):");
            r.escs().forEach(d -> out.println("  " + d));
        }
        if (r.beforeFolder() != null) out.println("Before the restore: " + r.beforeFolder());
        if (r.afterFolder() != null) out.println("After it:           " + r.afterFolder());
        out.println("What a restore never puts back:");
        NOT_PUT_BACK.forEach(n -> out.println("  - " + n));
        // its CLI was visited (the read before, at least): Betaflight restarted
        boolean back = r.beforeFolder() == null || BoardPort.isBack(port, timing, out);
        return r.exitCode() == 0 && !back ? 1 : r.exitCode();
    }
}
