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
import java.util.zip.CRC32;

/**
 * One input of the decoder fuzz ({@link DecoderFuzz}): what the app's video decoding is handed, in the order it is
 * handed it. A stream (LIVE, SECOND) is the phone's codec config and frames as the link delivers them; a CLIP is a
 * whole file as the clip player opens it. Saved as a small file of its own ({@link #write}), so an input that crashed
 * the decoding can be run again ({@code gradlew decoderFuzz -Preplay=<file>}).
 *
 * @param target which of the app's decoding paths runs it
 * @param flags  the path's own choices (screen size, a still taken, where a clip is sought to): see {@link FuzzChild}
 * @param units  for a stream: configs, frames, flushes in order; for a clip: one FILE unit
 */
public record FuzzInput(Target target, int flags, List<Unit> units) {
    /** The app's decoding paths. */
    public enum Target {
        /** The live video: VideoPipeline's decoder (FFmpeg's threads), its picture scaled to the screen; stills. */
        LIVE,
        /** The second decoders on one thread: the overwatch preview (always H.264), the OSD recording, STANAG. */
        SECOND,
        /** A clip or recording file opened and played by ClipPlayer (FFmpeg's demuxers first). */
        CLIP
    }

    public static final int CONFIG_H264 = 0, CONFIG_HEVC = 1, FRAME = 2, KEY_FRAME = 3, FLUSH = 4, FILE_MP4 = 5, FILE_MKV = 6, FILE_TS = 7;
    static final int KINDS = 8;
    private static final int MAGIC = 0x44465A31; // "DFZ1"

    public record Unit(int kind, byte[] data) {
        public Unit {
            if (kind < 0 || kind >= KINDS) throw new IllegalArgumentException("unit kind " + kind);
            data = data == null ? new byte[0] : data;
        }

        public boolean isConfig() {
            return kind == CONFIG_H264 || kind == CONFIG_HEVC;
        }

        public boolean isFrame() {
            return kind == FRAME || kind == KEY_FRAME;
        }

        public boolean isFile() {
            return kind == FILE_MP4 || kind == FILE_MKV || kind == FILE_TS;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Unit u && u.kind == kind && Arrays.equals(u.data, data);
        }

        @Override
        public int hashCode() {
            return kind * 31 + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return kindName(kind) + "[" + data.length + "]";
        }
    }

    public FuzzInput {
        units = List.copyOf(units);
    }

    public static String kindName(int kind) {
        return switch (kind) {
            case CONFIG_H264 -> "config-h264";
            case CONFIG_HEVC -> "config-hevc";
            case FRAME -> "frame";
            case KEY_FRAME -> "key";
            case FLUSH -> "flush";
            case FILE_MP4 -> "mp4";
            case FILE_MKV -> "mkv";
            case FILE_TS -> "ts";
            default -> "?" + kind;
        };
    }

    /** A clip's file name ending: what the clip player is given. */
    public static String extension(int fileKind) {
        return fileKind == FILE_MP4 ? "mp4" : fileKind == FILE_TS ? "ts" : "mkv";
    }

    /** The clip's file unit, or null for a stream. */
    public Unit file() {
        for (Unit u : units) if (u.isFile()) return u;
        return null;
    }

    public long totalBytes() {
        long n = 0;
        for (Unit u : units) n += u.data.length;
        return n;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 64, totalBytes() + 16L + units.size() * 5L));
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeInt(MAGIC);
            out.writeByte(target.ordinal());
            out.writeInt(flags);
            out.writeInt(units.size());
            for (Unit u : units) {
                out.writeByte(u.kind);
                out.writeInt(u.data.length);
                out.write(u.data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    public static FuzzInput fromBytes(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("not a decoder fuzz input");
            int t = in.readUnsignedByte();
            if (t >= Target.values().length) throw new IOException("target " + t);
            int flags = in.readInt();
            int n = in.readInt();
            if (n < 0 || n > 100_000) throw new IOException("units " + n);
            List<Unit> units = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int kind = in.readUnsignedByte();
                int len = in.readInt();
                if (kind >= KINDS || len < 0 || len > bytes.length) throw new IOException("unit " + i + ": kind " + kind + ", " + len + " bytes");
                byte[] data = new byte[len];
                in.readFully(data);
                units.add(new Unit(kind, data));
            }
            return new FuzzInput(Target.values()[t], flags, units);
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

    public static FuzzInput read(Path file) throws IOException {
        return fromBytes(Files.readAllBytes(file));
    }

    /** One line for a log: target, flags, units. */
    public String describe() {
        StringBuilder s = new StringBuilder(target.name().toLowerCase()).append(" flags=").append(flags).append(' ');
        int shown = 0;
        for (Unit u : units) {
            if (shown++ == 12) {
                s.append(" ... (").append(units.size()).append(" units)");
                break;
            }
            s.append(shown > 1 ? " " : "").append(u);
        }
        return s.append(", ").append(totalBytes()).append(" bytes").toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FuzzInput f && f.target == target && f.flags == flags && f.units.equals(units);
    }

    @Override
    public int hashCode() {
        return (target.hashCode() * 31 + flags) * 31 + units.hashCode();
    }
}
