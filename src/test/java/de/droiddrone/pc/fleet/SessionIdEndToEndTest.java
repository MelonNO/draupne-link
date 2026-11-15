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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.69, the session id, end to end on loopback: a virtual drone (the phone's own SessionGate) and the PC's link with
 * a wire between them that holds given packets back. A reconnect is one session on both sides - also when Connects of
 * the try arrive late (the reconnect storm: with the clock's rule each made a session more) and when the answers come
 * late (an older try's answer starts nothing). An older phone app and an older PC meet what they met before, to the
 * byte. And the reconnect itself never arms a drone that Betaflight's failsafe disarmed.
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
        wire.rule((toPhone, d, len) -> Wire.Rule.LOST);
        waitFor(() -> !drone.isConnected() && link.getState() != ControlLink.State.READY, 9000, "the phone's session timed out", log);
        Thread.sleep(1500); // the PC's own 4 s ran from the same moment: over by now
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

    @Test
    void aReconnectIsOneSessionOnBothSides() throws Exception {
        start(false, false);
        cutUntilBothSidesLetGo();
        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY && drone.sessions() >= 2, 8000, "the link back", log);
        Thread.sleep(1500); // nothing more comes of the Connects that were in flight
        assertEquals(2, drone.sessions(), "one new session on the phone");
        assertEquals(2, link.getSessionStarts(), "one on the PC");
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
        assertTrue(link.getSessionStarts() >= 3, "and its answer starts the PC's side over, as before 1.0.69: " + link.getSessionStarts());
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
     * back as it came - no mark - so the PC takes every answer, as before 1.0.69: the session starts over with each.
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

    /** A PC before 1.0.69: its Connect ends with the version, and the phone app of now answers it as it came. */
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
        int sessions = 1;
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
            int before = sessions;
            sessions = drone.sessions();
            assertTrue(cutMs < 4000 ? sessions <= before + 1 : sessions == before + 1, "one session for the reconnect, at most: " + before + " -> " + sessions);
            assertEquals(sessions, link.getSessionStarts(), "and the PC's side with it");
            assertTrue(link.getTelemetry().getStatus() != null && MonoClock.ms() - link.getTelemetry().getStatus().receivedAt() < 1000
                    && !link.getTelemetry().getStatus().armed(), "and the PC reads it disarmed");
        }
    }
}
