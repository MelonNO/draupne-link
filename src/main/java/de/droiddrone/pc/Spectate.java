package de.droiddrone.pc;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Spectator mode: the packets between a spectator PC, the phone and the PC that flies (the pilot). The phone serves one
 * PC; a second PC that connects is told which PC flies (by the phone, DRAUPNE Flight 2.3.0-pc.11 and on) and watches
 * through that PC instead. The phone sends nothing extra: the pilot PC passes on what it already gets, from a socket of
 * its own and a thread of its own, and the spectator buffers it for a smooth picture rather than a quick one.
 *
 * Codes from 0x60 up, clear of upstream's packet names (0-26); an older app reads them as unknown and drops them.
 * <pre>
 * spectator -> phone   PILOT_QUERY [0x60][key UTF]                 who flies? (a Connect from a 2nd PC is asked too)
 * phone -> spectator   PILOT_AT    [0x61][n u8][ip n bytes][port u16]
 * phone -> spectator   NO_PILOT    [0x62]                          (only to PILOT_QUERY)
 * spectator -> pilot   HELLO       [0x63][key UTF]                 to the pilot's link port
 * pilot -> spectator   WELCOME     [0x64][stream port u16]         from the link port; again from the stream port
 * pilot -> spectator   REFUSED     [0x65][reason u8]
 * pilot -> spectator   STREAM      [0x66][seq i32][pilot ms i32][packet as the phone sent it]
 * pilot -> spectator   SNAPSHOT    [0x69][packet]                  stream config, FC info, flight mode names
 * either way           BYE         [0x67]
 * spectator -> pilot   KEEP        [0x68][flags u8][n u8][missing seq i32 x n]   to the stream port, 4 a second
 * </pre>
 */
final class Spectate {
    static final byte PILOT_QUERY = 0x60;
    static final byte PILOT_AT = 0x61;
    static final byte NO_PILOT = 0x62;
    static final byte HELLO = 0x63;
    static final byte WELCOME = 0x64;
    static final byte REFUSED = 0x65;
    static final byte STREAM = 0x66;
    static final byte BYE = 0x67;
    static final byte KEEP = 0x68;
    static final byte SNAPSHOT = 0x69;

    static final byte REFUSED_NOT_FLYING = 1;
    static final byte REFUSED_FULL = 2;
    static final byte REFUSED_OFF = 3;

    /** KEEP flag: the spectator has no stream config or FC info yet; send the snapshot. */
    static final int FLAG_NEED_SNAPSHOT = 1;

    static final int STREAM_HEADER = 9;
    static final int MAX_NACKS = 64;

    private Spectate() {
    }

    static boolean isSpectatePacket(byte code) {
        return code >= PILOT_QUERY && code <= SNAPSHOT;
    }

    static byte[] keyed(byte code, String key) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(code);
            out.writeUTF(key);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key in a HELLO or PILOT_QUERY, or null if the packet is broken. */
    static String readKey(byte[] d, int len) {
        if (len < 3) return null;
        int n = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
        if (3 + n > len) return null;
        return new String(d, 3, n, StandardCharsets.UTF_8); // the key is plain ASCII, where modified UTF-8 is UTF-8
    }

    /** What the phone answers: where the PC that flies is. */
    static byte[] pilotAt(InetAddress ip, int port) {
        byte[] a = ip.getAddress();
        ByteBuffer b = ByteBuffer.allocate(4 + a.length);
        b.put(PILOT_AT).put((byte) a.length).put(a).putShort((short) port);
        return b.array();
    }

    record Address(InetAddress ip, int port) {}

    static Address readPilotAt(byte[] d, int len) {
        if (len < 2) return null;
        int n = d[1] & 0xff;
        if ((n != 4 && n != 16) || len < 4 + n) return null;
        try {
            InetAddress ip = InetAddress.getByAddress(java.util.Arrays.copyOfRange(d, 2, 2 + n));
            return new Address(ip, ((d[2 + n] & 0xff) << 8) | (d[3 + n] & 0xff));
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    static byte[] withPort(byte code, int port) {
        return new byte[]{code, (byte) (port >> 8), (byte) port};
    }

    static int readPort(byte[] d, int len) {
        return len < 3 ? -1 : ((d[1] & 0xff) << 8) | (d[2] & 0xff);
    }

    static byte[] stream(int seq, int pilotMs, byte[] packet, int len) {
        byte[] out = new byte[STREAM_HEADER + len];
        out[0] = STREAM;
        putInt(out, 1, seq);
        putInt(out, 5, pilotMs);
        System.arraycopy(packet, 0, out, STREAM_HEADER, len);
        return out;
    }

    static byte[] snapshot(byte[] packet) {
        byte[] out = new byte[1 + packet.length];
        out[0] = SNAPSHOT;
        System.arraycopy(packet, 0, out, 1, packet.length);
        return out;
    }

    static byte[] keep(int flags, int[] missing, int count) {
        int n = Math.min(count, MAX_NACKS);
        byte[] out = new byte[3 + 4 * n];
        out[0] = KEEP;
        out[1] = (byte) flags;
        out[2] = (byte) n;
        for (int i = 0; i < n; i++) putInt(out, 3 + 4 * i, missing[i]);
        return out;
    }

    static void putInt(byte[] d, int i, int v) {
        d[i] = (byte) (v >> 24);
        d[i + 1] = (byte) (v >> 16);
        d[i + 2] = (byte) (v >> 8);
        d[i + 3] = (byte) v;
    }

    static int readInt(byte[] d, int i) {
        return ((d[i] & 0xff) << 24) | ((d[i + 1] & 0xff) << 16) | ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
    }
}
