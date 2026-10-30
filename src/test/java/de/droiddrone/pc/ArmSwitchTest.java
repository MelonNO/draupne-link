package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArmSwitchTest {
    // shape of Betaflight 4.5's "aux" CLI output
    static final String AUX = """
            # aux
            aux 0 0 0 1700 2100 0 0
            aux 1 1 1 1300 1700 0 0
            aux 2 0 0 900 900 0 0
            aux 3 28 2 1700 2100 0 0
            # """;

    @Test
    void armIsFoundOnItsChannelWithItsRange() {
        ArmSwitch a = ArmSwitch.fromCli(AUX);
        assertNotNull(a);
        assertEquals(4, a.channel(), "AUX1 is the 5th channel this app sends");
        assertEquals(1, a.ranges().size(), "the unused slot (900-900) is not a range");
        assertTrue(a.on(new short[]{1500, 1500, 1500, 1000, 1800}));
        assertFalse(a.on(new short[]{1500, 1500, 1500, 1000, 1000}));
    }

    @Test
    void noArmModeOrArmOnTwoChannelsMeansUnknown() {
        assertNull(ArmSwitch.fromCli("aux 0 1 0 1700 2100 0 0"));
        assertNull(ArmSwitch.fromCli("aux 0 0 0 1700 2100 0 0\naux 1 0 2 1700 2100 0 0"));
    }

    @Test
    void savedAndReadBack() {
        ArmSwitch a = new ArmSwitch(5, List.of(new int[]{1700, 2100}, new int[]{900, 1100}));
        assertEquals("5:1700-2100,900-1100", a.format());
        ArmSwitch back = ArmSwitch.parse(a.format());
        assertEquals(5, back.channel());
        assertEquals(2, back.ranges().size());
        assertNull(ArmSwitch.parse("garbage"));
        assertNull(ArmSwitch.parse(""));
    }
}
