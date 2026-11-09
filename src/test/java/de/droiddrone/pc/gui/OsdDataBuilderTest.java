package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.RcSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The HUD's warnings worked out from the link's state, without a link: pilot and spectator. */
class OsdDataBuilderTest {
    private static final long NOW = 1_000_000;

    /** A pilot's healthy link, built on below. */
    private static final class B {
        boolean spectator, watchOnly, ready = true, hasStream = true;
        long sincePicture = 30, sinceStreamStart = 60_000;
        FcTelemetry.Status status;
        FcTelemetry.PhoneState phoneState;
        boolean session = true, rcEnabled = true, frozenStopped, armUnknown, standby;
        long msSinceInput = 10;
        RcSession.Banner resume;
        String rcWaiting, hint;
        short[] channels = {1500, 1500, 1000, 1500};
        int lagMs = 20;
        ControlLink.PilotView pilotView;
        String pilotLeft;

        OsdRenderer.Data build() {
            return OsdDataBuilder.build(new OsdDataBuilder.In(spectator, watchOnly, ready, hasStream, sincePicture, sinceStreamStart,
                    status, phoneState, session, rcEnabled, msSinceInput, resume, frozenStopped, armUnknown, rcWaiting, channels,
                    standby, lagMs, hint, pilotView, pilotLeft, 45, false, null, null, false, false, false, false, false, NOW));
        }
    }

    private static FcTelemetry.Status armed(long at) {
        return new FcTelemetry.Status(true, List.of(), List.of(), 10, 40, at);
    }

    @Test
    void aHealthyLinkShowsNoWarning() {
        OsdRenderer.Data d = new B().build();
        assertTrue(d.connected());
        assertTrue(d.linkReady());
        assertTrue(d.rcLive());
        assertFalse(d.videoFrozen());
        assertFalse(d.radioFrozen());
        assertNull(d.banner());
        assertNull(d.roleBanner());
        assertEquals(-1, d.sticksAgeMs());
        assertEquals(45, d.pingMs());
    }

    @Test
    void videoFrozenWaitsOutAStreamRestart() {
        B b = new B();
        b.sincePicture = OsdDataBuilder.VIDEO_FROZEN_MS + 1;
        assertTrue(b.build().videoFrozen());
        b.sinceStreamStart = 1000; // a restart a second ago: its first picture may take a while
        b.sincePicture = 1500;
        assertFalse(b.build().videoFrozen(), "the restart's grace");
        b.sincePicture = 900; // a picture since the restart, then none
        assertTrue(b.build().videoFrozen());
        b.hasStream = false;
        assertFalse(b.build().videoFrozen(), "no stream: nothing to freeze");
    }

    @Test
    void radioFrozenOnlyWhileArmed() {
        B b = new B();
        b.msSinceInput = OsdDataBuilder.RADIO_FROZEN_MS + 1;
        assertFalse(b.build().radioFrozen(), "disarmed: a still radio is fine");
        b.status = armed(NOW - 100);
        assertTrue(b.build().radioFrozen());
        b.status = armed(NOW - 2500);
        assertFalse(b.build().radioFrozen(), "a stale armed word doesn't count");
    }

    @Test
    void bannersInTheirOrder() {
        B b = new B();
        b.lagMs = 450;
        assertEquals("STICKS 0.5 S LATE", b.build().banner());
        b.frozenStopped = true;
        assertEquals("RADIO FROZEN - STICKS STOPPED", b.build().banner());
        b.standby = true;
        assertEquals("STANDBY", b.build().banner());
        b.resume = new RcSession.Banner("LINK BACK - ARMED - STICKS LIVE", NOW - 100);
        assertEquals("LINK BACK - ARMED - STICKS LIVE", b.build().banner());
        b.resume = new RcSession.Banner("LINK BACK - ARMED - STICKS LIVE", NOW - OsdDataBuilder.RESUME_BANNER_MS);
        assertEquals("STANDBY", b.build().banner(), "the resume banner gone after its time");
    }

    @Test
    void thePhonesWordOnTheSticks() {
        B b = new B();
        b.phoneState = new FcTelemetry.PhoneState(0, 1, 40, 2, NOW - 100);
        OsdRenderer.Data d = b.build();
        assertEquals(140, d.sticksAgeMs());
        assertFalse(d.sticksLost());
        b.phoneState = new FcTelemetry.PhoneState(0, 1, OsdRenderer.STICKS_LOST_ALARM_MS + 1, 2, NOW - 100);
        assertTrue(b.build().sticksLost());
        b.phoneState = new FcTelemetry.PhoneState(0, 1, 40, 2, NOW - 3000);
        assertEquals(-1, b.build().sticksAgeMs(), "an old word: unknown");
    }

    @Test
    void aSpectatorSeesThePilotsOwnWarnings() {
        B b = new B();
        b.spectator = true;
        b.session = false;
        b.lagMs = 900; // never read when watching
        OsdRenderer.Data d = b.build();
        assertEquals("NOT FLYING - SPECTATOR", d.roleBanner());
        assertTrue(d.roleAlarm());
        assertTrue(d.rcLive(), "no sticks of its own to lose");
        assertNull(d.banner());
        b.watchOnly = true;
        assertEquals("SPECTATOR", b.build().roleBanner());
        assertFalse(b.build().roleAlarm());

        short[] pilots = {1000, 2000, 1500, 1500};
        b.pilotView = new ControlLink.PilotView(false, false, true, true, true, true, "THROTTLE LOW", "RADIO FROZEN", "PHONE NOT ON TAILSCALE",
                pilots, "MobileAlpha", false);
        d = b.build();
        assertFalse(d.linkReady());
        assertTrue(d.radioFrozen());
        assertEquals("RADIO FROZEN", d.banner());
        assertEquals("PHONE NOT ON TAILSCALE", d.linkHint());
        assertEquals("PILOT LINK LOST", d.lostText());
        assertSame(pilots, d.channels());

        b.pilotLeft = "MobileAlpha";
        d = b.build();
        assertEquals("PILOT DISCONNECTED", d.lostText());
        assertEquals("PILOT ENDED ITS LINK TO MOBILEALPHA - NOT A LINK LOSS", d.linkHint());
    }
}
