package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's USB for Drone setup, with every request written down on the way to the board: each connection (given, or
 * refused and why), each MSP request (v1 and v2, with its payload), the CLI's entry '#' and each CLI line - so a
 * scenario can say what Drone setup sent the board, and hold it to what the app's log says it wrote. It stands in
 * front of another door to a board ({@link KeeperWire#usb}: the keeper's lease on the real one; a made-up board's own
 * in a test); every byte goes on unchanged. Unplugged, it has no port.
 */
final class SetupTap implements BetaflightUsb.Usb {
    /** One thing the app did on its USB: "open", "refused", "close", "msp", "msp2", "cli-entry" or "cli". */
    record Event(long atMs, int conn, String kind, int code, String text) {
    }

    private final BetaflightUsb.Usb inner;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean plugged = true;
    private volatile long lastAt = System.currentTimeMillis();

    SetupTap(BetaflightUsb.Usb inner) {
        this.inner = inner;
    }

    @Override
    public List<BetaflightUsb.Port> ports() {
        return plugged ? inner.ports() : List.of();
    }

    @Override
    public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws IOException {
        if (!plugged) throw new IOException(port.systemName() + " is gone - plug the flight controller in again.");
        int id = connections.incrementAndGet();
        BetaflightUsb.Wire wire;
        try {
            wire = inner.open(port);
        } catch (IOException e) {
            add(id, "refused", 0, String.valueOf(e.getMessage()));
            throw e;
        }
        add(id, "open", 0, port.systemName());
        return new TapWire(wire, id);
    }

    /** From now on the app finds no board here (what it still has open goes on to its end). */
    void unplug() {
        plugged = false;
    }

    private void add(int conn, String kind, int code, String text) {
        long now = System.currentTimeMillis();
        lastAt = now;
        events.add(new Event(now, conn, kind, code, text));
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    /** How often the app asked for the board (given or not). */
    int asks() {
        return connections.get();
    }

    /** Nothing was asked for or sent for so long. */
    boolean quietFor(long ms) {
        return System.currentTimeMillis() - lastAt >= ms;
    }

    /** The MSP v1 requests of this number the app sent, each with its payload as hex. */
    List<Event> msp(int code) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (e.kind().equals("msp") && e.code() == code) out.add(e);
        return out;
    }

    /** The MSP v2 requests of this number the app sent. */
    List<Event> msp2(int code) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (e.kind().equals("msp2") && e.code() == code) out.add(e);
        return out;
    }

    /** The CLI lines the app sent that change the board (HwDroneSetup.reads: no question, no "exit" or "save"), in order. */
    List<String> cliWrites() {
        List<String> out = new ArrayList<>();
        for (Event e : events) if (e.kind().equals("cli") && !HwDroneSetup.reads(e.text()) && !e.text().equalsIgnoreCase("save")) out.add(e.text());
        return out;
    }

    /** How often the app said "save" in the CLI. */
    int saves() {
        int n = 0;
        for (Event e : events) if (e.kind().equals("cli") && e.text().equalsIgnoreCase("save")) n++;
        return n;
    }

    /** Every event as a line of a table, its time from {@code t0}: for the film's folder. */
    List<String> lines(long t0) {
        List<String> out = new ArrayList<>();
        out.add("s\tconnection\tkind\tcode\twhat");
        for (Event e : events) {
            out.add(String.format(Locale.ROOT, "%.3f\t%d\t%s\t%s\t%s", (e.atMs() - t0) / 1000.0, e.conn(), e.kind(),
                    e.kind().equals("msp2") ? "0x" + Integer.toHexString(e.code()) : Integer.toString(e.code()), e.text()));
        }
        return out;
    }

    /** One connection of the app's, its bytes passed on as they come; what it sends is written down first. */
    private final class TapWire implements BetaflightUsb.Wire {
        private final BetaflightUsb.Wire wire;
        private final int id;
        private final MspFrames frames = new MspFrames();
        private boolean closed;

        TapWire(BetaflightUsb.Wire wire, int id) {
            this.wire = wire;
            this.id = id;
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return wire.read(buf);
        }

        @Override
        public int available() throws IOException {
            return wire.available();
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (data.length > 0 && data[0] == MspFrames.START) {
                for (MspFrames.Frame f : frames.feed(data, data.length)) {
                    if (f.type != MspFrames.REQUEST) continue;
                    add(id, f.version == MspFrames.V2 ? "msp2" : "msp", f.code, f.payload == null ? "" : Msp.hex(f.payload));
                }
            } else {
                String text = new String(data, StandardCharsets.US_ASCII);
                if (text.equals("#")) add(id, "cli-entry", 0, "#");
                else if (text.isBlank()) add(id, "cli", 0, ""); // Enter alone
                else for (String line : text.split("\\R")) if (!line.isBlank()) add(id, "cli", 0, line.strip());
            }
            wire.write(data);
        }

        @Override
        public boolean isOpen() {
            return wire.isOpen();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            add(id, "close", 0, "");
            wire.close();
        }
    }
}
