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
        boolean awaitingWake;
        long wakeInMs = -1;
        ControlLink.Tier tier = ControlLink.Tier.NORMAL;
        boolean neverUp;

        OsdRenderer.Data build() {
            return OsdDataBuilder.build(new OsdDataBuilder.In(spectator, watchOnly, ready, hasStream, sincePicture, sinceStreamStart,
                    status, phoneState, session, rcEnabled, msSinceInput, resume, frozenStopped, armUnknown, rcWaiting, channels,
                    standby, lagMs, hint, pilotView, pilotLeft, 45, false, null, null, false, false, false, false, false, NOW,
                    awaitingWake, wakeInMs, tier, neverUp));
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

    /**
     * 1.0.64: REGAINING CONTROL IN 4:12 on the button, and the OSD blinked a red LINK LOST over it with "STICKS STILL
     * SENT - RECONNECTING" under: waiting for a sleeper says WAKING and its countdown, and nothing about a loss.
     */
    @Test
    void waitingForASleeperSaysWakingNotLinkLost() {
        B b = new B();
        b.ready = false;
        b.hasStream = false;
        b.hint = "STICKS STILL SENT - RECONNECTING";
        OsdRenderer.Data lost = b.build();
        assertNull(lost.waking(), "a link lost in flight is still a link lost");
        assertFalse(lost.wakingNow());
        assertEquals("STICKS STILL SENT - RECONNECTING", lost.linkHint());

        b.awaitingWake = true;
        b.wakeInMs = 4 * 60_000 + 11_200;
        OsdRenderer.Data d = b.build();
        assertEquals("WAKING - 4:12", d.waking(), "as the button counts");
        assertTrue(d.wakingNow());
        assertNull(d.linkHint(), "no RECONNECTING under it");
        assertNull(d.lostText());
        b.wakeInMs = -1; // it listens all along, or its check-in isn't known
        assertEquals("WAKING", b.build().waking());
        b.wakeInMs = 0;
        assertEquals("WAKING - 0:01", b.build().waking());

        b.ready = true; // it woke: the link is up, nothing is waited for
        assertNull(b.build().waking());
        b.ready = false;
        b.spectator = true; // watching: never this PC's wait
        b.session = false;
        assertNull(b.build().waking());
    }

    /** 1.0.64: waiting for a sleeper puts no red frame round the picture; a lost link still does. */
    @Test
    void wakingDrawsNoRedFrame() {
        B b = new B();
        b.ready = false;
        b.hasStream = false;
        assertTrue(redFrame(b.build()), "LINK LOST: the red frame");
        b.awaitingWake = true;
        b.wakeInMs = 90_000;
        assertFalse(redFrame(b.build()), "WAKING: none");
    }

    /**
     * F-019, the pilot's rule (2026-11-20): right after PILOT UAV, before the link was ever up, the OSD blinked a red
     * LINK LOST for as long as the phone took to answer. Now CONNECTING, steady, its reason under it; LINK LOST only
     * once a link of this connection was up.
     */
    @Test
    void beforeTheFirstLinkItIsConnectingNotLinkLost() {
        B b = new B();
        b.ready = false;
        b.hasStream = false;
        b.hint = "WAITING FOR THE PHONE - ON TAILSCALE?";
        b.neverUp = true;
        OsdRenderer.Data d = b.build();
        assertEquals("CONNECTING", d.waking());
        assertTrue(d.connectingNow());
        assertTrue(d.wakingNow(), "drawn where LINK LOST would blink, steady and white");
        assertEquals("WAITING FOR THE PHONE - ON TAILSCALE?", d.linkHint(), "its reason stays under it");
        assertFalse(redFrame(d), "no red frame round the picture");

        b.neverUp = false; // a link was up: now it is lost
        OsdRenderer.Data lost = b.build();
        assertNull(lost.waking());
        assertFalse(lost.connectingNow());
        assertTrue(redFrame(lost), "LINK LOST and its red frame");

        b.neverUp = true;
        b.ready = true; // up: nothing to say
        assertNull(b.build().waking());
        b.ready = false;
        b.awaitingWake = true; // waking a sleeper comes first: WAKING with its time
        b.wakeInMs = 30_000;
        assertEquals("WAKING - 0:30", b.build().waking());
        b.awaitingWake = false;
        b.spectator = true; // watching: the pilot's warnings, never this
        b.session = false;
        assertNull(b.build().waking());
    }

    /**
     * I-004 (the bench's fly, 1.0.71): CONNECTING on the video said nothing more while PILOT UAV waited for a phone that
     * never answered - the questions were in the log only. After 8 s they stand under CONNECTING, gone the moment the
     * phone answers.
     */
    @Test
    void aPhoneUnansweredFor8sGetsTheQuestionsUnderConnecting() {
        ControlLink.State connecting = ControlLink.State.CONNECTING;
        assertNull(OsdDataBuilder.unansweredHint(false, connecting, false, 7_999), "the first seconds: CONNECTING alone");
        assertEquals("DRAUPNE FLIGHT OPEN? TAILSCALE? KEY?", OsdDataBuilder.unansweredHint(false, connecting, false, 8_001));
        assertNull(OsdDataBuilder.unansweredHint(false, ControlLink.State.CONFIGURING, false, 30_000), "the phone answered: gone");
        assertNull(OsdDataBuilder.unansweredHint(false, ControlLink.State.READY, false, 30_000));
        assertNull(OsdDataBuilder.unansweredHint(true, connecting, false, 30_000), "a link was up: lost, not unanswered");
        assertNull(OsdDataBuilder.unansweredHint(false, connecting, true, 30_000), "a sleeper waited for: WAKING");

        B b = new B();
        b.ready = false;
        b.hasStream = false;
        b.neverUp = true;
        b.hint = OsdDataBuilder.UNANSWERED;
        OsdRenderer.Data d = b.build();
        assertEquals("CONNECTING", d.waking());
        assertEquals("DRAUPNE FLIGHT OPEN? TAILSCALE? KEY?", d.linkHint(), "under CONNECTING");
    }

    private static boolean redFrame(OsdRenderer.Data d) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, OsdLayout.defaults());
        g.dispose();
        java.awt.Color c = new java.awt.Color(img.getRGB(640, 4)); // in the frame's top edge
        return c.getRed() > 200 && c.getGreen() < 140 && c.getBlue() < 140;
    }

    /** 1.0.64: AUTO going to bad coverage or survival was a log line only - the tier is tagged by the video's figures. */
    @Test
    void theTierUnderNormalIsTagged() {
        B b = new B();
        assertNull(b.build().tierTag(), "NORMAL: nothing");
        b.tier = ControlLink.Tier.SHARP;
        assertNull(b.build().tierTag());
        b.tier = ControlLink.Tier.ROBUST;
        assertEquals("ROBUST", b.build().tierTag(), "the menu's word");
        b.tier = ControlLink.Tier.SURVIVAL;
        assertEquals("SURVIVAL", b.build().tierTag());
        b.ready = false;
        assertNull(b.build().tierTag(), "no link: no tier to speak of");
        b.ready = true;
        b.spectator = true;
        b.session = false;
        assertNull(b.build().tierTag(), "watching: the pilot PC's tier isn't known here");
        b.tier = null;
        b.spectator = false;
        assertNull(b.build().tierTag());
    }

    /** The tag is drawn after the video's line, and changes nothing else of it. */
    @Test
    void theTierTagIsDrawnWithTheVideoLine() {
        OsdRenderer.Data sample = new OsdPreview().data();
        java.util.Map<OsdElement, java.awt.geom.Rectangle2D> plain = bounds(with(sample, null)), tagged = bounds(with(sample, "SURVIVAL"));
        assertNotNull(plain.get(OsdElement.VIDEO));
        assertTrue(tagged.get(OsdElement.VIDEO).getWidth() > plain.get(OsdElement.VIDEO).getWidth() + 30,
                "wider by the tag: " + plain.get(OsdElement.VIDEO).getWidth() + " -> " + tagged.get(OsdElement.VIDEO).getWidth());
        assertEquals(plain.get(OsdElement.PING), tagged.get(OsdElement.PING));
    }

    private static OsdRenderer.Data with(OsdRenderer.Data s, String tier) {
        return new OsdRenderer.Data(true, true, 42, true, false, s.telemetry(), s.video(), false, null, s.channels(), true, false, false, false,
                null, false, null, false, -1, false, null, false, false, false, null, null, tier);
    }

    /**
     * F-043: the warnings lay on the pitch ladder's 10 rung, and the ladder moves with the pitch. Each warning line now
     * has a dark ground: on a white picture the whole of the warnings' area is dark, not only the letters.
     */
    @Test
    void theWarningsStandOnADarkGround() {
        B b = new B();
        b.ready = false;
        b.hasStream = false;
        b.neverUp = true; // CONNECTING and its reason: both steady (a blinking line is not drawn every frame)
        b.hint = "WAITING FOR THE PHONE";
        OsdRenderer.Data d = b.build();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 1280, 720);
        java.util.Map<OsdElement, java.awt.geom.Rectangle2D> bounds = new java.util.EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, OsdLayout.defaults(), bounds);
        g.dispose();
        java.awt.Rectangle r = bounds.get(OsdElement.WARNINGS).getBounds().intersection(new java.awt.Rectangle(0, 0, 1280, 720));
        assertTrue(r.width > 100 && r.height > 30, "the warnings are drawn: " + r);
        // each row that has text in it: from its first dark pixel to its last, the white picture must not show through
        // between the letters (bare text on white leaves most of that light: the gaps between letters and words)
        int rows = 0, ground = 0, span = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            int first = -1, last = -1;
            for (int x = r.x; x < r.x + r.width; x++) if (lum(img, x, y) < 60) { // a letter's black outline
                if (first < 0) first = x;
                last = x;
            }
            if (first < 0 || last - first < 40) continue;
            rows++;
            for (int x = first; x <= last; x++) {
                span++;
                int l = lum(img, x, y);
                if (l >= 100 && l <= 160) ground++; // the white picture darkened: neither letter, outline nor picture
            }
        }
        assertTrue(rows > 10, "rows of text found: " + rows);
        assertTrue(ground > span * 0.2, "between the letters the dark ground, not the picture: " + ground + " of " + span + " pixels");
    }

    private static int lum(java.awt.image.BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return ((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10;
    }


    private static java.util.Map<OsdElement, java.awt.geom.Rectangle2D> bounds(OsdRenderer.Data d) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        java.util.Map<OsdElement, java.awt.geom.Rectangle2D> bounds = new java.util.EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, OsdLayout.defaults(), bounds);
        g.dispose();
        return bounds;
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
