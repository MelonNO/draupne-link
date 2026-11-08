package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrientationTest {
    private static byte[] attitude(int rollDeci, int pitchDeci, int yaw) {
        return new byte[]{(byte) rollDeci, (byte) (rollDeci >> 8), (byte) pitchDeci, (byte) (pitchDeci >> 8), (byte) yaw, (byte) (yaw >> 8)};
    }

    @Test
    void readsBetaflightsAttitude() {
        Orientation.Attitude a = Orientation.parse(attitude(-123, 417, 349));
        assertEquals(-12.3, a.rollDeg(), 1e-9);
        assertEquals(41.7, a.pitchDeg(), 1e-9);
        assertEquals(349, a.headingDeg());
        assertNull(Orientation.parse(new byte[3]));
    }

    @Test
    void aBoardTheRightWayRoundIsTurnedNothing() {
        // nose up: Betaflight's pitch goes negative; right side down: its roll goes positive
        assertEquals(0, Orientation.boardTurn(new double[]{2, -40}, new double[]{38, 3}));
    }

    @Test
    void aBoardTurnedRoundIsFoundAsTheFlightLogShowedIt() {
        // 6 Nov 2026, the F722 that flipped: the nose went up and Betaflight saw pitch +12, +42 - nose down
        assertEquals(180, Orientation.boardTurn(new double[]{-1, 41}, new double[]{-39, 2}));
    }

    @Test
    void quarterTurnsAreTold() {
        assertEquals(90, Orientation.boardTurn(new double[]{40, 1}, new double[]{2, 40}));
        assertEquals(270, Orientation.boardTurn(new double[]{-40, 1}, new double[]{2, -40}));
    }

    @Test
    void aMirroredOrUnclearReadingIsNoTurn() {
        // pitch right, roll the wrong way: no turn of the board does that
        assertEquals(-1, Orientation.boardTurn(new double[]{0, -40}, new double[]{-40, 0}));
        assertEquals(-1, Orientation.boardTurn(new double[]{30, -30}, new double[]{30, 30}));
        assertEquals(-1, Orientation.boardTurn(new double[]{0, 0}, new double[]{40, 0}));
    }

    @Test
    void lyingOnItsBackIsSeen() {
        assertTrue(Orientation.upsideDown(new Orientation.Attitude(179.5, 0.3, 10)));
        assertTrue(Orientation.upsideDown(new Orientation.Attitude(-178, 1, 10)));
        assertFalse(Orientation.upsideDown(new Orientation.Attitude(4.1, 1.1, 151)));
    }

    @Test
    void changesWrapRoundTheHalfTurn() {
        double[] d = Orientation.delta(new Orientation.Attitude(179, 0, 0), new Orientation.Attitude(-179, 5, 0));
        assertEquals(2, d[0], 1e-9);
        assertEquals(5, d[1], 1e-9);
        assertEquals(180, Orientation.turned(0, 180));
        assertEquals(90, Orientation.turned(270, 180));
        assertEquals(0, Orientation.turned(-90, 90));
    }

    @Test
    void readsTheSettingFromTheCli() {
        String reply = "align_board_yaw = 180\r\nAllowed range: -180 - 360\r\nDefault value: 0";
        assertEquals(180, Orientation.parseSetting(reply, "align_board_yaw"));
        assertEquals(-90, Orientation.parseSetting("align_board_roll = -90", "align_board_roll"));
        assertNull(Orientation.parseSetting("Invalid name", "align_board_yaw"));
    }

    // ------------------------------------------------------------------ the compass (1.0.52)

    private static double[] mul(int[][] m, double[] v) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i] += m[i][j] * v[j];
        return r;
    }

    private static int[][] t(int[][] m) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = m[j][i];
        return r;
    }

    /**
     * What Betaflight would read with the nose north, east, south, west (body x forward, y left, z up; the field
     * north and down, as in Norway), for a compass whose right align_mag is `right`, set to `current`, the board
     * alignment `board` (null = none) applied after it, and an uncalibrated offset added.
     */
    private static double[][] readings(int right, int current, int[][] board) {
        int[][] b = board == null ? Orientation.MAG_MATRICES[0] : board;
        double[] offset = {137, -260, 55};
        double[][] out = new double[4][];
        for (int h = 0; h < 4; h++) {
            double psi = Math.toRadians(h * 90);
            double[] body = {400 * Math.cos(psi), 400 * Math.sin(psi), -1100};
            // the right setting maps the sensor's raw reading onto the body: raw = Eright^T B^T body
            double[] raw = mul(t(Orientation.MAG_MATRICES[right]), mul(t(b), body));
            double[] m = mul(b, mul(Orientation.MAG_MATRICES[current], raw));
            for (int i = 0; i < 3; i++) m[i] += offset[i];
            out[h] = m;
        }
        return out;
    }

    @Test
    void findsTheCompassSettingFromFourHeadingsWhateverItIsSetToNow() {
        int[][][] boards = {null, Orientation.boardMatrix(0, 0, 180, true), Orientation.boardMatrix(180, 0, 0, true),
                Orientation.boardMatrix(0, 0, 90, true)};
        for (int[][] board : boards) {
            for (int right = 0; right < 8; right++) {
                for (int current = 0; current < 8; current++) {
                    double[][] r = readings(right, current, board);
                    String what = "right " + Orientation.MAG_ALIGNS[right] + ", set " + Orientation.MAG_ALIGNS[current];
                    assertEquals(right == current, Orientation.compassRight(r[0], r[1], r[2], r[3]), what);
                    assertEquals(right, Orientation.compassFix(current, r[0], r[1], r[2], r[3], board), what);
                }
            }
        }
    }

    @Test
    void noFitForReadingsThatTurnTheWrongWay() {
        double[] same = {100, 0, -500};
        assertEquals(-1, Orientation.compassFix(0, same, same, same, same, null), "the drone not turned: nothing fits");
        assertEquals(-1, Orientation.compassFix(-1, same, same, same, same, null), "DEFAULT / CUSTOM: unknown start");
    }

    @Test
    void readsTheCompassAndItsSetting() {
        byte[] p = new byte[18];
        int[] mag = {-300, 1200, -32000};
        for (int i = 0; i < 3; i++) {
            p[12 + 2 * i] = (byte) mag[i];
            p[13 + 2 * i] = (byte) (mag[i] >> 8);
        }
        assertArrayEquals(new double[]{-300, 1200, -32000}, Orientation.parseMag(p), 1e-9);
        assertNull(Orientation.parseMag(new byte[10]));
        assertEquals("CW270FLIP", Orientation.parseAlign("align_mag = CW270FLIP\r\nAllowed values: DEFAULT, CW0, ..."));
        assertEquals("DEFAULT", Orientation.parseAlign("align_mag = DEFAULT"));
        assertNull(Orientation.parseAlign("Invalid name"));
        assertEquals(7, Orientation.alignIndex("CW270FLIP"));
        assertEquals(-1, Orientation.alignIndex("DEFAULT"));
        assertNull(Orientation.boardMatrix(0, 0, 45, true), "only quarter turns");
        assertArrayEquals(Orientation.boardMatrix(0, 0, 90, true)[0], Orientation.boardMatrix(0, 0, 270, false)[0]);
    }

    @Test
    void saysTheTiltInWords() {
        assertEquals("nose up 31°, right side down 2°", Orientation.describe(2.2, -31));
        assertEquals("nose down 12°, sides level", Orientation.describe(0.1, 12.2));
    }
}
