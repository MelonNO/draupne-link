package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PC's own H.264 encoder (the virtual fleet's phones since 1.0.50; 1.0.65: every drone's STANAG 4609 stream): the
 * same picture again without converting it, and key frames only when asked.
 */
class H264EncoderTest {
    private static int[] plain(int w, int h, int rgb) {
        int[] p = new int[w * h];
        Arrays.fill(p, rgb);
        return p;
    }

    @Test
    void theSamePictureAgainDecodesAndCostsAlmostNothing() throws IOException {
        try (H264Encoder enc = new H264Encoder(320, 240, 5, 200_000, 5 * 3600)) {
            VideoDecoder dec = VideoDecoder.oneThread(false, enc.config());
            assertThrows(IOException.class, () -> enc.encodeAgain(true), "nothing to send again before the first picture");
            List<H264Encoder.Packet> first = enc.encode(plain(320, 240, 0x2060c0), true);
            assertEquals(1, first.size());
            assertTrue(first.get(0).keyFrame());
            assertTrue(dec.decode(first.get(0).data(), first.get(0).data().length));
            int again = 0;
            for (int i = 0; i < 20; i++) {
                List<H264Encoder.Packet> p = enc.encodeAgain(false);
                assertEquals(1, p.size(), "never skipped: a player's clock runs on the pictures");
                assertFalse(p.get(0).keyFrame(), "no key frame by itself");
                assertTrue(dec.decode(p.get(0).data(), p.get(0).data().length));
                again += p.get(0).data().length;
            }
            assertTrue(again / 20 < first.get(0).data().length, "a still picture again is smaller than its key frame: " + again / 20 + " bytes");
            int[] out = new int[320 * 240];
            dec.convert(out, 320, 240);
            int rgb = out[120 * 320 + 160];
            assertEquals(0x20, (rgb >> 16) & 0xff, 12);
            assertEquals(0x60, (rgb >> 8) & 0xff, 12);
            assertEquals(0xc0, rgb & 0xff, 12);
            List<H264Encoder.Packet> key = enc.encodeAgain(true);
            assertTrue(key.get(0).keyFrame(), "a key frame when asked");
            dec.close();
        }
    }

    @Test
    void keyFramesComeEveryTwoSecondsUnlessTheCallerAsksForItsOwn() throws IOException {
        int[] picture = plain(320, 240, 0x808080);
        try (H264Encoder two = new H264Encoder(320, 240, 5, 200_000); H264Encoder own = new H264Encoder(320, 240, 5, 200_000, 5 * 3600)) {
            int keysTwo = 0, keysOwn = 0;
            for (int i = 0; i < 31; i++) {
                for (H264Encoder.Packet p : two.encode(picture, false)) if (p.keyFrame()) keysTwo++;
                for (H264Encoder.Packet p : own.encode(picture, false)) if (p.keyFrame()) keysOwn++;
            }
            assertEquals(4, keysTwo, "pictures 0, 10, 20, 30 at five a second");
            assertEquals(1, keysOwn, "the first alone");
        }
    }
}
