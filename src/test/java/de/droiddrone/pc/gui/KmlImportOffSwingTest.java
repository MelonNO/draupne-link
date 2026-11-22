package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Kml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMPORT reads its file on a thread of its own: the map window answers while a big file is read, and what was read -
 * or the short line saying why nothing was - comes back on the Swing thread.
 */
class KmlImportOffSwingTest {
    @Test
    void theWindowAnswersWhileTheFileIsRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), mayFinish = new CountDownLatch(1), handedOn = new CountDownLatch(1);
        boolean[] readOnSwing = {true}, handedOnSwing = {false};
        Object[] got = new Object[2];
        SwingUtilities.invokeAndWait(() -> MapWindow.offSwing("test-import", () -> {
            readOnSwing[0] = SwingUtilities.isEventDispatchThread();
            reading.countDown();
            mayFinish.await(20, TimeUnit.SECONDS); // a read that takes as long as the test likes
            return "read";
        }, (result, failed) -> {
            handedOnSwing[0] = SwingUtilities.isEventDispatchThread();
            got[0] = result;
            got[1] = failed;
            handedOn.countDown();
        }));
        assertTrue(reading.await(10, TimeUnit.SECONDS), "the read began");
        SwingUtilities.invokeAndWait(() -> { }); // the Swing thread takes other work while the read goes on
        assertEquals(1, handedOn.getCount(), "nothing handed on before the read is over");
        mayFinish.countDown();
        assertTrue(handedOn.await(10, TimeUnit.SECONDS), "handed on");
        assertFalse(readOnSwing[0], "read on a thread of its own");
        assertTrue(handedOnSwing[0], "handed on to the Swing thread");
        assertEquals("read", got[0]);
        assertNull(got[1]);
    }

    @Test
    void aFileOverALimitComesBackAsAShortLine(@TempDir Path dir) throws Exception {
        StringBuilder kml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
        for (int i = 0; i < 50_000; i++) kml.append("<Placemark><name>P").append(i).append("</name><Point><coordinates>10.7,59.9,0</coordinates></Point></Placemark>\n");
        Path file = Files.writeString(dir.resolve("many.kml"), kml.append("</Document>\n</kml>\n"), StandardCharsets.UTF_8);
        assertEquals("over " + Kml.MAX_IMPORT_PLACEMARKS + " placemarks", failure(() -> Kml.readImport(file)));
        assertEquals("too big to read", failure(() -> {
            throw new StackOverflowError();
        }), "an Error on the way is a line too, not a thread that died without a word");
        assertEquals("too big to read", failure(() -> {
            throw new OutOfMemoryError("made up");
        }));
        assertTrue(failure(() -> Kml.readImport(dir.resolve("not-there.kml"))).contains("not-there.kml"), "a file that can't be read: its own word");
    }

    /** What offSwing hands on for a read that gives nothing. */
    private static String failure(java.util.concurrent.Callable<Kml.Parsed> read) throws Exception {
        CountDownLatch handedOn = new CountDownLatch(1);
        Object[] got = new Object[2];
        MapWindow.offSwing("test-import", read, (result, failed) -> {
            got[0] = result;
            got[1] = failed;
            handedOn.countDown();
        });
        assertTrue(handedOn.await(30, TimeUnit.SECONDS), "handed on");
        assertNull(got[0]);
        return (String) got[1];
    }
}
