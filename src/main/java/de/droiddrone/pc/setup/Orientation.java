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

    /** Betaflight's tilt as words: "nose up 31°, right side down 2°". */
    public static String describe(double rollDeg, double pitchDeg) {
        String pitch = Math.abs(pitchDeg) < 0.5 ? "nose level" : (pitchDeg > 0 ? "nose down " : "nose up ") + Math.round(Math.abs(pitchDeg)) + "°";
        String roll = Math.abs(rollDeg) < 0.5 ? "sides level" : (rollDeg > 0 ? "right side down " : "left side down ") + Math.round(Math.abs(rollDeg)) + "°";
        return pitch + ", " + roll;
    }
}
