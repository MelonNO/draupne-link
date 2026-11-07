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

    /** A channel the frame doesn't carry is judged as the FC gets it: 1000 (padded) up to the 8th, 0 beyond. */
    @Test
    void aMissingChannelCountsAsWhatTheFlightControllerGets() {
        ArmSwitch inverted = new ArmSwitch(5, List.of(new int[]{900, 1100})); // AUX2, arms when low
        assertTrue(inverted.on(new short[]{1500, 1500, 1500, 1000}), "AUX2 goes out padded as 1000: inside the range");
        assertFalse(inverted.on(new short[]{1500, 1500, 1500, 1000, 1000, 1500}), "sent as 1500: off");
        ArmSwitch usual = new ArmSwitch(5, List.of(new int[]{1700, 2100}));
        assertFalse(usual.on(new short[]{1500, 1500, 1500, 1000}), "padded 1000 is below the usual range");
        ArmSwitch beyond = new ArmSwitch(9, List.of(new int[]{900, 1100})); // AUX6: not padded, Betaflight sets it to 0
        assertTrue(beyond.on(new short[]{1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000, 1000, 1000}), "sent as 1000: on");
        assertFalse(beyond.on(new short[]{1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000, 1000, 1500}), "sent as 1500: off");
        assertFalse(usual.on(null));
    }

    /** 1.0.49 (M1): not sent, or sent as 0, Betaflight holds the channel at a value nobody knows - never "off". */
    @Test
    void aChannelNotSentOrSentAsZeroIsUnknownAndCountsAsOn() {
        ArmSwitch usual = new ArmSwitch(9, List.of(new int[]{1700, 2100})); // AUX6
        short[] eight = {1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000};
        assertTrue(usual.isUnknown(eight));
        assertTrue(usual.on(eight), "beyond the padded 8: unknown, not off");
        short[] zero = {1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000, 0, 0, 0, 0};
        assertTrue(usual.on(zero), "0 is not low");
        short[] low = {1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000, 0, 1000};
        assertFalse(usual.isUnknown(low));
        assertFalse(usual.on(low));
    }

    @Test
    void theRangeEndIsIncludedErringOnTheSideOfOn() {
        ArmSwitch a = new ArmSwitch(4, List.of(new int[]{1700, 2100}));
        assertTrue(a.on(new short[]{1500, 1500, 1500, 1000, 2100}), "Betaflight's own check would say off at exactly 2100");
        assertTrue(a.on(new short[]{1500, 1500, 1500, 1000, 1700}));
        assertFalse(a.on(new short[]{1500, 1500, 1500, 1000, 1699}));
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
