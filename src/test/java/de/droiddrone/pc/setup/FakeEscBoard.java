package de.droiddrone.pc.setup;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A made-up flight controller with ESCs behind it, at byte level, for {@link EscLink}: MSP (status, the passthrough
 * command), then Betaflight's 4-way interface as serial_4way.c has it, then MSP again after InterfaceExit. Each ESC
 * has a memory that behaves like flash - an erase sets a page to 0xFF, a write can only clear bits - so a write
 * without its erase reads back wrong, as on the real thing. Faults can be set per ESC.
 */
public final class FakeEscBoard implements BetaflightUsb.Wire {
    /** One ESC: what its bootloader says, its memory, and what may go wrong with it. */
    public static final class Esc {
        public final int signature, mode, pageSize;
        public final byte[] memory = new byte[0x10000];
        public boolean answers = true;
        /** That many connects are refused first (an ESC on its way into the bootloader). */
        public int refuseConnects;
        /** That many writes arrive with one bit wrong (the read-back shows it). */
        public int corruptWrites;
        /** Every write from now on is refused. */
        public boolean refuseWrites;
        /** After the next erase nothing is answered any more (the battery pulled). */
        public boolean diesAfterErase;
        public int erases, writes, connects, restarts;

        Esc(int signature, int mode, int pageSize) {
            this.signature = signature;
            this.mode = mode;
            this.pageSize = pageSize;
            Arrays.fill(memory, (byte) 0xFF);
        }

        public byte[] bytes(int at, int len) {
            return Arrays.copyOfRange(memory, at, at + len);
        }
    }

    public final List<Esc> escs = new ArrayList<>();
    public boolean armed;
    public final List<String> said = new ArrayList<>();
    private final ArrayDeque<Byte> out = new ArrayDeque<>();
    private final ByteArrayOutputStream in = new ByteArrayOutputStream();
    private boolean passthrough, open = true;
    private Esc connected;
    /** The next answer goes out with a wrong checksum (noise on the line), once. */
    public boolean noiseOnce;
    /**
     * The pack voltage MSP_BATTERY_STATE answers, one reading after the other, the last one kept (16.0, then 0.3: the
     * battery pulled during the read). Empty: not answered, as a Betaflight that does not say.
     */
    public final List<Double> volts = new ArrayList<>();

    /** An EFM8BB2 ESC (settings at 0x1A00) with BLHeli_S-kind settings. name "" = BLHeli_S itself. */
    public Esc silabs(String name, int main, int sub, String layout, int beacon, int direction) {
        return silabs(0xE8B2, 512, 0x1A00, name, main, sub, layout, beacon, direction);
    }

    /** An EFM8BB51 ESC (settings at 0x3000, pages of 2048). */
    public Esc bb51(String name, int main, int sub, String layout, int beacon, int direction) {
        return silabs(0xE8B5, 2048, 0x3000, name, main, sub, layout, beacon, direction);
    }

    private Esc silabs(int signature, int pageSize, int at, String name, int main, int sub, String layout, int beacon, int direction) {
        Esc e = new Esc(signature, EscLink.MODE_SILABS, pageSize);
        byte[] m = e.memory;
        Arrays.fill(m, 0, 0x1000, (byte) 0x02); // "firmware"
        Arrays.fill(m, at, at + 0x70, (byte) 0);
        m[at] = (byte) main;
        m[at + 1] = (byte) sub;
        m[at + 2] = 33;
        m[at + EscFirmware.MOTOR_DIRECTION] = (byte) direction;
        m[at + 0x1C] = 80;
        m[at + EscFirmware.BEACON_DELAY] = (byte) beacon;
        field(m, at + 0x40, layout);
        field(m, at + 0x50, "#BLHELI$EFM8B21#");
        field(m, at + 0x60, name);
        if (name.startsWith("Bluejay")) for (int i = 0x70; i < 0xF2; i++) m[at + i] = (byte) (i * 7); // its start-up melody
        escs.add(e);
        return e;
    }

    /** An AM32 ESC (STM32F051, settings at 0x7C00). */
    public Esc am32(int main, int sub) {
        Esc e = new Esc(0x1F06, EscLink.MODE_ARM, 1024);
        byte[] m = e.memory;
        Arrays.fill(m, 0x7C00, 0x7C00 + 0xB0, (byte) 0);
        m[0x7C00] = 1;
        m[0x7C01] = 2;
        m[0x7C02] = 13;
        m[0x7C03] = (byte) main;
        m[0x7C04] = (byte) sub;
        System.arraycopy("AM32 ESC    ".getBytes(StandardCharsets.US_ASCII), 0, m, 0x7C05, 12);
        escs.add(e);
        return e;
    }

    /** An ARM ESC whose settings say nothing this app knows (BLHeli_32). */
    public Esc arm32() {
        Esc e = new Esc(0x1F06, EscLink.MODE_ARM, 1024);
        for (int i = 0; i < 0xB0; i++) e.memory[0x7C00 + i] = (byte) (0xA5 ^ i);
        escs.add(e);
        return e;
    }

    private static void field(byte[] m, int at, String s) {
        byte[] b = String.format("%-16s", s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, m, at, 16);
    }

    // ------------------------------------------------------------------ the wire

    @Override
    public int read(byte[] buf) {
        if (!open) return -1;
        int n = 0;
        while (n < buf.length && !out.isEmpty()) buf[n++] = out.poll();
        return n;
    }

    @Override
    public int available() {
        return out.size();
    }

    @Override
    public void write(byte[] data) {
        in.write(data, 0, data.length);
        byte[] all = in.toByteArray();
        int used = passthrough ? fourWay(all) : msp(all);
        in.reset();
        in.write(all, used, all.length - used);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    /** The port opened again after a close (the same board, a new Drone setup). */
    public void plugInAgain() {
        open = true;
    }

    public boolean inPassthrough() {
        return passthrough;
    }

    private void send(byte[] b) {
        for (byte x : b) out.add(x);
    }

    /** "$M<" size command payload checksum; answers "$M>". Returns the bytes used. */
    private int msp(byte[] b) {
        int at = 0;
        while (b.length - at >= 6) {
            if (b[at] != '$' || b[at + 1] != 'M' || b[at + 2] != '<') {
                at++;
                continue;
            }
            int size = b[at + 3] & 0xff, cmd = b[at + 4] & 0xff;
            if (b.length - at < 6 + size) break;
            at += 6 + size;
            said.add("MSP " + cmd);
            byte[] payload;
            if (cmd == 101) {
                payload = new byte[22];
                payload[6] = (byte) (armed ? 1 : 0);
            } else if (cmd == 2) {
                payload = new byte[]{'B', 'T', 'F', 'L'};
            } else if (cmd == 130 && !volts.isEmpty()) { // MSP_BATTERY_STATE: cells, capacity, 0.1 V, mAh, A, state, 0.01 V
                double v = volts.size() > 1 ? volts.remove(0) : volts.get(0);
                int cV = (int) Math.round(v * 100);
                payload = new byte[11];
                payload[0] = (byte) (v >= 3 ? 4 : 0);
                payload[3] = (byte) Math.min(255, Math.round(v * 10));
                payload[9] = (byte) cV;
                payload[10] = (byte) (cV >> 8);
            } else if (cmd == EscLink.MSP_SET_PASSTHROUGH) {
                payload = new byte[]{(byte) escs.size()};
                passthrough = true;
                connected = null;
            } else {
                payload = new byte[0];
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write('$');
            o.write('M');
            o.write('>');
            o.write(payload.length);
            o.write(cmd);
            int sum = payload.length ^ cmd;
            for (byte p : payload) {
                o.write(p);
                sum ^= p & 0xff;
            }
            o.write(sum);
            send(o.toByteArray());
            if (passthrough) return at + fourWay(Arrays.copyOfRange(b, at, b.length));
        }
        return at;
    }

    /** '/' command addressHi addressLo count parameters crc crc. Returns the bytes used. */
    private int fourWay(byte[] b) {
        int at = 0;
        while (b.length - at >= 7) {
            if ((b[at] & 0xff) != EscLink.FROM_PC) {
                at++;
                continue;
            }
            int n = b[at + 4] & 0xff;
            if (n == 0) n = 256;
            if (b.length - at < 5 + n + 2) break;
            int cmd = b[at + 1] & 0xff, address = (b[at + 2] & 0xff) << 8 | b[at + 3] & 0xff;
            byte[] params = Arrays.copyOfRange(b, at + 5, at + 5 + n);
            int crc = 0;
            for (int i = at; i < at + 5 + n; i++) crc = EscLink.crc(crc, b[i] & 0xff);
            boolean crcOk = crc == ((b[at + 5 + n] & 0xff) << 8 | b[at + 6 + n] & 0xff);
            at += 7 + n;
            answer(cmd, address, params, crcOk);
            if (!passthrough) return at + msp(Arrays.copyOfRange(b, at, b.length));
        }
        return at;
    }

    private void answer(int cmd, int address, byte[] params, boolean crcOk) {
        byte[] back = {0};
        int ack = EscLink.ACK_OK;
        if (!crcOk) {
            ack = EscLink.ACK_INVALID_CRC;
        } else {
            switch (cmd) {
                case EscLink.INIT_FLASH -> {
                    back = new byte[4];
                    int i = params[0] & 0xff;
                    Esc e = i < escs.size() ? escs.get(i) : null;
                    connected = null;
                    if (e == null) {
                        ack = 8;
                    } else if (!e.answers || e.refuseConnects-- > 0) {
                        ack = EscLink.ACK_GENERAL_ERROR;
                    } else {
                        e.connects++;
                        connected = e;
                        back[0] = (byte) e.signature;
                        back[1] = (byte) (e.signature >> 8);
                        back[3] = (byte) e.mode;
                    }
                }
                case EscLink.READ -> {
                    int n = params[0] & 0xff;
                    if (n == 0) n = 256;
                    if (connected == null || !connected.answers) ack = EscLink.ACK_GENERAL_ERROR;
                    else back = connected.bytes(address, n);
                }
                case EscLink.PAGE_ERASE -> {
                    if (connected == null || !connected.answers) {
                        ack = EscLink.ACK_GENERAL_ERROR;
                    } else {
                        // Betaflight: the address is the page number * 512 (ARM: * 1024); the chip erases its own page there
                        int from = (params[0] & 0xff) * (connected.mode == EscLink.MODE_ARM ? 1024 : 512);
                        from -= from % connected.pageSize;
                        Arrays.fill(connected.memory, from, from + connected.pageSize, (byte) 0xFF);
                        connected.erases++;
                        if (connected.diesAfterErase) connected.answers = false;
                    }
                }
                case EscLink.WRITE -> {
                    if (connected == null || !connected.answers || connected.refuseWrites) {
                        ack = EscLink.ACK_GENERAL_ERROR;
                    } else {
                        for (int i = 0; i < params.length; i++) connected.memory[address + i] &= params[i]; // flash: bits only go to 0
                        if (connected.corruptWrites-- > 0) {
                            for (int i = 0; i < params.length; i++) {
                                int v = connected.memory[address + i] & 0xff;
                                if (v == 0) continue;
                                connected.memory[address + i] = (byte) (v & v - 1); // one bit lost
                                break;
                            }
                        }
                        connected.writes++;
                    }
                }
                case EscLink.RESET -> {
                    int i = params[0] & 0xff;
                    if (i < escs.size()) escs.get(i).restarts++;
                    else ack = 8;
                    connected = null;
                }
                case EscLink.EXIT -> passthrough = false;
                case EscLink.TEST_ALIVE -> {
                }
                default -> ack = 2;
            }
        }
        said.add(String.format("4way %02X @%04X -> %d", cmd, address, ack));
        byte[] frame = EscLink.frame(EscLink.FROM_INTERFACE, cmd, address, back, ack);
        if (noiseOnce) {
            noiseOnce = false;
            frame[frame.length - 1] ^= 0x55;
        }
        send(frame);
    }
}
