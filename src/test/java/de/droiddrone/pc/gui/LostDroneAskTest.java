package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.pc.fleet.FlightModel;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.gui.MainWindow.LostDroneAsk;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-002 (the maintainer, 2026-11-22: "Ask once, only when the drone was armed at the loss"): LOST DRONE with the link
 * down was one click - it ends the link, so a drone coming home on GPS rescue is not taken back until PILOT UAV is
 * pressed again. It now asks first when the flight controller's last word was ARMED, or was never read; with a last
 * word DISARMED it ends at the one click as ever, and a link that is back keeps its own question (1.0.64).
 */
class LostDroneAskTest {
    private static final long NOW = 5_000_000;
    private static final ControlLink.State DOWN = ControlLink.State.CONNECTING, UP = ControlLink.State.READY;

    private static FcTelemetry.Status status(boolean armed, boolean known, long at) {
        return new FcTelemetry.Status(armed, List.of(), List.of(), 5, 40, at, known);
    }

    // ------------------------------------------------------------------ the rule

    @Test
    void armedAsTheLinkWentIsAskedOnce() {
        FcTelemetry.Status armed = status(true, true, NOW - 8000);
        assertEquals(LostDroneAsk.ARMED_AT_LOSS, LostDroneAsk.of(DOWN, false, false, armed, NOW), "the link lost");
        assertEquals(LostDroneAsk.ARMED_AT_LOSS, LostDroneAsk.of(UP, true, false, armed, NOW), "stalled: the phone not heard");
        assertEquals(LostDroneAsk.ARMED_AT_LOSS, LostDroneAsk.of(UP, false, true, armed, NOW), "the flight controller gone from the phone");
        assertEquals(LostDroneAsk.ARMED_AT_LOSS, LostDroneAsk.of(DOWN, false, false, status(true, true, NOW - 9 * 60_000), NOW),
                "however long ago: its last word");
    }

    @Test
    void knownDisarmedAsTheLinkWentEndsAtTheOneClickAsEver() {
        FcTelemetry.Status disarmed = status(false, true, NOW - 8000);
        assertEquals(LostDroneAsk.NOTHING, LostDroneAsk.of(DOWN, false, false, disarmed, NOW));
        assertEquals(LostDroneAsk.NOTHING, LostDroneAsk.of(UP, true, false, disarmed, NOW));
        assertEquals(LostDroneAsk.NOTHING, LostDroneAsk.of(UP, false, true, disarmed, NOW));
        assertNull(LostDroneAsk.NOTHING.question(), "no question");
    }

    @Test
    void notReadAsTheLinkWentCountsAsArmed() {
        // F-062's rule: a status read before the mode names came says "not armed" of any drone
        assertEquals(LostDroneAsk.NOT_KNOWN_AT_LOSS, LostDroneAsk.of(DOWN, false, false, status(false, false, NOW - 8000), NOW));
        assertEquals(LostDroneAsk.NOT_KNOWN_AT_LOSS, LostDroneAsk.of(DOWN, false, false, null, NOW), "no status at all");
    }

    @Test
    void aLinkThatIsBackKeepsItsOwnQuestionAndOnlyThat() {
        for (FcTelemetry.Status last : new FcTelemetry.Status[]{status(true, true, NOW - 300), status(false, true, NOW - 300),
                status(false, false, NOW - 300), status(true, true, NOW - 8000), null}) {
            assertEquals(LostDroneAsk.LINK_BACK, LostDroneAsk.of(UP, false, false, last, NOW), "up and heard, whatever was said: " + last);
        }
    }

    // ------------------------------------------------------------------ what the pilot reads

    @Test
    void theQuestionSaysWhatTheButtonEndsAndKeepsTryingByDefault() {
        String ends = "\nLost drone ends the link: no more reconnecting, and it is marked OUT OF ACTION.";
        assertEquals("The drone was armed when the link was lost: it may still be flying." + ends, LostDroneAsk.ARMED_AT_LOSS.question());
        assertEquals("Lost drone while armed?", LostDroneAsk.ARMED_AT_LOSS.title());
        assertEquals("Keep trying", LostDroneAsk.ARMED_AT_LOSS.keep());
        assertEquals("Not known if the drone was armed when the link was lost." + ends, LostDroneAsk.NOT_KNOWN_AT_LOSS.question());
        assertEquals("Lost drone - armed?", LostDroneAsk.NOT_KNOWN_AT_LOSS.title());
        assertEquals("Keep trying", LostDroneAsk.NOT_KNOWN_AT_LOSS.keep());
        assertEquals("while armed", LostDroneAsk.ARMED_AT_LOSS.confirmedIn());
        assertEquals("with the armed state not known", LostDroneAsk.NOT_KNOWN_AT_LOSS.confirmedIn());
        // the question of 1.0.64, word for word
        assertEquals("Link is back. End it and mark the drone lost?", LostDroneAsk.LINK_BACK.question());
        assertEquals("Lost drone", LostDroneAsk.LINK_BACK.title());
        assertEquals("Keep flying", LostDroneAsk.LINK_BACK.keep());
    }

    @Test
    void theLogSaysThatTheButtonEndsTheLink() {
        String button = " LOST DRONE ends the link and shows the last position.";
        assertEquals("Link lost while armed." + button, LostDroneAsk.atLoss(status(true, true, NOW)).offered());
        assertEquals("Link lost, armed not known." + button, LostDroneAsk.atLoss(status(false, false, NOW)).offered());
        assertEquals("Link lost." + button, LostDroneAsk.atLoss(status(false, true, NOW)).offered(), "disarmed before the link went");
    }

    // ------------------------------------------------------------------ with a real link, on loopback

    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private volatile boolean sending;
    private volatile int armValue = 1000;
    private Thread sticks;

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (link != null) link.close();
        if (drone != null) BenchFleetAccess.stop(drone);
    }

    private LostDroneAsk ask() {
        return LostDroneAsk.of(link.getState(), link.isLinkStalled(), link.isFcLost(), link.getTelemetry().getStatus(), MonoClock.ms());
    }

    /**
     * A virtual drone armed, then 4G gone: the PC's last word of it is ARMED - asked. The link comes back by itself
     * (its failsafe has dropped and disarmed it meanwhile): the link-back question. Gone again after DISARMED was
     * said: nothing asked.
     */
    @Test
    void aLinkLostWhileArmedIsAskedAndOneLostAfterDisarmedIsNot() throws Exception {
        PcConfig config = PcConfig.defaults();
        long droneStartedAt = System.currentTimeMillis();
        drone = BenchFleetAccess.start(0, "MobileZulu", config.getKey(), log::add);
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        FcTelemetry t = link.getTelemetry();
        waitFor(() -> t.isDisarmedNow(MonoClock.ms(), 1000), 5000, "DISARMED, said", log);

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
        waitFor(() -> BenchFleetAccess.hadSticks(drone), 2000, "the sticks reach the virtual FC", log);
        Thread.sleep(Math.max(0, FlightModel.BOOT_GRACE_MS + 700 - (System.currentTimeMillis() - droneStartedAt)));
        armValue = 1900;
        if (!Waits.until(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000)) { // a gap in the sticks as the switch went on: off and on again
            armValue = 1000;
            Thread.sleep(700);
            armValue = 1900;
        }
        waitFor(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000, "armed, as the PC's telemetry says", log);

        drone.link().silence(System.currentTimeMillis(), 5000); // 4G gone
        waitFor(() -> link.getState() != ControlLink.State.READY, 4000, "the PC counts the link lost", log);
        assertEquals(LostDroneAsk.ARMED_AT_LOSS, ask(), "armed as the link went: LOST DRONE asks, Keep trying the default");

        armValue = 1000; // the pilot's switch off; the failsafe dropped it meanwhile
        waitFor(() -> link.getState() == ControlLink.State.READY && t.isDisarmedNow(MonoClock.ms(), 1000), 10_000,
                "the link back by itself, DISARMED said", log);
        waitFor(() -> ask() == LostDroneAsk.LINK_BACK, 3000, "the link-back question while the link is up", log);

        drone.link().silence(System.currentTimeMillis(), 5000);
        waitFor(() -> link.getState() != ControlLink.State.READY, 4000, "the link lost again", log);
        assertEquals(LostDroneAsk.NOTHING, ask(), "disarmed as the link went: ended at the one click, as ever");
    }
}
