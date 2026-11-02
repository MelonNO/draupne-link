package de.droiddrone.pc;

import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.pc.ControlLinkTest.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/** Spectator mode end to end on loopback: the fake phone, a pilot PC and a second PC. */
class SpectatorTest {
    private static final int PHONE_PORT = 46286; // ControlLinkTest.FakeFlightPhone's
    private static final int PILOT_PORT = 46320;
    private static final int SPECTATOR_PORT = 46321;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private ControlLinkTest.FakeFlightPhone phone;
    private ControlLink pilot;
    private ControlLink spectator;
    private VideoPipeline pilotVideo;
    private VideoPipeline spectatorVideo;

    /** The fake phone counts its 4 s link timeout in 1 ms sleeps, as on Android: needs the 1 ms timer on Windows. */
    @org.junit.jupiter.api.BeforeAll
    static void fineTimer() {
        WindowsTuning.apply();
    }

    @AfterEach
    void tearDown() {
        if (spectator != null) spectator.close();
        if (pilot != null) pilot.close();
        if (spectatorVideo != null) spectatorVideo.close();
        if (pilotVideo != null) pilotVideo.close();
        if (phone != null) phone.close();
    }

    private ControlLink link(int port, boolean allowSpectators, int delayMs) throws java.io.IOException {
        ControlLink l = new ControlLink(PcConfig.defaults(), LOOPBACK, PHONE_PORT, port);
        l.enableSpectators(allowSpectators, delayMs);
        return l;
    }

    private void pilotFlying(boolean allowSpectators) throws Exception {
        pilot = link(PILOT_PORT, allowSpectators, 600);
        pilot.start();
        waitFor(() -> pilot.getState() == ControlLink.State.READY, 5000, "pilot READY");
    }

    @Test
    void secondPcWatchesThroughThePilotAndSendsNoSticks() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 240, 10);
        ControlLinkTest.CapturingSink pilotSink = new ControlLinkTest.CapturingSink();
        pilotVideo = new VideoPipeline(pilotSink, () -> 30);
        pilot = link(PILOT_PORT, true, 600);
        pilot.setVideoListener(pilotVideo, false);
        pilotVideo.attach(pilot);
        pilot.start();
        waitFor(() -> pilot.getState() == ControlLink.State.READY, 5000, "pilot READY");

        ControlLinkTest.CapturingSink sink = new ControlLinkTest.CapturingSink();
        spectatorVideo = new VideoPipeline(sink, () -> -1);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.setVideoListener(spectatorVideo, false);
        spectatorVideo.attach(spectator);
        spectator.start();
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 5000, "spectator watching");
        waitFor(() -> sink.presented.get() >= 20, 10_000, "pictures on the spectator's screen");

        assertEquals(1, pilot.getSpectatorCount());
        assertEquals(ControlLink.State.READY, pilot.getState(), "the pilot keeps flying");
        assertEquals(320, sink.startedWidth);
        int px = sink.pixels[60 * 160 + 80];
        assertTrue(((px >> 16) & 0xff) > 200 && ((px >> 8) & 0xff) < 60, "the pilot's red picture, got " + Integer.toHexString(px));
        // one broken or lost frame is UDP on a loaded machine (seen on the release runner and a busy dev PC)
        assertTrue(spectatorVideo.stats().damaged() <= 1, "nothing broken on the way: " + spectatorVideo.stats());
        assertTrue(spectatorVideo.stats().lost() <= 1, "nothing lost on the way: " + spectatorVideo.stats());
        assertNotNull(spectator.getFcInfo(), "FC info from the pilot's snapshot");
        waitFor(() -> spectator.getTelemetry().hasBoxNames(), 5000, "flight mode names passed on");

        int rcBefore = phone.rcFrames.size();
        assertFalse(spectator.sendRc(new short[]{1500, 1500, 1500, 1000}), "a spectator sends no sticks");
        Thread.sleep(300);
        assertEquals(rcBefore, phone.rcFrames.size());
        assertTrue(pilot.sendRc(new short[]{1500, 1500, 1500, 1000}), "the pilot's sticks still go out");
        assertTrue(spectator.spectatorStatus().contains("watching"));
    }

    /**
     * A detailed picture, so frames fill whole packets: the pilot passes them on 9 bytes longer (its STREAM header).
     * Cut off on the way, the spectator's picture turned into coloured mush; it must end up the pilot's, pixel for pixel.
     */
    @Test
    void fullSizePacketsReachTheSpectatorWhole() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 150, 10, true);
        ControlLinkTest.CapturingSink pilotSink = new ControlLinkTest.CapturingSink();
        pilotVideo = new VideoPipeline(pilotSink, () -> 30);
        pilot = link(PILOT_PORT, true, 600);
        pilot.setVideoListener(pilotVideo, false);
        pilotVideo.attach(pilot);
        pilot.start();
        waitFor(() -> pilot.getState() == ControlLink.State.READY, 5000, "pilot READY");
        ControlLinkTest.CapturingSink sink = new ControlLinkTest.CapturingSink();
        spectatorVideo = new VideoPipeline(sink, () -> -1);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.setVideoListener(spectatorVideo, false);
        spectatorVideo.attach(spectator);
        spectator.start();
        waitFor(() -> sink.presented.get() >= 20, 10_000, "pictures on the spectator's screen");
        // the stream ends (150 frames, ~5 s): then both show its last frame
        int[] last = {-1};
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500);
            int now = sink.presented.get();
            if (now == last[0]) break;
            last[0] = now;
        }
        // the cut-off of 1.0.17 broke nearly every frame; one broken frame is UDP on a loaded build machine
        assertTrue(spectatorVideo.stats().damaged() <= 1, "no broken frames: " + spectatorVideo.stats());
        assertTrue(spectatorVideo.stats().lost() <= 1, "no lost frames: " + spectatorVideo.stats());
        assertArrayEquals(pilotSink.pixels, sink.pixels, "the spectator sees the pilot's picture");
    }

    /** Connect as: Spectator - asks the phone who flies and watches, never a Connect. */
    @Test
    void connectingAsSpectatorWatchesThePilot() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        pilotFlying(true);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.watchOnly();
        spectator.start();
        assertTrue(spectator.isSpectator(), "a spectator from the start");
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 5000, "watching");
        assertTrue(spectator.isWatchOnly());
        assertEquals(1, pilot.getSpectatorCount());
        assertFalse(spectator.sendRc(new short[]{1500, 1500, 1500, 1000}));
    }

    /** Connect as: Spectator with nobody flying - it waits, and never becomes the pilot. */
    @Test
    void spectatorByChoiceWaitsWhenNobodyFlies() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.watchOnly();
        spectator.start();
        waitFor(() -> spectator.spectatorStatus().contains("waiting for a pilot"), 5000, "told nobody flies");
        Thread.sleep(1500);
        assertFalse(phone.receiverBuffer.isConnected(), "the phone never got a Connect");
        assertEquals(ControlLink.State.SPECTATOR_WAITING, spectator.getState());
        // a pilot comes: the spectator joins it
        pilotFlying(true);
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 6000, "watching the new pilot");
    }

    @Test
    void watchingThroughARelayIsRefused() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(Connection.CONNECT_AS_KEY, "spectator");
        config.set("linkMode", "relay");
        config.set("ip", "127.0.0.1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Connection.open(config));
        assertTrue(e.getMessage().contains("spectator"));
    }

    @Test
    void spectatorNeverTakesTheDroneOver() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        pilotFlying(true);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.start();
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 5000, "spectator watching");

        pilot.close(); // the pilot's PC goes; the phone frees itself after ~4 s of silence
        pilot = null;
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATOR_WAITING, 2000, "spectator sees the pilot go");
        // 4000 ticks of a 1 ms sleep: ~4 s on a phone, up to ~7 s on a PC
        waitFor(() -> !phone.receiverBuffer.isConnected(), 10_000, "phone free");
        waitFor(() -> spectator.spectatorStatus().contains("nobody flies"), 4000, "spectator told nobody flies");
        Thread.sleep(1500);
        assertFalse(phone.receiverBuffer.isConnected(), "the spectator didn't connect to the phone");
        assertTrue(spectator.isSpectator());
    }

    @Test
    void pilotThatAllowsNobodyRefuses() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        pilotFlying(false);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.start();
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATOR_WAITING
                && spectator.spectatorStatus().contains("lets nobody watch"), 5000, "spectator refused");
        assertEquals(0, pilot.getSpectatorCount());
    }

    @Test
    void switchingSpectatorsOffWhileFlyingSendsThemAway() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        pilotFlying(true);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.start();
        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 5000, "spectator watching");
        pilot.setSpectatorsAllowed(false);
        assertEquals(0, pilot.getSpectatorCount());
        waitFor(() -> spectator.spectatorStatus().contains("lets nobody watch"), 5000, "spectator told");
        assertEquals(ControlLink.State.READY, pilot.getState());
    }

    @Test
    void spectatorsAreOffUntilSwitchedOn() throws Exception {
        PcConfig config = PcConfig.defaults();
        assertFalse(Connection.spectatorsAllowed(config));
        config.set(Connection.ALLOW_SPECTATORS_KEY, "true");
        assertTrue(Connection.spectatorsAllowed(config));
    }

    @Test
    void olderPhoneAppLeavesTheSecondPcWaitingAsBefore() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.answerSpectators = false;
        pilotFlying(true);
        spectator = link(SPECTATOR_PORT, true, 300);
        spectator.start();
        Thread.sleep(1500);
        assertEquals(ControlLink.State.CONNECTING, spectator.getState());
        assertFalse(spectator.isSpectator());
        assertEquals(ControlLink.State.READY, pilot.getState());
    }

    /**
     * After a link loss the phone may still hold this PC's old session and name it as the pilot: this PC is no
     * spectator of itself - it says so and keeps asking to connect, a little faster, until the phone lets go.
     */
    @Test
    void aPhoneNamingThisPcsOwnSessionAsThePilotIsAskedAgain() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.staleSession = new Spectate.Address(LOOPBACK, PILOT_PORT); // where this PC's previous session sat
        List<String> events = new CopyOnWriteArrayList<>();
        pilot = link(PILOT_PORT, true, 600);
        pilot.setEvents(events::add);
        pilot.start();
        waitFor(() -> events.stream().anyMatch(e -> e.contains("previous session")), 3000, "told what the phone does");
        int answersBefore = phone.spectatorAnswers.get();
        Thread.sleep(1500);
        assertFalse(pilot.isSpectator(), "no spectator of itself");
        assertEquals(ControlLink.State.CONNECTING, pilot.getState());
        assertTrue(phone.spectatorAnswers.get() - answersBefore >= 4, "kept asking to connect, faster than every 500 ms: "
                + (phone.spectatorAnswers.get() - answersBefore) + " in 1.5 s");
        assertEquals(1, events.stream().filter(e -> e.contains("previous session")).count(), "said once, not with every answer");
        phone.staleSession = null; // the phone lets the old session go
        waitFor(() -> pilot.getState() == ControlLink.State.READY, 5000, "connected at last");
        assertFalse(pilot.isSpectator());
    }

    /** The same by address: this PC's own addresses at this link's port or a session's that just closed. */
    @Test
    void thisPcIsKnownByItsAddressesAndRecentPorts() throws Exception {
        ControlLink closed = link(PILOT_PORT, true, 600);
        closed.start();
        closed.close();
        ControlLink fresh = link(SPECTATOR_PORT, true, 600);
        assertTrue(fresh.isThisPc(new Spectate.Address(LOOPBACK, SPECTATOR_PORT)), "its own port");
        assertTrue(fresh.isThisPc(new Spectate.Address(LOOPBACK, PILOT_PORT)), "the session that just closed");
        assertFalse(fresh.isThisPc(new Spectate.Address(LOOPBACK, PILOT_PORT + 7)), "some other PC on this address: a pilot");
        assertFalse(fresh.isThisPc(new Spectate.Address(InetAddress.getByName("192.0.2.1"), SPECTATOR_PORT)), "not this PC's address");
        pilot = link(PILOT_PORT, true, 600);
        pilot.start(); // the closed port is a live session again (a pilot, as in the tests above)
        assertFalse(fresh.isThisPc(new Spectate.Address(LOOPBACK, PILOT_PORT)));
        fresh.close();
    }

    @Test
    void wrongKeyLearnsNothing() throws Exception {
        phone = new ControlLinkTest.FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        pilotFlying(true);
        PcConfig other = PcConfig.defaults();
        other.set("key", "NOT-DD");
        spectator = new ControlLink(other, LOOPBACK, PHONE_PORT, SPECTATOR_PORT);
        spectator.enableSpectators(true, 300);
        spectator.start();
        Thread.sleep(1500);
        assertFalse(spectator.isSpectator());
        assertEquals(0, phone.spectatorAnswers.get(), "the phone doesn't tell where the pilot is");
    }

    /**
     * The host alone against a scripted spectator: a slow link to the drone pauses the stream, the spectator is told,
     * the sequence stands still (nothing to ask for again), and a packet from before the pause is not resent after it.
     */
    @Test
    void aCongestionPauseLeavesNoGapAndIsAnnounced() throws Exception {
        try (DatagramSocket linkSocket = new DatagramSocket(0, LOOPBACK);
             DatagramSocket viewer = new DatagramSocket(0, LOOPBACK)) {
            viewer.setSoTimeout(2500);
            int[] ping = {20};
            List<String> events = new CopyOnWriteArrayList<>();
            SpectatorHost host = new SpectatorHost("DD", true, linkSocket, () -> true, () -> ping[0], events::add);
            try {
                byte[] hello = Spectate.keyed(Spectate.HELLO, "DD");
                host.onHello(new DatagramPacket(hello, hello.length, LOOPBACK, viewer.getLocalPort()));
                assertEquals(1, host.count());
                DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
                viewer.receive(in);
                assertEquals(Spectate.WELCOME, in.getData()[0]);
                int streamPort = host.streamPort();
                byte[] keep = Spectate.keep(0, new int[0], 0);
                viewer.send(new DatagramPacket(keep, keep.length, LOOPBACK, streamPort));
                Thread keeper = new Thread(() -> { // as a real spectator: KEEP 4 times a second, or it counts as gone
                    while (!viewer.isClosed()) {
                        try {
                            viewer.send(new DatagramPacket(keep, keep.length, LOOPBACK, streamPort));
                            Thread.sleep(250);
                        } catch (Exception e) {
                            return;
                        }
                    }
                });
                keeper.setDaemon(true);
                keeper.start();
                byte[] packet = {UdpCommon.TelemetryData, 1};
                host.forward(packet, packet.length);
                int seq0 = receiveStream(viewer, in);
                assertEquals(0, seq0);
                Thread.sleep(1200); // one full second of ping 20: "the usual"
                ping[0] = 200;
                long high = System.currentTimeMillis();
                waitFor(host::isPaused, (int) SpectatorHost.CONGESTION_HOLD_MS + 1000, "paused");
                assertTrue(System.currentTimeMillis() - high >= SpectatorHost.CONGESTION_HOLD_MS - 100, "not before the hold time");
                assertTrue(events.get(events.size() - 1).startsWith("Spectator video paused"));
                host.forward(packet, packet.length); // during the pause: dropped, and no number used up
                viewer.receive(in);
                assertEquals(Spectate.PAUSE, in.getData()[0], "told: paused");
                assertEquals(1, in.getData()[1]);
                ping[0] = 20;
                waitFor(() -> !host.isPaused(), (int) SpectatorHost.PAUSE_MS + 1000, "on again");
                boolean onAgain = false;
                long end = System.currentTimeMillis() + 2000;
                while (!onAgain && System.currentTimeMillis() < end) {
                    viewer.receive(in);
                    onAgain = in.getData()[0] == Spectate.PAUSE && in.getData()[1] == 0;
                }
                assertTrue(onAgain, "told: on again");
                host.forward(packet, packet.length);
                assertEquals(1, receiveStream(viewer, in), "the sequence stood still during the pause: no gap to ask for");
                // asked again: the one from before the pause is not resent, the one after is
                byte[] nack = Spectate.keep(0, new int[]{0, 1}, 2);
                viewer.send(new DatagramPacket(nack, nack.length, LOOPBACK, streamPort));
                assertEquals(1, receiveStream(viewer, in), "only packet 1 again");
                assertThrows(java.net.SocketTimeoutException.class, () -> viewer.receive(in), "packet 0 is never resent");
            } finally {
                host.close();
            }
        }
    }

    /** The next STREAM packet's sequence number; PAUSE notices on the way are skipped. */
    private static int receiveStream(DatagramSocket viewer, DatagramPacket in) throws java.io.IOException {
        while (true) {
            viewer.receive(in);
            if (in.getData()[0] == Spectate.STREAM) return Spectate.readInt(in.getData(), 1);
        }
    }

    /**
     * The client alone against a scripted pilot: a pause notice shows as PAUSED until the stream goes on, and a
     * snapshot already played is played again after a rejoin (the decoder starts afresh and needs it).
     */
    @Test
    void pauseIsShownAndASnapshotIsPlayedAgainAfterARejoin() throws Exception {
        try (DatagramSocket pilotSocket = new DatagramSocket(0, LOOPBACK);
             DatagramSocket clientSocket = new DatagramSocket(0, LOOPBACK)) {
            pilotSocket.setSoTimeout(3000);
            List<byte[]> played = new CopyOnWriteArrayList<>();
            SpectatorClient client = new SpectatorClient(clientSocket, LOOPBACK, 1, "DD", 100,
                    new Spectate.Address(LOOPBACK, pilotSocket.getLocalPort()), played::add);
            Thread feeder = new Thread(() -> {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (!clientSocket.isClosed()) {
                    try {
                        p.setLength(buf.length);
                        clientSocket.receive(p);
                        client.onPacket(p);
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            feeder.setDaemon(true);
            feeder.start();
            try {
                DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
                pilotSocket.receive(in);
                assertEquals(Spectate.HELLO, in.getData()[0]);
                InetAddress clientIp = in.getAddress();
                int clientPort = in.getPort();
                byte[] welcome = Spectate.withPort(Spectate.WELCOME, pilotSocket.getLocalPort());
                pilotSocket.send(new DatagramPacket(welcome, welcome.length, clientIp, clientPort));
                waitFor(() -> client.phase() == SpectatorClient.Phase.WATCHING, 2000, "welcomed");
                assertFalse(client.isPaused());
                byte[] snapshot = Spectate.snapshot(new byte[]{UdpCommon.FcInfo, 0, 1, 2, 4, 5, 1, 0, 1, 46, 0});
                pilotSocket.send(new DatagramPacket(snapshot, snapshot.length, clientIp, clientPort));
                waitFor(() -> played.size() == 1, 2000, "the snapshot played");
                pilotSocket.send(new DatagramPacket(snapshot, snapshot.length, clientIp, clientPort));
                Thread.sleep(300);
                assertEquals(1, played.size(), "the same snapshot again is not played again");

                byte[] pause = {Spectate.PAUSE, 1};
                pilotSocket.send(new DatagramPacket(pause, pause.length, clientIp, clientPort));
                waitFor(client::isPaused, 1000, "PAUSED");
                byte[] s = Spectate.stream(0, 0, new byte[]{UdpCommon.TelemetryData, 1}, 2);
                pilotSocket.send(new DatagramPacket(s, s.length, clientIp, clientPort));
                waitFor(() -> !client.isPaused(), 1000, "the stream goes on: not paused");
                waitFor(() -> played.size() == 2, 2000, "the stream packet played");

                // the pilot goes and comes back: the spectator rejoins, and the snapshot it knows is played again
                byte[] bye = {Spectate.BYE};
                pilotSocket.send(new DatagramPacket(bye, bye.length, clientIp, clientPort));
                waitFor(() -> client.phase() == SpectatorClient.Phase.JOINING, 2000, "pilot gone");
                pilotSocket.receive(in); // its HELLO again
                pilotSocket.send(new DatagramPacket(welcome, welcome.length, clientIp, clientPort));
                waitFor(() -> client.phase() == SpectatorClient.Phase.WATCHING, 2000, "welcomed again");
                pilotSocket.send(new DatagramPacket(snapshot, snapshot.length, clientIp, clientPort));
                waitFor(() -> played.size() == 3, 2000, "the snapshot played again after the rejoin");
                assertEquals(UdpCommon.FcInfo, played.get(2)[0]);
            } finally {
                client.close();
            }
        }
    }

    /** The client alone against a scripted pilot: a lost packet is asked for, and everything plays in order, late. */
    @Test
    void lostPacketIsAskedForAgainAndPlayedInOrder() throws Exception {
        try (DatagramSocket pilotSocket = new DatagramSocket(0, LOOPBACK);
             DatagramSocket clientSocket = new DatagramSocket(0, LOOPBACK)) {
            pilotSocket.setSoTimeout(3000);
            List<byte[]> played = new CopyOnWriteArrayList<>();
            List<Long> playedAt = new CopyOnWriteArrayList<>();
            int delay = 250;
            SpectatorClient client = new SpectatorClient(clientSocket, LOOPBACK, 1, "DD", delay,
                    new Spectate.Address(LOOPBACK, pilotSocket.getLocalPort()), p -> {
                        played.add(p);
                        playedAt.add(System.currentTimeMillis());
                    });
            Thread feeder = new Thread(() -> {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (!clientSocket.isClosed()) {
                    try {
                        p.setLength(buf.length);
                        clientSocket.receive(p);
                        client.onPacket(p);
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            feeder.setDaemon(true);
            feeder.start();
            try {
                DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
                pilotSocket.receive(in);
                assertEquals(Spectate.HELLO, in.getData()[0]);
                assertEquals("DD", Spectate.readKey(in.getData(), in.getLength()));
                InetAddress clientIp = in.getAddress();
                int clientPort = in.getPort();
                byte[] welcome = Spectate.withPort(Spectate.WELCOME, pilotSocket.getLocalPort());
                pilotSocket.send(new DatagramPacket(welcome, welcome.length, clientIp, clientPort));
                waitFor(() -> client.phase() == SpectatorClient.Phase.WATCHING, 2000, "welcomed");

                long start = System.currentTimeMillis();
                for (int seq = 0; seq < 20; seq++) {
                    if (seq == 7) continue; // lost on the way
                    byte[] s = Spectate.stream(seq, seq * 10, new byte[]{UdpCommon.TelemetryData, (byte) seq}, 2);
                    pilotSocket.send(new DatagramPacket(s, s.length, clientIp, clientPort));
                    Thread.sleep(10);
                }
                boolean asked = false;
                long end = System.currentTimeMillis() + 2000;
                while (!asked && System.currentTimeMillis() < end) {
                    pilotSocket.receive(in);
                    byte[] d = in.getData();
                    if (d[0] != Spectate.KEEP) continue;
                    int n = d[2] & 0xff;
                    for (int i = 0; i < n; i++) asked |= Spectate.readInt(d, 3 + 4 * i) == 7;
                }
                assertTrue(asked, "packet 7 asked for again");
                byte[] s = Spectate.stream(7, 70, new byte[]{UdpCommon.TelemetryData, 7}, 2);
                pilotSocket.send(new DatagramPacket(s, s.length, clientIp, clientPort));

                waitFor(() -> played.size() >= 20, 3000, "all 20 played");
                for (int i = 0; i < 20; i++) assertEquals(i, played.get(i)[1], "in order");
                assertEquals(0, client.lostPackets());
                assertTrue(playedAt.get(0) - start >= delay - 20, "played " + (playedAt.get(0) - start) + " ms after it came");
            } finally {
                client.close();
            }
        }
    }
}
