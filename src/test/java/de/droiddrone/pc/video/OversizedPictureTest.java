package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-093: a damaged stream's oversized picture is left out before any room is made for it, by every kind of decoder the
 * app opens, with one line in the log; the stream goes on at its next good picture. Before, a decoder handed out a
 * picture of 640 x 196480, and the overwatch preview's copy of it alone took 479 MB.
 */
class OversizedPictureTest {
    /**
     * The parameter sets the decoder fuzz found (2026-11-24, seed 2): a 640 x 360 stream's with a few bits changed, its
     * pictures 640 x 196480 high.
     */
    private static final byte[] DAMAGED_CONFIG = HexFormat.of().parseHex("000000016742c01e8c8d40500017fc6a840f08b000000168ce3c80");

    @Test
    void aDamagedStreamsOversizedPictureIsLeftOutAndTheNextGoodOneShown() {
        TestStreams.Stream good = TestStreams.h264(640, 360, 6, 3, true);
        byte[] config = good.codecConfig(), key = good.packets().get(0).data();
        byte[] inBand = new byte[config.length + key.length]; // a key frame with its own parameter sets, as after a restart
        System.arraycopy(config, 0, inBand, 0, config.length);
        System.arraycopy(key, 0, inBand, config.length, key.length);
        for (String kind : new String[]{"live", "one-thread", "GPU"}) {
            PrintStream out = System.out;
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            try (VideoDecoder d = switch (kind) {
                case "live" -> new VideoDecoder(false, DAMAGED_CONFIG);
                case "one-thread" -> VideoDecoder.oneThread(false, DAMAGED_CONFIG);
                default -> new VideoDecoder(false, DAMAGED_CONFIG, true);
            }) {
                for (TestStreams.Packet p : good.packets()) {
                    assertFalse(d.decode(p.data(), p.data().length), kind + ": a picture of " + d.width() + " x " + d.height() + " handed out");
                }
                assertTrue(d.decode(inBand, inBand.length), kind + ": the next good picture");
                assertEquals(640, d.width());
                assertEquals(360, d.height());
                int[] px = new int[640 * 360];
                d.convert(px, 640, 360);
            } finally {
                System.setOut(out);
            }
            String log = said.toString(StandardCharsets.UTF_8);
            assertEquals(1, log.lines().filter(l -> l.contains("640 x 196480")).count(), kind + ", once in the log: " + log);
        }
    }
}
