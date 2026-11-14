package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pin's two newer parts in pins.txt - when it was dropped, and "rough" - beside files and apps that don't have them,
 * and the pins list's search.
 */
class PinsColumnsTest {
    @TempDir
    Path dir;

    @Test
    void anOldFileReadsAndTheNewColumnsRoundTrip() throws Exception {
        Path file = dir.resolve("pins.txt");
        Files.writeString(file, "Alpha\t59.9000000\t10.7000000\nCar park\t59.91\t10.71\n", StandardCharsets.UTF_8); // as written before
        Pins pins = new Pins(file);
        assertNull(pins.brokenMovedTo(), "an old file is a good file");
        assertEquals(List.of(new Pins.Pin("Alpha", 59.9, 10.7, 0, false), new Pins.Pin("Car park", 59.91, 10.71, 0, false)), pins.list(),
                "when they were dropped isn't known; not rough");

        long before = System.currentTimeMillis();
        Pins.Pin gate = pins.add("Gate", 59.92, 10.72);
        Pins.Pin roof = pins.add(null, 59.93, 10.73, true);
        long after = System.currentTimeMillis();
        assertTrue(gate.createdMs() >= before && gate.createdMs() <= after, "a pin added is dropped now");
        assertFalse(gate.rough());
        assertEquals("Bravo", roof.name());
        assertTrue(roof.rough());
        assertTrue(roof.createdMs() >= before && roof.createdMs() <= after);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("Alpha\t59.9000000\t10.7000000\t0\t0", "Car park\t59.9100000\t10.7100000\t0\t0",
                "Gate\t59.9200000\t10.7200000\t" + gate.createdMs() + "\t0", "Bravo\t59.9300000\t10.7300000\t" + roof.createdMs() + "\t1"), lines);

        Pins again = new Pins(file);
        assertNull(again.brokenMovedTo());
        assertEquals(pins.list(), again.list(), "read back as written");

        Pins.Pin renamed = again.rename(again.list().get(3), "Roof");
        assertEquals(new Pins.Pin("Roof", 59.93, 10.73, roof.createdMs(), true), renamed, "a new name: the same pin, dropped when it was");
        assertEquals(renamed, new Pins(file).list().get(3));
    }

    /** What the app read before the two parts came (Pins' constructor as it was): the first three parts of a line, the rest not looked at. */
    private static List<String> asAnOlderAppReads(Path file) throws Exception {
        List<String> pins = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t");
            if (p.length < 3) throw new NumberFormatException(line); // there: the whole file moved aside as broken
            pins.add(p[0] + " " + Double.parseDouble(p[1]) + " " + Double.parseDouble(p[2]));
        }
        return pins;
    }

    @Test
    void anOlderAppStillReadsAFileWrittenNow() throws Exception {
        Path file = dir.resolve("pins.txt");
        Pins pins = new Pins(file);
        pins.add("Gate", 59.92, 10.72);
        pins.add("Roof", -33.5, -70.25, true);
        pins.add(null, 59.94, 10.74);
        assertEquals(List.of("Gate 59.92 10.72", "Roof -33.5 -70.25", "Alpha 59.94 10.74"), asAnOlderAppReads(file),
                "name, latitude and longitude where they always were; no line it calls broken");
    }

    @Test
    void aTimeThatIsNoTimeIsUnknownNotABrokenFile() throws Exception {
        Path file = dir.resolve("pins.txt");
        Files.writeString(file, "Alpha\t59.9\t10.7\tsoon\tyes\nBravo\t59.91\t10.71\t-5\nCharlie\t59.92\t10.72\t1762603500000\t1\textra\n", StandardCharsets.UTF_8);
        Pins pins = new Pins(file);
        assertNull(pins.brokenMovedTo(), "the pins themselves are good");
        assertEquals(List.of(new Pins.Pin("Alpha", 59.9, 10.7), new Pins.Pin("Bravo", 59.91, 10.71), new Pins.Pin("Charlie", 59.92, 10.72, 1762603500000L, true)),
                pins.list());
    }

    @Test
    void matching() {
        Pins pins = new Pins(null);
        Pins.Pin car = pins.add("Car park", 59.9139, 10.7522);
        Pins.Pin field = pins.add("Landing field", 59.9339, 10.7522);
        Pins.Pin alpha = pins.add(null, 60.3913, 5.3221);

        assertEquals(List.of(car, field, alpha), pins.matching(""), "nothing typed: every pin, in the list's order");
        assertEquals(List.of(car, field, alpha), pins.matching(null));
        assertEquals(List.of(car, field, alpha), pins.matching("   "));
        assertEquals(List.of(car), pins.matching("car"));
        assertEquals(List.of(car), pins.matching("  CAR   PARK "), "upper or lower case, however many spaces");
        assertEquals(List.of(field), pins.matching("ing f"));
        assertEquals(List.of(alpha), pins.matching("ALPHA"));
        assertEquals(List.of(car, field, alpha), pins.matching("a"));
        assertEquals(List.of(), pins.matching("zulu"));

        // the place, as the pins list writes it in each format
        String mgrs = Coordinates.format(Coordinates.Format.MGRS, car.lat(), car.lon()); // "32V NM 97999 42606" or the like
        assertTrue(mgrs.matches("\\d+[A-Z] [A-Z]{2} \\d{5} \\d{5}"), mgrs);
        assertEquals(List.of(car), pins.matching(mgrs, Coordinates.Format.MGRS));
        assertEquals(List.of(car), pins.matching(mgrs.toLowerCase(java.util.Locale.ROOT)));
        assertEquals(List.of(car), pins.matching(mgrs.replace(" ", "").toLowerCase(java.util.Locale.ROOT), Coordinates.Format.MGRS), "a grid typed without its spaces");
        assertEquals(List.of(car, field), pins.matching(mgrs.substring(0, 6), Coordinates.Format.MGRS), "both in the same 100 km square");
        String utm = Coordinates.format(Coordinates.Format.UTM, alpha.lat(), alpha.lon());
        assertEquals(List.of(alpha), pins.matching(utm, Coordinates.Format.UTM));
        assertEquals(List.of(alpha), pins.matching(utm));
        assertEquals(List.of(field), pins.matching("59.9339", Coordinates.Format.DECIMAL));
        assertEquals(List.of(field), pins.matching("59.9339"), "any format when the list's isn't given");
        assertEquals(List.of(), pins.matching("59.9339", Coordinates.Format.MGRS), "the list shows MGRS: decimal degrees aren't on it");
        assertEquals(List.of(alpha), pins.matching(Coordinates.format(Coordinates.Format.DMS, alpha.lat(), alpha.lon()), Coordinates.Format.DMS));

        // one pin at a time, for a list that holds pins that aren't in the file (the tour's)
        assertTrue(Pins.matches(new Pins.Pin("Example", 59.9, 10.7), "exam", Coordinates.Format.MGRS));
        assertFalse(Pins.matches(new Pins.Pin("Example", 59.9, 10.7), "car", null));
    }
}
