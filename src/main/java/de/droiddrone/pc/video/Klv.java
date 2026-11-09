package de.droiddrone.pc.video;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * STANAG 4609 metadata (1.0.39): MISB ST 0601 (the UAS Datalink Local Set) with an ST 0102 security local set inside,
 * as KLV: a 16-byte key, a BER length, then tag / length / value items, the 16-bit checksum last. Every value is
 * MISB's fixed-point mapping of its range (heading 0..360 over 16 bits, latitude +-90 over 32 ...); a value that is not
 * known is left out, which ST 0601 allows for every item but the time stamp, the version and the checksum.
 */
public final class Klv {
    /** The UAS Datalink Local Set's universal key. */
    static final byte[] UAS_LS_KEY = {0x06, 0x0E, 0x2B, 0x34, 0x02, 0x0B, 0x01, 0x01,
            0x0E, 0x01, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00};
    /** The ST 0601 revision these items follow (tag 65). */
    static final int UAS_LS_VERSION = 17;
    /** The ST 0102 revision of the security set (its tag 22). */
    static final int SECURITY_VERSION = 12;
    /** ST 0102 classification UNCLASSIFIED, and ISO-3166 three-letter country codes. */
    static final int UNCLASSIFIED = 0x01, ISO3166_THREE_LETTER = 0x02;

    static final int TAG_CHECKSUM = 1, TAG_TIME = 2, TAG_MISSION = 3, TAG_HEADING = 5, TAG_PITCH = 6, TAG_ROLL = 7,
            TAG_DESIGNATION = 10, TAG_SENSOR_NAME = 11, TAG_COORDINATES = 12, TAG_SENSOR_LAT = 13, TAG_SENSOR_LON = 14,
            TAG_SENSOR_ALT = 15, TAG_HFOV = 16, TAG_VFOV = 17, TAG_REL_AZIMUTH = 18, TAG_REL_ELEVATION = 19,
            TAG_REL_ROLL = 20, TAG_SLANT_RANGE = 21, TAG_CENTRE_LAT = 23, TAG_CENTRE_LON = 24, TAG_CENTRE_ELEV = 25,
            TAG_OFFSET_CORNER = 26, TAG_SECURITY = 48, TAG_SPEED = 56, TAG_VERSION = 65, TAG_CORNER = 82,
            TAG_PITCH_FULL = 90, TAG_ROLL_FULL = 91;

    private Klv() {
    }

    /**
     * One frame's worth: the platform (the drone), the sensor (its camera) and where the picture lands. NaN = not
     * known (left out). Angles in degrees; pitch nose up positive, roll right wing down positive (MISB's sense).
     * corners: upper left, upper right, lower right, lower left as {lat, lon}, null (or a null corner) where the
     * picture's corner shows sky.
     */
    public record Frame(long timeMicros, String missionId, String designation, String sensorName,
                        double headingDeg, double pitchDeg, double rollDeg, double groundSpeedMs,
                        double sensorLat, double sensorLon, double sensorAltM,
                        double hfovDeg, double vfovDeg, double relAzimuthDeg, double relElevationDeg, double relRollDeg,
                        double slantRangeM, double centreLat, double centreLon, double centreElevM, double[][] corners,
                        String country) {
    }

    /** The whole ST 0601 packet, key to checksum. */
    public static byte[] encode(Frame f) {
        ByteArrayOutputStream items = new ByteArrayOutputStream(256);
        item(items, TAG_TIME, u64(f.timeMicros()));
        if (f.missionId() != null && !f.missionId().isEmpty()) item(items, TAG_MISSION, ascii(f.missionId(), 127));
        if (ok(f.headingDeg())) item(items, TAG_HEADING, u16(unsigned(Math.floorMod(Math.round(f.headingDeg() * 1000), 360_000) / 1000.0, 360, 16)));
        if (ok(f.pitchDeg())) {
            item(items, TAG_PITCH, i16(Math.abs(f.pitchDeg()) <= 20 ? signed(f.pitchDeg(), 20, 16) : Short.MIN_VALUE)); // out of range: the error value
            item(items, TAG_PITCH_FULL, i32(signed(f.pitchDeg(), 90, 32)));
        }
        if (ok(f.rollDeg())) {
            item(items, TAG_ROLL, i16(Math.abs(f.rollDeg()) <= 50 ? signed(f.rollDeg(), 50, 16) : Short.MIN_VALUE));
            item(items, TAG_ROLL_FULL, i32(Math.abs(f.rollDeg()) <= 90 ? signed(f.rollDeg(), 90, 32) : Integer.MIN_VALUE));
        }
        if (f.designation() != null && !f.designation().isEmpty()) item(items, TAG_DESIGNATION, ascii(f.designation(), 127));
        if (f.sensorName() != null && !f.sensorName().isEmpty()) item(items, TAG_SENSOR_NAME, ascii(f.sensorName(), 127));
        item(items, TAG_COORDINATES, ascii("Geodetic WGS84", 127));
        if (ok(f.sensorLat()) && ok(f.sensorLon())) {
            item(items, TAG_SENSOR_LAT, i32(signed(f.sensorLat(), 90, 32)));
            item(items, TAG_SENSOR_LON, i32(signed(f.sensorLon(), 180, 32)));
        }
        if (ok(f.sensorAltM())) item(items, TAG_SENSOR_ALT, u16(altitude(f.sensorAltM())));
        if (ok(f.hfovDeg())) item(items, TAG_HFOV, u16(unsigned(f.hfovDeg(), 180, 16)));
        if (ok(f.vfovDeg())) item(items, TAG_VFOV, u16(unsigned(f.vfovDeg(), 180, 16)));
        if (ok(f.relAzimuthDeg())) item(items, TAG_REL_AZIMUTH, u32(unsigned(Math.floorMod(Math.round(f.relAzimuthDeg() * 1000), 360_000) / 1000.0, 360, 32)));
        if (ok(f.relElevationDeg())) item(items, TAG_REL_ELEVATION, i32(signed(f.relElevationDeg(), 180, 32)));
        if (ok(f.relRollDeg())) item(items, TAG_REL_ROLL, u32(unsigned(Math.floorMod(Math.round(f.relRollDeg() * 1000), 360_000) / 1000.0, 360, 32)));
        if (ok(f.slantRangeM()) && f.slantRangeM() >= 0 && f.slantRangeM() <= 5_000_000) {
            item(items, TAG_SLANT_RANGE, u32(unsigned(f.slantRangeM(), 5_000_000, 32)));
        }
        boolean centre = ok(f.centreLat()) && ok(f.centreLon());
        if (centre) {
            item(items, TAG_CENTRE_LAT, i32(signed(f.centreLat(), 90, 32)));
            item(items, TAG_CENTRE_LON, i32(signed(f.centreLon(), 180, 32)));
            if (ok(f.centreElevM())) item(items, TAG_CENTRE_ELEV, u16(altitude(f.centreElevM())));
        }
        double[][] c = f.corners();
        if (c != null && c.length == 4 && c[0] != null && c[1] != null && c[2] != null && c[3] != null) {
            // the old offset corners (tags 26-33, +-0.075 degrees from the centre) for players that know no others
            boolean near = centre;
            for (double[] p : c) near &= Math.abs(p[0] - f.centreLat()) <= 0.075 && Math.abs(p[1] - f.centreLon()) <= 0.075;
            for (int i = 0; i < 4; i++) {
                if (near) {
                    item(items, TAG_OFFSET_CORNER + 2 * i, i16(signed(c[i][0] - f.centreLat(), 0.075, 16)));
                    item(items, TAG_OFFSET_CORNER + 2 * i + 1, i16(signed(c[i][1] - f.centreLon(), 0.075, 16)));
                }
            }
            for (int i = 0; i < 4; i++) {
                item(items, TAG_CORNER + 2 * i, i32(signed(c[i][0], 90, 32)));
                item(items, TAG_CORNER + 2 * i + 1, i32(signed(c[i][1], 180, 32)));
            }
        }
        item(items, TAG_SECURITY, security(f.country()));
        if (ok(f.groundSpeedMs()) && f.groundSpeedMs() >= 0) item(items, TAG_SPEED, new byte[]{(byte) Math.min(255, Math.round(f.groundSpeedMs()))});
        item(items, TAG_VERSION, new byte[]{(byte) UAS_LS_VERSION});
        byte[] body = items.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 24);
        out.writeBytes(UAS_LS_KEY);
        out.writeBytes(berLength(body.length + 4)); // + the checksum item
        out.writeBytes(body);
        out.write(TAG_CHECKSUM);
        out.write(2);
        out.write(0);
        out.write(0);
        byte[] packet = out.toByteArray();
        int sum = checksum(packet);
        packet[packet.length - 2] = (byte) (sum >> 8);
        packet[packet.length - 1] = (byte) sum;
        return packet;
    }

    /**
     * ST 0102 inside tag 48: UNCLASSIFIED, the classifying country and the object country as ISO-3166 three-letter
     * codes ("//NOR"; the object country as UTF-16, as ST 0102.12 asks), and the set's version.
     */
    static byte[] security(String country) {
        String cc = country == null || !country.matches("[A-Za-z]{3}") ? "XXX" : country.toUpperCase(Locale.ROOT);
        ByteArrayOutputStream s = new ByteArrayOutputStream(40);
        item(s, 1, new byte[]{UNCLASSIFIED});
        item(s, 2, new byte[]{ISO3166_THREE_LETTER});
        item(s, 3, ascii("//" + cc, 40));
        item(s, 12, new byte[]{ISO3166_THREE_LETTER});
        item(s, 13, cc.getBytes(StandardCharsets.UTF_16BE));
        item(s, 22, u16(SECURITY_VERSION));
        return s.toByteArray();
    }

    /** ST 0601's checksum: the 16-bit sum of the packet as big-endian words, from the key's first byte up to and including the checksum's own length byte. */
    static int checksum(byte[] packet) {
        int sum = 0;
        for (int i = 0; i < packet.length - 2; i++) sum += (packet[i] & 0xff) << (8 * ((i + 1) % 2));
        return sum & 0xffff;
    }

    /** Reads a packet back into tag -> value (for the tests and a sanity check); null if the key or checksum is wrong. */
    public static Map<Integer, byte[]> decode(byte[] packet) {
        if (packet.length < UAS_LS_KEY.length + 5) return null;
        for (int i = 0; i < UAS_LS_KEY.length; i++) if (packet[i] != UAS_LS_KEY[i]) return null;
        int[] at = {UAS_LS_KEY.length};
        int len = readBer(packet, at);
        if (at[0] + len != packet.length) return null;
        int sum = checksum(packet);
        if (((packet[packet.length - 2] & 0xff) << 8 | (packet[packet.length - 1] & 0xff)) != sum) return null;
        return items(packet, at[0], packet.length);
    }

    /** The tag -> value items between from and to (a local set's body). */
    public static Map<Integer, byte[]> items(byte[] b, int from, int to) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int[] at = {from};
        while (at[0] < to) {
            int tag = readBer(b, at); // tags are BER-OID; every one used here is under 128
            int n = readBer(b, at);
            out.put(tag, Arrays.copyOfRange(b, at[0], at[0] + n));
            at[0] += n;
        }
        return out;
    }

    private static int readBer(byte[] b, int[] at) {
        int first = b[at[0]++] & 0xff;
        if (first < 0x80) return first;
        int n = first & 0x7f, v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[at[0]++] & 0xff);
        return v;
    }

    static byte[] berLength(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
        return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
    }

    private static void item(ByteArrayOutputStream out, int tag, byte[] value) {
        out.write(tag);
        out.writeBytes(berLength(value.length));
        out.writeBytes(value);
    }

    private static boolean ok(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** 0..range over the unsigned bits (MISB: LS = value * (2^bits - 1) / range). */
    static long unsigned(double value, double range, int bits) {
        double max = bits == 32 ? 4294967295.0 : (1L << bits) - 1;
        return Math.round(Math.max(0, Math.min(range, value)) * max / range);
    }

    /** -range..+range over the signed bits, the lowest value left out (it means "error"). */
    static long signed(double value, double range, int bits) {
        double max = (1L << (bits - 1)) - 1;
        return Math.round(Math.max(-range, Math.min(range, value)) * max / range);
    }

    /** -900..19000 m over 16 bits (tags 15, 25). */
    static long altitude(double m) {
        return Math.round((Math.max(-900, Math.min(19000, m)) + 900) * 65535.0 / 19900);
    }

    static double decodeUnsigned(byte[] v, double range) {
        double max = v.length == 4 ? 4294967295.0 : (1L << (8 * v.length)) - 1;
        return be(v, false) * range / max;
    }

    static double decodeSigned(byte[] v, double range) {
        double max = (1L << (8 * v.length - 1)) - 1;
        return be(v, true) * range / max;
    }

    static double decodeAltitude(byte[] v) {
        return be(v, false) * 19900.0 / 65535 - 900;
    }

    static long be(byte[] v, boolean signed) {
        long x = 0;
        for (byte b : v) x = (x << 8) | (b & 0xff);
        if (signed && v.length < 8 && (v[0] & 0x80) != 0) x -= 1L << (8 * v.length);
        return x;
    }

    private static byte[] u64(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--, v >>>= 8) b[i] = (byte) v;
        return b;
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] i32(long v) {
        return u32(v);
    }

    private static byte[] u16(long v) {
        return new byte[]{(byte) (v >> 8), (byte) v};
    }

    private static byte[] i16(long v) {
        return u16(v);
    }

    private static byte[] ascii(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= 0x20 && ch < 0x7f ? ch : '_');
        String t = b.length() > max ? b.substring(0, max) : b.toString();
        return t.getBytes(StandardCharsets.US_ASCII);
    }
}
