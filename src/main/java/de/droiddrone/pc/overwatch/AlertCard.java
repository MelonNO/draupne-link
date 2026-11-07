package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchEvent;

/**
 * One drone's overwatch alert on this PC (1.0.47), the pilot's rules: a new event opens the card in the map window
 * (live preview, CONNECT, DISMISS). DISMISS closes it and stops the preview; the drone stays silent until it reports
 * the motion ended - or {@link #DISMISS_CAP_MS} passed, when it alerts again. Not answered: when the motion ends the
 * card keeps its last picture {@link #SHRINK_AFTER_MS}, then shrinks to a badge until closed. An event heard of
 * nothing for {@link #LOST_MS} counts as ended (the phone died, or went out of reach). Pure: times are passed in.
 */
public final class AlertCard {
    public static final long SHRINK_AFTER_MS = 90_000;
    public static final long DISMISS_CAP_MS = OverwatchEvent.DISMISS_CAP_MS;
    public static final long LOST_MS = 120_000;

    public enum Shown { HIDDEN, CARD, BADGE }

    private int event = -1;
    private int alertNo = -1;
    private long alertAt, heardAt;
    private long endedAt = -1, dismissedAt = -1;
    private boolean badgeClosed;

    /**
     * An ALERT came (resends too).
     * @return true when it is news - a new event, or the same one alerting again after a dismiss - so the caller
     * sounds it (unless this PC flies)
     */
    public boolean onAlert(int event, int alertNo, long now) {
        heardAt = now;
        if (event != this.event) {
            this.event = event;
            this.alertNo = alertNo;
            alertAt = now;
            endedAt = -1;
            dismissedAt = -1;
            badgeClosed = false;
            return true;
        }
        if (alertNo != this.alertNo) {
            this.alertNo = alertNo;
            alertAt = now;
            dismissedAt = -1;
            endedAt = -1;
            badgeClosed = false;
            return true;
        }
        return false;
    }

    /** Anything of this event came (a preview fragment, a clip chunk). */
    public void onHeard(long now) {
        heardAt = now;
    }

    /** MOTION ENDED. */
    public void onEnded(long now) {
        if (event >= 0 && endedAt < 0) endedAt = now;
    }

    public void dismiss(long now) {
        if (event >= 0) dismissedAt = now;
    }

    /** The badge's cross: gone until the next alert. */
    public void closeBadge() {
        badgeClosed = true;
    }

    /**
     * The motion ended: MOTION ENDED came, or nothing of it was heard for LOST_MS. A dismissed event is quiet on
     * purpose (no preview comes), so it counts as heard of until the dismiss's cap runs out.
     */
    public boolean ended(long now) {
        return event >= 0 && (endedAt >= 0 || now - lastSign() >= LOST_MS);
    }

    private long lastSign() {
        return dismissedAt >= 0 ? Math.max(heardAt, dismissedAt + DISMISS_CAP_MS) : heardAt;
    }

    /** When it ended (the phone's word, or the last sign of it), or -1. */
    public long endedAt(long now) {
        if (endedAt >= 0) return endedAt;
        return ended(now) ? lastSign() : -1;
    }

    public boolean dismissed(long now) {
        return dismissedAt >= 0 && now - dismissedAt < DISMISS_CAP_MS && !ended(now);
    }

    public Shown shown(long now) {
        if (event < 0) return Shown.HIDDEN;
        if (dismissedAt >= 0) return Shown.HIDDEN; // DISMISS closes it for this event; a new alert opens it again
        if (!ended(now)) return Shown.CARD;
        if (now - endedAt(now) < SHRINK_AFTER_MS) return Shown.CARD;
        return badgeClosed ? Shown.HIDDEN : Shown.BADGE;
    }

    /** The live preview is wanted: the card is open and the motion goes on. */
    public boolean previewWanted(long now) {
        return shown(now) == Shown.CARD && !ended(now);
    }

    public int event() {
        return event;
    }

    public long alertAt() {
        return alertAt;
    }
}
