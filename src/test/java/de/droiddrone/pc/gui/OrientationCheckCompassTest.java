package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.FakeCompassBoard;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-001: the orientation check's compass round wrote a new align_mag by itself after the fourth DONE - saved, try
 * after try - and when the tries ran out the last guess stayed saved. The maintainer's answer: show what it found
 * and ask before saving; a search that fails puts the old value back. Here the window is driven as a pilot drives it
 * (never shown), against a made-up board that keeps align_mag as Betaflight does and records every CLI command: no
 * "set" and no "save" before SAVE, and after a search that fails, is ended with KEEP OLD or SKIP COMPASS, or is
 * closed half way, the board has the alignment it had before. No real port.
 */
@Timeout(90)
class OrientationCheckCompassTest {
    private static final int CW90 = 1, CW180 = 2, CW90FLIP = 5;
    @TempDir
    Path backups;
    private OrientationCheckRig rig;
    private MotorsPageRig page;

    @AfterEach
    void close() throws Exception {
        if (rig != null) rig.close();
        if (page != null) page.close();
    }

    /** A board whose compass is right with CW90FLIP, set to CW0: the check, up to the question after the first round. */
    private FakeCompassBoard wrongWayRound() throws Exception {
        rig = new OrientationCheckRig(backups);
        FakeCompassBoard board = new FakeCompassBoard(1, "CW0", CW90FLIP, CW180).plug(rig.usb, "MADEUP1");
        rig.open(new SetupBoard("MADEUP1", MotorTestRig.id(1)));
        rig.boardCheck(board);
        rig.round(board, 0, 1, 2, 3);
        Waits.waitFor(() -> rig.offers("SAVE"), 15_000, "the question after the fourth DONE: compass row " + rig.row(OrientationCheckRig.COMPASS));
        return board;
    }

    private static void afterMoreReadings(FakeCompassBoard board) throws InterruptedException {
        int before = board.attitudes();
        Waits.waitFor(() -> board.attitudes() >= before + 5, 15_000, "the check to go on reading the board");
    }

    @Test
    void afterTheFourthDoneWhatWasFoundIsShownAndNothingIsWrittenBeforeSave() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        assertEquals("WARN: CW90FLIP found (now CW0). Save?", rig.row(OrientationCheckRig.COMPASS));
        assertTrue(rig.says("COMPASS: CW90FLIP FOUND"));
        assertTrue(rig.offers("KEEP OLD"), "two buttons: SAVE and KEEP OLD");
        afterMoreReadings(board); // Betaflight is back from the read, the check goes on: still nothing is written
        assertEquals(List.of(), board.writes(), "no set and no save before SAVE: " + board.cli());
        assertEquals("CW0", board.saved());
        assertEquals(List.of(), listed(backups), "and no backup yet: nothing is about to change");
    }

    @Test
    void saveWritesItWithABackupAndTheNextRoundShowsItRight() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        rig.press("SAVE");
        Waits.waitFor(() -> board.saved().equals("CW90FLIP"), 15_000, "the alignment found to be saved");
        assertEquals(List.of("set align_mag = CW90FLIP", "save"), board.writes());
        assertEquals(1, listed(backups).size(), "Betaflight's settings were kept before the write");
        assertTrue(Files.readString(listed(backups).get(0)).contains("set align_mag = CW0"), "as they were");
        rig.round(board, 0, 1, 2, 3); // again after the restart: now it reads right
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("OK: Right way round. Saved: CW90FLIP (was CW0)."), 15_000,
                "the compass to be shown right: " + rig.row(OrientationCheckRig.COMPASS));
        Waits.waitFor(() -> rig.passed, 5000, "the check to pass");
        rig.closeWindow();
        Waits.waitFor(() -> !rig.closed.isEmpty(), 15_000, "the window to say it is closed");
        assertEquals(List.of(""), rig.closed, "nothing to put back: what was saved is shown right");
        assertEquals("CW90FLIP", board.saved());
        assertEquals(List.of("set align_mag = CW90FLIP", "save"), board.writes());
    }

    @Test
    void keepOldWritesNothingAndSaysSo() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        rig.press("KEEP OLD");
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("WARN: Kept: CW0. Still the wrong way round."), 15_000,
                "the window to say what was kept: " + rig.row(OrientationCheckRig.COMPASS));
        assertEquals("WARN: Board right. Compass: by hand.", rig.row(OrientationCheckRig.RESULT));
        assertFalse(rig.offers("SAVE"), "the question is over");
        afterMoreReadings(board);
        assertEquals(List.of(), board.writes());
        assertEquals("CW0", board.saved());
    }

    /** SAVE, then a round that fits nothing (the nose never turned): the search fails with a value of its own saved. */
    @Test
    void aSearchThatFailsPutsTheOldValueBackAndSaysSo() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        rig.press("SAVE");
        Waits.waitFor(() -> board.saved().equals("CW90FLIP"), 15_000, "the alignment found to be saved");
        rig.round(board, 0, 0, 0, 0);
        Waits.waitFor(() -> board.saved().equals("CW0"), 15_000, "the old value to be put back: " + board.cli());
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("BAD: No setting fits. Put back: CW0. Set align_mag in Betaflight Configurator."),
                15_000, "the window to say so: " + rig.row(OrientationCheckRig.COMPASS));
        assertEquals("WARN: Board right. Compass: by hand.", rig.row(OrientationCheckRig.RESULT));
        assertEquals(List.of("set align_mag = CW90FLIP", "save", "set align_mag = CW0", "save"), board.writes());
        assertTrue(rig.log.stream().anyMatch(l -> l.contains("align_mag CW90FLIP -> CW0 put back")), rig.log.toString());
    }

    @Test
    void closedHalfWayTheOldValueIsPutBackAndWhoeverOpenedTheCheckIsTold() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        rig.press("SAVE");
        Waits.waitFor(() -> board.saved().equals("CW90FLIP"), 15_000, "the alignment found to be saved");
        rig.closeWindow(); // Betaflight is restarting with a value nobody has seen to be right
        Waits.waitFor(() -> !rig.closed.isEmpty(), 30_000, "the window to say it is closed, after the put-back");
        assertEquals(List.of("Compass put back: CW0."), rig.closed);
        assertEquals("CW0", board.saved());
        assertEquals(List.of("set align_mag = CW90FLIP", "save", "set align_mag = CW0", "save"), board.writes());
        Waits.waitFor(() -> !OrientationCheckDialog.isOpen(), 5000, "the check to have let go of the USB port");
    }

    @Test
    void skipCompassHalfWayPutsTheOldValueBack() throws Exception {
        FakeCompassBoard board = wrongWayRound();
        rig.press("SAVE");
        Waits.waitFor(() -> board.saved().equals("CW90FLIP"), 15_000, "the alignment found to be saved");
        Waits.waitFor(() -> rig.says("NOSE NORTH"), 15_000, "the round after the restart");
        rig.press("SKIP COMPASS");
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("INFO: Skipped. Put back: CW0."), 15_000,
                "the window to say so: " + rig.row(OrientationCheckRig.COMPASS));
        assertEquals("CW0", board.saved());
    }

    /** DEFAULT is not a known alignment to measure from: CW0 is asked for first - and that is a write too. */
    @Test
    void fromDefaultCw0IsAskedForFirstAndKeepOldAfterItPutsDefaultBack() throws Exception {
        rig = new OrientationCheckRig(backups);
        FakeCompassBoard board = new FakeCompassBoard(1, "DEFAULT", CW90, CW180).plug(rig.usb, "MADEUP1");
        rig.open(new SetupBoard("MADEUP1", MotorTestRig.id(1)));
        rig.boardCheck(board);
        rig.round(board, 0, 1, 2, 3);
        Waits.waitFor(() -> rig.offers("SAVE"), 15_000, "the first question");
        assertEquals("WARN: Now DEFAULT: CW0 first, to measure from. Save?", rig.row(OrientationCheckRig.COMPASS));
        assertEquals(List.of(), board.writes());
        rig.press("SAVE");
        Waits.waitFor(() -> board.saved().equals("CW0"), 15_000, "CW0 to be saved");
        rig.round(board, 0, 1, 2, 3);
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("WARN: CW90 found (now CW0). Save?"), 15_000,
                "the second question: " + rig.row(OrientationCheckRig.COMPASS));
        assertEquals(List.of("set align_mag = CW0", "save"), board.writes(), "the alignment found is not written before its own SAVE");
        rig.press("KEEP OLD");
        Waits.waitFor(() -> board.saved().equals("DEFAULT"), 15_000, "DEFAULT to be put back: " + board.cli());
        Waits.waitFor(() -> rig.row(OrientationCheckRig.COMPASS).equals("WARN: Put back: DEFAULT. Still the wrong way round."), 15_000,
                "the window to say so: " + rig.row(OrientationCheckRig.COMPASS));
    }

    /** Drone setup's row, when the check's window has closed: the line about the compass's old value comes first. */
    @Test
    void droneSetupsRowSaysWhatClosingPutBack() throws Exception {
        page = new MotorsPageRig();
        page.open();
        page.press("WRONG? CHECK IT");
        MotorsPageRig.onSwing(() -> page.orientationCheckClosed.accept("Compass put back: CW0."));
        assertEquals("WARN: Compass put back: CW0. Check it before flying.", page.shows(MotorsPageRig.ORIENTATION));
        page.press("WRONG? CHECK IT");
        MotorsPageRig.onSwing(() -> page.orientationCheckClosed.accept(null));
        assertEquals("WARN: Not finished. Check it before flying.", page.shows(MotorsPageRig.ORIENTATION));
    }

    private static List<Path> listed(Path dir) throws java.io.IOException {
        try (var files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }
}
