package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AS-11: what a Betaflight drone's "diff all" carries into its ArduPilot setup. */
class FromBetaflightTest {
    static final String DIFF = String.join("\n",
            "# version",
            "# Betaflight / STM32F405 (S405) 4.4.1 Jun 26 2023 / 12:33:07 (a0aa8f3) MSP API: 1.45",
            "board_name ORQAF405PRO",
            "# resources",
            "# mixer",
            "# aux",
            "aux 0 0 0 1700 2100 0 0",
            "aux 1 1 1 900 1300 0 0",
            "aux 2 13 3 1700 2100 0 0",
            "# master",
            "set align_board_yaw = 90",
            "set yaw_motors_reversed = ON",
            "set bat_capacity = 1550",
            "set force_battery_cell_count = 6",
            "# ");

    @Test
    void batterySwitchesTurnAndMotorOrderCarry() {
        FromBetaflight.Carried c = FromBetaflight.parse(DIFF, 4);
        assertEquals(6, c.cells(), "the set count beats the pack on now");
        assertEquals(1550, c.capacityMah());
        assertEquals(1, c.armAux());
        assertEquals(2, c.modeAux(), "ANGLE's AUX");
        assertEquals(2, c.ahrsOrientation(), "yaw 90");
        assertEquals(FromBetaflight.FRAME_TYPE_BF_X_REVERSED, c.frameType(), "props out");
        assertTrue(c.notes().isEmpty(), c.notes().toString());

        ArduPlan.Choices ch = c.apply(ArduPlan.Choices.defaults(3));
        assertEquals(6, ch.cells());
        assertEquals(1550, ch.capacityMah());
        assertEquals(3, ch.sysId());
        Map<String, ArduPlan.Wanted> extra = c.extra();
        assertEquals(18f, extra.get("FRAME_TYPE").value());
        assertEquals(1f, extra.get("FRAME_CLASS").value());
        assertEquals(2f, extra.get("AHRS_ORIENTATION").value());
        assertTrue(ArduPlan.plan(ch, extra, Map.of("FRAME_TYPE", 1f)).stream().anyMatch(x -> x.name().equals("FRAME_TYPE") && x.to() == 18f));
        assertTrue(c.summary().contains("props out"), c.summary());
    }

    @Test
    void defaultsLeftOutAndOddThingsNoted() {
        FromBetaflight.Carried plain = FromBetaflight.parse("# master\nset gyro_lpf1_static_hz = 0\n", 0);
        assertNull(plain.cells());
        assertNull(plain.capacityMah());
        assertNull(plain.armAux());
        assertEquals(0, plain.ahrsOrientation());
        assertEquals(FromBetaflight.FRAME_TYPE_BF_X, plain.frameType(), "a quad X, props in");
        assertEquals(4, plain.apply(ArduPlan.Choices.defaults(2)).cells(), "the default stays");

        FromBetaflight.Carried odd = FromBetaflight.parse(String.join("\n",
                "mixer HEX6X", "resource MOTOR 1 B07", "set align_board_yaw = 30", "aux 0 0 9 1700 2100 0 0"), 3);
        assertEquals(3, odd.cells(), "the pack on now");
        assertNull(odd.frameType());
        assertNull(odd.ahrsOrientation());
        assertNull(odd.armAux(), "AUX10 is beyond ArduPilot's switches here");
        assertEquals(4, odd.notes().size(), odd.notes().toString());
        assertTrue(odd.extra().isEmpty());
    }

    @Test
    void upsideDownTurns() {
        assertEquals(8, FromBetaflight.orientation(180, 0, 0));
        assertEquals(12, FromBetaflight.orientation(180, 0, 180), "roll 180 + yaw 180 = pitch 180");
        assertEquals(14, FromBetaflight.orientation(180, 0, 270));
        assertEquals(6, FromBetaflight.orientation(0, 0, -90));
        assertNull(FromBetaflight.orientation(0, 90, 0));
    }
}
