package de.droiddrone.pc;

import de.droiddrone.common.OverwatchProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pc.47 (2026-11-10, the pilot: walked past 5 m in front of a phone overwatching on HIGH, no alert): the check-in
 * says whether the motion detection gets pictures, so the PC can say a phone watches blind.
 */
class OverwatchBlindTest {
    private static ControlLink.SleepHello hello(int flags) {
        byte[] d = {ControlLink.SLEEP_HELLO, 65, 0, 1, 0x0f, (byte) 0xa0, (byte) flags, 0};
        return ControlLink.SleepHello.read(d, d.length, 1000);
    }

    @Test
    void aPhoneOverwatchingWithoutPicturesIsBlind() {
        assertTrue(hello(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES).watchesBlind());
        assertFalse(hello(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES | OverwatchProtocol.HELLO_ANALYSING).watchesBlind(),
                "pictures analysed: it sees");
        assertFalse(hello(OverwatchProtocol.HELLO_OVERWATCH).watchesBlind(), "an older phone says nothing about it: not called blind");
        assertFalse(hello(OverwatchProtocol.HELLO_PICTURES).watchesBlind(), "asleep, not overwatching");
        assertTrue(hello(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES).overwatch());
    }
}
