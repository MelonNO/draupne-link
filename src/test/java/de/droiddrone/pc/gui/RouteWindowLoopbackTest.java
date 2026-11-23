package de.droiddrone.pc.gui;

import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.route.Route;
import de.droiddrone.pc.route.TerrainStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;

import static de.droiddrone.testing.Waits.waitFor;
import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet RT-8 / AC-10 end to end on loopback: the ROUTE window's UPLOAD over this PC's real pilot link to a virtual
 * ArduPilot phone, which takes the rally point and the route in the pilot's session as the real phone does
 * (MissionBlock) and answers with its status record; then GO flies it. A refused block says why.
 */
class RouteWindowLoopbackTest {
    static final double LAT = 59.91, LON = 10.73, GROUND = 100;

    private VirtualFleetPhone phone;
    private ControlLink link;
    private RouteWindow window;

    @AfterEach
    void close() throws Exception {
        if (window != null) SwingUtilities.invokeAndWait(window::dispose);
        if (link != null) link.close();
        if (phone != null) phone.close();
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    void uploadAndGoThroughThePilotsSession(@TempDir Path dir) throws Exception {
        PcConfig config = PcConfig.defaults();
        phone = new VirtualFleetPhone("Lead", LAT, LON, GROUND, 0, 0, GROUND, config.getKey());
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), phone.address().getPort(), freePort());
        link.start();
        waitFor(() -> {
            FcTelemetry t = link.getTelemetry();
            FcTelemetry.Gps g = t.getGps();
            return link.isConnected() && link.getFcInfo() != null && t.getHome() != null && g != null && g.positionOk()
                    && t.getPhonePcBuild() >= ControlLink.FC_COMMAND_MIN_PC_BUILD;
        }, 15_000, "the session up, the drone's position and home in");

        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MapView map = new MapView(new TileCache(dir, "test", false));
            map.setSize(800, 600);
            map.setFollow(false);
            map.centreOn(LAT, LON, 15);
            window = new RouteWindow(null, map, new TerrainStore(null), (la, lo) -> GROUND, () -> link, () -> null, () -> false);
            Route r = new Route("Loop");
            r.add(LAT + 0.002, LON);
            r.add(LAT + 0.002, LON + 0.003);
            window.setRoute(r);
        });
        JButton upload = window.sendButtons()[0], go = window.sendButtons()[1];
        waitForOnSwing(() -> window.planned() && upload.isEnabled(), 15_000, "planned: " + window.statusText());
        SwingUtilities.invokeAndWait(upload::doClick);
        waitForOnSwing(() -> go.isEnabled(), 15_000, "on the drone: " + window.statusText());
        assertTrue(window.statusText().startsWith("Route on the drone, read back"), window.statusText());
        List<MissionBlock> blocks = phone.sessionBlocks();
        assertEquals(List.of(MissionUpload.TYPE_RALLY, MissionUpload.TYPE_MISSION), blocks.stream().map(b -> b.missionType).toList(),
                "the rally point, then the route, in the pilot's session");
        assertEquals(MissionItem.NAV_TAKEOFF, blocks.get(1).items.get(1).command, "landed: it takes off");

        SwingUtilities.invokeAndWait(go::doClick);
        waitFor(phone::flyingRoute, 5000, "GO: it flies the route");

        // the phone refuses the next block: the reason comes back, no GO
        phone.refuseBlocks = "item 3 refused";
        SwingUtilities.invokeAndWait(() -> window.setRallyPlace((la, lo) -> new double[]{la + 0.0005, lo}));
        long t0 = MonoClock.ms();
        SwingUtilities.invokeAndWait(upload::doClick);
        waitForOnSwing(() -> window.statusText().contains("item 3 refused"), 15_000, window.statusText());
        assertTrue(MonoClock.ms() - t0 < 10_000);
        SwingUtilities.invokeAndWait(() -> assertFalse(go.isEnabled()));
    }
}
