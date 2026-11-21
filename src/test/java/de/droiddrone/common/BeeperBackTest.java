package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-069 (a reader of the code, 2026-11-22): the drone's beeper could stay off after a sleep. At the wake the phone
 * deleted the settings it had kept, then wrote them back once - no second try, no look at what the flight controller
 * then had, through a slot the channel map's request shares - and to whichever flight controller was on the port. One
 * lost write left the lost-receiver buzzer and the motors' beacon off until the flight controller lost power.
 */
class BeeperBackTest {
    /** MSP_BEEPER_CONFIG as Betaflight answers: off flags u32, beacon tone u8, beacon off flags u32, little-endian. */
    private static byte[] config(int beeperOff, int tone, int beaconOff) {
        return new byte[]{(byte) beeperOff, (byte) (beeperOff >>> 8), (byte) (beeperOff >>> 16), (byte) (beeperOff >>> 24), (byte) tone,
                (byte) beaconOff, (byte) (beaconOff >>> 8), (byte) (beaconOff >>> 16), (byte) (beaconOff >>> 24)};
    }

    /** As Drone setup leaves a drone: the lost receiver beeps (bits 1 and 2 on), the beacon too. */
    private static final byte[] OWN = config(0x00FFFFFF & ~((1 << 1) | (1 << 2) | (1 << 9) | (1 << 17)), 3, 0);
    /** What the sleep wrote. */
    private static final byte[] QUIET = BeeperQuiet.quiet(OWN);
    private static final String BOARD = "0123456789abcdef01234567", OTHER = "ffeeddccbbaa998877665544";

    @Test
    void theKeptSettingsAreWrittenAndCountAsBackOnlyWhenTheFlightControllerReadsThemBack() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 1000), "awake, still quiet: written");
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 1030), "read back as they were: only now forgotten");
        assertEquals(1, b.writes());
    }

    @Test
    void aBoardThatLostPowerMeanwhileHasThemAlreadyAndNothingIsWritten() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 1000));
        assertEquals(0, b.writes());
    }

    /** The finding itself: one write lost, and nothing ever wrote again. */
    @Test
    void aWriteThatWasLostIsMadeAgainUntilItHolds() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 1000));
        // its read-back says quiet still: the write never reached the flight controller (the 20 ms limit, a skipped request)
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, BOARD, QUIET, 1030), "not kept as done, and not written twice in a breath");
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 2000), "the next look a second later: again");
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, BOARD, QUIET, 2030));
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 3000));
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 3030), "the third one held");
        assertEquals(3, b.writes());
    }

    @Test
    void afterFiveWritesThatDoNotHoldItIsSaidOnceAndLeftUntilTheNextConnection() {
        BeeperBack b = new BeeperBack();
        int writes = 0, gaveUp = 0, said = 0;
        for (long t = 1000; t <= 60_000; t += 1000) {
            int r = b.read(OWN, BOARD, BOARD, QUIET, t);
            assertNotEquals(BeeperBack.DONE, r, "never counted as back while the flight controller says quiet");
            if (r == BeeperBack.WRITE) writes++;
            if (r == BeeperBack.GAVE_UP) {
                gaveUp++;
                if (b.sayOnce(r)) said++;
            }
        }
        assertEquals(BeeperBack.MAX_WRITES, writes, "a clear limit");
        assertEquals(5, BeeperBack.MAX_WRITES);
        assertEquals(55, gaveUp, "then nothing more is written on this connection");
        assertEquals(1, said, "and it is said once");
        // the flight controller's port opened anew (a replug, the next wake, the app started again): tried again
        b.restart();
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 61_000));
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 61_030));
    }

    @Test
    void theLastWriteGetsItsReadBackBeforeItIsGivenUp() {
        BeeperBack b = new BeeperBack();
        for (int i = 1; i <= BeeperBack.MAX_WRITES; i++) assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, i * 1000L));
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, BOARD, QUIET, 5020), "an answer asked for before the fifth write");
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 5040), "the fifth held");
    }

    @Test
    void theyAreNeverWrittenToAnotherBoard() {
        BeeperBack b = new BeeperBack();
        for (long t = 1000; t <= 30_000; t += 1000) {
            assertEquals(BeeperBack.OTHER_BOARD, b.read(OWN, BOARD, OTHER, QUIET, t), "whatever it has - even quiet as ours would be");
        }
        assertEquals(BeeperBack.OTHER_BOARD, b.read(OWN, BOARD, OTHER, OWN, 31_000), "nor forgotten: they are the first board's");
        assertEquals(0, b.writes());
        assertTrue(b.sayOnce(BeeperBack.OTHER_BOARD));
        assertFalse(b.sayOnce(BeeperBack.OTHER_BOARD));
        // the board they came from is back on the port
        b.restart();
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 40_000));
    }

    @Test
    void aBoardNotIdentifiedYetIsNotWrittenTo() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, null, QUIET, 1000), "its id was not read yet");
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, null, OWN, 2000), "nor forgotten on the word of a board nobody knows");
        assertEquals(0, b.writes());
        assertEquals(BeeperBack.WRITE, b.read(OWN, BOARD, BOARD, QUIET, 3000));
    }

    @Test
    void anAnswerThatIsNotUnderstoodWritesNothingAndForgetsNothing() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, BOARD, null, 1000));
        assertEquals(BeeperBack.WAIT, b.read(OWN, BOARD, BOARD, new byte[]{1, 2, 3, 4}, 2000), "an older Betaflight's short reply");
        assertEquals(0, b.writes());
        assertEquals(BeeperBack.NOTHING, b.read(null, "", BOARD, QUIET, 3000), "nothing kept: nothing to do");
    }

    /** The app before this rule kept the settings without a board. */
    @Test
    void settingsKeptWithoutABoardGoOnlyToABoardThatIsExactlyAsTheQuietLeftIt() {
        BeeperBack b = new BeeperBack();
        assertEquals(BeeperBack.WAIT, b.read(OWN, "", null, QUIET, 500), "this board's id first: it is kept with them from here on");
        assertEquals(BeeperBack.ADOPT, b.read(OWN, "", BOARD, QUIET, 1000), "as our quiet left it: theirs, and written");
        assertEquals(1, b.writes());
        assertEquals(BeeperBack.DONE, b.read(OWN, BOARD, BOARD, OWN, 1030));

        assertEquals(BeeperBack.DONE, new BeeperBack().read(OWN, "", BOARD, OWN, 1000), "already as they were");
        // quiet by settings of its own, or anything else: not the board that was quieted
        byte[] another = config(0x00000006, 1, 0x00000002);
        assertTrue(BeeperQuiet.isQuiet(another));
        BeeperBack c = new BeeperBack();
        assertEquals(BeeperBack.FORGET, c.read(OWN, "", BOARD, another, 1000));
        assertEquals(0, c.writes());
    }

    @Test
    void beforeASleepTheFirstBoardsOwnSettingsStayKeptAndAnotherBoardsAreReplaced() {
        // nothing kept: what is read is kept, quiet or not
        assertTrue(BeeperBack.keepWhatIsRead(OWN, BOARD, null, ""));
        assertTrue(BeeperBack.keepWhatIsRead(QUIET, BOARD, null, ""));
        // this board's kept from the sleep before and not yet back: a quiet one read now is not the pilot's own
        assertFalse(BeeperBack.keepWhatIsRead(QUIET, BOARD, OWN, BOARD));
        // ... but anything else it has now is (it lost power meanwhile, the pilot changed it)
        assertTrue(BeeperBack.keepWhatIsRead(OWN, BOARD, OWN, BOARD));
        // another board's: this board's own take their place, even when quiet (they are its own)
        assertTrue(BeeperBack.keepWhatIsRead(QUIET, BOARD, OWN, OTHER));
        // kept without a board: this board's only when it is as that quiet left it
        assertFalse(BeeperBack.keepWhatIsRead(QUIET, BOARD, OWN, ""));
        assertTrue(BeeperBack.keepWhatIsRead(config(0x06, 1, 0x02), BOARD, OWN, ""));
    }

    @Test
    void theOffWriteBeforeASleepHoldsWhenTheReadBackIsWhatWasWritten() {
        assertTrue(BeeperBack.holds(QUIET, QUIET));
        assertTrue(BeeperBack.holds(QUIET, java.util.Arrays.copyOf(QUIET, 12)), "bytes a newer Betaflight adds are not compared");
        assertFalse(BeeperBack.holds(QUIET, OWN), "still beeping: the PC's log says it may beep");
        assertFalse(BeeperBack.holds(QUIET, null));
        assertFalse(BeeperBack.holds(QUIET, new byte[]{6, 0, 0, 0}));
        assertEquals(3, BeeperBack.QUIET_TRIES);
        assertTrue(BeeperBack.QUIET_TRIES * BeeperBack.QUIET_WAIT_MS <= 500, "the sleep waits half a second for it at the most");
    }

    @Test
    void theBoardsIdIsItsProcessorsUniqueId() {
        byte[] uid = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef, 0x01, 0x23, 0x45, 0x67};
        assertEquals(BOARD, BeeperBack.boardId(uid));
        assertEquals(BOARD, BeeperBack.boardId(java.util.Arrays.copyOf(uid, 16)));
        assertNull(BeeperBack.boardId(null));
        assertNull(BeeperBack.boardId(new byte[11]), "a short answer names no board");
        assertNull(BeeperBack.boardId(new byte[12]), "nor does one of zeros");
        assertEquals(160, BeeperBack.MSP_UID);
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and ReleaseHasNoBenchCodeTest do. */
    @Test
    void thePhoneWritesTheBeeperInLineWithItsOtherRequestsAndForgetsABoardWithItsConnection() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String msp = new String(Files.readAllBytes(flight.resolve("Msp.java")), StandardCharsets.UTF_8);
        String service = new String(Files.readAllBytes(flight.resolve("DDService.java")), StandardCharsets.UTF_8);
        // the one-place slot is the channel map's alone: a beeper write there could be replaced unwritten, or replace it
        assertEquals(1, msp.split("serial\\.writeDataMspUrgent\\(", -1).length - 1, "writeDataMspUrgent in Msp.java");
        assertTrue(msp.contains("serial.writeDataMspUrgent(getMspRequest(FcCommon.MSP_RX_MAP))"));
        // a new connection may be another board: neither its settings nor its id are the last one's
        String close = msp.substring(msp.indexOf("public void close(){"), msp.indexOf("private final Runnable mspRun"));
        assertTrue(close.contains("beeperConfig = null;") && close.contains("boardId = null;") && close.contains("beeperBack.restart();"), "Msp.close()");
        // kept settings leave the phone's store only where the rule says they are back (or fit no board)
        String read = service.substring(service.indexOf("void beeperConfigRead("), service.indexOf("void powerSleepOn("));
        assertEquals(2, read.split("\\.remove\\(BEEPER_AS_READ\\)", -1).length - 1, "removals in beeperConfigRead");
        assertTrue(read.indexOf("case BeeperBack.DONE:") < read.indexOf(".remove(BEEPER_AS_READ)"), "none before the rule is asked");
        assertTrue(read.contains("case BeeperBack.FORGET:"));
    }
}
