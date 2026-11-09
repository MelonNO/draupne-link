package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An old flight's CSV as a virtual drone's telemetry (Recording.readTrack, csvOf): the columns by name, rows that can't be read skipped. */
class RecordingTrackTest {
    @TempDir
    Path dir;

    @Test
    void theCsvBesideARecordingOrItsOsdCopy() {
        Path d = Path.of("x");
        assertEquals(d.resolve("flight-1.csv"), Recording.csvOf(d.resolve("flight-1.mkv")));
        assertEquals(d.resolve("flight-1.csv"), Recording.csvOf(d.resolve("flight-1-osd.mkv")));
    }

    @Test
    void columnsAreFoundByNameInAnyOrderAndMissingOnesAreNaN() throws Exception {
        Path csv = dir.resolve("f.csv");
        Files.writeString(csv, "Heading,LAT,lon,pts_ms,extra,armed\n"
                + "90,59.1,10.2,0,a,1\n"
                + "91,59.2,10.3,500,b,0\n");
        List<double[]> rows = Recording.readTrack(csv);
        assertEquals(2, rows.size());
        double[] r = rows.get(1);
        assertEquals(500, r[0]);
        assertEquals(59.2, r[1]);
        assertEquals(10.3, r[2]);
        assertTrue(Double.isNaN(r[3]), "no alt_m column");
        assertTrue(Double.isNaN(r[4]) && Double.isNaN(r[5]), "no roll, pitch");
        assertEquals(91, r[6]);
        assertEquals(0, r[7]);
    }

    @Test
    void rowsWithoutATimeAreSkippedAndBadNumbersAreNaN() throws Exception {
        Path csv = dir.resolve("f.csv");
        Files.writeString(csv, "pts_ms,lat,lon,alt_m\n"
                + ",59,10,1\n"           // no time: skipped
                + "100,abc,10,\n"        // a bad latitude, a blank height
                + "200,59.5\n");         // cut short
        List<double[]> rows = Recording.readTrack(csv);
        assertEquals(2, rows.size());
        assertTrue(Double.isNaN(rows.get(0)[1]) && Double.isNaN(rows.get(0)[3]));
        assertEquals(59.5, rows.get(1)[1]);
        assertTrue(Double.isNaN(rows.get(1)[2]), "the missing longitude");
    }

    @Test
    void noTrackWithoutTheNeededColumnsOrFile() throws Exception {
        assertTrue(Recording.readTrack(dir.resolve("none.csv")).isEmpty());
        Path csv = dir.resolve("f.csv");
        Files.writeString(csv, "pts_ms,lat,alt_m\n0,59,1\n");
        assertTrue(Recording.readTrack(csv).isEmpty(), "no lon column");
        Files.writeString(csv, "");
        assertTrue(Recording.readTrack(csv).isEmpty(), "empty");
    }
}
