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

    @Test
    void saysTheTiltInWords() {
        assertEquals("nose up 31°, right side down 2°", Orientation.describe(2.2, -31));
        assertEquals("nose down 12°, sides level", Orientation.describe(0.1, 12.2));
    }
}
