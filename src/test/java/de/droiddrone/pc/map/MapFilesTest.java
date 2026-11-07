package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The map's own files and counts (1.0.49): broken files moved aside (M12), OpenCelliD's day kept and held (L11, H7). */
class MapFilesTest {
    @TempDir
    Path dir;

    private List<Path> broken(String name) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(name + ".broken-")).toList();
        }
    }

    @Test
    void aBrokenGraphicsFileIsMovedAsideBeforeAnythingIsWrittenOverIt() throws Exception {
        Path file = dir.resolve("graphics.kml");
        String damaged = "<?xml version=\"1.0\"?><kml><Document><Placemark><name>Gun line</name>"; // cut off mid-write
        Files.writeString(file, damaged, StandardCharsets.UTF_8);
        MapGraphics g = new MapGraphics(file);
        assertTrue(g.isEmpty());
        assertNotNull(g.brokenMovedTo());
        assertEquals(damaged, Files.readString(g.brokenMovedTo(), StandardCharsets.UTF_8), "kept whole, to be had by hand");
        assertEquals(List.of(g.brokenMovedTo()), broken("graphics.kml"));
        g.add(MapGraphics.Item.line("New", List.of(new MapGraphics.LatLon(59.9, 10.7), new MapGraphics.LatLon(59.91, 10.71)), 0xff0000, 3));
        assertTrue(Files.exists(file), "the new graphics are kept as before");
        assertEquals(damaged, Files.readString(g.brokenMovedTo(), StandardCharsets.UTF_8), "and the broken file is untouched");
        assertNull(new MapGraphics(file).brokenMovedTo(), "a good file stays where it is");
    }

    @Test
    void aPinsFileWithBrokenLinesKeepsItsPinsAndIsMovedAside() throws Exception {
        Path file = dir.resolve("pins.txt");
        Files.writeString(file, "Alpha\t59.9000000\t10.7000000\nBravo\t59,91\t10,71\n\nCharlie\t59.92\t10.72\n", StandardCharsets.UTF_8);
        Pins pins = new Pins(file);
        assertEquals(List.of("Alpha", "Charlie"), pins.list().stream().map(Pins.Pin::name).toList(), "the good ones read");
        assertNotNull(pins.brokenMovedTo());
        assertTrue(Files.readString(pins.brokenMovedTo()).contains("Bravo\t59,91"), "the line that isn't one kept");
        pins.add("Delta", 59.93, 10.73);
        assertEquals(3, new Pins(file).list().size(), "Alpha, Charlie, Delta written; Bravo only in the broken file");
        assertTrue(Files.readString(pins.brokenMovedTo()).contains("Bravo"));
        assertNull(new Pins(file).brokenMovedTo());
    }

    @Test
    void openCellIdKeepsTheDaysCountAcrossARestart() {
        OpenCellId first = new OpenCellId(dir, "test", false, () -> false);
        int full = first.creditsLeft();
        first.countRequest();
        first.countRequest();
        first.countRequest();
        assertEquals(full - 3, first.creditsLeft());
        OpenCellId restarted = new OpenCellId(dir, "test", false, () -> false);
        assertEquals(full - 3, restarted.creditsLeft(), "a restart forgot the count before 1.0.49");
    }

    @Test
    void openCellIdAsksNothingWhilePossiblyFlying() {
        boolean[] held = {true};
        OpenCellId cells = new OpenCellId(dir, "test", false, () -> held[0]);
        int left = cells.creditsLeft();
        OpenCellId.Answer a = cells.fetch(OpenCellId.Tile.at(59.91, 10.75), "key");
        assertEquals(OpenCellId.HELD, a.errorCode());
        assertNull(a.cells());
        assertEquals(left, cells.creditsLeft(), "nothing counted");
        held[0] = false;
        assertEquals(0, cells.fetch(OpenCellId.Tile.at(59.91, 10.75), "key").errorCode(), "landed: asked (offline in the test)");
    }

    @Test
    void heightsAreNotLookedUpWhilePossiblyFlying() throws Exception {
        boolean[] held = {true};
        Elevation e = new Elevation("test", true, () -> held[0]); // network on: held alone must stop it
        Double[] got = {0.0};
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        e.lookup(59.91, 10.75, z -> {
            got[0] = z;
            done.countDown();
        });
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(got[0], "unknown: no request went out");
    }
}
