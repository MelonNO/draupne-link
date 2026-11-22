package de.droiddrone.pc.setup;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.common.MspFrames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up board for the orientation check, at byte level: Betaflight's attitude and compass as the test "holds the
 * drone", and the CLI the check uses. align_mag is kept as Betaflight keeps a setting - in memory until "save", back
 * to what was saved at every restart ("save" and "exit" both restart, and end the USB connection). The compass is
 * fitted one way round ({@code right}); what it reads is right only when align_mag says so - the arithmetic is
 * OrientationTest's. No board alignment, no flash, no motors. A test that holds the board (synchronized) keeps it
 * from answering.
 */
public final class FakeCompassBoard {
    private final byte[] uid;
    private final int right, defaultIs;
    private String saved, ram;
    private double roll, pitch;
    private int nose;
    private boolean cli, gone;
    private final StringBuilder line = new StringBuilder();
    private final List<String> cliSeen = new ArrayList<>();
    private int saves, restarts, attitudes;
    private Link current;

    /**
     * @param id        the last byte of its MSP_UID
     * @param alignMag  align_mag as saved: DEFAULT, or one of Orientation.MAG_ALIGNS
     * @param right     the alignment that is right for the compass as fitted (an index into Orientation.MAG_ALIGNS)
     * @param defaultIs what DEFAULT does on this board (such an index too)
     */
    public FakeCompassBoard(int id, String alignMag, int right, int defaultIs) {
        uid = new byte[]{0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, (byte) id};
        saved = ram = alignMag;
        this.right = right;
        this.defaultIs = defaultIs;
    }

    /** On this port of the made-up USB; each open is a new connection. */
    public FakeCompassBoard plug(FakeUsb usb, String port) {
        usb.plug(port, "Made-up Betaflight board", this::connect);
        return this;
    }

    // ------------------------------------------------------------------ the test's hands

    /** The drone held like this: Betaflight's roll and pitch in degrees (nose up is pitch -90, right side down roll 90). */
    public synchronized void hold(double rollDeg, double pitchDeg) {
        roll = rollDeg;
        pitch = pitchDeg;
    }

    /** Flat, the nose pointing 0 north, 1 east, 2 south, 3 west. */
    public synchronized void nose(int quarter) {
        roll = pitch = 0;
        nose = quarter;
    }

    /** The cable out: reads say "gone", writes fail, until {@link #plugIn}. The board loses what was not saved. */
    public synchronized void unplug() {
        gone = true;
        restart();
    }

    public synchronized void plugIn() {
        gone = false;
    }

    // ------------------------------------------------------------------ what happened, what it holds

    /** align_mag as saved: what Betaflight has after its next restart. */
    public synchronized String saved() {
        return saved;
    }

    /** Every CLI command that came, in order (save and exit too). */
    public synchronized List<String> cli() {
        return List.copyOf(cliSeen);
    }

    /** The CLI commands that change something: every "set ..." and "save". */
    public synchronized List<String> writes() {
        return cliSeen.stream().filter(c -> c.startsWith("set ") || c.equals("save")).toList();
    }

    public synchronized int saves() {
        return saves;
    }

    public synchronized int restarts() {
        return restarts;
    }

    /** How often its attitude was asked: the check's thread is talking to it. */
    public synchronized int attitudes() {
        return attitudes;
    }

    // ------------------------------------------------------------------ the wire

    private synchronized BetaflightUsb.Wire connect() {
        Link link = new Link();
        if (gone) {
            link.alive = false;
        } else {
            if (current != null) current.alive = false; // one connection: an older one is gone
            current = link;
        }
        return link;
    }

    private void restart() {
        ram = saved;
        cli = false;
        line.setLength(0);
        if (current != null) current.alive = false;
        current = null;
        restarts++;
    }

    private final class Link implements BetaflightUsb.Wire {
        private final MspFrames frames = new MspFrames(MspFrames.V1);
        private final ArrayDeque<Byte> out = new ArrayDeque<>();
        private boolean alive = true, closed;

        @Override
        public int read(byte[] buf) {
            synchronized (FakeCompassBoard.this) {
                if (closed || !alive) return -1;
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
            synchronized (FakeCompassBoard.this) {
                return closed || !alive ? 0 : out.size();
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            synchronized (FakeCompassBoard.this) {
                if (closed || !alive) throw new IOException("Could not write to the flight controller");
                if (cli) {
                    for (byte b : data) {
                        if (alive) typed(this, (char) b);
                    }
                } else if (data.length == 1 && data[0] == '#') {
                    cli = true;
                    line.setLength(0);
                    send("\r\nEntering CLI Mode, type 'exit' to return, or 'help'\r\n\r\n# ");
                } else {
                    for (MspFrames.Frame f : frames.feed(data, data.length)) {
                        byte[] reply = msp(f.code);
                        byte[] frame = MspFrames.v1Request(f.code, reply);
                        frame[2] = reply == null ? MspFrames.ERROR : MspFrames.RESPONSE;
                        for (byte x : frame) out.add(x);
                    }
                }
            }
        }

        @Override
        public boolean isOpen() {
            synchronized (FakeCompassBoard.this) {
                return !closed && alive;
            }
        }

        @Override
        public void close() {
            synchronized (FakeCompassBoard.this) {
                closed = true;
                if (current == this) current = null;
            }
        }

        void send(String text) {
            for (byte b : text.getBytes(StandardCharsets.US_ASCII)) out.add(b);
        }
    }

    // ------------------------------------------------------------------ MSP

    private static byte[] s16(int... values) {
        byte[] p = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            p[2 * i] = (byte) values[i];
            p[2 * i + 1] = (byte) (values[i] >> 8);
        }
        return p;
    }

    /** The answer's payload; null is Betaflight's error answer. Called with the board held. */
    private byte[] msp(int code) {
        if (code == 2) return "BTFL".getBytes(StandardCharsets.US_ASCII); // MSP_FC_VARIANT
        if (code == 160) return uid.clone(); // MSP_UID
        if (code == 101) return s16(125, 0, 1 | 1 << 2, 0, 0, 0); // MSP_STATUS: the accelerometer and a compass
        if (code == Orientation.MSP_BOARD_ALIGNMENT_CONFIG) return s16(0, 0, 0);
        if (code == Orientation.MSP_ATTITUDE) {
            attitudes++;
            return s16((int) Math.round(roll * 10), (int) Math.round(pitch * 10), nose * 90);
        }
        if (code == Orientation.MSP_RAW_IMU) {
            double[] m = compass();
            return s16(0, 0, 0, 0, 0, 0, (int) Math.round(m[0]), (int) Math.round(m[1]), (int) Math.round(m[2]));
        }
        return null;
    }

    /**
     * What Betaflight reads from the compass now (body x forward, y left, z up; the field north and down), with an
     * uncalibrated offset: right only when align_mag is the alignment the compass is fitted for.
     */
    private double[] compass() {
        double psi = Math.toRadians(nose * 90);
        double[] body = {400 * Math.cos(psi), 400 * Math.sin(psi), -1100};
        int set = "DEFAULT".equals(ram) ? defaultIs : Orientation.alignIndex(ram);
        double[] raw = mul(transposed(Orientation.MAG_MATRICES[right]), body);
        double[] m = mul(Orientation.MAG_MATRICES[Math.max(0, set)], raw);
        return new double[]{m[0] + 137, m[1] - 260, m[2] + 55};
    }

    private static double[] mul(int[][] m, double[] v) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i] += m[i][j] * v[j];
        return r;
    }

    private static int[][] transposed(int[][] m) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = m[j][i];
        return r;
    }

    // ------------------------------------------------------------------ the CLI

    /** One character typed: at the line's end the command is echoed, run, and the prompt printed. */
    private void typed(Link link, char c) {
        if (c != '\n') {
            if (c != '\r') line.append(c);
            return;
        }
        String command = line.toString().trim();
        line.setLength(0);
        cliSeen.add(command);
        if (command.equals("save")) {
            saved = ram;
            saves++;
            restart();
            return;
        }
        if (command.equals("exit")) {
            restart();
            return;
        }
        link.send(command + "\r\n" + run(command) + "\r\n# ");
    }

    private String run(String command) {
        if (command.equals("version")) return "# Betaflight / STM32F405 (S405) 4.4.1 Apr 11 2023 / 01:20:48 (madeup000) MSP API: 1.45";
        if (command.equals("get align_mag")) return "align_mag = " + ram + "\r\nAllowed values: DEFAULT, " + String.join(", ", Orientation.MAG_ALIGNS) + ", CUSTOM";
        if (command.matches("get align_board_(roll|pitch|yaw)")) return command.substring(4) + " = 0\r\nAllowed range: -180 - 360";
        if (command.equals("diff all")) return "# version\r\n# Betaflight / STM32F405 (S405) 4.4.1\r\n\r\n# master\r\nset align_mag = " + ram;
        if (command.startsWith("set align_mag = ")) {
            String value = command.substring("set align_mag = ".length()).trim();
            if (!value.equals("DEFAULT") && Orientation.alignIndex(value) < 0) return "###ERROR IN set: INVALID VALUE###";
            ram = value;
            return "align_mag set to " + value;
        }
        return "###ERROR IN " + command + ": UNKNOWN COMMAND###";
    }
}
