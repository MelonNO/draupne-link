package de.droiddrone.pc.fleet;

import de.droiddrone.common.PacketIds;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone keeps who its pilot was across a start of its app anew (KeptPilot; the virtual fleet's phone as the phone
 * app): after a restart in the air the pilot's own PC gets the drone back at once, and another PC with the key only by
 * the takeover rule - before, the app knew no pilot after a restart and the first keyed Connect from anywhere started a
 * session on the armed drone. A clean DISCONNECT and STANDBY forget the pilot.
 */
class KeptPilotEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;
    private volatile boolean sending;
    private volatile int armValue = 1000, throttleValue = 1000;
    private Thread sticks;
    private long droneStartedAt;

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    @Test
    void anAppStartedAnewInTheAirGivesItsPilotBackAndNoOtherPc() throws Exception {
        PcConfig config = smallPicture();
        FlightModel.Setup setup = new FlightModel.Setup();
        setup.procedure = FlightModel.Procedure.GPS_RESCUE; // no sticks: it stays armed and flies on by itself
        startDrone(config, setup);
        armAndFly();

        wire.rule((toPhone, d, len) -> Wire.Rule.LOST); // the pilot's PC not heard (it goes on asking)
        drone.restartApp();
        waitFor(() -> said("phone app back"), 6000, "the phone app back", log);
        assertTrue(drone.fc.isArmed(), "armed and in the air as the app comes back");

        // another PC with the key, at another address: told who flies (or that nobody does), never answered as a pilot
        List<Byte> answers = otherPcAsks(config.getKey(), 1500);
        assertFalse(answers.contains(UdpCommon.Connect), "another PC got a session on the armed drone: " + answers + "\n" + String.join("\n", log));
        assertFalse(said("from 127.0.0.2"), String.join("\n", log));

        // the pilot's PC heard again: its session at once, armed as it is, its sticks at the flight controller
        int framesAt = drone.rcFramesTaken();
        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY, 6000, "the pilot's link back", log);
        waitFor(() -> drone.rcFramesTaken() > framesAt, 3000, "the pilot's stick frames taken", log);
        assertTrue(drone.fc.isArmed());
        assertEquals(1, drone.fc.arms(), "armed all along");
    }

    @Test
    void aCleanDisconnectForgetsThePilot() throws Exception {
        PcConfig config = smallPicture();
        startDrone(config, new FlightModel.Setup());
        waitFor(() -> drone.keptPilot() != null, 2000, "the pilot kept while its session is up", log);
        link.close(); // DISCONNECT
        link = null;
        waitFor(() -> drone.keptPilot() == null, 2000, "forgotten at the PC's Disconnect", log);
        waitFor(() -> !drone.isConnected(), 6000, "the session over", log);
        drone.restartApp();
        waitFor(() -> said("phone app back"), 6000, "the phone app back", log);
        // nobody known: another PC is answered as a pilot at once, as before (the flight controller not even read yet)
        List<Byte> answers = otherPcAsks(config.getKey(), 1500);
        assertTrue(answers.contains(UdpCommon.Connect), "answered: " + answers);
    }

    @Test
    void standbyForgetsThePilotAndAFlightAfterItKeepsItAgain() throws Exception {
        PcConfig config = smallPicture();
        startDrone(config, new FlightModel.Setup());
        waitFor(() -> drone.keptPilot() != null, 2000, "the pilot kept", log);
        waitFor(() -> { // STANDBY, sent again until the phone is in it (as the PC does)
            link.sendPowerMode(1, 0);
            return drone.keptPilot() == null;
        }, 4000, "forgotten in standby", log);
        waitFor(() -> { // awake again, the same session: it may fly on
            link.sendPowerMode(0, 0);
            return drone.keptPilot() != null;
        }, 4000, "kept again", log);
        assertTrue(drone.isConnected(), "one session all along");
    }

    // ---- helpers

    private void startDrone(PcConfig config, FlightModel.Setup setup) throws Exception {
        droneStartedAt = System.currentTimeMillis();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                setup, log::add);
        drone.link().setBaseMs(0); // its own network delay off: the wire is the network here
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY", log);
    }

    private void armAndFly() throws Exception {
        sending = true;
        sticks = new Thread(() -> {
            while (sending) {
                ControlLink l = link;
                if (l != null) l.sendRc(new short[]{1500, 1500, 1500, (short) throttleValue, (short) armValue, 1500, 1000, 1000});
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testSticks");
        sticks.setDaemon(true);
        sticks.start();
        int framesAt = drone.rcFramesTaken();
        waitFor(() -> drone.rcFramesTaken() > framesAt + 10, 3000, "the PC's sticks at the phone, the switch off", log);
        Thread.sleep(Math.max(0, FlightModel.BOOT_GRACE_MS + 700 - (System.currentTimeMillis() - droneStartedAt)));
        for (int tries = 0; tries < 4 && !drone.fc.isArmed(); tries++) {
            armValue = 1000;
            Thread.sleep(700);
            armValue = 1900;
            if (!de.droiddrone.testing.Waits.until(() -> drone.fc.isArmed(), 2000)) log.add("TEST: not armed at the switch; off and on again");
        }
        waitFor(() -> drone.fc.isArmed(), 1000, "armed", log);
        throttleValue = 1700;
        waitFor(() -> drone.fc.altitudeM() > 10, 8000, "in the air", log);
        throttleValue = 1450;
    }

    /**
     * Another PC with the key, at another loopback address: a Connect with a session id of its own every 150 ms, as
     * DRAUPNE Link sends them; what comes back (by first byte).
     */
    private List<Byte> otherPcAsks(String key, long ms) throws Exception {
        List<Byte> got = new CopyOnWriteArrayList<>();
        try (DatagramSocket other = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0))) {
            other.setSoTimeout(50);
            byte[] connect = connect(key, 0x5eed0000L);
            InetSocketAddress to = new InetSocketAddress(InetAddress.getLoopbackAddress(), drone.port());
            long end = System.currentTimeMillis() + ms, nextSend = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    other.send(new DatagramPacket(connect, connect.length, to));
                    nextSend = System.currentTimeMillis() + 150;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    other.receive(p);
                    if (p.getLength() > 0) got.add(buf[0]);
                } catch (SocketTimeoutException e) {
                    // on
                }
            }
        }
        assertFalse(got.isEmpty(), "the phone answered the other PC at all");
        assertTrue(got.stream().allMatch(b -> b == UdpCommon.Connect || b == PacketIds.SPECTATE_PILOT_AT || b == PacketIds.SPECTATE_NO_PILOT
                || b == UdpCommon.Ping || b == UdpCommon.PacketReceived || b == UdpCommon.TelemetryData), "answers: " + got);
        return got;
    }

    /** [Connect][num i16 = -1][client type 1][key UTF][version i16][session id u32][byId u8 = 0], as DRAUPNE Link 1.0.71 sends it. */
    private static byte[] connect(String key, long sessionId) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(UdpCommon.Connect);
        out.writeShort(-1);
        out.writeByte(1);
        out.writeUTF(key);
        out.writeShort(SettingsCommon.versionCompatibleCode);
        out.writeInt((int) sessionId);
        out.writeByte(0);
        return bytes.toByteArray();
    }

    private boolean said(String part) {
        return log.stream().anyMatch(l -> l.contains(part));
    }

    /** The PC's settings with a small picture: the made-up drone's encoder leaves this PC's cores to the sticks' beat. */
    private static PcConfig smallPicture() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "320");
        config.set("cameraHeight", "180");
        config.set(ControlLink.VIDEO_MODE_KEY, "NORMAL");
        return config;
    }
}
