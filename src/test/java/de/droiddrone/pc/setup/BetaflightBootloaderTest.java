package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet H1 / H4: the CLI's "bl" goes only to a board that says it is disarmed (on a made-up port). */
class BetaflightBootloaderTest {
    /** MSP_STATUS' answer: 11 bytes, the arming flags' first byte at 6. */
    static String status(boolean armed) {
        byte[] p = new byte[11];
        if (armed) p[6] = 1;
        int sum = p.length ^ 101;
        StringBuilder sb = new StringBuilder("$M>").append((char) p.length).append((char) 101);
        for (byte b : p) {
            sb.append((char) b);
            sum ^= b;
        }
        return sb.append((char) sum).toString();
    }

    @Test
    void disarmedTheBoardIsSentToItsBootloader() throws Exception {
        BetaflightTest.FakeWire wire = new BetaflightTest.FakeWire();
        wire.reply(0, status(false));
        wire.reply(0, "\r\nEntering CLI Mode, type 'exit' to return, or 'help'\r\n\r\n# ");
        BetaflightUsb fc = BetaflightUsb.onWire(wire);
        fc.rebootToBootloader();
        assertTrue(wire.written.toString().endsWith("#bl\n"), wire.written.toString());
        assertFalse(wire.written.toString().contains("exit"), "it restarts: no exit");
        assertFalse(wire.open);
    }

    @Test
    void armedNothingIsSent() {
        BetaflightTest.FakeWire wire = new BetaflightTest.FakeWire();
        wire.reply(0, status(true));
        BetaflightUsb fc = BetaflightUsb.onWire(wire);
        IOException e = assertThrows(IOException.class, fc::rebootToBootloader);
        assertTrue(e.getMessage().contains("armed"));
        assertFalse(wire.written.toString().contains("bl"), wire.written.toString());
        fc.close();
    }
}
