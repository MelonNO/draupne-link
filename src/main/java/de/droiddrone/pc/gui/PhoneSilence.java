package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.setup.Tailscale;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Q-009 (the pilot's choice, PC only - the phone stays silent to a wrong key, on purpose): PILOT UAV pressed and not one
 * packet back from the phone. When this PC's Tailscale has the phone online and the phone's Tailscale answers a ping,
 * the way to the phone works: DRAUPNE Flight on it is closed, or it has another key. The connecting screen says so under
 * CONNECTING, in I-004's place, after 8 s. One `tailscale ping` goes out from {@link #PING_FROM_MS} of silence on (again
 * every {@link #PING_EVERY_MS} until one is answered), on the phone check's background thread: the connect itself goes
 * on as it is, and nothing else goes to the phone. A phone that answered anything at all (a session, who flies, a
 * sleeping phone's check-in) never gets it: the PC has its own words for those.
 */
final class PhoneSilence {
    /** Under CONNECTING (the OSD's capitals): the pilot's words. */
    static final String HINT = "PHONE ONLINE, NO ANSWER: DRAUPNE FLIGHT CLOSED, OR ANOTHER KEY? (MENU > LINK)";
    /** Not one packet from the phone for this long: Tailscale's ping is asked (a phone answers within a second, relayed too). */
    static final long PING_FROM_MS = 2000;
    /** A ping that got no answer is asked again this long after it went. */
    static final long PING_EVERY_MS = 4000;
    /**
     * The phone's Tailscale answered this long ago and still nothing from the phone. WireGuard tries its handshake again
     * 5 s after one that got no answer: a way that answers Tailscale's ping carries the link's Connects (every 500 ms)
     * within this. A link that simply comes up late over a relay gets its first answer inside it - no hint.
     */
    static final long PONG_GRACE_MS = 6000;

    /**
     * A pilot's connection while it waits for its first link (I-004, Q-009).
     * @param waitedMs since PILOT UAV (the connection's opening)
     * @param heard anything at all came from the phone on this connection
     * @param pongAgoMs how long ago the phone's Tailscale answered a ping asked in this wait; -1 none
     */
    record Wait(boolean everUp, ControlLink.State state, boolean awaitingWake, long waitedMs, boolean heard, long pongAgoMs) {
        /** Still waiting for the phone: CONNECTING, never up, no sleeper waited for (WAKING), nothing heard. */
        boolean silent() {
            return !everUp && !awaitingWake && state == ControlLink.State.CONNECTING && !heard;
        }
    }

    /** The hint instead of I-004's questions: online, its Tailscale answered, and silent for 8 s and the grace since. */
    static boolean shows(Tailscale.PhoneState tailscale, Wait w) {
        return tailscale == Tailscale.PhoneState.ONLINE && w != null && w.silent()
                && w.waitedMs() > OsdDataBuilder.UNANSWERED_MS && w.pongAgoMs() >= PONG_GRACE_MS;
    }

    /** Whether Tailscale's ping is asked now; sinceAskedMs since the last one went (Long.MAX_VALUE: none yet). */
    static boolean pingDue(Tailscale.PhoneState tailscale, Wait w, long sinceAskedMs) {
        return tailscale == Tailscale.PhoneState.ONLINE && w != null && w.silent() && w.pongAgoMs() < 0
                && w.waitedMs() >= PING_FROM_MS && sinceAskedMs >= PING_EVERY_MS;
    }

    private final Function<String, Tailscale.PingResult> ping;
    private final Executor background;
    private final LongSupplier clock;
    private final AtomicBoolean asking = new AtomicBoolean();
    /** The connection the two below are about (Swing thread). */
    private Object of;
    private long askedAt = -1;
    private boolean told;

    /** The phone's Tailscale answered: for which connection, when (the clock's ms), in how many ms (-1: a second or more). */
    private record Pong(Object of, long at, int ms) {}

    private volatile Pong pong;

    /**
     * @param ping Tailscale's quick ping (Tailscale::pingOnce); background where it runs (the phone check's thread);
     *             clock MonoClock::ms
     */
    PhoneSilence(Function<String, Tailscale.PingResult> ping, Executor background, LongSupplier clock) {
        this.ping = ping;
        this.background = background;
        this.clock = clock;
    }

    /** How long ago the phone's Tailscale answered a ping asked for this connection; -1 none. */
    long pongAgo(Object connection) {
        Pong p = pong;
        return p == null || p.of() != connection ? -1 : Math.max(0, clock.getAsLong() - p.at());
    }

    /**
     * Swing thread, at the LINK card's slow round. Asks Tailscale's ping when it is due (one at a time, on the background
     * thread). True the one time this connection's hint first shows: the log's line.
     * @param connection the connection now (null none); ip the phone's address; w null when watching
     */
    boolean follow(Object connection, String ip, Tailscale.PhoneState tailscale, Wait w) {
        if (connection != of) {
            of = connection;
            askedAt = -1;
            told = false;
        }
        if (connection == null || w == null) return false;
        long now = clock.getAsLong();
        if (pingDue(tailscale, w, askedAt < 0 ? Long.MAX_VALUE : now - askedAt) && asking.compareAndSet(false, true)) {
            askedAt = now;
            try {
                background.execute(() -> {
                    try {
                        Tailscale.PingResult r = ping.apply(ip);
                        if (r != null && r.reached()) pong = new Pong(connection, clock.getAsLong(), r.bestMs());
                    } finally {
                        asking.set(false);
                    }
                });
            } catch (RuntimeException e) { // the window closing: its background thread is gone
                asking.set(false);
            }
        }
        if (told || !shows(tailscale, w)) return false;
        told = true;
        return true;
    }

    /** The log's line as the hint first shows, in the pilot's words, with the ping's time. */
    String logLine(Object connection) {
        Pong p = pong;
        int ms = p == null || p.of() != connection ? -1 : p.ms();
        return "Phone online (Tailscale ping " + (ms >= 0 ? ms + " ms" : "1 s or more")
                + "), no answer: DRAUPNE Flight closed, or another key? (MENU > LINK)";
    }
}
