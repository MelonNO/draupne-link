package de.droiddrone.pc.sitl;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SITL lab flown as the pilot flies it: the PC's own link (ControlLink, what the window's PILOT UAV starts) to a
 * phone drone - through the lab's relay, the emulator, the real phone app (bench build) and its TCP flight controller
 * port to ArduPilot SITL set up by Drone setup's plan. ArduPilot found, the drone armed by the ARM switch (AUX1),
 * climbed in ALT HOLD (AUX2 in the middle) on the throttle, and down again. Needs the emulator and SITL; a few minutes.
 */
@Tag("ardusitl")
class SitlLabFlightTest {
    /** The lab's files (its emulator log stays open a moment after the emulator, so not a @TempDir). */
    private final Path dir = Path.of(System.getProperty("bench.build", "build"), "sitl-lab-test");
    private SitlLab lab;
    private ControlLink link;
    private volatile boolean sending;
    private volatile int throttle = 1000, arm = 1000;

    @AfterEach
    void close() {
        sending = false;
        if (link != null) link.close();
        if (lab != null) {
            lab.close();
            // the emulators' memory given back before the next SITL test: a SITL drone started right after them was
            // starved, lost its GPS health and was refused arming (SitlReturnTest in a full fleetSitl run, 2026-11-24)
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void aPhoneDroneIsConnectedArmedAndFlownOnTheSticks() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        Assumptions.assumeTrue(de.droiddrone.bench.PhoneRig.setUp(), "no Android emulator set up");
        SitlLabSettings s = SitlLabSettings.read(null, Map.of("phones", "1", "fleetOnly", "0", "lat", "59.93", "lon", "10.75", "alt", "100"));
        java.nio.file.Files.createDirectories(dir);
        lab = new SitlLab(s, dir, dir.resolve("virtual-fleet.txt"));
        lab.run();
        InetSocketAddress phone = lab.phoneAddresses().get(0);
        ArduSitl sitl = lab.drones().get(0);

        PcConfig config = PcConfig.defaults();
        link = new ControlLink(config, phone.getAddress(), phone.getPort(), 0);
        link.start();
        until(() -> link.getState() == ControlLink.State.READY, 30_000, "the link READY");
        until(() -> link.getFcInfo() != null, 30_000, "the phone says which flight controller");
        assertEquals(FcInfo.FC_VARIANT_ARDUPILOT, link.getFcInfo().getFcVariant(), "ArduPilot behind the phone");

        sending = true;
        Thread sticks = new Thread(() -> {
            while (sending) {
                link.sendRc(new short[]{1500, 1500, 1500, (short) throttle, (short) arm, 1500, 1000, 1000}); // AUX2 1500: ALT HOLD
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "labSticks");
        sticks.setDaemon(true);
        sticks.start();
        Thread.sleep(2000);
        assertFalse(sitl.fc.armedFlag(), "on the ground, disarmed");
        assertEquals(2, waitMode(sitl, 2), () -> "AUX2 in the middle: ALT HOLD (mode 2), from the radio through the phone\nthe phone app said:\n"
                + lab.phoneLog(0, 60));

        arm = 1900;
        until(sitl.fc::armedFlag, 20_000, "ArduPilot armed by the ARM switch");
        until(() -> link.getTelemetry().isArmedNow(MonoClock.ms(), 2000), 10_000, "the PC's telemetry says ARMED");
        throttle = 1800;
        until(() -> sitl.fc.position() != null && sitl.fc.position()[3] > 15, 40_000, "climbed above 15 m on the throttle");
        throttle = 1500; // ALT HOLD: the middle holds the height
        double held = sitl.fc.position()[3];
        Thread.sleep(5000);
        assertEquals(held, sitl.fc.position()[3], 4, "ALT HOLD holds with the throttle in the middle");
        throttle = 1100;
        until(() -> sitl.fc.position()[3] < 1, 90_000, "down again");
        throttle = 1000;
        arm = 1000;
        until(() -> !sitl.fc.armedFlag(), 20_000, "disarmed by the switch on the ground");
    }

    /**
     * Fleet PRD FD-3 on the real phone app: two phone drones marked SAFE TO AUTOLAUNCH from the window's fleet logic and
     * DISPATCHed - each phone's own LaunchRunner and FleetRole (GUIDED, arm, the climb, the route block put together and
     * uploaded over MAVLink, AUTO) against ArduPilot; each must end holding at its slot. The stand-in phone of the other
     * SITL tests is not in it.
     */
    @Test
    void twoPhoneDronesAreDispatchedByTheirOwnPhoneApps() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        Assumptions.assumeTrue(de.droiddrone.bench.PhoneRig.setUp(), "no Android emulator set up");
        double lat = 59.93, lon = 10.75, alt = 100;
        SitlLabSettings s = SitlLabSettings.read(null, Map.of("phones", "2", "fleetOnly", "0", "lat", "" + lat, "lon", "" + lon, "alt", "" + alt));
        java.nio.file.Files.createDirectories(dir);
        lab = new SitlLab(s, dir, dir.resolve("virtual-fleet.txt"));
        lab.run();
        java.util.List<InetSocketAddress> at = lab.phoneAddresses();
        Map<String, InetSocketAddress> phones = Map.of("P1", at.get(0), "P2", at.get(1));
        java.util.List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        de.droiddrone.pc.gui.FleetOpsAccess ops = new de.droiddrone.pc.gui.FleetOpsAccess(null, phones::get, log, (la, lo) -> alt);
        try {
            for (String n : phones.keySet()) ops.click(n, "SAFE TO AUTOLAUNCH: mark");
            Thread.sleep(3000);
            double[] target = de.droiddrone.common.group.GeoLocal.offset(lat, lon, 0, 200);
            ops.dispatch(target[0], target[1]);
            long end = System.currentTimeMillis() + 300_000;
            boolean held = false;
            while (System.currentTimeMillis() < end && !held) {
                ops.tick();
                held = lab.drones().stream().allMatch(d -> d.fc.customMode() == 3 && d.fc.position() != null
                        && de.droiddrone.common.group.GeoLocal.distance(target[0], target[1], d.fc.position()[0], d.fc.position()[1]) < 80
                        && Math.hypot(d.fc.position()[5], d.fc.position()[6]) < 0.5);
                Thread.sleep(500);
            }
            String states = "P1 " + ops.stateOf("P1") + " [" + ops.launchOf("P1") + "], P2 " + ops.stateOf("P2") + " [" + ops.launchOf("P2") + "]";
            assertTrue(held, () -> "both hold at their slots in AUTO: FLEET says " + states + "; modes " + lab.drones().stream().map(d -> d.fc.customMode()).toList()
                    + "\nthe window's log: " + log + "\nphone 1 said:\n" + lab.phoneLog(0, 50) + "\nphone 2 said:\n" + lab.phoneLog(1, 50));
            double h1 = lab.drones().get(0).fc.position()[3], h2 = lab.drones().get(1).fc.position()[3];
            assertTrue(Math.abs(h1 - h2) > 8, "each its own layer: " + h1 + " / " + h2);
        } finally {
            ops.close();
        }
    }

    private static int waitMode(ArduSitl s, int mode) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && s.fc.customMode() != mode) Thread.sleep(100);
        return s.fc.customMode();
    }

    private void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("waited " + ms / 1000 + " s for: " + what + " (link " + link.getState() + ", ArduPilot mode "
                + lab.drones().get(0).fc.customMode() + ", armed " + lab.drones().get(0).fc.armedFlag() + ")\nthe phone app said:\n" + lab.phoneLog(0, 60));
    }
}
