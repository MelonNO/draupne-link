package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenCelliD import's limits: a file is read up to a size once unpacked and a count of cells kept, a line up to
 * a length, and over any of them the import stops with a short line and what was imported before stays. Only cells
 * with a place on the globe are taken. A country's file comes in whole.
 */
class OpenCellIdImportTest {
    private static final String GSM_ROW = "GSM,242,1,100,200,,10.70,59.90,3000,5,1,1409522613,1409522613,0\n";

    @TempDir
    Path dir;

    private static String lteRow(int i) {
        return "LTE,242,2,300," + i + ",12," + String.format(java.util.Locale.ROOT, "%.5f,%.5f", 10 + (i % 1000) * 1e-3, 59 + (i / 1000) * 1e-3)
                + ",1200,40,1,1409522613,1409522613,0\n";
    }

    /** A packed file: one 4G cell, then the same text over and over. */
    private Path gz(String name, String once, long times) throws IOException {
        Path file = dir.resolve(name);
        byte[] row = once.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(lteRow(7).getBytes(StandardCharsets.UTF_8)); // a cell in it: without the limits the file would be imported
            for (long i = 0; i < times; i++) out.write(row);
        }
        return file;
    }

    private static void assertShort(IOException refused) {
        String line = refused.getMessage();
        assertNotNull(line);
        assertTrue(line.length() <= 60 && !line.contains("\n"), "a short line for the pilot: " + line);
    }

    private OpenCellId storeWithAnImport() throws IOException {
        OpenCellId store = new OpenCellId(dir.resolve("store"), "test", false);
        Path before = Files.writeString(dir.resolve("before.csv"), lteRow(1) + lteRow(2));
        assertEquals(2, store.importCsv(before));
        return store;
    }

    private void assertTheEarlierImportStays(OpenCellId store) {
        assertEquals(2, store.importedIn(-90, 90, -180, 180).size(), "what was imported before is still there");
        assertEquals(2, new OpenCellId(dir.resolve("store"), "test", false).importedIn(-90, 90, -180, 180).size(), "on disk too");
    }

    @Test
    void aSmallFileThatUnpacksBigIsStoppedAtTheLimit() throws Exception {
        // 64 MB of rows in a file of some 200 KB, against a limit of 1 MB: stopped there, nothing of it kept
        Path big = gz("big.csv.gz", GSM_ROW, (64L << 20) / GSM_ROW.length());
        assertTrue(Files.size(big) < 1 << 20, "small as a file: " + Files.size(big));
        OpenCellId store = storeWithAnImport();
        IOException refused = assertThrows(IOException.class, () -> store.importCsv(big, 1 << 20, OpenCellId.MAX_IMPORT_CELLS));
        assertShort(refused);
        assertTrue(refused.getMessage().contains("1 MB"), "the limit is said: " + refused.getMessage());
        assertTheEarlierImportStays(store);
        // the same not packed
        Path plain = dir.resolve("plain.csv");
        Files.writeString(plain, lteRow(7) + GSM_ROW.repeat((3 << 20) / GSM_ROW.length()));
        IOException plainRefused = assertThrows(IOException.class, () -> store.importCsv(plain, 1 << 20, OpenCellId.MAX_IMPORT_CELLS));
        assertTrue(plainRefused.getMessage().contains("1 MB"), plainRefused.getMessage());
        assertTheEarlierImportStays(store);
        // under the limit the same rows are an import like any other
        assertEquals(1, store.importCsv(plain, 4 << 20, OpenCellId.MAX_IMPORT_CELLS));
    }

    @Test
    void oneEndlessLineIsNotReadIntoMemory() throws Exception {
        // 32 MB without a line break (a row is some 70 characters): refused at the line's limit, the size's not yet reached
        Path line = gz("line.csv.gz", "A".repeat(1 << 16), (32L << 20) >> 16);
        OpenCellId store = storeWithAnImport();
        IOException refused = assertThrows(IOException.class, () -> store.importCsv(line, OpenCellId.MAX_IMPORT_UNPACKED_BYTES, OpenCellId.MAX_IMPORT_CELLS));
        assertShort(refused);
        assertTrue(refused.getMessage().contains("line over " + OpenCellId.MAX_IMPORT_LINE), refused.getMessage());
        assertTheEarlierImportStays(store);
    }

    @Test
    void aFileWithMoreCellsThanTheLimitIsStopped() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 1_500; i++) rows.append(lteRow(i));
        Path file = Files.writeString(dir.resolve("many.csv"), rows);
        OpenCellId store = storeWithAnImport();
        IOException refused = assertThrows(IOException.class, () -> store.importCsv(file, OpenCellId.MAX_IMPORT_UNPACKED_BYTES, 1_000));
        assertShort(refused);
        assertTrue(refused.getMessage().contains("1000"), refused.getMessage());
        assertTheEarlierImportStays(store);
        assertEquals(1_500, store.importCsv(file, OpenCellId.MAX_IMPORT_UNPACKED_BYTES, 1_500), "at the limit it is taken");
    }

    @Test
    void onlyCellsWithAPlaceOnTheGlobeAreTaken() throws Exception {
        String rows = "radio,mcc,net,area,cell,unit,lon,lat,range,samples\n"
                + "LTE,242,2,1,1,1,10.71,59.91,1200,40\n"
                + "LTE,242,2,1,2,1,NaN,59.91,1200,40\n" + "LTE,242,2,1,3,1,10.71,NaN,1200,40\n"
                + "LTE,242,2,1,4,1,Infinity,59.91,1200,40\n" + "LTE,242,2,1,5,1,10.71,-Infinity,1200,40\n"
                + "LTE,242,2,1,6,1,181,59.91,1200,40\n" + "LTE,242,2,1,7,1,10.71,90.5,1200,40\n"
                + "LTE,242,2,1,8,1,1e308,-1e308,1200,40\n" + "LTE,242,2,1,9,1,0x1p10,59.91,1200,40\n"
                + "NR,242,14,1,10,1,-180,-90,600,2\n";
        List<OpenCellId.Cell> cells = OpenCellId.parseCsv(rows);
        assertEquals(List.of(new OpenCellId.Cell("LTE", 242, 2, 59.91, 10.71, 1200, 40), new OpenCellId.Cell("NR", 242, 14, -90, -180, 600, 2)), cells);
        // the API's answers too
        OpenCellId.Answer a = OpenCellId.parseJson("{\"count\":4,\"cells\":["
                + "{\"radio\":\"LTE\",\"mcc\":242,\"mnc\":2,\"lat\":59.91,\"lon\":10.71,\"range\":1200,\"samples\":40},"
                + "{\"radio\":\"LTE\",\"mcc\":242,\"mnc\":2,\"lat\":1e999,\"lon\":10.71,\"range\":1200,\"samples\":40},"
                + "{\"radio\":\"LTE\",\"mcc\":242,\"mnc\":2,\"lat\":59.91,\"lon\":\"Infinity\",\"range\":1200,\"samples\":40},"
                + "{\"radio\":\"LTE\",\"mcc\":242,\"mnc\":2,\"lat\":95,\"lon\":10.71,\"range\":1200,\"samples\":40}]}");
        assertEquals(1, a.cells().size());

        // an import of such a file: the area it covers is where its cells are, not the world
        Path file = Files.writeString(dir.resolve("odd.csv"), rows.replace("NR,242,14,1,10,1,-180,-90,600,2\n", "LTE,242,2,1,11,1,11.5,60.5,1200,40\n"));
        OpenCellId store = new OpenCellId(dir.resolve("store2"), "test", false);
        assertEquals(2, store.importCsv(file));
        assertTrue(store.coveredByImport(OpenCellId.Tile.at(60.2, 11.1)), "between its two cells");
        assertFalse(store.coveredByImport(OpenCellId.Tile.at(40, 20)), "a place far from every cell is not covered");
        assertFalse(store.coveredByImport(OpenCellId.Tile.at(70, 11.1)));
    }

    @Test
    void aCountrysFileComesInWhole() throws Exception {
        // 300 000 rows, a third of them 4G: more than Norway's file has of 4G and 5G together
        Path file = dir.resolve("242.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            for (int i = 0; i < 300_000; i++) out.write((i % 3 == 0 ? lteRow(i) : GSM_ROW).getBytes(StandardCharsets.UTF_8));
        }
        OpenCellId store = new OpenCellId(dir.resolve("store3"), "test", false);
        assertEquals(100_000, store.importCsv(file));
        assertTrue(store.hasImport());
        assertEquals(100_000, new OpenCellId(dir.resolve("store3"), "test", false).importedIn(-90, 90, -180, 180).size(), "kept for the next start");
        assertTrue(OpenCellId.MAX_IMPORT_CELLS >= 2_000_000 && OpenCellId.MAX_IMPORT_UNPACKED_BYTES >= 1L << 30, "the limits leave a country's file room");
    }
}
