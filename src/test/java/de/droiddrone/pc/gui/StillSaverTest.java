package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** STILL: the picture as two PNG files, written off the caller's thread. Every file in a folder of the test's own. */
class StillSaverTest {
    private static final int W = 640, H = 360, RED = 0xff0000, BLUE = 0x0000ff;

    /** A decoded picture, made up: red, its top-left quarter blue. */
    private static final class Picture implements StillSaver.Shot {
        volatile Thread convertedOn;
        volatile boolean closed;
        final CountDownLatch converting = new CountDownLatch(1);
        CountDownLatch hold;
        final int w, h;
        final boolean grain;

        Picture() {
            this(W, H, false);
        }

        Picture(int w, int h, boolean grain) {
            this.w = w;
            this.h = h;
            this.grain = grain;
        }

        public int width() {
            return w;
        }

        public int height() {
            return h;
        }

        public void pixels(int[] dst) {
            convertedOn = Thread.currentThread();
            converting.countDown();
            if (hold != null) {
                try {
                    assertTrue(hold.await(20, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            if (grain) { // a camera's picture, about: shades with noise on them - what a PNG packs worst
                java.util.Random random = new java.util.Random(7);
                for (int i = 0; i < w * h; i++) {
                    int v = 60 + (i / w) * 120 / h + random.nextInt(24);
                    dst[i] = v << 16 | (v + 20) << 8 | (v - 10);
                }
                return;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) dst[y * w + x] = x < w / 2 && y < h / 2 ? BLUE : RED;
            }
        }

        public void close() {
            closed = true;
        }
    }

    /** The video, made up: hands its picture over at once, as the decode thread would between two frames. */
    private static final class Video implements StillSaver.Source {
        Picture picture = new Picture();
        boolean any = true, handNothing, flipX, flipY;
        int asks;
        OsdRenderer.Data data = new OsdPreview().data();

        public boolean ask(Consumer<StillSaver.Shot> to) {
            asks++;
            if (!any) return false;
            to.accept(handNothing ? null : picture);
            return true;
        }

        public OsdRenderer.Data osdData() {
            return data;
        }

        public OsdLayout osdLayout() {
            return OsdLayout.defaults();
        }

        public boolean flipX() {
            return flipX;
        }

        public boolean flipY() {
            return flipY;
        }

        public double displayMs() {
            return 5;
        }
    }

    /** What was said on the video and in the log; await() takes one line, waiting for it if it has not come. */
    private static final class Said implements StillSaver.Lines {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>()), log = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> bad = Collections.synchronizedList(new ArrayList<>());
        volatile long ms;
        private final Semaphore said = new Semaphore(0);

        public void say(String text, boolean isBad, long forMs) {
            lines.add(text);
            bad.add(isBad);
            ms = forMs;
            said.release();
        }

        void await() throws InterruptedException {
            assertTrue(said.tryAcquire(30, TimeUnit.SECONDS), "a line about the still");
        }
    }

    private final Video video = new Video();
    private final Said said = new Said();

    private StillSaver saver(StillSaver.Dir dir) {
        return new StillSaver(video, dir, said, said.log::add, Runnable::run);
    }

    /** The folder's files: the pictures alone first, their copies with the OSD after them. */
    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted(Comparator.comparing((Path p) -> p.getFileName().toString().endsWith("-osd.png")).thenComparing(Comparator.naturalOrder())).toList();
        }
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xffffff;
    }

    @Test
    void twoPngFilesOfTheDecodedSizeOneWithTheOsd(@TempDir Path dir) throws Exception {
        saver(() -> dir).save();
        said.await();
        assertEquals(List.of(StillSaver.SAVED), said.lines);
        assertEquals(List.of(false), said.bad);
        assertEquals(2000, said.ms, "for 2 s");
        List<Path> files = files(dir);
        assertEquals(2, files.size(), files.toString());
        Path raw = files.get(0), osd = files.get(1);
        assertTrue(raw.getFileName().toString().matches("still-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.png"), raw.toString());
        assertEquals(StillSaver.osdFile(raw), osd);
        BufferedImage a = ImageIO.read(raw.toFile()), b = ImageIO.read(osd.toFile());
        assertEquals(W, a.getWidth());
        assertEquals(H, a.getHeight());
        assertEquals(W, b.getWidth());
        assertEquals(H, b.getHeight());
        int differ = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals(x < W / 2 && y < H / 2 ? BLUE : RED, rgb(a, x, y), "the picture alone, pixel for pixel");
                if (rgb(a, x, y) != rgb(b, x, y)) differ++;
            }
        }
        assertTrue(differ > 2000, "the OSD is in the second file: " + differ + " pixels of it");
        assertTrue(video.picture.closed, "the decoder's picture was let go");
        assertEquals(List.of("Still saved: " + raw + " and " + osd.getFileName() + "."), said.log);
    }

    @Test
    void convertedAndWrittenOffTheCallingThreadAndAPressMeanwhileIsLeftOut(@TempDir Path dir) throws Exception {
        video.picture.hold = new CountDownLatch(1);
        StillSaver saver = saver(() -> dir);
        saver.save(); // back at once, though the picture is not even converted yet
        assertTrue(video.picture.converting.await(20, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), video.picture.convertedOn);
        assertEquals("stillSaver", video.picture.convertedOn.getName());
        assertEquals(Thread.MIN_PRIORITY, video.picture.convertedOn.getPriority());
        assertTrue(video.picture.convertedOn.isDaemon());
        assertTrue(files(dir).isEmpty());
        assertTrue(said.lines.isEmpty());
        saver.save(); // pressed again while the first is in hand: left out, nothing waits
        saver.save();
        assertEquals(1, video.asks);
        video.picture.hold.countDown();
        said.await();
        assertEquals(List.of(StillSaver.SAVED), said.lines);
        assertEquals(2, files(dir).size());

        video.picture = new Picture(); // done: the next press is a still again, and never over the first one's files
        saver.save();
        said.await();
        assertEquals(2, video.asks);
        assertEquals(4, files(dir).size());
    }

    @Test
    void noPictureSaysSoAndWritesNothing(@TempDir Path dir) throws Exception {
        StillSaver saver = saver(() -> dir);
        video.any = false; // no stream, no picture yet
        saver.save();
        assertEquals(List.of(StillSaver.NO_PICTURE), said.lines, "said at once");
        assertEquals(List.of(true), said.bad);
        said.await();
        video.any = true;
        video.handNothing = true; // a stream, but the decoder had no picture in hand
        saver.save();
        said.await();
        assertEquals(List.of(StillSaver.NO_PICTURE, StillSaver.NO_PICTURE), said.lines);
        assertTrue(files(dir).isEmpty());
        assertEquals(List.of("Still: no picture.", "Still: no picture."), said.log);
        video.handNothing = false; // and it is not stuck: the next one is saved
        saver.save();
        said.await();
        assertEquals(StillSaver.SAVED, said.lines.get(2));
    }

    @Test
    void savedTheWayUpTheScreenShowsIt(@TempDir Path dir) throws Exception {
        video.flipX = video.flipY = true; // a camera mounted upside down: the screen turns it
        video.data = null;
        saver(() -> dir).save();
        said.await();
        List<Path> files = files(dir);
        assertEquals(1, files.size(), "no OSD figures (not connected): the picture alone");
        assertFalse(said.log.get(0).contains(" and "), said.log.get(0));
        BufferedImage img = ImageIO.read(files.get(0).toFile());
        assertEquals(BLUE, rgb(img, W - 10, H - 10), "the top-left quarter is bottom-right");
        assertEquals(RED, rgb(img, 10, 10));

        int[] px = {1, 2, 3, 4, 5, 6}; // 3 wide, 2 high
        StillSaver.turn(px, 3, 2, true, false);
        assertArrayEquals(new int[]{3, 2, 1, 6, 5, 4}, px, "mirrored");
        StillSaver.turn(px, 3, 2, false, true);
        assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, px, "and upside down: turned 180 degrees");
    }

    @Test
    void aFolderThatCannotBeHadIsAFailureAndTheNextStillWorks(@TempDir Path dir) throws Exception {
        boolean[] away = {true};
        StillSaver saver = saver(() -> {
            if (away[0]) throw new IOException("no folder");
            return dir;
        });
        saver.save();
        said.await();
        assertEquals(List.of(StillSaver.FAILED), said.lines);
        assertEquals(List.of(true), said.bad);
        assertEquals(List.of("Still failed: no folder."), said.log);
        assertTrue(video.picture.closed);
        assertTrue(files(dir).isEmpty());
        away[0] = false;
        video.picture = new Picture();
        saver.save();
        said.await();
        assertEquals(StillSaver.SAVED, said.lines.get(1));
        assertEquals(2, files(dir).size());
    }

    @Test
    void stampedAsTheRecordingsAndNeverOverAnotherStill(@TempDir Path dir) throws Exception {
        LocalDateTime at = LocalDateTime.of(2026, 11, 14, 10, 50, 2);
        Path first = StillSaver.file(dir, at);
        assertEquals(dir.resolve("still-2026-11-14_10-50-02.png"), first);
        assertEquals(dir.resolve("still-2026-11-14_10-50-02-osd.png"), StillSaver.osdFile(first));
        Files.createFile(first);
        Path second = StillSaver.file(dir, at);
        assertEquals(dir.resolve("still-2026-11-14_10-50-02-2.png"), second, "the same second: -2");
        Files.createFile(StillSaver.osdFile(second)); // only its OSD copy is there: not that name either
        assertEquals(dir.resolve("still-2026-11-14_10-50-02-3.png"), StillSaver.file(dir, at));
    }

    /**
     * What a still costs: the press on the caller's thread (the Swing thread) is the OSD's figures taken and the
     * picture asked for; the two files of a 1920 x 1080 picture are the saver's own thread's work. The budget for the
     * press is far above what it measures (the test prints both), to catch a change that puts work there.
     */
    @Test
    void thePressIsCheapAndTheFilesAreTheSaversOwnWork(@TempDir Path dir) throws Exception {
        StillSaver saver = saver(() -> dir);
        video.picture = new Picture(1920, 1080, true);
        saver.save(); // the first: the thread is made, the classes loaded
        said.await();
        double press = 0;
        long files = 0;
        int n = 5;
        for (int i = 0; i < n; i++) {
            video.picture = new Picture(1920, 1080, true);
            long t0 = System.nanoTime();
            saver.save();
            press += (System.nanoTime() - t0) / 1e6;
            said.await();
            files += System.nanoTime() - t0;
        }
        assertEquals(n + 1, said.lines.stream().filter(StillSaver.SAVED::equals).count());
        assertEquals(2 * (n + 1), files(dir).size());
        System.out.printf("STILL: the press %.3f ms on the caller's thread; a 1920x1080 picture's two files %.0f ms later, on the saver's%n",
                press / n, files / 1e6 / n);
        assertTrue(press / n < 20, "the press: " + press / n + " ms");
    }

    /** The window's video view, telling when a picture was handed to it. */
    private static final class View extends VideoView {
        final CountDownLatch shown = new CountDownLatch(1);

        @Override
        public void presentFrame() {
            super.presentFrame();
            shown.countDown();
        }
    }

    /**
     * The real thing: a 320 x 240 stream decoded for a view that shows it at 160 x 120. The still is the decoder's
     * picture - 320 x 240 - not the view's scaled copy.
     */
    @Test
    void theStillIsTheDecodersPictureNotTheViewsScaledOne(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 6);
        View view = new View();
        view.setSize(160, 120);
        VideoPipeline[] pipeline = {null};
        StillSaver saver = new StillSaver(StillSaver.Source.of(view, () -> pipeline[0], new OsdPreview()::data), () -> dir, said, said.log::add, Runnable::run);
        saver.save();
        assertEquals(List.of(StillSaver.NO_PICTURE), said.lines, "not connected: no pipeline");
        said.await();
        pipeline[0] = new VideoPipeline(view, () -> 40);
        try {
            saver.save();
            assertEquals(List.of(StillSaver.NO_PICTURE, StillSaver.NO_PICTURE), said.lines, "connected, no picture yet");
            said.await();
            pipeline[0].onVideoConfig(320, 240, false, false, s.codecConfig());
            short n = 0;
            for (TestStreams.Packet p : s.packets()) {
                byte[] d = p.data();
                pipeline[0].onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            }
            assertTrue(view.shown.await(30, TimeUnit.SECONDS), "a picture on the view");
            assertArrayEquals(new int[]{160, 120}, view.targetSize(), "the view's copy is scaled down");
            saver.save();
            said.await();
            assertEquals(StillSaver.SAVED, said.lines.get(2), said.log.toString());
        } finally {
            pipeline[0].close();
        }
        List<Path> files = files(dir);
        assertEquals(2, files.size(), files.toString());
        BufferedImage raw = ImageIO.read(files.get(0).toFile()), osd = ImageIO.read(files.get(1).toFile());
        assertEquals(320, raw.getWidth());
        assertEquals(240, raw.getHeight());
        assertEquals(320, osd.getWidth());
        assertEquals(240, osd.getHeight());
        int differ = 0;
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int c = rgb(raw, x, y);
                assertTrue(((c >> 16) & 255) > 200 && ((c >> 8) & 255) < 60 && (c & 255) < 60, "the stream's red at " + x + "," + y + ": " + Integer.toHexString(c));
                if (c != rgb(osd, x, y)) differ++;
            }
        }
        assertTrue(differ > 300, "the OSD over it: " + differ);
    }
}
