package de.droiddrone.pc;

import de.droiddrone.common.PcAudio;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The PC's side of the live audio (1.0.45): MUTE, the 10 s before the phone is told to stop, and the AUDIO packet. */
class AudioRequestTest {
    private static PcAudio.Request read(byte[] p) {
        assertNotNull(p, "a request due");
        return PcAudio.decode(p, p.length);
    }

    @Test
    void onAtOnceThenEverySecond() {
        AudioRequest r = new AudioRequest();
        PcAudio.Request first = read(r.due(0, false));
        assertTrue(first.on);
        assertEquals(PcAudio.DEFAULT_KBPS, first.kbps);
        assertFalse(first.resendConfig);
        assertNull(r.due(500, false), "not again within the second");
        assertTrue(read(r.due(1000, false)).on, "again after a second: the phone forgets an on after 3 s");
    }

    @Test
    void mutedTheSoundGoesOnForTenSecondsThenStops() {
        AudioRequest r = new AudioRequest();
        r.due(0, false);
        r.setMuted(true, 500);
        assertTrue(r.streamWanted(500), "muted: silent here at once, the phone still sends");
        PcAudio.Request atMute = read(r.due(800, false)); // 1.0.50: the "in the air too" goes off at once
        assertTrue(atMute.on);
        assertFalse(atMute.inFlight);
        assertNull(r.due(1000, false), "no change, no packet before the second is up");
        assertTrue(read(r.due(1800, false)).on);
        assertTrue(r.streamWanted(10_499));
        assertFalse(r.streamWanted(10_500), "10 s muted");
        PcAudio.Request stop = read(r.due(10_500, false));
        assertFalse(stop.on, "the phone is told to stop at once");
        assertNull(r.due(11_000, false));
        assertFalse(read(r.due(11_500, false)).on, "and again every second");
    }

    @Test
    void unmuteStartsItAgainAtOnce() {
        AudioRequest r = new AudioRequest();
        r.setMuted(true, 0);
        assertFalse(read(r.due(10_000, false)).on);
        r.setMuted(false, 10_200);
        assertTrue(read(r.due(10_200, false)).on, "a change goes out without waiting for the second");
    }

    @Test
    void muteAgainKeepsTheFirstMuteTime() {
        AudioRequest r = new AudioRequest();
        r.setMuted(true, 0);
        r.setMuted(true, 9000); // a second press of a muted MUTE comes as a toggle; a repeat set changes nothing
        assertFalse(r.streamWanted(10_000));
        r.setMuted(false, 10_500);
        r.setMuted(true, 11_000);
        assertTrue(r.streamWanted(20_999), "muted again: ten seconds from then");
        assertFalse(r.streamWanted(21_000));
    }

    @Test
    void aMissingConfigIsAskedFor() {
        AudioRequest r = new AudioRequest();
        assertTrue(read(r.due(0, true)).resendConfig);
        r.setMuted(true, 0);
        assertFalse(read(r.due(20_000, true)).resendConfig, "never with an off");
    }

    @Test
    void aNewSessionGetsTheRequestAtOnce() {
        AudioRequest r = new AudioRequest();
        r.due(0, false);
        assertNull(r.due(100, false));
        r.newSession();
        assertNotNull(r.due(100, false));
    }

    /** 1.0.50: not muted means the pilot wants it in the air too; muted (the arming's own MUTE) never asks for that. */
    @Test
    void inTheAirOnlyWhenNotMuted() {
        AudioRequest r = new AudioRequest();
        assertTrue(read(r.due(0, false)).inFlight, "unmuted: the sound in the air too");
        r.setMuted(true, 100);
        PcAudio.Request muted = read(r.due(100, false));
        assertTrue(muted.on, "the 10 s after a mute: still sent on the ground");
        assertFalse(muted.inFlight, "but never armed");
        r.setMuted(false, 200);
        assertTrue(read(r.due(200, false)).inFlight, "unmuted in the air: armed too, at once");
    }
}
