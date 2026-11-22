package de.droiddrone.pc.gui;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.Orientation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up board for the live view of Drone setup's second step: it says it runs Betaflight and which board it is
 * (MSP_UID), lies flat, and counts the level calibrations it is told to make (MSP_ACC_CALIBRATION). Anything else
 * gets Betaflight's error answer. A test that holds the board (synchronized) keeps it from answering: the thread
 * that asks it waits.
 */
final class LevelBoard {
    private final byte[] uid;
    private final List<Integer> requests = new ArrayList<>();
    private int calibrations, attitudes, opens;
    private boolean gone, armed;

    /** @param id the last byte of its id: board {@code id} of MotorTestRig.id */
    LevelBoard(int id) {
        uid = MotorTestRig.uid(id);
    }

    /** A board on this port of the made-up USB. */
    static LevelBoard plug(FakeUsb usb, String port, int id) {
        LevelBoard board = new LevelBoard(id);
        usb.plug(port, "Made-up Betaflight board", board::connect);
        return board;
    }

    synchronized int calibrations() {
        return calibrations;
    }

    synchronized int attitudes() {
        return attitudes;
    }

    /** How often its port was opened. */
    synchronized int opens() {
        return opens;
    }

    /** Every MSP request that came, by number, in order. */
    synchronized List<Integer> requests() {
        return List.copyOf(requests);
    }

    /** The cable out: reads say "gone", writes fail. */
    synchronized void unplug() {
        gone = true;
    }

    synchronized BetaflightUsb.Wire connect() {
        opens++;
        return new BetaflightUsb.Wire() {
            private final MspFrames frames = new MspFrames(MspFrames.V1);
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
                    for (MspFrames.Frame f : frames.feed(data, data.length)) {
                        requests.add(f.code);
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

    /** Armed (by its radio): MSP_STATUS says so, and a calibration is answered OK but not made - as Betaflight does (msp.c). */
    synchronized void arm() {
        armed = true;
    }

    /** Called with the board held. */
    private byte[] answer(int code) {
        if (code == 2) return "BTFL".getBytes(StandardCharsets.US_ASCII); // MSP_FC_VARIANT
        if (code == 160) return uid.clone(); // MSP_UID
        if (code == 101) { // MSP_STATUS: ARM is the first mode flag
            byte[] status = new byte[11];
            status[6] = (byte) (armed ? 1 : 0);
            return status;
        }
        if (code == Orientation.MSP_ATTITUDE) {
            attitudes++;
            return new byte[6]; // flat, heading 0
        }
        if (code == DroneSetup.MotorsPage.MSP_ACC_CALIBRATION) {
            if (!armed) calibrations++;
            return new byte[0];
        }
        return null;
    }
}
