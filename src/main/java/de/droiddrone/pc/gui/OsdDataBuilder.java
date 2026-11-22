package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.setup.Tailscale;
import de.droiddrone.pc.video.VideoPipeline;

import java.util.Locale;

/**
 * What the HUD shows, out of MainWindow: the warnings and banners worked out from what the link, the session and the
 * video say right now. Pure: MainWindow reads those into {@link In} on the Swing thread, {@link #build} decides.
 */
final class OsdDataBuilder {
    /** A stream (re)start may take this long to show its first picture before VIDEO FROZEN says so. */
    static final long VIDEO_RESTART_GRACE_MS = 2500;
    /** How long "LINK BACK - ARMED - STICKS LIVE" stays on the HUD. */
    static final long RESUME_BANNER_MS = 3000;
    /** No new picture for this long while the stream runs: VIDEO FROZEN. */
    static final long VIDEO_FROZEN_MS = 700;
    /** Armed and not a single bit of the radio's input changed for this long: RADIO FROZEN? */
    static final long RADIO_FROZEN_MS = 5000;
    /** The sticks reach the phone this much later than the link's usual (phone app pc.31): STICKS LATE. */
    static final int STICKS_LATE_MS = 300;

    private OsdDataBuilder() {
    }

    /**
     * The link's state as read now.
     * @param ready the link READY (SPECTATING when watching)
     * @param hasStream a video pipeline with a stream
     * @param sincePicture ms since the last picture shown; sinceStreamStart since the stream (re)started, -1 unknown
     * @param status the FC's last status, or null; phoneState the phone's word on the sticks, or null
     * @param session a pilot's RcSession is there (false when watching); the next five are its figures
     * @param standbyWanted this PC asked the phone for STANDBY
     * @param lagMs the phone's worst stick lateness in the last second (-1 unknown or watching)
     * @param linkHint the line under LINK LOST from this PC's view (Tailscale, sticks still sent), or null
     * @param pilotView watching: the pilot PC's own warnings, or null; pilotLeft its BYE (null none, "" no phone named)
     * @param stats the video's figures, or null
     * @param now MonoClock
     * @param awaitingWake 1.0.64: PILOT UAV waits for a sleeping phone (the link not READY since)
     * @param wakeInMs until its next check-in; -1: it listens all along, or unknown
     * @param tier the video tier now, or null (watching: the pilot PC's isn't known here)
     * @param linkHistory I-011: the link's last 30 s (LINK HISTORY), handed on as it is; null none
     */
    record In(boolean spectator, boolean watchOnly, boolean ready, boolean hasStream, long sincePicture, long sinceStreamStart,
              FcTelemetry.Status status, FcTelemetry.PhoneState phoneState, boolean session, boolean rcEnabled,
              long msSinceInputChanged, RcSession.Banner resume, boolean frozenStopped, boolean armSwitchUnknown,
              String rcWaiting, short[] channels, boolean standbyWanted, int lagMs, String linkHint,
              ControlLink.PilotView pilotView, String pilotLeft, int ping, boolean fcLost, FcTelemetry telemetry,
              VideoPipeline.Stats stats, boolean invertPitch, boolean recording, boolean spectatorPaused,
              boolean linkStalled, boolean relayed, long now, boolean awaitingWake, long wakeInMs, ControlLink.Tier tier,
              boolean neverUp, LinkHistory linkHistory) {
        /** Up to 1.0.71: no link history. */
        In(boolean spectator, boolean watchOnly, boolean ready, boolean hasStream, long sincePicture, long sinceStreamStart,
           FcTelemetry.Status status, FcTelemetry.PhoneState phoneState, boolean session, boolean rcEnabled,
           long msSinceInputChanged, RcSession.Banner resume, boolean frozenStopped, boolean armSwitchUnknown,
           String rcWaiting, short[] channels, boolean standbyWanted, int lagMs, String linkHint,
           ControlLink.PilotView pilotView, String pilotLeft, int ping, boolean fcLost, FcTelemetry telemetry,
           VideoPipeline.Stats stats, boolean invertPitch, boolean recording, boolean spectatorPaused,
           boolean linkStalled, boolean relayed, long now, boolean awaitingWake, long wakeInMs, ControlLink.Tier tier,
           boolean neverUp) {
            this(spectator, watchOnly, ready, hasStream, sincePicture, sinceStreamStart, status, phoneState, session, rcEnabled,
                    msSinceInputChanged, resume, frozenStopped, armSwitchUnknown, rcWaiting, channels, standbyWanted, lagMs, linkHint,
                    pilotView, pilotLeft, ping, fcLost, telemetry, stats, invertPitch, recording, spectatorPaused, linkStalled, relayed,
                    now, awaitingWake, wakeInMs, tier, neverUp, null);
        }

        /** Up to F-019: the link of this connection taken as up before. */
        In(boolean spectator, boolean watchOnly, boolean ready, boolean hasStream, long sincePicture, long sinceStreamStart,
           FcTelemetry.Status status, FcTelemetry.PhoneState phoneState, boolean session, boolean rcEnabled,
           long msSinceInputChanged, RcSession.Banner resume, boolean frozenStopped, boolean armSwitchUnknown,
           String rcWaiting, short[] channels, boolean standbyWanted, int lagMs, String linkHint,
           ControlLink.PilotView pilotView, String pilotLeft, int ping, boolean fcLost, FcTelemetry telemetry,
           VideoPipeline.Stats stats, boolean invertPitch, boolean recording, boolean spectatorPaused,
           boolean linkStalled, boolean relayed, long now, boolean awaitingWake, long wakeInMs, ControlLink.Tier tier) {
            this(spectator, watchOnly, ready, hasStream, sincePicture, sinceStreamStart, status, phoneState, session, rcEnabled,
                    msSinceInputChanged, resume, frozenStopped, armSwitchUnknown, rcWaiting, channels, standbyWanted, lagMs, linkHint,
                    pilotView, pilotLeft, ping, fcLost, telemetry, stats, invertPitch, recording, spectatorPaused, linkStalled, relayed,
                    now, awaitingWake, wakeInMs, tier, false);
        }

        /** Up to 1.0.63: nobody waited for, no tier. */
        In(boolean spectator, boolean watchOnly, boolean ready, boolean hasStream, long sincePicture, long sinceStreamStart,
           FcTelemetry.Status status, FcTelemetry.PhoneState phoneState, boolean session, boolean rcEnabled,
           long msSinceInputChanged, RcSession.Banner resume, boolean frozenStopped, boolean armSwitchUnknown,
           String rcWaiting, short[] channels, boolean standbyWanted, int lagMs, String linkHint,
           ControlLink.PilotView pilotView, String pilotLeft, int ping, boolean fcLost, FcTelemetry telemetry,
           VideoPipeline.Stats stats, boolean invertPitch, boolean recording, boolean spectatorPaused,
           boolean linkStalled, boolean relayed, long now) {
            this(spectator, watchOnly, ready, hasStream, sincePicture, sinceStreamStart, status, phoneState, session, rcEnabled,
                    msSinceInputChanged, resume, frozenStopped, armSwitchUnknown, rcWaiting, channels, standbyWanted, lagMs, linkHint,
                    pilotView, pilotLeft, ping, fcLost, telemetry, stats, invertPitch, recording, spectatorPaused, linkStalled, relayed,
                    now, false, -1, null, false);
        }
    }

    /**
     * 1.0.64: what the OSD says while a sleeping phone is waited for (it blinked LINK LOST in red through the whole
     * countdown): "WAKING - 4:12" to its next check-in, "WAKING" when it listens all along or the time isn't known.
     */
    static String wakingText(long wakeInMs) {
        if (wakeInMs < 0) return "WAKING";
        long s = Math.max(1, (wakeInMs + 999) / 1000); // as the button's REGAINING CONTROL IN counts
        return "WAKING - " + s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** 1.0.64: the tier's tag by the video's figures - the menu's words (ROBUST, SURVIVAL); nothing at NORMAL or SHARP. */
    static String tierTag(ControlLink.Tier tier) {
        if (tier == ControlLink.Tier.ROBUST) return "ROBUST";
        if (tier == ControlLink.Tier.SURVIVAL) return "SURVIVAL";
        return null;
    }

    /** F-019: in LINK LOST's place until the connection's first link. */
    static final String CONNECTING = "CONNECTING";

    /** I-004: PILOT UAV unanswered this long: the usual reasons under CONNECTING. */
    static final long UNANSWERED_MS = 8000;
    static final String UNANSWERED = "DRAUPNE FLIGHT OPEN? TAILSCALE? KEY?";

    /**
     * I-004: the line under CONNECTING while PILOT UAV has waited more than UNANSWERED_MS for a phone that has not
     * answered once - the log's questions, short; null as soon as the phone answers (CONNECTING is the link's state
     * while the phone is unheard), once a link was up (then it is lost, not unanswered) and for a sleeper waited for.
     */
    static String unansweredHint(boolean everUp, ControlLink.State state, boolean awaitingWake, long waitedMs) {
        return !everUp && !awaitingWake && state == ControlLink.State.CONNECTING && waitedMs > UNANSWERED_MS ? UNANSWERED : null;
    }

    /**
     * The steady line under CONNECTING / LINK LOST: why the link is down, as far as this PC can tell. Tailscale's word
     * on the phone first (offline, not on the network, this PC off it or without it); for a pilot's wait unanswered for
     * 8 s, Q-009's "closed, or another key?" when the phone's Tailscale answers and nothing came from the phone, else
     * I-004's questions; else that the sticks still go out.
     * @param tailscale this PC's Tailscale on the phone (ONLINE while a link is up); null when not asked (a virtual
     *                  phone, no address)
     * @param wait the pilot's connection; null when watching or not connected
     */
    static String linkHint(Tailscale.PhoneState tailscale, PhoneSilence.Wait wait, boolean sticksSent) {
        if (tailscale != null && tailscale.isProblem()) {
            return switch (tailscale) {
                case OFFLINE -> "PHONE NOT ON TAILSCALE";
                case NOT_FOUND -> "PHONE ADDRESS NOT ON YOUR TAILSCALE";
                case PC_OFF -> "TAILSCALE ON THIS PC IS OFF";
                case NOT_INSTALLED -> "TAILSCALE NOT INSTALLED ON THIS PC";
                case ONLINE -> null;
            };
        }
        String ask = wait == null ? null : unansweredHint(wait.everUp(), wait.state(), wait.awaitingWake(), wait.waitedMs());
        if (ask != null) return PhoneSilence.shows(tailscale, wait) ? PhoneSilence.HINT : ask;
        return sticksSent ? "STICKS STILL SENT - RECONNECTING" : null;
    }

    static OsdRenderer.Data build(In in) {
        boolean spectator = in.spectator();
        long now = in.now();
        long sincePicture = in.sincePicture(), sinceStreamStart = in.sinceStreamStart();
        // a stream (re)start gets a moment for its first picture (a size switch takes up to ~2 s) - but no longer
        boolean videoFrozen = in.hasStream() && sincePicture > VIDEO_FROZEN_MS
                && (sinceStreamStart < 0 || sinceStreamStart > VIDEO_RESTART_GRACE_MS || sincePicture < sinceStreamStart);
        FcTelemetry.Status st = in.status();
        boolean armed = st != null && st.armed() && now - st.receivedAt() < 2000;
        boolean radioFrozen = armed && in.session() && in.rcEnabled() && in.msSinceInputChanged() > RADIO_FROZEN_MS;
        String banner = !spectator ? null : in.watchOnly() ? "SPECTATOR" : "NOT FLYING - SPECTATOR";
        // the phone's own word on the sticks (pc.19+): how long ago its last stick frame reached the FC
        FcTelemetry.PhoneState ps = in.phoneState();
        int sticksAge = ps == null || now - ps.receivedAt() > 2500 || ps.rcWriteAgeMs() < 0 ? -1
                : (int) Math.min(65_534, ps.rcWriteAgeMs() + (now - ps.receivedAt()));
        RcSession.Banner resume = in.resume();
        String liveBanner = resume != null && now - resume.at() < RESUME_BANNER_MS ? resume.text() : null;
        if (liveBanner == null && !spectator && in.standbyWanted()) liveBanner = "STANDBY";
        if (liveBanner == null && in.session() && in.frozenStopped()) liveBanner = "RADIO FROZEN - STICKS STOPPED";
        int lagMs = spectator ? -1 : in.lagMs();
        if (liveBanner == null && lagMs > STICKS_LATE_MS) {
            liveBanner = String.format(Locale.ROOT, "STICKS %.1f S LATE", lagMs / 1000.0);
        }
        boolean linkReady = in.ready(), rcLive = spectator || (in.session() && in.rcEnabled());
        boolean stalled = in.linkStalled(), relayed = in.relayed(), armUnknown = in.session() && in.armSwitchUnknown();
        String rcWaiting = in.session() ? in.rcWaiting() : null, hint = in.linkHint();
        short[] channels = in.session() ? in.channels() : null;
        // watching: the pilot PC's own warnings, as its OSD shows them (1.0.41: RC OFF - NO RADIO there, nothing here)
        ControlLink.PilotView pv = spectator ? in.pilotView() : null;
        if (pv != null) {
            linkReady = in.ready() && pv.linkReady();
            rcLive = pv.rcLive();
            rcWaiting = pv.rcWaiting();
            radioFrozen = pv.radioFrozen();
            armUnknown = pv.armSwitchUnknown();
            relayed = pv.relayed();
            stalled = pv.linkStalled();
            if (pv.banner() != null) liveBanner = pv.banner();
            if (!pv.linkReady()) hint = pv.linkHint();
            channels = pv.channels();
        }
        // watching: say what kind of loss - the pilot gone on purpose, or its own link to the drone lost (1.0.41)
        String lostText = null;
        String left = spectator ? in.pilotLeft() : null;
        if (left != null) {
            linkReady = false;
            lostText = "PILOT DISCONNECTED";
            hint = "PILOT ENDED ITS LINK" + (left.isEmpty() ? "" : " TO " + left.toUpperCase(Locale.ROOT)) + " - NOT A LINK LOSS";
        } else if (pv != null && !pv.linkReady()) {
            lostText = "PILOT LINK LOST";
        }
        // 1.0.64: the pilot's own wait for a sleeping phone - no LINK LOST, no "reconnecting" under it
        String waking = !spectator && in.awaitingWake() && !linkReady ? wakingText(in.wakeInMs()) : null;
        if (waking != null) hint = null;
        // F-019, the pilot's rule (2026-11-20): before the connection's first link it is CONNECTING, steady and white,
        // with its reason under it - LINK LOST (red, blinking, the alarm) only once a link was up
        else if (!spectator && in.neverUp() && !linkReady) waking = CONNECTING;
        return new OsdRenderer.Data(true, linkReady, in.ping(), rcLive,
                in.fcLost(), in.telemetry(), in.stats(), in.invertPitch(),
                hint, channels, false, stalled, videoFrozen, radioFrozen,
                rcWaiting, in.recording(), banner, spectator && !in.watchOnly(),
                sticksAge, armUnknown, liveBanner, spectator && in.spectatorPaused(), relayed,
                ps != null && now - ps.receivedAt() <= 2500 && ps.rcWriteAgeMs() > OsdRenderer.STICKS_LOST_ALARM_MS, lostText,
                waking, spectator || !in.ready() ? null : tierTag(in.tier()), null, in.linkHistory());
    }
}
