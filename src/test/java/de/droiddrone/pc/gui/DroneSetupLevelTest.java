package de.droiddrone.pc.gui;

import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.Orientation;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-063: CALIBRATE (the accelerometer's level, Drone setup's second step) pressed with no flight controller waited
 * without limit and was done on the next board plugged in - which may be held in a hand, and is then saved as "flat".
 * Here a calibration that finds no board is dropped at once and said, and one asked of a board is never done on
 * another. The step is in a guide that is never shown; the boards are made up, no real port.
 */
@Timeout(60)
class DroneSetupLevelTest {
    private MotorsPageRig rig;

    @AfterEach
    void close() throws Exception {
        if (rig != null) rig.close();
    }

    private void open() throws Exception {
        rig = new MotorsPageRig();
        rig.open(() -> null); // Drone setup's port choice: automatic
    }

    private LevelBoard plug(String port) {
        LevelBoard board = new LevelBoard();
        rig.usb.plug(port, "Made-up Betaflight board", board::connect);
        return board;
    }

    @Test
    void calibratePressedWithNoBoardIsDroppedAtOnceAndSaid() throws Exception {
        open();
        rig.press("CALIBRATE");
        assertEquals("WARN: No flight controller: not calibrated. USB to this PC, then Calibrate.", rig.shows(MotorsPageRig.LEVEL));
    }

    @Test
    void calibratePressedWithNoBoardIsNeverDoneOnABoardPluggedInLater() throws Exception {
        open();
        rig.press("CALIBRATE");
        LevelBoard later = plug("MADEUP1");
        Waits.await(() -> later.attitudes() >= 3, 20_000, "the live view to follow the board plugged in later");
        assertEquals(0, later.calibrations(), "the board plugged in after the press is not calibrated");
        assertTrue(rig.shows(MotorsPageRig.LEVEL).contains("not calibrated"), rig.shows(MotorsPageRig.LEVEL));
    }

    /**
     * The board goes between the press and the calibration: the live thread is held in a request to board A (the
     * test holds the made-up board, which then answers nothing), CALIBRATE is pressed, A's cable comes out and
     * board B goes in. B must not be calibrated for a press that was made with A on the table.
     */
    @Test
    void aCalibrationAskedOfABoardThatIsLostIsNotDoneOnTheNextBoard() throws Exception {
        open();
        LevelBoard a = plug("MADEUP1");
        Waits.await(() -> a.attitudes() >= 2, 20_000, "the live view to follow board A");
        Thread live = MotorsPageRig.liveThread();
        LevelBoard b;
        synchronized (a) {
            Waits.await(() -> live.getState() == Thread.State.BLOCKED, 10_000, "the live thread to wait for board A");
            rig.press("CALIBRATE");
            a.unplug();
            rig.usb.unplug("MADEUP1");
            b = plug("MADEUP2");
        }
        LevelBoard other = b;
        Waits.await(() -> other.attitudes() >= 3, 20_000, "the live view to follow board B");
        assertEquals(0, other.calibrations(), "board B is not calibrated for a press made with board A there");
        assertEquals(0, a.calibrations());
        assertEquals("WARN: Flight controller lost: not calibrated. Calibrate again.", rig.shows(MotorsPageRig.LEVEL));
    }

    @Test
    void withABoardThereCalibrateLevelsThatBoard() throws Exception {
        open();
        LevelBoard board = plug("MADEUP1");
        Waits.await(() -> board.attitudes() >= 1, 20_000, "the live view to follow the board");
        rig.press("CALIBRATE");
        assertEquals("PENDING: Calibrating. Keep it still...", rig.shows(MotorsPageRig.LEVEL));
        Waits.await(() -> board.calibrations() == 1, 10_000, "the board to be told to calibrate");
        Waits.waitForOnSwing(() -> rig.says(MotorsPageRig.LEVEL).equals("Calibrated: reads flat."), 15_000, "the row to say it is calibrated");
        assertEquals(1, board.calibrations(), "once");
    }

    /**
     * A made-up board for the step's live view: it says it runs Betaflight, lies flat, and counts the level
     * calibrations it is told to make (MSP_ACC_CALIBRATION). Anything else gets Betaflight's error answer.
     */
    private static final class LevelBoard {
        private int calibrations, attitudes;
        private boolean gone;

        synchronized int calibrations() {
            return calibrations;
        }

        synchronized int attitudes() {
            return attitudes;
        }

        /** The cable out: reads say "gone", writes fail. */
        synchronized void unplug() {
            gone = true;
        }

        BetaflightUsb.Wire connect() {
            return new BetaflightUsb.Wire() {
                private final MspFrames requests = new MspFrames(MspFrames.V1);
                private final ArrayDeque<Byte> out = new ArrayDeque<>();
                private boolean closed;

                @Override
                public int read(byte[] buf) {
                    synchronized (LevelBoard.this) {
                        if (gone || closed) return -1;
                        int n = 0;
                        while (n < buf.length && !out.isEmpty()) buf[n++] = out.poll();
                        if (n > 0) return n;
                    }
                    try {
                        Thread.sleep(2); // nothing yet: a port waits a little before it says so
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }

                @Override
                public int available() {
                    synchronized (LevelBoard.this) {
                        return out.size();
                    }
                }

                @Override
                public void write(byte[] data) throws IOException {
                    synchronized (LevelBoard.this) {
                        if (gone || closed) throw new IOException("Could not write to the flight controller");
                        for (MspFrames.Frame f : requests.feed(data, data.length)) {
                            byte[] reply = answer(f.code);
                            byte[] frame = MspFrames.v1Request(f.code, reply);
                            frame[2] = reply == null ? MspFrames.ERROR : MspFrames.RESPONSE;
                            for (byte x : frame) out.add(x);
                        }
                    }
                }

                @Override
                public boolean isOpen() {
                    synchronized (LevelBoard.this) {
                        return !gone && !closed;
                    }
                }

                @Override
                public void close() {
                    synchronized (LevelBoard.this) {
                        closed = true;
                    }
                }
            };
        }

        /** Called with the board held. */
        private byte[] answer(int code) {
            if (code == 2) return "BTFL".getBytes(StandardCharsets.US_ASCII); // MSP_FC_VARIANT
            if (code == Orientation.MSP_ATTITUDE) {
                attitudes++;
                return new byte[6]; // flat, heading 0
            }
            if (code == DroneSetup.MotorsPage.MSP_ACC_CALIBRATION) {
                calibrations++;
                return new byte[0];
            }
            return null;
        }
    }
}
