package de.droiddrone.pc.fleet;

import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorClient;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The virtual fleet's pilot PC (1.0.56, the tour's spectator steps) against DRAUPNE Link's real spectator code on
 * loopback, ephemeral ports: a spectator finds it flying - the drone's picture and telemetry through its spectator
 * relay, PILOT mode and the drone's name through the mirror - and follows it to OBSERVER, its tiles' streams relayed.
 */
class VirtualPilotPcTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Fleet fleet;
    private VirtualPilotPc pilot;
    private ControlLink spectator;
    private MirrorClient mirror;

    @AfterEach
    void tearDown() {
        if (mirror != null) mirror.close();
        if (spectator != null) spectator.close();
        if (pilot != null) pilot.close();
        if (fleet != null) fleet.close();
    }

    private static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("waited " + ms + " ms for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void aSpectatorWatchesItFlyThenFollowsItToObservation() throws Exception {
        PcConfig config = PcConfig.defaults();
        fleet = new Fleet(config, null, log::add);
        VirtualDrone a = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0);
        VirtualDrone b = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT + 0.001, Fleet.DEFAULT_LON, 90);
        pilot = new VirtualPilotPc(fleet, config, log::add);
        pilot.fly(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON + 0.001);
        InetAddress at = InetAddress.getByName(pilot.address());

        // the spectator, as Connection.open makes one to watch a PC picked by name
        AtomicInteger configs = new AtomicInteger();
        spectator = new ControlLink(config, at, pilot.port(), 0);
        spectator.enableSpectators(false, 300);
        spectator.watchOnly(true);
        spectator.setVideoListener(new ControlLink.VideoListener() {
            @Override
            public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                configs.incrementAndGet();
            }

            @Override
            public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
            }
        }, false);
        spectator.start();
        AtomicReference<Map<String, byte[]>> state = new AtomicReference<>();
        List<Integer> tiles = new CopyOnWriteArrayList<>();
        mirror = new MirrorClient(at, pilot.port(), config.getKey(), new MirrorClient.Listener() {
            @Override
            public void state(Map<String, byte[]> sections) {
                state.set(sections);
            }

            @Override
            public void tile(int tile, byte[] d, int len) {
                if (d[0] == UdpCommon.VideoInitialFrame) tiles.add(tile);
            }
        });

        waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 8000, "spectating the virtual pilot PC");
        waitFor(() -> configs.get() > 0, 8000, "the flown drone's picture");
        waitFor(() -> spectator.getTelemetry().getGps() != null, 5000, "the flown drone's position");
        waitFor(() -> mode(state) != null && mode(state).mode() == Mirror.PILOT, 3000, "PILOT mode mirrored");
        assertEquals(pilot.drone().name(), mode(state).phone());
        // the autopilot arms it and climbs (the FC's GPS fix comes after 6 s)
        waitFor(() -> pilot.drone().fc.isArmed() && pilot.drone().fc.altitudeM() > 5, 20_000, "the drone up");
        // ...to its cruise height, and holds it while it circles
        waitFor(() -> pilot.drone().fc.altitudeM() > VirtualPilotPc.CRUISE_M - 5, 15_000, "at cruise height");
        Thread.sleep(2000);
        double alt = pilot.drone().fc.altitudeM();
        assertTrue(Math.abs(alt - VirtualPilotPc.CRUISE_M) < 8, "holds its height: " + alt);
        assertTrue(pilot.drone().fc.groundSpeedMs() > 1, "circles");

        pilot.observe(List.of(a, b));
        assertTrue(!pilot.drone().fc.isArmed(), "landed and disarmed before it observes");
        waitFor(() -> mode(state) != null && mode(state).mode() == Mirror.OBSERVER, 3000, "OBSERVER mode mirrored");
        assertEquals(List.of(a.name(), b.name()), mode(state).tiles());
        waitFor(() -> tiles.contains(0) && tiles.contains(1), 8000, "both tiles' streams relayed");
    }

    private static Mirror.Mode mode(AtomicReference<Map<String, byte[]>> s) {
        Map<String, byte[]> m = s.get();
        return m == null ? null : Mirror.Mode.decode(m.get(Mirror.MODE));
    }
}
