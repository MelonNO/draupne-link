package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.setup.dfu.DfuDevice;
import de.droiddrone.pc.setup.dfu.DfuLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A made-up STM32 in its ROM DFU bootloader (ST's DfuSe as dfu-util knows it): a flash that only clears bits when
 * written (so a missed erase shows), sectors from its layout string, the state machine (dfuIDLE, DNBUSY, DNLOAD-IDLE,
 * dfuERROR), the address pointer, erase, write as block 2+, UPLOAD, ABORT, CLRSTATUS and leave. No real USB.
 */
final class FakeDfu implements DfuDevice {
    static final String F405 = "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg";
    static final String F411 = "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,03*128Kg";
    static final int IDLE = 2, DNBUSY = 4, DNLOAD_IDLE = 5, ERROR = 10;

    final String id;
    final String layout;
    final DfuLayout map;
    final byte[] flash;
    final long base;
    int state = IDLE, status;
    long pointer;
    boolean busyPending, leaving;
    /** Restarted into the written firmware (after leave): gone from DFU. */
    volatile boolean running;
    volatile boolean opened, closed;
    final List<Long> erased = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    /** A byte that reads back wrong at this address (-1: none). */
    long corruptAt = -1;

    FakeDfu(String id, String layout) throws IOException {
        this.id = id;
        this.layout = layout;
        this.map = DfuLayout.parse(layout);
        this.base = map.sectors().get(0).address();
        this.flash = new byte[(int) map.total()];
        Arrays.fill(flash, (byte) 0x5A); // Betaflight and its settings
    }

    private void needOpen() throws IOException {
        if (running) throw new IOException("gone");
    }

    @Override
    public synchronized void out(int request, int value, byte[] data) throws IOException {
        needOpen();
        requests.add("out " + request + " " + value + " " + data.length);
        switch (request) {
            case DNLOAD -> {
                if (state != IDLE && state != DNLOAD_IDLE) {
                    state = ERROR;
                    status = 0x0F;
                    return;
                }
                if (value == 0 && data.length == 0) {
                    leaving = true;
                    state = DNBUSY;
                    return;
                }
                if (value == 0) {
                    long a = data.length >= 5 ? (data[1] & 0xffL) | (data[2] & 0xffL) << 8 | (data[3] & 0xffL) << 16 | (data[4] & 0xffL) << 24 : -1;
                    if ((data[0] & 0xff) == 0x21 && data.length == 5) pointer = a;
                    else if ((data[0] & 0xff) == 0x41 && data.length == 5) {
                        DfuLayout.Sector s = map.at(a);
                        if (s == null || s.address() != a) {
                            state = ERROR;
                            status = 0x08;
                            return;
                        }
                        Arrays.fill(flash, (int) (a - base), (int) (s.end() - base), (byte) 0xff);
                        erased.add(a);
                    } else if ((data[0] & 0xff) == 0x41 && data.length == 1) {
                        Arrays.fill(flash, (byte) 0xff);
                        erased.add(-1L);
                    } else {
                        state = ERROR;
                        status = 0x0F;
                        return;
                    }
                } else {
                    long at = pointer + (long) (value - 2) * transferSize();
                    if (at < base || at + data.length > base + flash.length) {
                        state = ERROR;
                        status = 0x08;
                        return;
                    }
                    for (int i = 0; i < data.length; i++) flash[(int) (at - base) + i] &= data[i]; // flash only clears bits
                }
                state = DNBUSY;
                busyPending = true;
            }
            case ABORT -> state = IDLE;
            case CLRSTATUS -> {
                state = IDLE;
                status = 0;
            }
            default -> throw new IOException("unexpected request " + request);
        }
    }

    @Override
    public synchronized byte[] in(int request, int value, int length) throws IOException {
        needOpen();
        requests.add("in " + request + " " + value + " " + length);
        switch (request) {
            case GETSTATUS -> {
                int st = state;
                if (state == DNBUSY) {
                    if (leaving) {
                        running = true; // manifestation: the chip restarts into the new firmware
                        return new byte[]{0, 0, 0, 0, 7, 0};
                    }
                    state = DNLOAD_IDLE;
                }
                return new byte[]{(byte) status, 3, 0, 0, (byte) st, 0};
            }
            case UPLOAD -> {
                if (state != IDLE) {
                    state = ERROR;
                    status = 0x0F;
                    return new byte[0];
                }
                long at = pointer + (long) (value - 2) * transferSize();
                byte[] out = Arrays.copyOfRange(flash, (int) (at - base), (int) (at - base) + length);
                if (corruptAt >= at && corruptAt < at + length) out[(int) (corruptAt - at)] ^= 0x01;
                return out;
            }
            default -> throw new IOException("unexpected request " + request);
        }
    }

    byte[] read(long address, int n) {
        return Arrays.copyOfRange(flash, (int) (address - base), (int) (address - base) + n);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String layout() {
        return layout;
    }

    @Override
    public int transferSize() {
        return 2048;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** A made-up USB with DFU devices on it; records which were opened. */
    static final class Bus implements DfuDevice.Bus {
        final Map<String, FakeDfu> devices = new LinkedHashMap<>();
        final List<String> opened = new ArrayList<>();

        synchronized void add(FakeDfu d) {
            devices.put(d.id, d);
        }

        @Override
        public synchronized List<String> present() {
            List<String> out = new ArrayList<>();
            for (FakeDfu d : devices.values()) if (!d.running) out.add(d.id);
            return out;
        }

        @Override
        public synchronized DfuDevice open(String id) throws IOException {
            FakeDfu d = devices.get(id);
            if (d == null || d.running) throw new IOException("no such device");
            opened.add(id);
            d.opened = true;
            return d;
        }
    }
}
