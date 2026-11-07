package de.droiddrone.pc;

import de.droiddrone.common.PcAudio;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The live audio's packet and the phone's gate (PcAudio, compiled into both apps; the phone runs the gate). */
class PcAudioTest {
    @Test
    void packetRoundTrip() {
        byte[] p = PcAudio.encode(true, 48, true);
        assertArrayEquals(new byte[]{0x79, 1, 48, 1}, p);
        PcAudio.Request r = PcAudio.decode(p, p.length);
        assertTrue(r.on);
        assertEquals(48, r.kbps);
        assertTrue(r.resendConfig);
        PcAudio.Request off = PcAudio.decode(PcAudio.encode(false, 32, false), 4);
        assertFalse(off.on);
        assertFalse(off.resendConfig);
    }

    @Test
    void oddPacketsAreReadSafely() {
        assertNull(PcAudio.decode(new byte[]{0x79, 1}, 2), "too short");
        assertNull(PcAudio.decode(new byte[]{0x78, 1, 48, 0}, 4), "another packet");
        assertFalse(PcAudio.decode(new byte[]{0x79, 1, 48}, 3).resendConfig, "no flags byte: none");
        assertEquals(PcAudio.MAX_KBPS, PcAudio.decode(new byte[]{0x79, 1, (byte) 200}, 3).kbps, "clamped");
        assertEquals(PcAudio.MIN_KBPS, PcAudio.decode(new byte[]{0x79, 1, 1}, 3).kbps, "clamped");
    }

    @Test
    void streamsOnlyWhileAskedForAndDisarmed() {
        PcAudio gate = new PcAudio();
        assertFalse(gate.allowed(0, true, true), "nobody asked");
        gate.onRequest(new PcAudio.Request(true, 48, false), 1000);
        assertTrue(gate.allowed(1000, true, true));
        assertFalse(gate.allowed(1000, false, true), "armed (or unknown): never");
        assertFalse(gate.allowed(1000, true, false), "standby or deep sleep: no");
        assertEquals(48_000, gate.bitRate());
    }

    @Test
    void anOnRunsOutWhenThePcFallsSilent() {
        PcAudio gate = new PcAudio();
        gate.onRequest(new PcAudio.Request(true, 48, false), 0);
        assertTrue(gate.wanted(PcAudio.WANT_EXPIRES_MS - 1));
        assertFalse(gate.wanted(PcAudio.WANT_EXPIRES_MS), "a PC gone without a word: the microphone stops");
        gate.onRequest(new PcAudio.Request(true, 48, false), 5000);
        gate.onRequest(new PcAudio.Request(false, 48, false), 5100);
        assertFalse(gate.wanted(5100), "off: at once");
        gate.onRequest(new PcAudio.Request(true, 48, false), 6000);
        gate.forget();
        assertFalse(gate.wanted(6000), "disconnected");
        gate.onRequest(null, 7000);
        assertFalse(gate.wanted(7000), "a broken packet changes nothing");
    }
}
