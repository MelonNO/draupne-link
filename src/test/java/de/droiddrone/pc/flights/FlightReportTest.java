package de.droiddrone.pc.flights;

import de.droiddrone.pc.flights.FlightFiles.Link;
import de.droiddrone.pc.flights.FlightFiles.Pt;
import de.droiddrone.pc.flights.FlightFiles.Row;
import de.droiddrone.pc.map.Pins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The report file: it stands alone (no script, nothing fetched), what the pilot typed can't become markup, and of the
 * link log only the graphs get in - no address.
 */
class FlightReportTest {
    static final String T0 = "2026-11-08T14:05:00";
    static final Pattern ADDRESS = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    static final Pattern PICTURE = Pattern.compile("src=\"data:image/png;base64,([A-Za-z0-9+/=]+)\"");

    @TempDir
    Path root;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    /** Two minutes with a track, a recording and a link log that names an address in its "Connecting to" line and in an event. */
    private FlightData flight(String drone) throws IOException {
        FlightFiles.gpx(dir("Tracks"), T0, 120, FlightSummaryTest::point);
        FlightFiles.recording(dir("Videos"), "2026-11-08T14:05:05", 110, FlightSummaryTest::row);
        FlightFiles.linkLog(dir("logs"), "2026-11-08T14:04:52", 135,
                List.of("Connecting to " + drone + " (192.0.2.7:8888)", "Tailscale path: direct 192.0.2.7:41641", "PCAlpha 203.0.113.5 is the master PC"),
                FlightSummaryTest::link);
        List<FlightLibrary.Flight> flights = FlightLibrary.scan(dir("Tracks"), dir("Videos"), dir("logs"));
        assertEquals(1, flights.size());
        return FlightData.load(flights.get(0));
    }

    private static BufferedImage picture() {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x203040));
        g.fillRect(0, 0, 320, 200);
        g.setColor(new Color(0xe31b23));
        g.drawLine(10, 190, 300, 20);
        g.dispose();
        return img;
    }

    /** The report without its picture's bytes: base64 is letters and digits, and any four of them can spell anything. */
    private static String text(String html) {
        return PICTURE.matcher(html).replaceAll("src=\"data:image/png;base64,\"");
    }

    private static void assertStandsAlone(String html) {
        String t = text(html), lower = t.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("http"), "no address of anything on a network");
        assertFalse(lower.contains("<script"), "no script");
        assertFalse(lower.contains("<link") || lower.contains("@import") || lower.contains("url(") || lower.contains("@font-face"), "no style sheet or font from elsewhere");
        assertFalse(lower.contains("href="), "no link");
        assertFalse(lower.contains("<iframe") || lower.contains("<object") || lower.contains("<embed") || lower.contains("<form"), "nothing embedded");
        assertFalse(Pattern.compile("\\son\\w+\\s*=").matcher(lower).find(), "no event handler");
        Matcher src = Pattern.compile("src=\"([^\"]{0,30})").matcher(lower);
        while (src.find()) assertTrue(src.group(1).startsWith("data:image/png;base64,"), "the only picture is the one inside the file: " + src.group(1));
    }

    @Test
    void theReportIsOneFileThatStandsAlone() throws IOException {
        FlightData d = flight("MobileAlpha");
        FlightSummary summary = FlightSummary.of(d);
        FlightNotes notes = FlightNotes.load(d.flight);
        notes.addBookmark(42_000, "Link back, FAILSAFE shown");
        notes.setNote("Wind from the west.\nLanded 220 m short.");
        Pins pins = new Pins(null);
        List<FlightMarks.Mark> marks = FlightMarks.of(d.flight, notes,
                List.of(new Pins.Pin("Gate", 59.912, 10.731, d.startMs() + 65_000, false), pins.add("Not of this flight", 59.9, 10.7)), List.of());
        BufferedImage picture = picture();

        String html = FlightReport.html(d, summary, marks, notes, picture);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.trim().endsWith("</html>"));
        assertStandsAlone(html);

        assertTrue(html.contains("<title>Flight 2026-11-08 14:05 - MobileAlpha</title>"), "when, and which drone");
        assertTrue(html.contains("<h1>FLIGHT 2026-11-08 14:05</h1>"));
        assertTrue(html.contains("<p class=\"drone\">MobileAlpha</p>"));
        for (FlightSummary.Figure fig : summary.figures()) {
            assertTrue(html.contains("<dt>" + fig.label() + "</dt><dd>" + fig.value() + "</dd>"), "the summary's " + fig.label());
        }
        assertTrue(html.contains("<dt>MAX HEIGHT</dt><dd>42 M</dd>"));
        assertTrue(html.contains("above take-off"), "which altitude the highest one is");
        assertTrue(html.contains("<td class=\"time\">0:42</td><td class=\"kind\">BOOKMARK</td><td>Link back, FAILSAFE shown</td>"));
        assertTrue(html.contains("<td class=\"time\">1:05</td><td class=\"kind\">PIN</td><td>Gate</td>"));
        assertFalse(html.contains("Not of this flight"));
        assertTrue(html.contains("<p class=\"note\">Wind from the west.\nLanded 220 m short.</p>"), "the note, its lines kept");

        // the picture: inside the file, the one given
        Matcher m = PICTURE.matcher(html);
        assertTrue(m.find(), "the track's picture is in the file");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(m.group(1))));
        assertEquals(320, back.getWidth());
        assertEquals(200, back.getHeight());
        assertEquals(picture.getRGB(5, 5), back.getRGB(5, 5));
        assertEquals(picture.getRGB(155, 105), back.getRGB(155, 105));
        assertFalse(m.find(), "once");

        // three graphs, drawn in the file: height, cells, ping - and the link's two losses behind the ping
        assertEquals(3, html.split("<svg ", -1).length - 1);
        assertTrue(html.contains("<h2>HEIGHT ABOVE TAKE-OFF</h2>") && html.contains("<h2>CELL VOLTAGE</h2>") && html.contains("<h2>PING</h2>"));
        assertEquals(3, html.split("<path class=\"line ", -1).length - 1, "a line in each");
        assertEquals(2, html.split("<rect class=\"down\"", -1).length - 1, "the two losses of the flight");
        Matcher ping = Pattern.compile("<path class=\"line ping\" d=\"([^\"]+)\"").matcher(html);
        assertTrue(ping.find());
        assertEquals(3, ping.group(1).split("M", -1).length - 1, "the ping's line stops at each loss and starts again after it");
        assertTrue(html.contains(">42 M</text>") && html.contains(">0 M</text>"), "the height's scale");
        assertTrue(html.contains(">4.20 V</text>") && html.contains(">0.80 V</text>"), "the cells' scale: all that was recorded");
        assertTrue(html.contains(">310 MS</text>"), "the ping's scale");
        assertTrue(html.contains(">1:59</text>"), "the flight's length under them");
        assertFalse(text(html).contains("NaN") || text(html).contains("Infinity"));
    }

    @Test
    void nothingOfTheLinkLogButItsGraphsGetsIn() throws IOException {
        FlightData d = flight("MobileAlpha");
        String log = Files.readString(d.flight.linkLog(), StandardCharsets.UTF_8);
        assertTrue(ADDRESS.matcher(log).find(), "the made-up log does hold addresses");
        String html = FlightReport.html(d, FlightSummary.of(d), d.marks(FlightNotes.load(d.flight), null, null), null, picture());
        String t = text(html);
        Matcher m = ADDRESS.matcher(t);
        assertFalse(m.find(), () -> "address-like text in the report: " + m.group());
        for (String s : List.of("192.0.2", "203.0.113", "8888", "41641", "Tailscale", "PCAlpha", "Connecting", "master PC")) {
            assertFalse(t.contains(s), "from the link log's events: " + s);
        }
        for (Path p : d.flight.files()) {
            assertFalse(t.contains(p.getFileName().toString()) || t.contains(p.getParent().toString()) || t.contains(p.getParent().getFileName().toString()),
                    "no file's name or folder (a path carries the Windows user's name): " + p);
        }

        // a log from before phones had names gives an address for the drone: left out
        FlightLibrary.Flight f = d.flight;
        for (String address : List.of("192.0.2.7:8888", "192.0.2.7", "(192.0.2.7:8888)", "phone.example:8888")) {
            FlightLibrary.Flight unnamed = new FlightLibrary.Flight(f.startMs(), f.endMs(), address, f.track(), f.video(), f.osdVideo(), f.telemetry(), f.linkLog(), f.distanceM());
            String h = FlightReport.html(FlightData.load(unnamed), FlightSummary.of(unnamed), List.of(), null, null);
            assertTrue(h.contains("<title>Flight 2026-11-08 14:05</title>"), address);
            assertFalse(h.contains("192.0.2") || h.contains("8888") || h.contains("class=\"drone\">"), address);
        }
    }

    @Test
    void whatWasTypedCannotBecomeMarkup() throws IOException {
        FlightData d = flight("<b>&");
        assertEquals("<b>&", d.flight.drone(), "the name as the flight's data gives it");
        FlightNotes notes = FlightNotes.load(d.flight);
        notes.addBookmark(10_000, "<img src=x onerror=alert(1)> \"quoted\" & 'single'");
        notes.setNote("</p><script>alert('note')</script>\n<a href=\"https://example.invalid/\">look</a> 5 < 6 & 7 > 6\u0000\u0007");
        List<FlightMarks.Mark> marks = FlightMarks.of(d.flight, notes, List.of(new Pins.Pin("<svg onload=x>", 59.9, 10.7, d.startMs() + 20_000, true)), List.of());

        String html = FlightReport.html(d, FlightSummary.of(d), marks, notes, null);
        assertTrue(html.contains("<title>Flight 2026-11-08 14:05 - &lt;b&gt;&amp;</title>"));
        assertTrue(html.contains("<p class=\"drone\">&lt;b&gt;&amp;</p>"));
        assertFalse(html.contains("<b>"));
        assertTrue(html.contains("<td>&lt;img src=x onerror=alert(1)&gt; &quot;quoted&quot; &amp; &#39;single&#39;</td>"));
        assertTrue(html.contains("<td>&lt;svg onload=x&gt;</td>"));
        assertTrue(html.contains("<p class=\"note\">&lt;/p&gt;&lt;script&gt;alert(&#39;note&#39;)&lt;/script&gt;\n"
                + "&lt;a href=&quot;https://example.invalid/&quot;&gt;look&lt;/a&gt; 5 &lt; 6 &amp; 7 &gt; 6</p>"), "the note as text, its control characters gone");
        String lower = html.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("<script") || lower.contains("<img") || lower.contains("<a ") || lower.contains("<svg onload"));
        assertEquals(1, html.split("<p class=\"note\">", -1).length - 1);
        assertEquals(1, html.split("</html>", -1).length - 1);
        // the only "http" in it is the pilot's own text, as text
        assertEquals(1, lower.split("http", -1).length - 1);
        assertEquals(1, lower.split("href=", -1).length - 1);
    }

    @Test
    void aFlightWithOnlyATrackAndNoPictureStillMakesAReport() throws IOException {
        FlightFiles.gpx(dir("Tracks"), T0, 60, s -> new Pt(59.91 + s * 0.0001, 10.73, 100 + s, s > 0));
        FlightLibrary.Flight f = FlightLibrary.scan(dir("Tracks"), null, null).get(0);
        FlightData d = FlightData.load(f);
        String html = FlightReport.html(d, null, null, null, null);
        assertStandsAlone(html);
        assertFalse(html.contains("<img"), "no picture given: none in it");
        assertTrue(html.contains("<title>Flight 2026-11-08 14:05</title>"), "the drone isn't known without a link log");
        assertFalse(html.contains("class=\"drone\""));
        assertTrue(html.contains("<dt>MIN CELL</dt><dd>-</dd>") && html.contains("<dt>LINK LOSSES</dt><dd>-</dd>"), "what isn't known says so");
        assertTrue(html.contains("<h2>GPS ALTITUDE</h2>"), "the track's altitude is the GPS's, and the graph says so");
        assertTrue(html.contains(">159 M</text>") && html.contains(">100 M</text>"));
        assertEquals(1, html.split("<svg ", -1).length - 1, "one graph: the altitude");
        assertEquals(2, html.split("<p class=\"none\">NO DATA</p>", -1).length - 1, "no battery and no ping to draw");
        assertTrue(html.contains("<p class=\"none\">NO MARKS</p>"));
        assertFalse(html.contains("<h2>NOTE</h2>"));

        // nothing at all but the times
        FlightLibrary.Flight bare = new FlightLibrary.Flight(f.startMs(), f.endMs(), null, null, null, null, null, null, Double.NaN);
        String empty = FlightReport.html(FlightData.load(bare), FlightSummary.of(bare), List.of(), null, null);
        assertStandsAlone(empty);
        assertTrue(empty.contains("<h2>ALTITUDE</h2>"));
        assertEquals(3, empty.split("<p class=\"none\">NO DATA</p>", -1).length - 1);
    }

    @Test
    void writtenWholeThenMovedIntoPlace() throws IOException {
        FlightData d = flight("MobileAlpha");
        assertEquals("flight-2026-11-08_1405-report.html", FlightReport.fileName(d.flight));
        Path target = root.resolve("Exports").resolve("new folder").resolve(FlightReport.fileName(d.flight));
        Files.createDirectories(target.getParent());
        Files.writeString(target, "an older report");
        Path written = FlightReport.write(target, d, FlightSummary.of(d), List.of(), null, picture());
        assertEquals(target, written);
        String html = Files.readString(target, StandardCharsets.UTF_8);
        assertEquals(FlightReport.html(d, FlightSummary.of(d), List.of(), null, picture()), html);
        try (var s = Files.list(target.getParent())) {
            assertEquals(List.of(target), s.toList(), "nothing left beside it");
        }
    }

    @Test
    void aLinkLogWithoutPingStillShowsWhereTheLinkWasDown() throws IOException {
        FlightFiles.gpx(dir("Tracks"), T0, 60, s -> new Pt(59.91, 10.73, 100, s > 0));
        FlightFiles.linkLog(dir("logs"), "2026-11-08T14:04:55", 60, List.of("Connecting to MobileAlpha (192.0.2.7:8888)"),
                i -> i >= 300 && i < 340 ? Link.down() : new Link("READY", -1, "0", "0"));
        FlightData d = FlightData.load(FlightLibrary.scan(dir("Tracks"), null, dir("logs")).get(0));
        String html = FlightReport.html(d, FlightSummary.of(d), List.of(), null, null);
        assertEquals(1, html.split("<rect class=\"down\"", -1).length - 1);
        assertTrue(html.contains("RED: LINK DOWN"));
        assertTrue(html.contains("<dt>LINK LOSSES</dt><dd>1</dd>") && html.contains("<dt>WORST PING</dt><dd>-</dd>"));
    }
}
