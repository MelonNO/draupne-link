package de.droiddrone.bench.edge;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * What the app finds on disk when it starts is not always what it wrote: a settings file with rubbish in it, flight
 * files cut short or empty, a last-known file that is garbage, hundreds of flights. The app must start, say what it
 * can't read, and show the rest - never an error nobody caught, never a window that hangs.
 */
@Tag("edge")
class FilesScenario {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** A made-up armed flight of a minute: its record and its track, as the app writes them. */
    static void flight(Sandbox user, long startMs, String drone, Random random) throws Exception {
        Files.createDirectories(user.tracks());
        String stamp = STAMP.format(Instant.ofEpochMilli(startMs));
        Path track = user.tracks().resolve("track-" + stamp + "-" + drone + ".gpx");
        StringBuilder gpx = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"DRAUPNE Link dev\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:dl=\"https://github.com/MelonNO/draupne-link/gpx\">\n <metadata><time>"
                + Instant.ofEpochMilli(startMs) + "</time></metadata>\n <trk>\n  <name>Flight " + drone + "</name>\n  <trkseg>\n");
        double lat = 60.8669 + random.nextDouble() * 0.01, lon = 11.7381 + random.nextDouble() * 0.01;
        for (int i = 0; i < 60; i++) {
            lat += (random.nextDouble() - 0.5) * 0.0002;
            lon += (random.nextDouble() - 0.5) * 0.0004;
            gpx.append(String.format(Locale.ROOT, "   <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%d</ele><time>%s</time><sat>12</sat><extensions><dl:speed_kmh>%.1f</dl:speed_kmh>"
                            + "<dl:course>%d</dl:course><dl:armed>%d</dl:armed><dl:source>drone</dl:source></extensions></trkpt>\n",
                    lat, lon, Math.min(i, 60 - i), Instant.ofEpochMilli(startMs + i * 1000L), 20 + random.nextDouble() * 30, random.nextInt(360), i > 3 && i < 57 ? 1 : 0));
        }
        gpx.append("  </trkseg>\n </trk>\n</gpx>\n");
        Files.writeString(track, gpx, StandardCharsets.UTF_8);
        Files.writeString(user.tracks().resolve("flight-" + stamp + ".record"), "drone=" + drone + "\naddress=127.0.0.99\napp=dev\nstart=" + startMs + "\ntrack=" + track
                + "\narmed=" + (startMs + 4000) + "\narmedlast=" + (startMs + 56_000) + "\nend=" + (startMs + 60_000) + " DISCONNECT\n", StandardCharsets.UTF_8);
    }

    private static Window openPreviousFlights(Ui ui) {
        if (ui.has("POP OUT MAP")) ui.click("POP OUT MAP");
        ui.until("the map window", 15_000, () -> ui.window("DRAUPNE Link map") != null);
        if (!ui.has("PREVIOUS FLIGHTS")) {
            ui.click("MAP TOOLS");
            ui.click("DATA");
        }
        ui.click("PREVIOUS FLIGHTS");
        ui.until("the Previous flights window", 30_000, () -> ui.window("Previous flights") != null);
        return ui.window("Previous flights");
    }

    @Test
    void rubbishInTheSettingsFileDoesNotStopTheApp() throws Exception {
        AppRig.scenario("files-settings-rubbish").drones(1).before(user -> {
            // the good lines stay (among them the bench's own port); after them what a crash or another program leaves
            Files.writeString(user.settingsFile(), "\r\nthis line has no equals sign\r\n=value without a key\r\nmapWindow=not,a,window\r\nmainWindow=-99999,-99999,5,5,true\r\n"
                    + "rcRate=fast\r\nphone.sleepCheckMin=minus three\r\nname.=\r\nname.MobileAlpha=not an address\r\ndrone.outOfAction=@@@\r\nfleet.queue=;;;;\r\n"
                    + "videoMode=NOPE\r\ncoordinateFormat=klingon\r\npath=C:\\Users\\nobody\\u00zz\r\n\u0000\u0001\u0002\u00ff\u00fe binary\r\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); // bytes that are no UTF-8 at all: finding F-009, EdgeFindingsScenario
        }).run(rig -> {
            Ui ui = rig.ui;
            ui.shot("started with rubbish in the settings file");
            rig.check(ui.has("PILOT UAV"), "the main window is up and offers PILOT UAV");
            java.awt.Rectangle b = Ui.edt(() -> rig.main().getBounds());
            java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            rig.check(b.intersects(screen) && b.width > 300 && b.height > 200, "the window is on the screen at a usable size despite mainWindow=-99999,-99999,5,5: " + b);
            MenusScenario_open(ui, "VIDEO");
            ui.shot("the VIDEO menu with videoMode=NOPE in the file");
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            FilesScenario.openPreviousFlights(ui);
            rig.check(ui.window("Previous flights") != null, "PREVIOUS FLIGHTS opens");
        });
    }

    /**
     * F-025: "cameraWidth=wide" - one of the link's numbers that is none. PILOT UAV waited for the phone for ever and
     * the app's log grew by 6 MB a second. Now the link comes up on the built-in value, the window's log says which
     * value it could not read, and the app's log stays small.
     */
    @Test
    void aWrongNumberInTheSettingsFileNeitherStopsTheLinkNorFloodsTheLog() throws Exception {
        AppRig.scenario("files-settings-wrong-numbers").drones(1).before(user -> {
            Files.writeString(user.settingsFile(), "\r\ncameraWidth=wide\r\ntelemetryRate=often\r\nserialBaudRate=115200baud\r\nbitrateLimitMbps=4,5\r\nsharpWidth=\r\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }).run(rig -> {
            Ui ui = rig.ui;
            de.droiddrone.bench.Pilot pilot = new de.droiddrone.bench.Pilot(rig);
            pilot.connect();
            pilot.waitForVideo();
            ui.shot("LINK READY with five numbers that are none in the settings file");
            rig.check(rig.logged("cameraWidth=wide is not a number"), "the log says which value it could not read");
            MenusScenario_open(ui, "VIDEO");
            ui.shot("the VIDEO menu with sharpWidth empty in the file");
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            Ui.pause(4000);
            pilot.standby();
            long bytes = 0;
            try (java.util.stream.Stream<Path> logs = Files.list(rig.sandbox.logsDir())) {
                for (Path p : logs.filter(p -> p.getFileName().toString().startsWith("app-")).toList()) bytes += Files.size(p);
            }
            rig.check(bytes < 200_000, "the app's log stayed small: " + bytes + " bytes (it grew by 6 MB a second)");
        });
    }

    private static void MenusScenario_open(Ui ui, String menu) {
        java.util.List<java.awt.Component> top = ui.all(menu);
        if (top.isEmpty() || ui.whyNotPressable(top.get(0), null) != null) ui.click("MENU");
        ui.click(menu);
        Ui.pause(350);
    }

    @Test
    void brokenFlightFilesDoNotBreakPreviousFlights() throws Exception {
        AppRig.scenario("files-broken-flights").drones(1).before(user -> {
            Random r = new Random(5);
            long day = System.currentTimeMillis() - 86_400_000L;
            flight(user, day, "MobileGood", r); // one whole flight among the wreckage
            Path t = Files.createDirectories(user.tracks()), v = Files.createDirectories(user.videos());
            Files.write(t.resolve("track-20261101-100000-MobileEmpty.gpx"), new byte[0]);
            Files.writeString(t.resolve("track-20261101-110000-MobileCut.gpx"), "<?xml version=\"1.0\"?>\n<gpx><trk><trkseg>\n   <trkpt lat=\"60.1\" lon=\"11.1\"><ele>5</ele><time>2026-11-01T11:00:0");
            Files.writeString(t.resolve("track-20261101-120000-MobileNotXml.gpx"), "this is not a track at all \u0000\u0001");
            Files.writeString(t.resolve("track-20261101-130000-MobileFar.gpx"), "<?xml version=\"1.0\"?>\n<gpx><trk><trkseg>\n   <trkpt lat=\"999\" lon=\"-999\"><ele>NaN</ele><time>never</time></trkpt>\n"
                    + "   <trkpt lat=\"abc\" lon=\"\"><time>2026-11-01T13:00:01Z</time></trkpt>\n  </trkseg></trk></gpx>");
            Files.writeString(t.resolve("flight-20261101-140000.record"), "drone=MobileGhost\nstart=not a number\ntrack=C:\\nowhere\\at\\all.gpx\nvideo=Z:\\gone.mkv\narmed=1\nend=");
            Files.writeString(t.resolve("flight-20261101-150000.record"), "");
            Files.writeString(t.resolve("flight-20261101-160000.record"), "drone=MobileHalf\nstart=1793545200000\narmed=1793545205000\ntr");
            Files.writeString(v.resolve("flight-2026-11-01_17-00-00-MobileTiny.mkv"), "not video");
            Files.writeString(v.resolve("flight-2026-11-01_17-00-00-MobileTiny.csv"), "time,lat\nx,y\n,,,,,,\n");
            Files.write(v.resolve("flight-2026-11-01_18-00-00-MobileZero.mkv"), new byte[0]);
            Files.createDirectories(user.dataDir());
            Files.writeString(user.dataDir().resolve("last-known.txt"), "MobileA\tnot\ta\tnumber\nonly one field\n\t\t\t\t\t\t\t\t\t\t\n\u0000\u0000\nMobileB\t91.5\t500\t-1\t250\t\t\tNOPE\t\n");
        }).run(rig -> {
            Ui ui = rig.ui;
            ui.shot("started among broken flight files");
            Window flights = openPreviousFlights(ui);
            ui.shot("PREVIOUS FLIGHTS among broken files");
            int rows = Ui.edt(() -> ui.all(javax.swing.JList.class).stream().mapToInt(l -> l.getModel().getSize()).sum());
            rig.film.remark("PREVIOUS FLIGHTS lists " + rows + " flight(s)");
            rig.check(rows >= 1, "the whole flight among them is listed");
            // every row can be chosen without an error
            for (javax.swing.JList<?> list : ui.all(javax.swing.JList.class)) {
                int n = Ui.edt(() -> list.getModel().getSize());
                for (int i = 0; i < Math.min(n, 8); i++) {
                    ui.clickRow(list, i);
                    Ui.pause(700);
                }
            }
            ui.closeWindow(flights);
            ui.until("Previous flights closed", 5000, () -> ui.window("Previous flights") == null);
            ui.click("LAST KNOWN");
            ui.until("the Last known window", 5000, () -> ui.window("Last known") != null);
            ui.shot("LAST KNOWN from a garbage file");
            ui.click("CLOSE");
        });
    }

    @Test
    void fourHundredFlightsOpenWithoutTheWindowHanging() throws Exception {
        AppRig.scenario("files-400-flights").drones(1).before(user -> {
            Random r = new Random(11);
            long t = System.currentTimeMillis() - 400L * 3 * 3_600_000;
            for (int i = 0; i < 400; i++) flight(user, t + i * 3L * 3_600_000, "Mobile" + (char) ('A' + i % 26) + "lpha", r);
        }).run(rig -> {
            Ui ui = rig.ui;
            long t0 = System.currentTimeMillis();
            Window flights = openPreviousFlights(ui);
            long opened = System.currentTimeMillis() - t0;
            ui.until("the list filled", 60_000, () -> ui.all(javax.swing.JList.class).stream().mapToInt(l -> l.getModel().getSize()).sum() >= 400);
            long filled = System.currentTimeMillis() - t0;
            ui.shot("PREVIOUS FLIGHTS with 400 flights");
            rig.film.remark("PREVIOUS FLIGHTS with 400 flights: window after " + opened + " ms, list full after " + filled + " ms; the window's slowest answer " + rig.watch.maxLagMs() + " ms");
            rig.check(filled < 20_000, "400 flights are listed within 20 s: " + filled + " ms");
            // SEARCH narrows it
            javax.swing.JTextField search = ui.first(javax.swing.JTextField.class, f -> "Drone or date".equals(f.getToolTipText()));
            ui.fill(search, "MobileQlpha");
            Ui.pause(1500);
            int found = Ui.edt(() -> ui.all(javax.swing.JList.class).stream().mapToInt(l -> l.getModel().getSize()).sum());
            ui.shot("SEARCH MobileQlpha");
            rig.check(found > 0 && found < 400, "SEARCH by a drone's name narrows the list: " + found + " of 400");
            ui.closeWindow(flights);
        });
    }
}
