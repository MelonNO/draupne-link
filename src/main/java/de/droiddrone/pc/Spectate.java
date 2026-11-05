package de.droiddrone.pc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
 * pilot -> spectator   PAUSE       [0x6D][paused u8]                 the stream is held (pilot link slow); 0 = on again
 * pilot -> spectator   PILOT_VIEW  [0x76][version u8 = 1][flags u8][rcWaiting UTF][banner UTF][linkHint UTF]
 *                                  [channels u8][channel u16 x n][phone UTF]   the pilot PC's own OSD warnings and the
 *                                  phone it flies, 4 a second
 * pilot -> spectator   BYE         [0x67][reason u8]                 1 = the pilot disconnected (its DISCONNECT or quit)
 * </pre>
 * 0x6A-0x6C are the PC-to-phone video packets (ControlLink), so PAUSE skips to 0x6D; 0x6E-0x75 are the power and name
 * packets, so PILOT_VIEW is 0x76 (1.0.41). An older spectator drops both. PILOT_VIEW flags: bit 0 the pilot's link is
 * ready, 1 its sticks are live, 2 its radio looks frozen, 3 its arm switch isn't read, 4 its way to the phone is a relay,
 * 5 its link stalls, 6 its picture is turned upside down (the spectator turns its own the same way).
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
    static final byte PAUSE = 0x6D;
    static final byte PILOT_VIEW = 0x76;

    /** BYE's reason: the pilot ended its link on purpose (DISCONNECT, or the program closed). */
    static final byte BYE_PILOT_DISCONNECTED = 1;

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
        return code >= PILOT_QUERY && code <= SNAPSHOT || code == PAUSE || code == PILOT_VIEW;
    }

    static byte[] pilotView(ControlLink.PilotView v) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(PILOT_VIEW);
            out.writeByte(1);
            out.writeByte((v.linkReady() ? 1 : 0) | (v.rcLive() ? 2 : 0) | (v.radioFrozen() ? 4 : 0) | (v.armSwitchUnknown() ? 8 : 0)
                    | (v.relayed() ? 16 : 0) | (v.linkStalled() ? 32 : 0) | (v.upsideDown() ? 64 : 0));
            out.writeUTF(v.rcWaiting() == null ? "" : v.rcWaiting());
            out.writeUTF(v.banner() == null ? "" : v.banner());
            out.writeUTF(v.linkHint() == null ? "" : v.linkHint());
            short[] ch = v.channels() == null ? new short[0] : v.channels();
            int n = Math.min(32, ch.length);
            out.writeByte(n);
            for (int i = 0; i < n; i++) out.writeShort(ch[i]);
            out.writeUTF(v.phoneName() == null ? "" : v.phoneName());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A PILOT_VIEW back; null if it is broken or a version this PC doesn't know. Empty texts read as null. */
    static ControlLink.PilotView readPilotView(byte[] d, int len) {
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(d, 0, len));
            if (in.readByte() != PILOT_VIEW || in.readByte() != 1) return null;
            int f = in.readByte() & 0xff;
            String rcWaiting = in.readUTF(), banner = in.readUTF(), hint = in.readUTF();
            short[] ch = new short[in.readByte() & 0xff];
            for (int i = 0; i < ch.length; i++) ch[i] = in.readShort();
            String phone = in.available() > 0 ? in.readUTF() : "";
            return new ControlLink.PilotView((f & 1) != 0, (f & 2) != 0, (f & 4) != 0, (f & 8) != 0, (f & 16) != 0, (f & 32) != 0,
                    rcWaiting.isEmpty() ? null : rcWaiting, banner.isEmpty() ? null : banner, hint.isEmpty() ? null : hint,
                    ch.length == 0 ? null : ch, phone.isEmpty() ? null : phone, (f & 64) != 0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
