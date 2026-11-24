package de.droiddrone.bench.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * One input of the protocol fuzz ({@link ProtocolFuzz}): a short stream of datagrams for one of the link's receive
 * paths ({@link Target}), in the order they arrive, each with where it came from and how long after the one before.
 * Saved as a small file of its own ({@link #write}, .pfz), so an input that showed something can be run again
 * ({@code gradlew protocolFuzz -Preplay=<file>}).
 *
 * @param target  which receive path reads the stream
 * @param flags   {@link #BYTES_INTACT}: every datagram is one the sender made, unchanged (only lost, doubled, moved, late)
 * @param packets the datagrams in arrival order
 */
public record LinkInput(Target target, int flags, List<Packet> packets) {
    /** The link's receive paths: what the phone reads from a PC, and what the PC reads from a phone (or another PC). */
    public enum Target {
        /** The phone's receive thread in a session: the session gate, the buffer, the sticks, the commands' readers. */
        PHONE_LINK,
        /** The phone's packets read in any state, before the session gate: names, fleet modes, observers, sleep. */
        PHONE_OUTSIDE,
        /** The PC's link: ControlLink's receive thread and buffer thread on the phone's packets. */
        PC_LINK,
        /** The PC's video: fragments and the three kinds of parity into the frame assembler. */
        PC_VIDEO,
        /** The PC's telemetry: the phone's records read for each kind of flight controller. */
        PC_TELEMETRY,
        /** The PC's other readers: overwatch, sleep check-ins, names, spectators, observers, fleet status. */
        PC_OUTSIDE
    }

    /** Every datagram is one the sender made, unchanged: the strict checks (whole frames are the sent ones) apply. */
    public static final int BYTES_INTACT = 1;
    /** Where a datagram comes from: the peer of the session, its address on another socket, another address. */
    public static final int FROM_PEER = 0, FROM_PEER_ADDRESS = 1, FROM_OTHER = 2;
    private static final int MAGIC = 0x50465A31; // "PFZ1"
    public static final int MAX_PACKETS = 600, MAX_PACKET_BYTES = 2048;

    /** One datagram: from whom, how many ms after the one before, its bytes. */
    public record Packet(int from, int dtMs, byte[] data) {
        public Packet {
            data = data == null ? new byte[0] : data;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Packet p && p.from == from && p.dtMs == dtMs && Arrays.equals(p.data, data);
        }

        @Override
        public int hashCode() {
            return (from * 31 + dtMs) * 31 + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return (from == FROM_PEER ? "" : from == FROM_PEER_ADDRESS ? "port'" : "other:") + String.format(Locale.ROOT, "%02x", data.length > 0 ? data[0] & 0xff : 0)
                    + "[" + data.length + "]" + (dtMs != 0 ? "+" + dtMs : "");
        }
    }

    public LinkInput {
        packets = List.copyOf(packets);
    }

    public boolean intact() {
        return (flags & BYTES_INTACT) != 0;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeInt(MAGIC);
            out.writeByte(target.ordinal());
            out.writeInt(flags);
            out.writeInt(packets.size());
            for (Packet p : packets) {
                out.writeByte(p.from);
                out.writeInt(p.dtMs);
                out.writeInt(p.data.length);
                out.write(p.data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    public static LinkInput fromBytes(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("not a protocol fuzz input");
            int t = in.readUnsignedByte();
            if (t >= Target.values().length) throw new IOException("target " + t);
            int flags = in.readInt();
            int n = in.readInt();
            if (n < 0 || n > 100_000) throw new IOException("packets " + n);
            List<Packet> packets = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int from = in.readUnsignedByte();
                int dt = in.readInt();
                int len = in.readInt();
                if (len < 0 || len > bytes.length) throw new IOException("packet " + i + ": " + len + " bytes");
                byte[] data = new byte[len];
                in.readFully(data);
                packets.add(new Packet(from, dt, data));
            }
            return new LinkInput(Target.values()[t], flags, packets);
        }
    }

    /** The input's checksum: the child names each input by its number and this, the parent checks the one it rebuilt. */
    public long crc() {
        CRC32 c = new CRC32();
        c.update(toBytes());
        return c.getValue();
    }

    public void write(Path file) throws IOException {
        Files.write(file, toBytes());
    }

    public static LinkInput read(Path file) throws IOException {
        return fromBytes(Files.readAllBytes(file));
    }

    /** One line for a log: target, the datagrams' kinds and lengths. */
    public String describe() {
        StringBuilder s = new StringBuilder(target.name().toLowerCase(Locale.ROOT)).append(intact() ? " intact " : " ");
        int shown = 0;
        long bytes = 0;
        for (Packet p : packets) {
            bytes += p.data.length;
            if (shown++ < 16) s.append(shown > 1 ? " " : "").append(p);
        }
        if (packets.size() > 16) s.append(" ... (").append(packets.size()).append(" datagrams)");
        return s.append(", ").append(bytes).append(" bytes").toString();
    }

    /** The input as text, every datagram in hex: for a finding's folder. */
    public String dump() {
        StringBuilder s = new StringBuilder(describe()).append(System.lineSeparator());
        int i = 0;
        for (Packet p : packets) {
            s.append(String.format(Locale.ROOT, "%3d from %d +%d ms, %d bytes:", i++, p.from, p.dtMs, p.data.length));
            for (int k = 0; k < Math.min(p.data.length, 96); k++) s.append(String.format(Locale.ROOT, " %02x", p.data[k] & 0xff));
            if (p.data.length > 96) s.append(" ...");
            s.append(System.lineSeparator());
        }
        return s.toString();
    }
}
