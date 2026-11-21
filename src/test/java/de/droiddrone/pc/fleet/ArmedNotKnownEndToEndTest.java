package de.droiddrone.pc.fleet;

import de.droiddrone.common.FcCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ArmedCheck;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.PossiblyFlying;
import de.droiddrone.testing.Waits;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-062 end to end on loopback: a virtual drone armed and in the air, the PC's link closed (the app closed) and a new
 * one opened (the app started again), with the wire losing the answer that carries Betaflight's mode names - the
 * seconds in which every status reads "not armed". Up to 1.0.71 the guards took that for DISARMED. And a phone with no
 * flight controller: asked nothing once the link says FC LOST.
 */
class ArmedNotKnownEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private volatile ControlLink link;
    private Wire wire;
    private volatile boolean sending;
    private volatile int armValue = 1000, throttle = 1000;
    private Thread sticks;

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    /** The PC's stick frames, 50 a second, to whichever link is the app's now: AUX1 the arm switch, AUX2 angle mode. */
    private void startSticks() {
        sending = true;
        sticks = new Thread(() -> {
            while (sending) {
                link.sendRc(new short[]{1500, 1500, 1500, (short) throttle, (short) armValue, 1500, 1000, 1000});
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

    /** Whether this datagram from the phone is the telemetry packet that answers the PC's request for the mode names. */
    private static boolean carriesTheModeNames(byte[] d, int len) {
        return len >= 3 && d[0] == UdpCommon.TelemetryData && ((d[1] & 0xff) << 8 | d[2] & 0xff) == FcCommon.MSP_BOXNAMES;
    }

    private static String armedColumn(List<String> lines, int row) {
        return lines.get(row).split(",", -1)[List.of(lines.get(0).split(",")).indexOf("armed")];
    }

    @Test
    void theAppStartedAgainWithItsDroneInTheAirDoesNotTakeItForDisarmed(@TempDir Path dir) throws Exception {
        PcConfig config = PcConfig.defaults();
        FlightModel.Setup setup = new FlightModel.Setup();
        setup.procedure = FlightModel.Procedure.GPS_RESCUE; // without sticks it stays armed and flies on by itself
        long droneStartedAt = System.currentTimeMillis();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                setup, log::add);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        ControlLink first = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link = first;
        first.start();
        PossiblyFlying flag = new PossiblyFlying(MonoClock::ms); // as the window attaches its link to this PC's flag
        flag.attach(first, "MobileZulu", () -> PossiblyFlying.of(first.getTelemetry().getStatus()));
        waitFor(() -> first.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        FcTelemetry t = first.getTelemetry();
        waitFor(() -> t.isDisarmedNow(MonoClock.ms(), 1000), 5000, "DISARMED, said with the mode names", log);
        assertEquals(ArmedCheck.CLEAR, ArmedCheck.of(first, 0, MonoClock.ms()), "on the ground and read: nothing is asked");
        assertFalse(flag.holds());

        // armed, and up
        startSticks();
        waitFor(() -> drone.fc.hadSticks(), 2000, "the sticks reach the virtual FC", log);
        Thread.sleep(Math.max(0, FlightModel.BOOT_GRACE_MS + 700 - (System.currentTimeMillis() - droneStartedAt)));
        armValue = 1900;
        if (!Waits.until(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000)) { // a gap in the sticks as the switch went on: off and on again
            log.add("TEST: not armed at the switch's first try; off and on again");
            armValue = 1000;
            Thread.sleep(700);
            armValue = 1900;
        }
        waitFor(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000, "armed, as the PC's telemetry says", log);
        throttle = 1900;
        waitFor(() -> drone.fc.altitudeM() > 45, 15_000, "in the air", log);
        waitFor(() -> ArmedCheck.of(first, MonoClock.ms(), MonoClock.ms()) == ArmedCheck.ARMED, 3000, "ARMED, for the guards", log);
        assertTrue(flag.holds());

        // the app is closed with the drone in the air (asked, and confirmed); the names' answer is lost from here on
        sending = false;
        flag.detach(first);
        first.close();
        assertTrue(flag.holds(), "lost armed: its window runs");
        AtomicInteger namesLost = new AtomicInteger();
        wire.rule((toPhone, d, len) -> {
            if (toPhone || !carriesTheModeNames(d, len)) return 0;
            namesLost.incrementAndGet();
            return Wire.Rule.LOST;
        });

        // started again, PILOT UAV: a new link, which knows nothing of the flight before
        ControlLink again = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link = again;
        flag.attach(again, "MobileZulu", () -> PossiblyFlying.of(again.getTelemetry().getStatus()));
        again.start();
        FcTelemetry t2 = again.getTelemetry();
        waitFor(() -> again.getState() == ControlLink.State.READY && t2.getStatus() != null && namesLost.get() > 0, 10_000,
                "the new link up, its first statuses here, the mode names' answer lost on the wire", log);
        assertTrue(drone.fc.isArmed(), "still armed in the air (" + drone.fc.failsafe() + ", " + Math.round(drone.fc.altitudeM()) + " m)\n" + String.join("\n", log));
        assertFalse(t2.hasBoxNames());

        FcTelemetry.Status status = t2.getStatus();
        assertFalse(status.armed(), "read without the mode names: \"not armed\", of a drone in the air");
        assertFalse(status.armedKnown(), "which says nothing");
        assertEquals(ArmedCheck.NOT_KNOWN, ArmedCheck.of(again, 0, MonoClock.ms()),
                "STANDBY MODE, closing the window, DEEP SLEEP, OVERWATCH: asked, not let through");
        assertTrue(flag.holds(), "a status that can't say doesn't end the lost drone's window");
        PossiblyFlying.Hold hold = flag.why();
        assertTrue(hold.live() && hold.notKnown(), "held by the new link, as not known");
        Path file;
        List<String> lines;
        try (LinkLog linkLog = LinkLog.open(dir)) {
            file = linkLog.file();
            linkLog.sample(again, false, false, 0, null, "");

            // the answer gets through: the flight controller's own word
            wire.clear();
            waitFor(() -> {
                FcTelemetry.Status s = t2.getStatus();
                return s != null && s.armedKnown() && s.armed() && MonoClock.ms() - s.receivedAt() < 500;
            }, 8000, "the mode names here: ARMED, said", log);
            assertTrue(drone.fc.isArmed());
            assertEquals(ArmedCheck.ARMED, ArmedCheck.of(again, 0, MonoClock.ms()));
            assertTrue(flag.holds());
            assertFalse(flag.why().notKnown());
            linkLog.sample(again, false, false, 0, null, "");
        }
        lines = Files.readAllLines(file);
        assertEquals("", armedColumn(lines, 1), "the link log's armed column: blank while not known (the bench's own guard reads it)");
        assertEquals("1", armedColumn(lines, 2));
    }

    @Test
    void aPhoneWithNoFlightControllerIsAskedNothingOnceTheLinkSaysSo() throws Exception {
        PcConfig config = PcConfig.defaults();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.setFcLost(true); // nothing on the phone's USB
        drone.start(0);
        ControlLink l = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        link = l;
        PossiblyFlying flag = new PossiblyFlying(MonoClock::ms);
        flag.attach(l, "MobileZulu", () -> PossiblyFlying.of(l.getTelemetry().getStatus()));
        l.start();
        waitFor(() -> l.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        assertEquals(ArmedCheck.NOT_KNOWN, ArmedCheck.of(l, 0, MonoClock.ms()), "just up, nothing from a flight controller yet: too early to say");
        // FC LOST on the LINK card: from then on STANDBY MODE and closing the window ask nothing
        waitFor(() -> l.isFcLost() && ArmedCheck.of(l, 0, MonoClock.ms()) == ArmedCheck.CLEAR, 10_000, "FC LOST, and nothing asked", log);
        assertNull(l.getTelemetry().getStatus());
        assertFalse(flag.holds(), "and nothing here may be flying");
    }
}
