package de.droiddrone.pc.setup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drone setup &gt; Orientation (1.0.42): does Betaflight see the drone's moves the right way round? The pilot puts the
 * drone flat, lifts the nose, puts it flat again and lowers the right side, while Betaflight's attitude (MSP_ATTITUDE)
 * is read. A board mounted turned (its arrow not to the front) and not set in Betaflight's board alignment makes it
 * correct the wrong way - it flips over on takeoff. From the two tilts: the turn that explains them.
 *
 * <p>Betaflight's signs: roll positive = right side down, pitch positive = nose down.
 */
public final class Orientation {
    private Orientation() {}

    public static final int MSP_ATTITUDE = 108;

    /** A tilt this big from flat counts as the move asked for. */
    public static final double MOVE_DEG = 30;
    /** Back within this of flat: flat again. */
    public static final double FLAT_DEG = 10;
    /** Each tilt must point this well (cosine, about 37°) the way the turn found says: a board at 45° fits none. */
    static final double MATCH = 0.8;

    /** Betaflight's attitude: degrees; heading 0..359 (without a compass, from the gyro since start-up). */
    public record Attitude(double rollDeg, double pitchDeg, int headingDeg) {}

    /** MSP_ATTITUDE: roll and pitch in tenths of a degree, heading in degrees, little-endian s16 each. */
    public static Attitude parse(byte[] p) {
        if (p == null || p.length < 6) return null;
        int roll = (short) ((p[0] & 0xff) | (p[1] << 8));
        int pitch = (short) ((p[2] & 0xff) | (p[3] << 8));
        int yaw = (short) ((p[4] & 0xff) | (p[5] << 8));
        return new Attitude(roll / 10.0, pitch / 10.0, Math.floorMod(yaw, 360));
    }

    /** From level to now: {roll change, pitch change}, each within -180..180. */
    public static double[] delta(Attitude level, Attitude now) {
        return new double[]{wrap(now.rollDeg() - level.rollDeg()), wrap(now.pitchDeg() - level.pitchDeg())};
    }

    public static double size(double[] d) {
        return Math.hypot(d[0], d[1]);
    }

    static double wrap(double deg) {
        double d = ((deg + 180) % 360 + 360) % 360 - 180;
        return d == -180 ? 180 : d;
    }

    /** Lying flat, Betaflight sees it on its back: the board is mounted upside down and not set so. */
    public static boolean upsideDown(Attitude level) {
        return Math.abs(level.rollDeg()) > 120;
    }

    /**
     * How far the board is turned (clockwise from above, 0, 90, 180 or 270) for these two tilts as Betaflight saw
     * them, {roll change, pitch change} each: the nose lifted, and the right side lowered. -1 when no turn explains
     * both (a mirrored board, or the moves not done as asked).
     */
    public static int boardTurn(double[] noseUp, double[] rightDown) {
        double nl = size(noseUp), rl = size(rightDown);
        if (nl < 1e-6 || rl < 1e-6) return -1;
        int best = -1;
        double bestScore = -9;
        for (int turn = 0; turn < 360; turn += 90) {
            double s = Math.sin(Math.toRadians(turn)), c = Math.cos(Math.toRadians(turn));
            // the craft's tilt in the board's axes: nose up reads as {s, -c}, right down as {c, s} (roll, pitch)
            double a = (noseUp[0] * s + noseUp[1] * -c) / nl;
            double b = (rightDown[0] * c + rightDown[1] * s) / rl;
            if (a < MATCH || b < MATCH) continue;
            if (a + b > bestScore) {
                bestScore = a + b;
                best = turn;
            }
        }
        return best;
    }

    /** The alignment angle after turning it by more degrees, as 0..359. */
    public static int turned(int current, int more) {
        return Math.floorMod(current + more, 360);
    }

    private static final Pattern VALUE = Pattern.compile("=\\s*(-?\\d+)");

    /** The number in a CLI "get align_board_yaw" reply ("align_board_yaw = 180 ..."), or null. */
    public static Integer parseSetting(String reply, String name) {
        if (reply == null) return null;
        for (String line : reply.split("\\R")) {
            if (!line.trim().startsWith(name)) continue;
            Matcher m = VALUE.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    // ------------------------------------------------------------------ the compass (1.0.52)

    public static final int MSP_RAW_IMU = 102;

    /**
     * Betaflight's align_mag choices, as its CLI names them, with what each does to a reading (x, y, z) -
     * Betaflight's alignSensorViaRotation. Its body axes: x forward, y left, z up.
     */
    public static final String[] MAG_ALIGNS = {"CW0", "CW90", "CW180", "CW270", "CW0FLIP", "CW90FLIP", "CW180FLIP", "CW270FLIP"};
    static final int[][][] MAG_MATRICES = {
            {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},     // CW0: x, y, z
            {{0, 1, 0}, {-1, 0, 0}, {0, 0, 1}},    // CW90: y, -x, z
            {{-1, 0, 0}, {0, -1, 0}, {0, 0, 1}},   // CW180: -x, -y, z
            {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},    // CW270: -y, x, z
            {{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}},   // CW0FLIP: -x, y, -z
            {{0, 1, 0}, {1, 0, 0}, {0, 0, -1}},    // CW90FLIP: y, x, -z
            {{1, 0, 0}, {0, -1, 0}, {0, 0, -1}},   // CW180FLIP: x, -y, -z
            {{0, -1, 0}, {-1, 0, 0}, {0, 0, -1}},  // CW270FLIP: -y, -x, -z
    };

    /** MSP_RAW_IMU: acc, gyro, mag, three little-endian s16 each; the mag's {x, y, z}, or null. */
    public static double[] parseMag(byte[] p) {
        if (p == null || p.length < 18) return null;
        double[] m = new double[3];
        for (int i = 0; i < 3; i++) m[i] = (short) ((p[12 + 2 * i] & 0xff) | (p[13 + 2 * i] << 8));
        return m;
    }

    /** The align_mag value in a CLI "get align_mag" reply ("align_mag = CW270FLIP"), upper case, or null. */
    public static String parseAlign(String reply) {
        if (reply == null) return null;
        Matcher m = Pattern.compile("align_mag\\s*=\\s*(\\w+)").matcher(reply);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    /** Index into MAG_ALIGNS, or -1 (DEFAULT, CUSTOM, unknown). */
    public static int alignIndex(String name) {
        for (int i = 0; i < MAG_ALIGNS.length; i++) if (MAG_ALIGNS[i].equals(name)) return i;
        return -1;
    }

    /**
     * The compass's readings with the nose pointing north, east, south and west (the drone flat): is it the right
     * way round? North minus south must point forward (+x) and east minus west to the left (+y) - the differences,
     * so the compass's own offset (uncalibrated) drops out. Each within 45° (MATCH-like, cos 0.7).
     */
    public static boolean compassRight(double[] n, double[] e, double[] s, double[] w) {
        double[] fwd = {n[0] - s[0], n[1] - s[1]};
        double[] left = {e[0] - w[0], e[1] - w[1]};
        return dir(fwd, new double[]{1, 0}) > 0.7 && dir(left, new double[]{0, 1}) > 0.7;
    }

    /**
     * The align_mag that makes the readings right, given the one they were taken with (an index into MAG_ALIGNS):
     * the raw sensor's directions back out of the current one, then the choice whose result points north forward
     * and east left. -1 when none fits (the moves not as asked, or a compass mounted at an angle).
     * @param board the board alignment as Betaflight applies it after align_mag (rows of a matrix; null = none)
     */
    public static int compassFix(int current, double[] n, double[] e, double[] s, double[] w, int[][] board) {
        if (current < 0) return -1;
        double[] fwd = {n[0] - s[0], n[1] - s[1], n[2] - s[2]};
        double[] left = {e[0] - w[0], e[1] - w[1], e[2] - w[2]};
        int[][] b = board == null ? MAG_MATRICES[0] : board;
        // measured = B * Ecur * raw: raw = Ecur^T * B^T * measured; wanted: B * Enew * raw = expected
        double[] rawFwd = mul(transpose(MAG_MATRICES[current]), mul(transpose(b), fwd));
        double[] rawLeft = mul(transpose(MAG_MATRICES[current]), mul(transpose(b), left));
        double[] wantFwd = mul(transpose(b), new double[]{1, 0, 0});
        double[] wantLeft = mul(transpose(b), new double[]{0, 1, 0});
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < MAG_MATRICES.length; i++) {
            double a = dir(xy(mul(MAG_MATRICES[i], rawFwd)), xy(wantFwd));
            double c = dir(xy(mul(MAG_MATRICES[i], rawLeft)), xy(wantLeft));
            if (a > 0.7 && c > 0.7 && a + c > bestScore) {
                bestScore = a + c;
                best = i;
            }
        }
        return best;
    }

    /**
     * Betaflight's board alignment as a matrix, for the turns the orientation check writes: yaw in quarter turns
     * (clockwise from above when clockwise is true), roll 0 or 180, pitch 0. null for anything else.
     */
    public static int[][] boardMatrix(int rollDeg, int pitchDeg, int yawDeg, boolean clockwise) {
        if (Math.floorMod(pitchDeg, 360) != 0 || Math.floorMod(rollDeg, 180) != 0 || Math.floorMod(yawDeg, 90) != 0) return null;
        int quarter = Math.floorMod(yawDeg, 360) / 90;
        if (!clockwise) quarter = (4 - quarter) % 4;
        int[][] m = MAG_MATRICES[quarter]; // CW0 ... CW270 are the yaw turns
        if (Math.floorMod(rollDeg, 360) == 180) m = mulM(MAG_MATRICES[6], m); // roll 180: x, -y, -z
        return m;
    }

    private static double[] xy(double[] v) {
        return new double[]{v[0], v[1]};
    }

    private static double dir(double[] a, double[] b) {
        double la = Math.hypot(a[0], a[1]), lb = Math.hypot(b[0], b[1]);
        if (la < 1e-9 || lb < 1e-9) return -1;
        return (a[0] * b[0] + a[1] * b[1]) / (la * lb);
    }

    private static double[] mul(int[][] m, double[] v) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i] += m[i][j] * v[j];
        return r;
    }

    private static int[][] mulM(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    private static int[][] transpose(int[][] m) {
        int[][] t = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) t[i][j] = m[j][i];
        return t;
    }

    /** Betaflight's tilt as words: "nose up 31°, right side down 2°". */
    public static String describe(double rollDeg, double pitchDeg) {
        String pitch = Math.abs(pitchDeg) < 0.5 ? "nose level" : (pitchDeg > 0 ? "nose down " : "nose up ") + Math.round(Math.abs(pitchDeg)) + "°";
        String roll = Math.abs(rollDeg) < 0.5 ? "sides level" : (rollDeg > 0 ? "right side down " : "left side down ") + Math.round(Math.abs(rollDeg)) + "°";
        return pitch + ", " + roll;
    }
}
