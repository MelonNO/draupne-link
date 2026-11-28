package de.droiddrone.pc.setup;

import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.BeeperRule;
import de.droiddrone.common.FcCommon;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Drone setup keeps a drone on this PC's USB quiet (1.0.72; the pilot, 2026-11-23: setting up a new drone, it beeped
 * without end once CONFIRM was pressed). Since 1.0.71 Drone setup switches on Betaflight's DShot beacon for a lost
 * receiver (the motors beep, to find a drone whose link went). A DRAUPNE drone's receiver is MSP - the phone - and on
 * the bench the phone is not plugged in: Betaflight sees its receiver lost and the motors beep from its next start.
 * <p>
 * The phone keeps a disarmed drone quiet with idle sticks (pc.33, pc.54's own frame); Drone setup now does the same
 * over USB while its window is open: MSP_SET_RAW_RC every {@link #EVERY_MS} with the sticks centred, the throttle and
 * every AUX low ({@link BeeperRule#ownIdle}), in the board's channel order (MSP_RX_MAP). Nothing is written to the
 * board's settings, so nothing of it can be saved: a lost link in the field beeps as before.
 * <p>
 * Only when it is safe: the receiver is MSP (another receiver has its own sticks), the board's own ARM ranges say the
 * frame can't arm ({@link ArmRanges}), and the board reads DISARMED - checked again every second while held.
 * <p>
 * Who feeds: between Drone setup's steps the holder here keeps a connection of its own ({@link #hold}); every other
 * opening of a flight controller's USB asks it to let go first ({@link #opening}, from {@link BetaflightUsb#open}),
 * and it takes the port again {@link #RESUME_MS} after the last one closed. A tool that keeps the port for a while -
 * the motor test, the orientation check, FIND GPS - is fed on its own connection once it is known to be the held
 * board ({@link #verified}, from SetupBoard.mustBe). Never while the CLI is open (Betaflight takes no MSP
 * there) or the ESCs have the port.
 */
public final class RestingSticks {
    /** As the phone's idle sticks (Udp.IDLE_EVERY_MS): Betaflight takes an MSP receiver as lost after 200 ms or so. */
    static final long EVERY_MS = 50;
    /** After the last other connection closed: room for the next step's opening (a round, then a tool). */
    static final long RESUME_MS = 400;
    /** Between tries to open the held board (it restarts after a save, or is unplugged). */
    static final long RETRY_MS = 500;
    /** After a board that can't be fed (not MSP, the frame could arm, armed): asked again this much later. */
    static final long NOT_NOW_MS = 10_000;
    static final long STATUS_EVERY_MS = 1000;

    private RestingSticks() {
    }

    // ------------------------------------------------------------------ the frame (pure)

    /**
     * MSP_SET_RAW_RC's payload for the resting sticks: {@link BeeperRule#ownIdle} put in Betaflight's channel order by
     * its channel map (as the phone's Msp.setRawRc does: the PC's channel i goes to map[i]). Null when the map doesn't
     * fit, or by the board's ARM ranges the frame could arm (or they weren't understood).
     * @param rxMap      MSP_RX_MAP's answer
     * @param modeRanges MSP_MODE_RANGES' answer
     */
    static byte[] payload(byte[] rxMap, byte[] modeRanges) {
        int[][] slots = ArmRanges.parse(modeRanges);
        if (slots == null || rxMap == null || rxMap.length == 0) return null;
        short[] frame = BeeperRule.ownIdle(f -> ArmRanges.mayArm(f, f.length, slots));
        if (frame == null) return null;
        int count = Math.min(frame.length, FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT);
        byte[] out = new byte[count * 2];
        boolean[] taken = new boolean[count];
        for (int i = 0; i < count; i++) {
            int at = i;
            if (i < rxMap.length) {
                at = rxMap[i] & 0xff;
                if (at >= count) return null;
            }
            if (taken[at]) return null; // two channels on one place: not a map
            taken[at] = true;
            out[at * 2] = (byte) frame[i];
            out[at * 2 + 1] = (byte) (frame[i] >> 8);
        }
        return out;
    }

    /** Why a board is not fed, or null when it may be: the payload to feed is in what[0]. */
    static String check(BetaflightUsb fc, byte[][] what) throws IOException {
        if (!BetaflightPlan.isMspReceiver(fc.featureMask())) return "its receiver is not MSP";
        byte[] payload = payload(fc.msp(FcCommon.MSP_RX_MAP, null), fc.msp(ArmRanges.MSP_MODE_RANGES, null));
        if (payload == null) return "by its ARM range they could arm";
        if (MotorTest.armed(fc.msp(FcCommon.MSP_STATUS, null))) return "it is armed";
        what[0] = payload;
        return null;
    }

    // ------------------------------------------------------------------ the holder

    private static final Object lock = new Object();
    /** The board Drone setup shows: its USB port and its id ({@link SetupConfirm#boardId}); null: none held. */
    private static String port, board;
    private static Consumer<String> log = s -> {
    };
    private static Thread thread;
    /** The holder's own connection, null: none open now. */
    private static BetaflightUsb held;
    /** Other connections open now (Drone setup's rounds and tools): the holder lets them have the port. */
    private static int others;
    private static long resumeAt, nextTryAt, statusAt;
    /** Said once per board: fed, or why not. */
    private static String said;

    /**
     * Drone setup has read this board: keep it quiet until {@link #stop}. A board without an id (it does not say it)
     * is not held: there is no telling it is still the same one after a restart.
     * @param usbPort the port it was read on (COM7)
     * @param boardId its id, {@link SetupConfirm#boardId}
     */
    public static void hold(String usbPort, String boardId, Consumer<String> logTo) {
        synchronized (lock) {
            if (usbPort == null || boardId == null) {
                stopLocked();
                return;
            }
            if (!usbPort.equals(port) || !boardId.equals(board)) {
                closeHeld();
                said = null;
                nextTryAt = 0;
            }
            port = usbPort;
            board = boardId;
            log = logTo == null ? s -> {
            } : logTo;
            if (thread == null) {
                thread = new Thread(RestingSticks::run, "drone-setup-resting-sticks");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** Drone setup closed: the port is let go (the board then beeps for its lost receiver, as Betaflight does). */
    public static void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        port = board = null;
        closeHeld();
        lock.notifyAll();
    }

    /** The holder has a connection open and feeds it. */
    static boolean holding() {
        synchronized (lock) {
            return held != null;
        }
    }

    /** From {@link BetaflightUsb#open}, before another connection opens a port: the holder lets go of its own. */
    static void opening() {
        synchronized (lock) {
            others++;
            closeHeld();
        }
    }

    /** That connection is closed (or did not open). */
    static void closed() {
        synchronized (lock) {
            if (others > 0) others--;
            resumeAt = System.currentTimeMillis() + RESUME_MS;
            lock.notifyAll();
        }
    }

    /**
     * From a tool's opening once it knows its board (SetupBoard.mustBe): this connection is to boardId. When that is
     * the held board it is fed for as long as it is open (see the class comment). Never throws: a tool works the same
     * without it.
     */
    static void verified(BetaflightUsb fc, String boardId) {
        synchronized (lock) {
            if (board == null || !board.equals(boardId)) return;
        }
        try {
            byte[][] what = new byte[1][];
            if (check(fc, what) == null) fc.feedRestingSticks(what[0]);
        } catch (IOException e) {
            // the tool will find out itself
        }
    }

    /** As {@link #verified}, the board asked its id first - only while one is held (the motor test opens any board). */
    static void verify(BetaflightUsb fc) {
        synchronized (lock) {
            if (board == null) return;
        }
        verified(fc, SetupConfirm.boardId(fc));
    }

    private static void closeHeld() {
        if (held != null) held.close();
        held = null;
    }

    private static void run() {
        synchronized (lock) {
            try {
                while (board != null) {
                    long now = System.currentTimeMillis();
                    if (others > 0 || now < resumeAt) {
                        lock.wait(others > 0 ? 200 : Math.max(1, resumeAt - now));
                        continue;
                    }
                    if (held == null) {
                        if (now >= nextTryAt) take(port, board);
                    } else if (now >= statusAt) {
                        statusAt = now + STATUS_EVERY_MS;
                        try {
                            if (MotorTest.armed(held.msp(FcCommon.MSP_STATUS, null))) {
                                closeHeld();
                                nextTryAt = now + NOT_NOW_MS;
                                log.accept("Drone setup: the FC reads ARMED - resting sticks stopped.");
                                said = "armed";
                            }
                        } catch (IOException e) {
                            closeHeld(); // unplugged, or restarting after a save
                            nextTryAt = now + RETRY_MS;
                        }
                    }
                    lock.wait(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeHeld();
                thread = null;
            }
        }
    }

    /** Opens the held board and feeds it, or says once why not. Under the lock: no other opening meanwhile. */
    private static void take(String usbPort, String boardId) {
        long now = System.currentTimeMillis();
        BetaflightUsb.Port found = null;
        for (BetaflightUsb.Port p : BetaflightUsb.findFlightControllers()) if (p.systemName().equals(usbPort)) found = p;
        if (found == null) {
            nextTryAt = now + RETRY_MS;
            return;
        }
        BetaflightUsb fc = null;
        try {
            fc = BetaflightUsb.openForRestingSticks(found);
            if (!fc.firmware().startsWith("BTFL") || !boardId.equals(SetupConfirm.boardId(fc))) {
                fc.close();
                nextTryAt = now + NOT_NOW_MS; // another board on the port: nothing sent to it
                return;
            }
            byte[][] what = new byte[1][];
            String why = check(fc, what);
            if (why != null) {
                fc.close();
                nextTryAt = now + NOT_NOW_MS;
                if (!why.equals(said)) log.accept("Drone setup: no resting sticks to the FC - " + why + ".");
                said = why;
                return;
            }
            fc.feedRestingSticks(what[0]);
            held = fc;
            statusAt = now + STATUS_EVERY_MS;
            if (!"fed".equals(said)) log.accept("Drone setup: resting sticks to the FC over USB - no lost-link beeping while it is set up.");
            said = "fed";
        } catch (IOException | RuntimeException e) {
            if (fc != null) fc.close();
            nextTryAt = now + RETRY_MS; // restarting, or the port in use (Betaflight Configurator)
        }
    }
}
