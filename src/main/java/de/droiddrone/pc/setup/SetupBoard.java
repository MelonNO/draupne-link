package de.droiddrone.pc.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The flight controller on USB that Drone setup's tools work on: the USB port and the board id
 * ({@link SetupConfirm#boardId}) as its first step last read them - the board its page shows and its CONFIRM is for.
 * F-060 (the motor test) and F-071 (the orientation check, the compass calibration, the live view behind CALIBRATE,
 * FIND GPS, the ESC row): each of them took the first Betaflight board that answered, so with two flight controllers
 * on USB one was read on the page and the other was turned, levelled or written to.
 * <p>
 * {@link #open} is the one way in for all of them. For a board (port and id): that port only, and only the board with
 * that id - another one found there is asked what it is and sent nothing more. For a port alone (id null: the pilot's
 * port choice with nothing read on it yet, or a board that does not say its id): whichever Betaflight board is on
 * it. For none (null: nothing read, the port choice on Automatic): the one Betaflight board on USB; with two there is
 * no telling which the pilot means, and none is opened.
 *
 * @param port the USB port's name (COM7)
 * @param id   the board's id, or null when it is not known
 */
public record SetupBoard(String port, String id) {
    /** How a tool opens the board on a port: {@link #betaflight} for a plain connection, MotorTest::open for the motor test. */
    public interface Opener<T> {
        /** @param boardId the board it must be, or null for whichever is there; another one: {@link #mustBe} */
        T open(BetaflightUsb.Port port, String boardId) throws IOException;
    }

    /** Where the pilot has another board read in, for {@link NotThere#html}: on Drone setup's first step, or back on it. */
    public static final String READ_AGAIN = "<b>Read again</b>", BACK_AND_READ_AGAIN = "<b>Back</b> and <b>Read again</b>";

    /** Another board than the one asked for is on the port. It was asked what it is, nothing more. */
    static final class OtherBoard extends IOException {
        OtherBoard() {
            super("Not the board Drone setup read. Nothing sent.");
        }
    }

    /** No board was opened, and why: {@link #html} is the line for the window or the row. */
    public static final class NotThere extends IOException {
        public enum Why {
            /** Nothing that looks like a flight controller is on USB (or on the pilot's port). */
            NOT_FOUND,
            /** The board Drone setup read is not on its port. */
            NOT_PLUGGED,
            /** Another board is on the port of the one Drone setup read. */
            OTHER_BOARD,
            /** Opened for no board, and more than one is on USB. */
            SEVERAL,
            /** The port did not open, or what is on it can't be used. */
            FAILED
        }

        private final Why why;
        private final String line, wayOut;

        private NotThere(Why why, String line, String wayOut) {
            super(plain(line + (wayOut == null ? "" : " " + wayOut + READ_AGAIN + ".")));
            this.why = why;
            this.line = line;
            this.wayOut = wayOut;
        }

        public Why why() {
            return why;
        }

        /** Nothing is wrong, the board is just not there: a warning, not an error. */
        public boolean waiting() {
            return why == Why.NOT_FOUND || why == Why.NOT_PLUGGED;
        }

        /**
         * The line to show (HTML). {@link #getMessage} is the same in plain text, for a tool on Drone setup's first step.
         * @param readAgain how the pilot has another board read in from where this is shown: {@link #READ_AGAIN}
         *                  or {@link #BACK_AND_READ_AGAIN}
         */
        public String html(String readAgain) {
            return line + (wayOut == null ? "" : "<br>" + wayOut + readAgain + ".");
        }

        private static String plain(String html) {
            return html.replace("<br>", " ").replaceAll("<[^>]+>", "");
        }
    }

    /** {@link #open(SetupBoard, Opener)} for a plain connection to the board. */
    public static BetaflightUsb open(SetupBoard target) throws NotThere {
        return open(target, SetupBoard::betaflight);
    }

    /**
     * Opens the flight controller a tool is for (see the class comment), or says why not.
     * @param target the board Drone setup read, a port alone (id null), or null for none
     */
    public static <T> T open(SetupBoard target, Opener<T> opener) throws NotThere {
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        if (target != null) {
            BetaflightUsb.Port port = null;
            for (BetaflightUsb.Port p : ports) if (p.systemName().equals(target.port())) port = p;
            if (port == null && target.id() != null) {
                throw new NotThere(NotThere.Why.NOT_PLUGGED, "Drone setup's board is not on <b>" + target.port() + "</b>.", "Plug it in, or ");
            }
            if (port == null) throw notFound();
            try {
                return opener.open(port, target.id());
            } catch (OtherBoard e) {
                throw new NotThere(NotThere.Why.OTHER_BOARD, "<b>" + target.port() + "</b>: not the board Drone setup read. Nothing sent.",
                        "Plug that one in, or ");
            } catch (IOException e) {
                throw new NotThere(NotThere.Why.FAILED, target.port() + ": " + e.getMessage(), null);
            }
        }
        if (ports.isEmpty()) throw notFound();
        List<BetaflightUsb.Port> boards = ports;
        String why = null;
        if (ports.size() > 1) { // a GPS or ESC adapter may be among them: which ones answer as Betaflight (asked, nothing written)
            boards = new ArrayList<>();
            for (BetaflightUsb.Port port : ports) {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    String firmware = fc.firmware();
                    if (firmware.startsWith("BTFL")) boards.add(port);
                    else why = port.systemName() + ": " + notBetaflight(firmware);
                } catch (IOException e) {
                    why = port.systemName() + ": " + e.getMessage();
                }
            }
            if (boards.size() > 1) {
                throw new NotThere(NotThere.Why.SEVERAL, boards.size() + " flight controllers on USB. Unplug all but one.", null);
            }
        }
        for (BetaflightUsb.Port port : boards) {
            try {
                return opener.open(port, null);
            } catch (IOException e) {
                why = port.systemName() + ": " + e.getMessage();
            }
        }
        throw new NotThere(NotThere.Why.FAILED, why, null);
    }

    private static NotThere notFound() {
        return new NotThere(NotThere.Why.NOT_FOUND, "Not found. Plug in a USB data cable.", null);
    }

    private static String notBetaflight(String firmware) {
        return "This flight controller doesn't run Betaflight (" + firmware.trim() + ").";
    }

    /**
     * The Betaflight board on this port, opened and asked what it is - nothing is written to it. With a board id:
     * only that board; another one is closed again ({@link #mustBe}).
     */
    public static BetaflightUsb betaflight(BetaflightUsb.Port port, String boardId) throws IOException {
        BetaflightUsb fc = BetaflightUsb.open(port);
        try {
            String firmware = fc.firmware();
            if (!firmware.startsWith("BTFL")) throw new IOException(notBetaflight(firmware));
            mustBe(fc, boardId);
            return fc;
        } catch (IOException | RuntimeException e) {
            fc.close();
            throw e;
        }
    }

    /**
     * This connection is to the board with this id (null: any), or it is refused - asked over MSP, before anything
     * else is sent to the board. For an {@link Opener} that makes its own connection (the motor test's).
     */
    static void mustBe(BetaflightUsb fc, String boardId) throws IOException {
        if (boardId != null && !boardId.equals(SetupConfirm.boardId(fc))) throw new OtherBoard();
    }
}
