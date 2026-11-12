package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.video.H264Encoder;
import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoDecoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a drone's STANAG 4609 stream costs this PC (1.0.65: each one is made here, on a thread of its own at the lowest
 * priority). Measured on the work as the feed does it, one thread, no waiting between pictures. The budgets are several
 * times what this PC needs, to catch a slip without failing on a busy release runner; the figures are printed.
 */
class StanagPerfTest {
    private static final int W = StanagFeed.W, H = StanagFeed.H;

    @BeforeAll
    static void look() {
        Theme.install();
    }

    /** Ten drones' pages are fifty of these a second. */
    @Test
    void aPageAgainCostsAFewMillisecondsAndAStillPageStreamFewBits() throws Exception {
        BufferedImage page = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        StanagPagePainter.page(page, "MobileAlpha", false, new StanagShow.Page("DEEP SLEEP", "Asleep. Checks in every 5 min.", StanagShow.Tone.PLAIN));
        int[] pixels = ((DataBufferInt) page.getRaster().getDataBuffer()).getData();
        try (H264Encoder enc = new H264Encoder(W, H, StanagFeed.PAGE_FPS, StanagFeed.PAGE_BPS, StanagFeed.PAGE_FPS * 3600)) {
            long bytes = 0;
            for (H264Encoder.Packet p : enc.encode(pixels, true)) bytes += p.data().length;
            long keyBytes = bytes;
            for (int i = 0; i < 20; i++) enc.encodeAgain(false); // warm
            // twenty seconds of a page as the feed sends it: five a second, a key frame every four seconds
            int n = 100;
            bytes = 0;
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                for (H264Encoder.Packet p : enc.encodeAgain(i % 20 == 0)) bytes += p.data().length;
            }
            double ms = (System.nanoTime() - start) / 1e6 / n;
            double kbps = bytes * 8 / 1000.0 / (n / (double) StanagFeed.PAGE_FPS);
            System.out.printf("STANAG page: %.2f ms a picture, key frame %d bytes, a still page %.0f kbit/s%n", ms, keyBytes, kbps);
            assertTrue(ms < 25, "a page picture took " + ms + " ms");
            assertTrue(kbps < 150, "a still page stream is " + kbps + " kbit/s");
        }
    }

    /** The piloted drone's stream is thirty of these a second; an observed tile's ten. */
    @Test
    void aPictureOfTheVideoWithItsOsdCostsTensOfMillisecondsAtMost() throws Exception {
        TestStreams.Stream s = TestStreams.h264(1280, 720, 60, 30, true); // moving detail and noise, like a camera
        VideoDecoder decoder = VideoDecoder.oneThread(false, s.codecConfig());
        OsdFrame frame = new OsdFrame();
        frame.size(W, H);
        FcTelemetry telemetry = new FcTelemetry();
        OsdLayout layout = OsdLayout.defaults();
        try (H264Encoder enc = new H264Encoder(W, H, StanagFeed.PILOT_FPS, 2_000_000, StanagFeed.PILOT_FPS * 3600)) {
            List<TestStreams.Packet> packets = s.packets();
            double decodeMs = 0, drawMs = 0, encodeMs = 0;
            int timed = 0;
            for (int i = 0; i < packets.size(); i++) {
                TestStreams.Packet p = packets.get(i);
                long t0 = System.nanoTime();
                boolean got = decoder.decode(p.data(), p.data().length);
                long t1 = System.nanoTime();
                if (!got) continue;
                frame.draw(decoder, false, false, ObservationOsd.data(telemetry, null, MonoClock.ms(), false, MonoClock.ms()), layout, 0);
                long t2 = System.nanoTime();
                enc.encode(frame.pixels(), i % 60 == 0);
                long t3 = System.nanoTime();
                if (i < 20) continue; // warm
                decodeMs += (t1 - t0) / 1e6;
                drawMs += (t2 - t1) / 1e6;
                encodeMs += (t3 - t2) / 1e6;
                timed++;
            }
            decodeMs /= timed;
            drawMs /= timed;
            encodeMs /= timed;
            double all = decodeMs + drawMs + encodeMs;
            System.out.printf("STANAG video: %.1f ms a 720p picture (decode %.1f, picture + OSD %.1f, encode %.1f): %.0f %% of one core at 30 a second, %.0f %% for six tiles at 10%n",
                    all, decodeMs, drawMs, encodeMs, all * 30 / 10, all * 60 / 10);
            assertTrue(all < 100, "a picture took " + all + " ms");
        } finally {
            decoder.close();
        }
    }
}
