package de.droiddrone.pc.video;

/**
 * How often to ask the phone for a key frame. A request is answered by the next key frame; when that one comes
 * damaged too, asking again at once only adds bursts to a link that is dropping them, so the spacing doubles
 * (1.5 -> 3 -> 6 s) until a key frame comes whole. In bad coverage mode the phone sends every key frame twice and
 * the assembler waits for the second copy, so a damaged one there means both copies failed: it starts at 3 s.
 */
final class KeyFrameBackoff {
    static final long BASE_MS = 1500;
    static final long ROBUST_BASE_MS = 3000;
    static final long MAX_MS = 6000;
    private long spacing = BASE_MS;
    private long lastSentAt = Long.MIN_VALUE / 2;
    /** A request went out and no key frame has come since: the next one answers it. */
    private boolean outstanding;
    private boolean robust;

    synchronized void setRobust(boolean on) {
        if (robust == on) return;
        robust = on;
        spacing = Math.max(base(), Math.min(spacing, MAX_MS));
    }

    private long base() {
        return robust ? ROBUST_BASE_MS : BASE_MS;
    }

    /** True to send a request now (and it counts as sent). */
    synchronized boolean shouldSend(long now) {
        if (now - lastSentAt < spacing) return false;
        lastSentAt = now;
        outstanding = true;
        return true;
    }

    synchronized void onKeyFrame(boolean damaged) {
        if (!damaged) spacing = base();
        else if (outstanding) spacing = Math.min(MAX_MS, spacing * 2);
        outstanding = false;
    }

    synchronized long spacingMs() {
        return spacing;
    }
}
