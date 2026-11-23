package de.droiddrone.pc.gui;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.ArduShow;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualFleet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD area I: the tour's virtual ArduPilot drones (ArduShow driven by the window's FleetOps) - on the virtual
 * fleet's loopback addresses, answering name queries as phones (map, FLEET: ARDU), a group formed by the same
 * right-click items the pilot presses, DISCARD LEAD handing the lead on; the steps' tags; SETUP > Fleet demo's start.
 * Places and names made up; real UDP on loopback.
 */
class TourFleetTest {
    private Fleet fleet;
    private ArduShow show;

    @AfterEach
    void close() {
        if (show != null) show.close();
        if (fleet != null) fleet.close();
    }

    @Test
    void theShowsDronesAreVirtualPhonesThatFlyAGroupByTheWindowsOwnLogic(@TempDir Path dir) throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        fleet = new Fleet(PcConfig.defaults(), dir.resolve(VirtualFleet.FILE), log::add);
        show = TourArdu.start(fleet, 59.9, 10.75, log::add);
        assertNotNull(show, log.toString());
        assertEquals(8, show.names().size(), "a lead, four followers, a reserve, two scouts");
        for (String n : show.names()) {
            String ip = show.addressOf(n).getAddress().getHostAddress();
            assertTrue(VirtualFleet.isVirtual(ip), n + " on loopback only (TO-2: never a real drone)");
        }
        // a name query answered as a phone does: its name, where it is, the device the FLEET card calls ARDU
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            s.setSoTimeout(2000);
            byte[] q = Names.query(show.key());
            s.send(new DatagramPacket(q, q.length, show.addressOf(ArduShow.LEAD)));
            DatagramPacket p = new DatagramPacket(new byte[512], 512);
            s.receive(p);
            Names.Reply r = Names.parseReply(p.getData(), p.getLength(), show.key());
            assertNotNull(r);
            assertEquals(ArduShow.LEAD, r.name());
            assertEquals(ArduShow.DEVICE, r.device());
            assertNotNull(r.position());
        }
        // FOLLOW GROUP by the window's own right-click items, as the pilot does
        Thread.sleep(500);
        javax.swing.SwingUtilities.invokeAndWait(() -> assertTrue(TourArdu.formGroup(show), "the start item was there"));
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end && show.ops().picture() == null) Thread.sleep(100);
        assertNotNull(show.ops().picture(), "the group formed: " + log);
        assertEquals(4, show.ops().picture().followers());
        for (String f : ArduShow.FOLLOWERS) {
            end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end && !show.phone(f).isFollower()) Thread.sleep(100);
            assertTrue(show.phone(f).isFollower(), f + " follows");
        }
        // DISCARD LEAD: the next lead from the bottom row, "flown" by the show
        Thread.sleep(1500);
        show.ops().discardLead();
        end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && ArduShow.LEAD.equals(show.lead())) Thread.sleep(100);
        assertTrue(ArduShow.FOLLOWERS.contains(show.lead()), "a follower leads now: " + show.lead() + " " + log);
        assertTrue(show.phone(ArduShow.RESERVE).launchState() == de.droiddrone.common.launch.LaunchSequence.IDLE
                && !show.phone(ArduShow.RESERVE).armed(), "the reserve never armed: nobody called it");
    }

    @Test
    void theFleetStepsAreTaggedAndTheFleetDemoStartsAtThem() {
        List<Tour.Step> steps = List.of(new Tour.Step("PREVIOUS FLIGHTS", "", () -> null), new Tour.Step("ARDUPILOT DRONES", "", () -> null),
                new Tour.Step("FLEET", "", () -> null));
        assertEquals(1, Tour.arduStepIndex(steps));
        assertEquals(-1, Tour.arduStepIndex(List.of(new Tour.Step("MENU", "", () -> null))));
        assertTrue(Tour.FEATURES.containsAll(List.of(Tour.FEATURE_ARDUPILOT, Tour.FEATURE_ROUTES, Tour.FEATURE_DISPATCH,
                Tour.FEATURE_FOLLOW, Tour.FEATURE_AUTOLAUNCH)));
    }
}
