package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.video.LatencyMeter;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Component;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HwGlassToGlassScenario}'s own run before it meets the real phone and its camera: the same
 * {@link HwGlassToGlassRun} in the sealed app - the windows placed and the code found on the painted view, MENU &gt;
 * VIDEO's picture and RECORD VIDEO, MENU &gt; LINK's STANAG, the codec setting, TOOLS &gt; Latency test on and off,
 * PILOT UAV, STANDBY MODE, the readings tapped one by one, the app's own figures from its link log, the rows and the
 * film's words - against a made-up drone behind the bench's wire (each cell's network on it).
 * <ul>
 * <li>With a made-up camera: a thread that hands the app's own Latency test pictures of its very code, each
 * {@link #DELAY_MS} after the code was made - as a camera would that films the screen with that delay. Every kind of
 * cell measured: the readings must be that delay, the made-up drone's FRAME_TIME gives the app's own figure, the
 * stand-in spectator gets the picture passed on.</li>
 * <li>Without one (the made-up drone films a made-up scene): the camera check finds no code, says so plainly with a
 * still of what the camera saw, and skips the rest.</li>
 * </ul>
 * In quiet mode (BENCH_MODE=quiet) the windows are not moved onto the screen: the placement is said, not held to.
 */
@Tag("benchself")
class HwGlassToGlassSelfTest {
    private static final long SEED = 20261123L;
    /** The made-up camera's delay from the code made to its picture on the screen. */
    static final long DELAY_MS = 150;
    private static final long SETTLE_MS = 3000, MEASURE_MS = 8000;

    private static HwGlassToGlass.Cell cell(String name, de.droiddrone.bench.BadLink net, ControlLink.VideoMode picture, Boolean h264, boolean recording,
                                            boolean stanag, boolean spectator) {
        return new HwGlassToGlass.Cell(name, net, picture, h264, recording, stanag, spectator, SETTLE_MS, MEASURE_MS);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void withAMadeUpCameraEveryKindOfCellIsMeasuredAndWrittenInTheSealedApp() throws Exception {
        List<HwGlassToGlass.Cell> plan = List.of(
                cell("clean, NORMAL, H.265", HwGlassToGlass.clean(SEED), ControlLink.VideoMode.NORMAL, false, true, false, false),
                cell("one bar, SURVIVAL", HwGlassToGlass.preset(SEED, "one bar"), ControlLink.VideoMode.SURVIVAL, null, true, false, false),
                cell("clean, ROBUST, recording off", HwGlassToGlass.clean(SEED), ControlLink.VideoMode.ROBUST, null, false, false, false),
                cell("clean, NORMAL, H.264, STANAG on", HwGlassToGlass.clean(SEED), ControlLink.VideoMode.NORMAL, true, true, true, false),
                cell("clean, NORMAL, H.265, a spectator watching", HwGlassToGlass.clean(SEED), ControlLink.VideoMode.NORMAL, false, true, false, true));
        HwGlassToGlassRun[] run = new HwGlassToGlassRun[1];
        String[] skipped = {"not run"};
        AppRig.scenario("self-hw-glass-to-glass").setting("advanced", "true").setting(Connection.ALLOW_SPECTATORS_KEY, "true").run(rig -> {
            // a phone's name - Mobile and a letter of the spelling alphabet: the app files no other in its PHONE list
            VirtualDrone drone = BenchFleetAccess.start(0, "MobileGolf", rig.config.getKey(), rig.fleetLog::add);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), drone.port()); MadeUpCamera camera = new MadeUpCamera(rig.ui, DELAY_MS)) {
                run[0] = new HwGlassToGlassRun(rig, wire, null, new HwGlassToGlassRun.Options(false, 10_000));
                skipped[0] = run[0].run(plan);
                rig.film.remark("The made-up camera handed the Latency test " + camera.pictures() + " picture(s) of its code.");
            } finally {
                BenchFleetAccess.stop(drone);
            }
        });
        assertNull(skipped[0], "the camera check passed: the made-up camera's code was read");
        assertNotNull(run[0].placement, "the windows were placed and the code found on the painted view");
        assertTrue(run[0].placement.startsWith("the code a "), run[0].placement);
        List<HwLinkMatrix.Row> rows = run[0].rows;
        assertEquals(plan.size(), rows.size(), "a row a cell");
        for (HwLinkMatrix.Row r : rows) {
            String cell = r.get("cell");
            assertEquals("ok", r.get("verdict"), cell + ": " + r.get("what"));
            assertTrue(Integer.parseInt(r.get("readings")) >= 5, cell + ": readings " + r.get("readings"));
            long median = Long.parseLong(r.get("camera median ms")), max = Long.parseLong(r.get("camera max ms"));
            assertTrue(median >= DELAY_MS - 2 && median <= DELAY_MS + 40, cell + ": the made-up camera's " + DELAY_MS + " ms, measured " + median);
            assertTrue(max >= median && Long.parseLong(r.get("camera p90 ms")) >= median, cell + ": the 90th percentile and the most over the middle");
            assertNotEquals("-", r.get("app median ms"), cell + ": the made-up drone's FRAME_TIME gives the app's own figure");
            assertTrue(r.get("codec seen").startsWith("H.26"), cell + ": the stream's codec as the app's log says it: " + r.get("codec seen"));
            assertTrue(r.get("app's own test said").startsWith("median "), cell + ": the app's own test said its figure: " + r.get("app's own test said"));
            assertTrue(r.get("network").contains("passed"), cell + ": the wire's counts: " + r.get("network"));
        }
        assertEquals("one watching", rows.get(4).get("spectator"));
        assertTrue(Long.parseLong(rows.get(4).get("spectator packets")) > 0, "the stand-in spectator got the picture passed on");
        assertEquals("off", rows.get(2).get("recording"));
        assertEquals("on", rows.get(3).get("STANAG"));
        assertTrue(rows.get(1).get("size seen").contains("480x270"), "SURVIVAL's picture: " + rows.get(1).get("size seen"));
        Path table = Sandbox.runRoot().resolve("matrix").resolve(HwGlassToGlass.TABLE + ".tsv");
        List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        assertEquals(String.join("\t", HwGlassToGlass.COLUMNS), lines.get(0));
        for (HwLinkMatrix.Row r : rows) assertTrue(lines.contains(r.line()), "the row in " + table + ": " + r.get("cell"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void withoutACameraThatSeesTheCodeTheRunSaysSoWithAStillAndSkipsTheRest() throws Exception {
        List<HwGlassToGlass.Cell> plan = List.of(cell("clean, NORMAL, H.265", HwGlassToGlass.clean(SEED), ControlLink.VideoMode.NORMAL, false, true, false, false),
                cell("one bar, NORMAL, H.265", HwGlassToGlass.preset(SEED, "one bar"), ControlLink.VideoMode.NORMAL, false, true, false, false));
        HwGlassToGlassRun[] run = new HwGlassToGlassRun[1];
        String[] skipped = new String[1];
        AppRig.scenario("self-hw-glass-to-glass-no-code").setting("advanced", "true").run(rig -> {
            VirtualDrone drone = BenchFleetAccess.start(0, "MobileGolf", rig.config.getKey(), rig.fleetLog::add);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), drone.port())) {
                run[0] = new HwGlassToGlassRun(rig, wire, null, new HwGlassToGlassRun.Options(false, 6000));
                skipped[0] = run[0].run(plan);
            } finally {
                BenchFleetAccess.stop(drone);
            }
        });
        assertNotNull(skipped[0], "the camera check found no code");
        assertTrue(skipped[0].startsWith("THE CAMERA DOES NOT SEE THE LATENCY TEST'S CODE: no reading of it in "), skipped[0]);
        assertTrue(skipped[0].contains("no failure of the app") && skipped[0].contains(HwGlassToGlass.SET_UP), skipped[0]);
        Path still = Sandbox.runRoot().resolve("matrix").resolve(HwGlassToGlass.TABLE + "-camera-check.png");
        assertTrue(skipped[0].contains("What the camera saw: " + still), "the still of what the camera saw, saved by the app's STILL: " + skipped[0]);
        assertTrue(Files.size(still) > 1000, "a picture: " + still);
        assertEquals(1, run[0].rows.size(), "the first cell's row, then nothing");
        HwLinkMatrix.Row r = run[0].rows.get(0);
        assertEquals("skipped", r.get("verdict"));
        assertTrue(r.get("what").startsWith("THE CAMERA DOES NOT SEE"), r.get("what"));
        assertEquals("-", r.get("readings"), "nothing measured");
    }

    /**
     * A camera that films the screen with a known delay, made up: while the app's Latency test runs, it takes the
     * test's code as the test makes it now, waits its delay, and hands the test a picture of it - the way the decoder
     * hands the test each picture from the phone. The app's own meter reads it as it reads the phone's.
     */
    private static final class MadeUpCamera implements AutoCloseable {
        private final Thread thread;
        private volatile boolean running = true;
        private volatile long pictures;

        MadeUpCamera(Ui ui, long delayMs) {
            thread = new Thread(() -> {
                Component view = null;
                while (running) {
                    try {
                        if (view == null) view = MeterTap.videoView(ui);
                        LatencyMeter m = view == null ? null : MeterTap.meter(view);
                        if (m == null) {
                            Thread.sleep(50);
                            continue;
                        }
                        int[] frame = HwGlassToGlassTest.frameWithCode(m.codeNow(), 640, 360, 100, 40, 9);
                        Thread.sleep(delayMs);
                        m.offer(frame, 640, 360, System.nanoTime());
                        pictures++;
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        try {
                            Thread.sleep(100); // the app closing, a window changing: looked at again
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                }
            }, "madeUpCamera");
            thread.setDaemon(true);
            thread.start();
        }

        long pictures() {
            return pictures;
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            thread.interrupt();
            thread.join(2000);
        }
    }
}
