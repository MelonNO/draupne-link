package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.pc.Connection;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The picture delay glass to glass (the test week's delay item): the real phone films the app's own Latency test - its
 * code on this PC's main screen - and every reading of it (TOOLS &gt; Latency test: from the code painted to the
 * picture of it on the screen, the whole way a pilot's eyes have) is set beside the app's own estimate of the same
 * delay, the OSD's G2G (video.GlassToGlass: the phone's capture to send, the network, this PC), per network, picture,
 * codec, with recording, STANAG and a spectator on and off ({@link HwGlassToGlass#plan}). The real, sealed Windows app
 * and the one real phone, the bench's wire between them (as in {@link HwLinkMatrixScenario}: each network on top of
 * what the real one does); no flight controller is needed, nothing is armed.
 * <p>
 * By hand, before: {@link HwGlassToGlass#SET_UP}. The scenario places the app's windows itself. If the camera does
 * not read the code within {@link HwGlassToGlass#CAMERA_CHECK_MS} the run says so plainly, with a still of what the
 * camera saw ({@code matrix\hw-g2g-camera-check.png}), and the scenario is skipped - no failure of the app. In quiet
 * mode (a real drone armed on this PC, BENCH_MODE=quiet) the windows are off the screen: skipped too.
 * <p>
 * A row a cell into {@code matrix\hw-g2g.tsv} in the run's folder (median, 90th percentile and most of each, side by
 * side), each cell's readings, the app's link-log rows and the phone's log in {@code matrix\logs}, each cell's figures
 * in plain words in the film. {@code -Dbench.quick=true} (bench.ps1's {@code -Quick}): the network as it is and one bar,
 * about five minutes; the whole table about 25. Without a hardware file it skips itself.
 */
@Tag("hw")
class HwGlassToGlassScenario {
    private static final long SEED = Long.getLong("bench.seed", 20261123L);
    private static final boolean QUICK = Boolean.getBoolean("bench.quick");

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void theRealPhoneFilmsTheLatencyTestsCodeAndEachReadingStandsBesideTheAppsOwnEstimate() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        List<HwGlassToGlass.Cell> plan = HwGlassToGlass.plan(SEED, QUICK);
        PhoneRig.Target target = PhoneRig.Target.device();
        String[] skipped = new String[1];
        // TOOLS > Advanced shows MENU > LINK's STANAG row; "Allow spectators" is read at PILOT UAV (the spectator cell's)
        AppRig.scenario("hw-glass-to-glass").hardware(bench).phoneThroughWire().setting("advanced", "true")
                .setting(Connection.ALLOW_SPECTATORS_KEY, "true").run(rig -> {
                    try (PhoneRig phone = new PhoneRig(rig.film, target)) {
                        phone.ensureRunning(); // a real phone: its log followed into the film's folder, its battery looked at
                        if (!phone.serviceRunning()) {
                            phone.launch();
                            phone.startIfAsked();
                        }
                        InetSocketAddress at = phone.linkAddress(); // the hardware file's address: the fence's one way through
                        try (Wire wire = new Wire(at.getAddress(), at.getPort())) {
                            skipped[0] = new HwGlassToGlassRun(rig, wire, phone, HwGlassToGlassRun.Options.real()).run(plan);
                        }
                    }
                });
        Assumptions.assumeTrue(skipped[0] == null, () -> skipped[0]);
    }
}
