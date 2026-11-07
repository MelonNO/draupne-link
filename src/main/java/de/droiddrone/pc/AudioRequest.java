package de.droiddrone.pc;

import de.droiddrone.common.PcAudio;

/**
 * The pilot PC's side of the live audio (1.0.45): what it asks the phone for, and when. Not muted, the sound is
 * wanted. MUTE silences this PC at once (LiveAudio); the phone goes on sending for STOP_AFTER_MUTED_MS, so an unmute
 * within that brings the sound straight back, and after it is asked to stop (data and battery saved) - an unmute then
 * starts it again in about half a second (the phone's next gate tick and its encoder's start). The request goes out at
 * every change and again every REPEAT_MS (the phone forgets an "on" after PcAudio.WANT_EXPIRES_MS). Thread-safe.
 */
final class AudioRequest {
    static final long STOP_AFTER_MUTED_MS = 10_000;
    static final long REPEAT_MS = 1000;

    private boolean muted;
    private long mutedAt;
    /** What went out last (null = nothing yet this session) and when. */
    private Boolean lastOn;
    private boolean lastInFlight;
    private long lastSentAt;

    synchronized void setMuted(boolean muted, long nowMs) {
        if (muted && !this.muted) mutedAt = nowMs;
        this.muted = muted;
    }

    synchronized boolean isMuted() {
        return muted;
    }

    /** The phone should send: not muted, or muted for less than STOP_AFTER_MUTED_MS. */
    synchronized boolean streamWanted(long nowMs) {
        return !muted || nowMs - mutedAt < STOP_AFTER_MUTED_MS;
    }

    /**
     * The AUDIO packet to send now, or null.
     * @param missingConfig frames come but their config (AudioInitialFrame) never did: the phone is asked for it
     */
    synchronized byte[] due(long nowMs, boolean missingConfig) {
        boolean on = streamWanted(nowMs);
        boolean inFlight = on && !muted;
        if (lastOn != null && lastOn == on && lastInFlight == inFlight && nowMs - lastSentAt < REPEAT_MS) return null;
        lastOn = on;
        lastInFlight = inFlight;
        lastSentAt = nowMs;
        // not muted: the pilot wants to hear it, in the air too (1.0.50 - the PC mutes by itself at every arming, so
        // unmuted while armed is always the pilot's own choice); muted but still within the 10 s: on the ground only
        return PcAudio.encode(on, PcAudio.DEFAULT_KBPS, on && missingConfig, inFlight);
    }

    /** A new session with the phone: the next request goes out at once. */
    synchronized void newSession() {
        lastOn = null;
    }
}
