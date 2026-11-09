package de.droiddrone.pc;

import de.droiddrone.common.PacketIds;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Spectator mode's second half (1.0.56, the pilot: "spectator mode just mirrors the pilot PC"): what the pilot PC itself
 * shows, beside the drone's own stream ({@link Spectate}) - its mode (PILOT, OBSERVER), the FLEET MANAGEMENT list, the
 * drones, pins and drawings on its map, and in OBSERVER mode its tiles' streams. PC to PC only: the phones send nothing
 * extra, and it runs whether the pilot PC flies or not.
 * <pre>
 * spectator -> pilot   MIRROR_HELLO   [0x5A][key UTF]                       to the pilot's link port
 * pilot -> spectator   MIRROR_WELCOME [0x5B][mirror port u16][reason u8]    port 0: refused (reason REFUSED_OFF)
 * spectator -> pilot   MIRROR_KEEP    [0x5C][flags u8][key frame asks u8]   to the mirror port, 4 a second
 * pilot -> spectator   MIRROR_STATE   [0x5D][seq u16][part u8][parts u8][a part of the deflated state]
 * pilot -> spectator   MIRROR_TILE    [0x5E][tile u8][packet as the observed phone sent it]
 * either way           MIRROR_BYE     [0x5F]
 * </pre>
 * The state is named sections ({@link #MODE} and what the window adds: the fleet, the map's drones, pins, drawings),
 * each opaque here; sent whole every {@link MirrorHost#STATE_MS} and at once on a change. KEEP's asks: bit i = tile i
 * needs its stream's config and a key frame (a spectator joining, or one that lost a packet).
 */
public final class Mirror {
    public static final byte HELLO = PacketIds.MIRROR_HELLO;
    public static final byte WELCOME = PacketIds.MIRROR_WELCOME;
    public static final byte KEEP = PacketIds.MIRROR_KEEP;
    public static final byte STATE = PacketIds.MIRROR_STATE;
    public static final byte TILE = PacketIds.MIRROR_TILE;
    public static final byte BYE = PacketIds.MIRROR_BYE;

    public static final byte REFUSED_OFF = 1;
    /** KEEP flag: the spectator has no state yet: send it now. */
    public static final int FLAG_NEED_STATE = 1;
    /** The state's payload per packet (deflated bytes). */
    static final int PART = 1100;
    static final int MAX_PARTS = 255;

    /** The pilot PC's mode: the section {@link #MODE}. */
    public static final int IDLE = 0, PILOT = 1, OBSERVER = 2;
    public static final String MODE = "mode";

    private Mirror() {
    }

    /** The mode section: [mode u8][phone UTF][tiles u8][tile UTF x n][selected i8][enlarged i8]. */
    public record Mode(int mode, String phone, List<String> tiles, int selected, int enlarged) {
        public Mode {
            phone = phone == null ? "" : phone;
            tiles = tiles == null ? List.of() : List.copyOf(tiles);
        }

        public byte[] encode() {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(b);
                out.writeByte(mode);
                out.writeUTF(phone);
                out.writeByte(Math.min(255, tiles.size()));
                for (int i = 0; i < Math.min(255, tiles.size()); i++) out.writeUTF(tiles.get(i));
                out.writeByte(selected);
                out.writeByte(enlarged);
                return b.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Null if broken. */
        public static Mode decode(byte[] d) {
            if (d == null) return null;
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
                int mode = in.readUnsignedByte();
                String phone = in.readUTF();
                int n = in.readUnsignedByte();
                List<String> tiles = new ArrayList<>();
                for (int i = 0; i < n; i++) tiles.add(in.readUTF());
                int sel = in.available() > 0 ? in.readByte() : -1;
                int enl = in.available() > 0 ? in.readByte() : -1;
                return new Mode(mode, phone, tiles, sel, enl);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ the state

    /** Sections to bytes: [n u8] then [name UTF][len i32][bytes] each, deflated. */
    static byte[] encodeState(Map<String, byte[]> sections) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeByte(sections.size());
            for (Map.Entry<String, byte[]> e : sections.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
            Deflater d = new Deflater(Deflater.BEST_SPEED);
            d.setInput(b.toByteArray());
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            return z.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null if broken. */
    static Map<String, byte[]> decodeState(byte[] z) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(z);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                b.write(buf, 0, n);
                if (b.size() > 8 << 20) return null;
            }
            inf.end();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b.toByteArray()));
            int n = in.readUnsignedByte();
            Map<String, byte[]> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > in.available()) return null;
                byte[] v = new byte[len];
                in.readFully(v);
                out.put(name, v);
            }
            return out;
        } catch (IOException | DataFormatException | RuntimeException e) {
            return null;
        }
    }

    /** The state's packets: [STATE][seq u16][part][parts][payload]. Null when it is too big to send. */
    static List<byte[]> stateParts(int seq, byte[] z) {
        int parts = Math.max(1, (z.length + PART - 1) / PART);
        if (parts > MAX_PARTS) return null;
        List<byte[]> out = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            int from = i * PART, n = Math.min(PART, z.length - from);
            byte[] p = new byte[5 + n];
            p[0] = STATE;
            p[1] = (byte) (seq >> 8);
            p[2] = (byte) seq;
            p[3] = (byte) i;
            p[4] = (byte) parts;
            System.arraycopy(z, from, p, 5, n);
            out.add(p);
        }
        return out;
    }

    static byte[] hello(String key) {
        return Spectate.keyed(HELLO, key);
    }

    static byte[] welcome(int port, int reason) {
        return new byte[]{WELCOME, (byte) (port >> 8), (byte) port, (byte) reason};
    }

    static byte[] keep(int flags, int keyAsks) {
        return new byte[]{KEEP, (byte) flags, (byte) keyAsks};
    }

    static byte[] tile(int tile, byte[] packet, int len) {
        byte[] out = new byte[2 + len];
        out[0] = TILE;
        out[1] = (byte) tile;
        System.arraycopy(packet, 0, out, 2, len);
        return out;
    }

    /** A text section (pins, drawings): UTF-8 bytes. */
    public static byte[] text(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    public static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** The first byte of a datagram that is part of the mirror (the link's receive thread routes HELLO by it). */
    public static boolean isMirror(byte code) {
        return code >= HELLO && code <= BYE;
    }
}
