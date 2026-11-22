package de.droiddrone.pc.fleet;

import de.droiddrone.common.SessionGate;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.OlderPcLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.71, the session id, end to end on loopback: a virtual drone (the phone's own SessionGate) and the PC's link with
 * a wire between them that holds given packets back. A reconnect is one session on both sides - also when Connects of
 * the try arrive late (the reconnect storm: with the clock's rule each made a session more) and when the answers come
 * late (an older try's answer starts nothing). An older phone app and an older PC meet what they met before, to the
 * byte. And the reconnect itself never arms a drone that Betaflight's failsafe disarmed.
 * <p>
 * The two reconnect tests end their cut on a Connect of their own choosing ({@link Cut}), not at a moment of the
 * clock: on a new try's first Connect the reconnect is one session on both sides; on a Connect of the try kept
 * through the cut it is two on the phone and one on the PC - the most F-001's rule lets a reconnect cost.
 */
class SessionIdEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;
    private volatile boolean sending;
    private volatile int armValue = 1000;
    private Thread sticks;
    private long droneStartedAt;
    /** The stream configs the PC got: each is a numbered packet the phone sent in answer to a numbered one of the PC's. */
    private final AtomicInteger videoConfigs = new AtomicInteger();

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    private PcConfig config() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "320");
        config.set("cameraHeight", "180");
        config.set(ControlLink.VIDEO_MODE_KEY, "NORMAL");
        return config;
    }

    private void start(boolean olderPc, boolean olderPhone) throws Exception {
        PcConfig config = config();
        droneStartedAt = System.currentTimeMillis();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.setOlderApp(olderPhone);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        link = olderPc ? new OlderPcLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0)
                : new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.setVideoListener(new ControlLink.VideoListener() {
            @Override
            public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                videoConfigs.incrementAndGet();
            }

            @Override
            public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
            }
        }, true);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY && link.isVideoConfigReceived(), 10_000, "the link READY, the stream's config", log);
        assertEquals(1, drone.sessions(), "one session to begin with");
        assertEquals(1, link.getSessionStarts());
    }

    /**
     * The cut: nothing passes either way until both sides have let their session go (4 s without a packet) - from
     * then on only a Connect brings the link back. (A shorter cut ends in the session of before: the first packets
     * to arrive again find it still up on both sides, and no Connect is answered at all.)
     */
    private void cutUntilBothSidesLetGo() throws Exception {
        cut = new Cut();
        wire.rule(cut);
        waitFor(() -> !drone.isConnected() && link.getState() != ControlLink.State.READY, 9000, "the phone's session timed out", log);
        Thread.sleep(1500); // the PC's own 4 s ran from the same moment: over by now
    }

    /** Where a cut ends: on which of the PC's Connects the wire opens again. */
    private enum End {NOT_YET, ON_A_NEW_TRY, ON_THE_TRY_IN_HAND}

    private Cut cut;

    /**
     * The cut as the wire keeps it: nothing passes either way while it watches the PC's Connects go by - which try
     * each belongs to (its id) and when that try's first one came. It ends on the Connect the test asks for
     * ({@link #endOn}): that Connect is the first packet through, and from it on everything passes, both ways.
     * <p>
     * Why its end is not left to the test's clock (a third session on the phone on the CI runner, 2026-11-21): F-001's
     * rule keeps a try's id while the phone is not heard and gives it up the moment the phone is heard again. When a
     * cut just stops, the phone's picture mostly reaches the PC first - a new try at once, and one session. But a
     * Connect of the old try that goes out in the few ms before the picture is there gets through as well: a session
     * for it, and one for the new try the PC begins a tick later, the first answer still on its way - the two sessions
     * the rule allows, with one start on the PC. Which of the two a run saw hung on where the test's own wait ended in
     * the PC's Connect pace (a Connect every 150 - 190 ms, the picture 0 - 50 ms after the wire was cleared). Ended on
     * a Connect of the test's choosing, each of the two is a test of its own and neither is left to the moment.
     */
    private final class Cut implements Wire.Rule {
        private final long cutAt = MonoClock.ms();
        private volatile End end = End.NOT_YET;
        private volatile boolean open;
        /** The try whose Connects are going by (the wire's thread to the phone): its id, and when its first one came. */
        private long tryId = SessionGate.NO_ID, trySince;
        /** The try whose Connect ended the cut and when it did, how long that try's answer is held on its way back, and until when. */
        private volatile long endedOn = SessionGate.NO_ID, endedAt, answerHeldMs, heldUntil;
        /** The tries that have reached the phone since the cut ended, in order. */
        final List<Long> reachedThePhone = new CopyOnWriteArrayList<>();
        /** When each try's first Connect came by, lost or not: when the PC began it. */
        private final Map<Long, Long> began = new ConcurrentHashMap<>();
        /** F-072: the next try waits for the answer to the one the cut ended on, which has come by or not; that next try's answers to lose. */
        private volatile boolean nextTryWaits, answered;
        private volatile int answersToLose;

        long began(long id) {
            return began.get(id);
        }

        /**
         * F-072's order of things, with {@link End#ON_THE_TRY_IN_HAND}: the PC's next try reaches the phone only once
         * the phone's answer to the try in hand is on the wire (the phone sends it only if it gets to it before a
         * newer try's Connect is there), and the phone's first answer to that next try is lost on its way back.
         */
        void theNextTrysAnswerIsLost() {
            nextTryWaits = true;
            answersToLose = 1;
        }

        /**
         * From now on the cut ends
         * <ul>
         * <li>{@link End#ON_A_NEW_TRY}: on the first Connect of the PC's next try - an id that has not gone by yet
         * (unheard, the PC keeps a try {@link SessionGate.Ids#UNHEARD_KEEP_MS}). That try has its whole time for its
         * answer - a second, with the phone heard; the answer takes 0.1 s - so the picture the PC hears with it ends
         * nothing;</li>
         * <li>{@link End#ON_THE_TRY_IN_HAND}: on a Connect of a try that has been going out for over a second (and
         * half a second more) - the try that hearing the phone again ends at once.</li>
         * </ul>
         * @param answerHeldMs the answer to that try is this long on its way back (0: as the rest)
         */
        void endOn(End end, long answerHeldMs) {
            this.answerHeldMs = answerHeldMs;
            this.end = end;
        }

        @Override
        public long decide(boolean toPhone, byte[] d, int len) {
            boolean connect = len > 0 && d[0] == UdpCommon.Connect;
            if (!toPhone) {
                if (!open) return Wire.Rule.LOST;
                if (!connect) return 0;
                long id = SessionGate.sessionId(d, len), held = id == endedOn ? answerHeldMs : 0;
                if (id == endedOn) answered = true;
                if (id != endedOn && answersToLose > 0) {
                    answersToLose--; // this thread's alone once the cut has ended
                    say("the phone's answer to try " + id + ", lost");
                    return Wire.Rule.LOST;
                }
                if (held > 0) heldUntil = MonoClock.ms() + held;
                say("the phone's answer to try " + id + (held > 0 ? ", held " + held + " ms" : ""));
                return held;
            }
            if (!connect) return open ? 0 : Wire.Rule.LOST;
            long id = SessionGate.sessionId(d, len), now = MonoClock.ms();
            boolean first = id != tryId;
            if (first) {
                tryId = id;
                trySince = now;
                began.put(id, now);
            }
            if (!open) {
                End e = end;
                boolean inHand = !first && now - trySince >= SessionGate.SAME_SESSION_MS + 500;
                if (e == End.ON_A_NEW_TRY && first || e == End.ON_THE_TRY_IN_HAND && inHand) {
                    endedOn = id;
                    endedAt = now;
                    open = true;
                    say("the cut ends on " + (first ? "the first Connect of try " + id : "a Connect of try " + id + ", " + (now - trySince) + " ms old"));
                }
            }
            if (!open || id != endedOn && nextTryWaits && !answered) return Wire.Rule.LOST;
            if (!reachedThePhone.contains(id)) {
                reachedThePhone.add(id);
                if (id != endedOn) say("the first Connect of try " + id + " to reach the phone, " + (now - began(id)) + " ms after the try began");
            }
            return 0;
        }

        private void say(String what) {
            log.add("wire, " + (MonoClock.ms() - cutAt) + " ms into the cut: " + what);
        }
    }

    /** The two sides count packets in step: a numbered request of the PC's is answered by a numbered packet of the phone's. */
    private void assertBothSidesAreInOneSession() throws Exception {
        int before = videoConfigs.get();
        long until = System.currentTimeMillis() + 6000;
        while (videoConfigs.get() == before && System.currentTimeMillis() < until) {
            link.requestVideoConfig(); // GetVideoConfig, at most once a second
            Thread.sleep(100);
        }
        assertTrue(videoConfigs.get() > before, "GetVideoConfig answered with the stream's config\n" + String.join("\n", log));
        assertEquals(ControlLink.State.READY, link.getState());
    }

    /** What the wire and the drone said, for a failure's message. */
    private String said() {
        return "\n" + String.join("\n", log);
    }

    /**
     * The link comes back as a try of the PC's begins - that try's first Connect is the first packet through, and the
     * phone's picture, heard with it, ends nothing: one session, both sides.
     */
    @Test
    void aReconnectIsOneSessionOnBothSides() throws Exception {
        start(false, false);
        cutUntilBothSidesLetGo();
        cut.endOn(End.ON_A_NEW_TRY, 0);
        waitFor(() -> link.getState() == ControlLink.State.READY && drone.sessions() >= 2, 12_000, "the link back", log);
        Thread.sleep(1500); // nothing more comes of the Connects that were in flight
        assertEquals(1, cut.reachedThePhone.size(), "one try of the PC's reached the phone: " + cut.reachedThePhone + said());
        assertEquals(2, drone.sessions(), "one new session on the phone" + said());
        assertEquals(2, link.getSessionStarts(), "one on the PC" + said());
        assertBothSidesAreInOneSession();
    }

    /**
     * The other end a cut can have, and the most a reconnect costs under F-001's rule ("one session when the link goes,
     * one when it is back"): the link comes back on a Connect of the try the PC kept through the cut, and that try's
     * answer is slow on its way back. The phone starts the try's session; the PC hears the phone's picture, gives the
     * try up at once - its answer may be what was lost - and begins the next, which the phone takes for a new session.
     * Two on the phone, never a third; one start on the PC, with the newer try's answer - the older try's, when it
     * comes, starts nothing (the phone sends it only if it gets to it before the next try's Connect is there).
     */
    @Test
    void aReconnectOnAConnectOfTheOldTryIsTwoSessionsOnThePhoneAndOneOnThePc() throws Exception {
        start(false, false);
        cutUntilBothSidesLetGo();
        cut.endOn(End.ON_THE_TRY_IN_HAND, 1000);
        waitFor(() -> link.getState() == ControlLink.State.READY && drone.sessions() >= 2, 12_000, "the link back", log);
        waitFor(() -> MonoClock.ms() >= cut.heldUntil, 5000, "the held answer is at the PC", log);
        Thread.sleep(1500); // and whatever it starts has started
        List<Long> tries = cut.reachedThePhone;
        assertEquals(2, tries.size(), "the try in hand and the one begun on hearing the phone: " + tries + said());
        assertEquals((tries.get(0) + 1) & 0xFFFFFFFFL, tries.get(1), "the PC's very next try");
        assertEquals(3, drone.sessions(), "a session for each of the two tries on the phone, none more" + said());
        assertEquals(2, link.getSessionStarts(), "one start on the PC: the older try's answer started nothing" + said());
        assertBothSidesAreInOneSession();
    }

    /**
     * F-072: the phone's answer to the try kept through a cut comes when the PC has gone on to the next - an older
     * try's answer, and those are counted for how slow the way is (on a slow way the tries are kept longer). This one
     * took 0.4 s from the Connect it answers; the try it belongs to began 3.5 s earlier, in the cut, and counted from
     * there the next try was kept 5 s. With that try's answer lost - the case a try's second is there for - the PC
     * begins the one after a second later, as F-001's rule has it, and LINK READY is there 1.25 s after the link came
     * back, not 5.3 s.
     */
    @Test
    void anAnswerAfterACutIsNotTakenForASlowWay() throws Exception {
        start(false, false);
        cutUntilBothSidesLetGo();
        cut.theNextTrysAnswerIsLost();
        cut.endOn(End.ON_THE_TRY_IN_HAND, 300);
        waitFor(() -> link.getState() == ControlLink.State.READY && drone.sessions() >= 2, 12_000, "the link back", log);
        long readyAfter = MonoClock.ms() - cut.endedAt;
        Thread.sleep(1500); // nothing more comes of the Connects that were in flight
        List<Long> tries = cut.reachedThePhone;
        assertEquals(3, tries.size(), "the try in hand, the one begun on hearing the phone, the one after its answer was lost: " + tries + said());
        assertEquals((tries.get(0) + 1) & 0xFFFFFFFFL, tries.get(1), "the PC's tries, one after the other");
        assertEquals((tries.get(1) + 1) & 0xFFFFFFFFL, tries.get(2));
        long kept = cut.began(tries.get(2)) - cut.began(tries.get(1));
        assertTrue(kept >= SessionGate.SAME_SESSION_MS - 100 && kept < 2 * SessionGate.SAME_SESSION_MS,
                "the try whose answer was lost was kept a second - the cut's length is no slow way: " + kept + " ms" + said());
        assertTrue(readyAfter < 3000, "LINK READY a second and a way there and back after the link came back, not five: " + readyAfter + " ms" + said());
        assertEquals(4, drone.sessions(), "a session on the phone for each of the three tries, none more" + said());
        assertEquals(2, link.getSessionStarts(), "one start on the PC" + said());
        assertBothSidesAreInOneSession();
    }

    /**
     * The storm: after the cut the first three Connects of the try are held up on their way (1.6 s) and the fourth
     * gets through at once - it starts the session, and the three arrive when that session is over a second old.
     */
    private void lateConnectsOfATry() throws Exception {
        cutUntilBothSidesLetGo();
        AtomicInteger connects = new AtomicInteger();
        long arrived = wire.toPhone(UdpCommon.Connect);
        wire.rule((toPhone, d, len) -> toPhone && len > 0 && d[0] == UdpCommon.Connect && connects.incrementAndGet() <= 3 ? 1600 : 0);
        waitFor(() -> link.getState() == ControlLink.State.READY, 8000, "the link back", log);
        waitFor(() -> wire.toPhone(UdpCommon.Connect) >= arrived + 4, 5000, "the held Connects have arrived", log);
        Thread.sleep(1500); // and whatever they start has started
    }

    @Test
    void lateConnectsOfATryAreOneSession() throws Exception {
        start(false, false);
        lateConnectsOfATry();
        assertEquals(2, drone.sessions(), "one new session on the phone, however late the try's Connects came\n" + String.join("\n", log));
        assertEquals(2, link.getSessionStarts(), "and one on the PC");
        assertBothSidesAreInOneSession();
    }

    /** The same wire under a phone app before pc.53 (sessions told by the clock): the storm as it was, and as it stays there. */
    @Test
    void lateConnectsUnderAnOlderPhoneAppAreASessionMore() throws Exception {
        start(false, true);
        lateConnectsOfATry();
        assertTrue(drone.sessions() >= 3, "a Connect over a second late is a new session by the clock: " + drone.sessions());
        assertTrue(link.getSessionStarts() >= 3, "and its answer starts the PC's side over, as before 1.0.71: " + link.getSessionStarts());
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link up all the same", log);
    }

    /**
     * The answers come late - 1.5 s behind their Connects, longer than a try is kept: by the time the first try's answer
     * is here the PC has gone on to the next. That answer starts nothing; the try in hand is kept until its own answer
     * has had its time, and that one starts the session - once.
     */
    @Test
    void lateAnswersOfAnOlderRoundStartNothing() throws Exception {
        start(false, false);
        cutUntilBothSidesLetGo();
        wire.keep(true);
        wire.rule((toPhone, d, len) -> !toPhone && len > 0 && d[0] == UdpCommon.Connect ? 1500 : 0);
        waitFor(() -> link.getState() == ControlLink.State.READY, 12_000, "the link back", log);
        Thread.sleep(2500); // every answer still on the wire has arrived
        long answers = wire.sentToPc().stream().filter(d -> d[0] == UdpCommon.Connect).count();
        long ids = wire.sentToPc().stream().filter(d -> d[0] == UdpCommon.Connect).map(d -> SessionGate.sessionId(d, d.length)).distinct().count();
        assertTrue(answers >= 2 && ids >= 2, "the phone answered more than one try: " + answers + " answers, " + ids + " ids");
        assertEquals(2, link.getSessionStarts(), "one session on the PC: the older tries' answers started nothing");
        assertEquals(1 + ids, drone.sessions(), "the phone: a session for each try that reached it, the last one the PC's");
        wire.clear();
        assertBothSidesAreInOneSession();
    }

    /**
     * The same late answers from a phone app before pc.53: it tells the sessions by the clock and sends each Connect
     * back as it came - no mark - so the PC takes every answer, as before 1.0.71: the session starts over with each.
     */
    @Test
    void lateAnswersOfAnOlderPhoneAppEachStartTheSessionOver() throws Exception {
        start(false, true);
        cutUntilBothSidesLetGo();
        wire.keep(true);
        wire.rule((toPhone, d, len) -> !toPhone && len > 0 && d[0] == UdpCommon.Connect ? 1500 : 0);
        waitFor(() -> link.getState() == ControlLink.State.READY, 12_000, "the link back", log);
        Thread.sleep(2500);
        long answers = wire.sentToPc().stream().filter(d -> d[0] == UdpCommon.Connect).count();
        assertTrue(answers >= 2, "the phone started a session a second, and answered each: " + answers);
        assertEquals(1 + answers, link.getSessionStarts(), "every answer taken");
        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "and the link up in the end, as it was", log);
    }

    /** A PC before 1.0.71: its Connect ends with the version, and the phone app of now answers it as it came. */
    @Test
    void anOlderPcIsAnsweredAsEver() throws Exception {
        PcConfig config = config();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        wire.keep(true);
        link = new OlderPcLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 10_000, "the link READY", log);
        byte[] connect166 = OlderPcLink.connect166(config.getKey());
        List<byte[]> sent = wire.sentToPhone().stream().filter(d -> d[0] == UdpCommon.Connect).toList();
        List<byte[]> answers = wire.sentToPc().stream().filter(d -> d[0] == UdpCommon.Connect).toList();
        assertFalse(sent.isEmpty());
        for (byte[] c : sent) assertArrayEquals(connect166, c, "1.0.66's Connect");
        assertEquals(1, answers.size(), "answered once");
        assertArrayEquals(connect166, answers.get(0), "with the Connect as it came: not a byte more");
        // and its reconnect is by the clock's rule: one session for the Connects of a second
        wire.keep(false);
        cutUntilBothSidesLetGo();
        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY && drone.sessions() >= 2, 8000, "the link back", log);
        Thread.sleep(1200);
        assertEquals(2, drone.sessions());
        assertEquals(2, link.getSessionStarts());
    }

    /** A phone app before pc.53 sends the longer Connect back as it came - no mark - and the PC takes that answer as ever. */
    @Test
    void anOlderPhoneAppTakesTheLongerConnect() throws Exception {
        PcConfig config = config();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.setOlderApp(true);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        wire.keep(true);
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 10_000, "the link READY", log);
        byte[] sent = wire.sentToPhone().stream().filter(d -> d[0] == UdpCommon.Connect).findFirst().orElseThrow();
        byte[] answer = wire.sentToPc().stream().filter(d -> d[0] == UdpCommon.Connect).findFirst().orElseThrow();
        assertEquals(OlderPcLink.connect166(config.getKey()).length + 5, sent.length, "the session id and its byte behind the version");
        assertArrayEquals(sent, answer, "sent back as it came");
        assertFalse(SessionGate.answeredById(answer, answer.length), "no mark: this phone tells sessions by the clock");
        assertEquals(1, link.getSessionStarts());
        // the phone app of now marks its answer, and changes nothing else in it
        link.close();
        drone.setOlderApp(false);
        int from = wire.sentToPc().size();
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 10_000, "a second link READY", log);
        List<byte[]> toPc = wire.sentToPc();
        byte[] marked = toPc.subList(from, toPc.size()).stream().filter(d -> d[0] == UdpCommon.Connect).findFirst().orElseThrow();
        assertTrue(SessionGate.answeredById(marked, marked.length));
        byte[] unmarked = marked.clone();
        unmarked[unmarked.length - 1] = 0;
        List<byte[]> toPhone = wire.sentToPhone();
        assertTrue(toPhone.stream().anyMatch(d -> Arrays.equals(d, unmarked)), "the Connect it answers, but for the mark");
    }

    /** The PC's stick frames, 50 a second: throttle low, AUX1 at armValue. */
    private void startSticks() {
        sending = true;
        sticks = new Thread(() -> {
            while (sending) {
                link.sendRc(new short[]{1500, 1500, 1500, 1000, (short) armValue, 1500, 1000, 1000});
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testSticks");
        sticks.setDaemon(true);
        sticks.start();
    }

    /**
     * Armed on the bench, the link cut for 3 s and for 6 s (Betaflight's failsafe disarms after 1.9 s), the arm switch
     * left on all the while. After 3 s both sides still hold the session and go on in it (a Connect that got through
     * first makes one new session, on both sides); after 6 s both have let go and the link comes back in one new
     * session. Either way the sticks flow again - and the drone stays disarmed until the switch has been off.
     */
    @Test
    void aLinkCutWithTheDroneArmedNeverArmsItAgainByItself() throws Exception {
        start(false, false);
        startSticks();
        waitFor(() -> drone.fc.hadSticks(), 3000, "the sticks reach the virtual FC", log);
        Thread.sleep(Math.max(0, FlightModel.BOOT_GRACE_MS + 700 - (System.currentTimeMillis() - droneStartedAt)));
        armValue = 1900;
        waitFor(() -> drone.fc.isArmed(), 4000, "armed", log);
        int sessions = 1, pcStarts = (int) link.getSessionStarts();
        for (long cutMs : new long[]{3000, 6000}) {
            if (!drone.fc.isArmed()) { // the second round: the switch off and on, as the pilot must
                armValue = 1000;
                Thread.sleep(600);
                armValue = 1900;
                waitFor(() -> drone.fc.isArmed(), 4000, "armed again by the switch", log);
            }
            long cutAt = System.currentTimeMillis();
            wire.rule((toPhone, d, len) -> Wire.Rule.LOST);
            waitFor(() -> !drone.fc.isArmed(), 4000, "the failsafe disarms", log);
            assertEquals("failsafe (drop)", drone.fc.lastDisarm());
            Thread.sleep(Math.max(0, cutMs - (System.currentTimeMillis() - cutAt)));
            wire.clear();
            waitFor(() -> link.getState() == ControlLink.State.READY, 8000, "the link back after " + cutMs + " ms", log);
            long framesAt = wire.toPhone(UdpCommon.RcFrame);
            waitFor(() -> wire.toPhone(UdpCommon.RcFrame) > framesAt + 50, 4000, "stick frames at the phone again, the arm switch still on", log);
            Thread.sleep(1500);
            assertFalse(drone.fc.isArmed(), "not armed by the reconnect after " + cutMs + " ms\n" + String.join("\n", log));
            int before = sessions, pcBefore = pcStarts;
            sessions = drone.sessions();
            pcStarts = (int) link.getSessionStarts();
            // F-001, the pilot's rule (2026-11-20): one session when the link goes, one when it is back - two at most.
            // Two when the cut ends just as the PC renews its try's id (heard again; or its 4 s unheard: a 6 s cut ends
            // at 2 + 4 s): a Connect of the old id gets through, then one of the new (red 1 in 8 here, once on the CI
            // runner, 2026-11-21, while this asked for one at most). The PC takes the newest try's answer only.
            assertTrue(cutMs < 4000 ? sessions <= before + 2 : sessions >= before + 1 && sessions <= before + 2,
                    "a session when the link goes and one when it is back, at most: " + before + " -> " + sessions);
            assertTrue(cutMs < 4000 ? pcStarts <= pcBefore + 1 : pcStarts == pcBefore + 1,
                    "the PC started once for the reconnect at most (3 s: the old session may go on): " + pcBefore + " -> " + pcStarts);
            assertTrue(pcStarts - pcBefore <= sessions - before, "never a start on the PC without one on the phone");
            assertTrue(link.getTelemetry().getStatus() != null && MonoClock.ms() - link.getTelemetry().getStatus().receivedAt() < 1000
                    && !link.getTelemetry().getStatus().armed(), "and the PC reads it disarmed");
        }
    }
}
