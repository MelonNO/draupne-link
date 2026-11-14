package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.VideoDecoder;
import de.droiddrone.pc.video.VideoPipeline;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * STILL: the picture on the video saved as two PNG files beside the recordings - "still-DATE.png", the picture alone,
 * and "still-DATE-osd.png" with the OSD over it as the screen shows it now. Both at the size the stream carries (the
 * decoder's picture), not the view's scaled copy, and the way up the screen shows it. The Swing thread only asks and
 * takes the OSD's figures; the decoder hands its picture over between two frames without copying it
 * ({@link VideoPipeline#keepPicture}); converting, painting the OSD and writing the files is a thread of its own at
 * the lowest priority. One still at a time: a press while one is being written is left out, never waited for.
 */
final class StillSaver {
    /** The video's line about it stays this long. */
    static final long LINE_MS = 2000;
    static final String SAVED = "STILL SAVED", FAILED = "STILL FAILED", NO_PICTURE = "NO PICTURE";
    /** The decoder answers within a frame, 200 ms at most: no answer this long after asking and the next press asks again. */
    static final long ANSWER_MS = 2000;
    /** The recordings' stamp (flight-DATE.mkv). */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** One decoded picture, held for the saver: its pixels (0xRRGGBB, row by row) on the saver's thread, then let go. */
    interface Shot {
        int width();

        int height();

        void pixels(int[] dst);

        void close();
    }

    /** The video as the screen shows it. */
    interface Source {
        /**
         * Asks for the picture on screen as the decoder has it. `to` gets it soon, on any thread, and must not be
         * kept waiting; null: there was none after all.
         * @return false when there is no picture to ask for
         */
        boolean ask(Consumer<Shot> to);

        /** The OSD's figures now, or null (the Swing thread). */
        OsdRenderer.Data osdData();

        OsdLayout osdLayout();

        boolean flipX();

        boolean flipY();

        double displayMs();

        /** The window's video: its pipeline's picture (null while there is none), the view's way up, the window's OSD figures. */
        static Source of(VideoView view, Supplier<VideoPipeline> video, Supplier<OsdRenderer.Data> osd) {
            return new Source() {
                public boolean ask(Consumer<Shot> to) {
                    VideoPipeline v = video.get();
                    if (v == null || !view.hasPicture()) return false;
                    v.keepPicture(kept -> to.accept(kept == null ? null : shot(kept)));
                    return true;
                }

                public OsdRenderer.Data osdData() { return osd.get(); }
                public OsdLayout osdLayout() { return view.getOsdLayout(); }
                public boolean flipX() { return view.pictureFlipX(); }
                public boolean flipY() { return view.isUpsideDown(); }
                public double displayMs() { return view.displayMs(); }
            };
        }

        private static Shot shot(VideoDecoder.Kept kept) {
            return new Shot() {
                public int width() { return kept.width(); }
                public int height() { return kept.height(); }
                public void pixels(int[] dst) { kept.rgb(dst); }
                public void close() { kept.close(); }
            };
        }
    }

    /** The video's short lines (VideoMark.say). */
    interface Lines {
        void say(String text, boolean bad, long ms);
    }

    /** Where the files go (AppPaths.videosDir); asked on the saver's thread - it may make the folder. */
    interface Dir {
        Path get() throws IOException;
    }

    private final Source source;
    private final Dir dir;
    private final Lines lines;
    private final Consumer<String> log;
    private final Executor ui;
    /** Its own renderer (it keeps per-paint state), used on the saver's thread only. */
    private final OsdRenderer osd = new OsdRenderer();
    private volatile ExecutorService worker; // made at the first still
    // guarded by this
    private boolean asked, writing;
    private long askedNs;
    private int round;

    /** @param ui runs what it is given on the Swing thread (SwingUtilities::invokeLater): the line and the log */
    StillSaver(Source source, Dir dir, Lines lines, Consumer<String> log, Executor ui) {
        this.source = source;
        this.dir = dir;
        this.lines = lines;
        this.log = log;
        this.ui = ui;
    }

    /** The STILL button and S, on the Swing thread: asks for the picture and returns. */
    void save() {
        int mine;
        synchronized (this) {
            if (writing || (asked && System.nanoTime() - askedNs < ANSWER_MS * 1_000_000L)) return; // one at a time
            asked = true;
            askedNs = System.nanoTime();
            mine = ++round;
            if (worker == null) { // made and started here: the decode thread, handing the picture over, only queues it
                ThreadPoolExecutor w = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "stillSaver");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
                w.prestartAllCoreThreads();
                worker = w;
            }
        }
        // what the screen shows now, for the copy with the OSD
        OsdRenderer.Data data = source.osdData();
        OsdLayout layout = source.osdLayout();
        boolean flipX = source.flipX(), flipY = source.flipY();
        double displayMs = source.displayMs();
        boolean any;
        try {
            any = source.ask(shot -> take(mine, shot, data, layout, flipX, flipY, displayMs));
        } catch (RuntimeException e) {
            any = false;
        }
        if (any) return;
        synchronized (this) {
            if (mine == round) asked = false;
        }
        lines.say(NO_PICTURE, true, LINE_MS);
        log.accept("Still: no picture.");
    }

    /** The picture came (the decode thread): on to the saver's thread, at once. */
    private void take(int mine, Shot shot, OsdRenderer.Data data, OsdLayout layout, boolean flipX, boolean flipY, double displayMs) {
        synchronized (this) {
            if (mine != round || !asked) { // not waited for any more: a newer press asked again
                if (shot != null) shot.close();
                return;
            }
            asked = false;
            writing = shot != null;
        }
        if (shot == null) {
            done(NO_PICTURE, "Still: no picture.");
            return;
        }
        try {
            worker.execute(() -> write(shot, data, layout, flipX, flipY, displayMs));
        } catch (RuntimeException e) {
            shot.close();
            finish(FAILED, "Still failed: " + e + ".");
        }
    }

    // ------------------------------------------------------------------ its own thread

    private void write(Shot shot, OsdRenderer.Data data, OsdLayout layout, boolean flipX, boolean flipY, double displayMs) {
        String text = FAILED, line;
        try {
            int w, h;
            BufferedImage img;
            try {
                w = shot.width();
                h = shot.height();
                if (w <= 0 || h <= 0) throw new IOException("no picture");
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                shot.pixels(pixels(img));
            } finally {
                shot.close();
            }
            turn(pixels(img), w, h, flipX, flipY);
            Path file = file(dir.get(), LocalDateTime.now());
            png(img, file);
            line = "Still saved: " + file;
            if (data != null && data.connected()) {
                Graphics2D g = img.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    osd.setDisplayMs(displayMs);
                    osd.setPictureFlip(flipX, flipY); // the motion boxes as the screen draws them
                    osd.paint(g, 0, 0, w, h, data, layout);
                } finally {
                    g.dispose();
                }
                png(img, osdFile(file));
                line += " and " + osdFile(file).getFileName();
            }
            line += ".";
            text = SAVED;
        } catch (Throwable e) { // the disk full, the folder away, no memory for the picture: said, and the next one may work
            line = "Still failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + ".";
        }
        finish(text, line);
    }

    private void finish(String text, String line) {
        synchronized (this) {
            writing = false;
        }
        done(text, line);
    }

    private void done(String text, String line) {
        ui.execute(() -> {
            lines.say(text, !SAVED.equals(text), LINE_MS);
            log.accept(line);
        });
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    /** The picture mirrored and / or upside down in place, as the screen shows it (both: turned 180 degrees). */
    static void turn(int[] px, int w, int h, boolean flipX, boolean flipY) {
        if (flipX) {
            for (int y = 0; y < h; y++) {
                for (int a = y * w, b = a + w - 1; a < b; a++, b--) {
                    int t = px[a];
                    px[a] = px[b];
                    px[b] = t;
                }
            }
        }
        if (flipY) {
            int[] row = new int[w];
            for (int top = 0, bottom = h - 1; top < bottom; top++, bottom--) {
                System.arraycopy(px, top * w, row, 0, w);
                System.arraycopy(px, bottom * w, px, top * w, w);
                System.arraycopy(row, 0, px, bottom * w, w);
            }
        }
    }

    /** still-(date)_(time).png, as the recordings are stamped; a second one within the second gets -2, -3 ... */
    static Path file(Path dir, LocalDateTime at) {
        String stamp = at.format(STAMP);
        Path file = dir.resolve("still-" + stamp + ".png");
        for (int n = 2; Files.exists(file) || Files.exists(osdFile(file)); n++) file = dir.resolve("still-" + stamp + "-" + n + ".png");
        return file;
    }

    /** The copy with the OSD beside it: "still-x.png" -> "still-x-osd.png". */
    static Path osdFile(Path file) {
        return file.resolveSibling(file.getFileName().toString().replaceFirst("\\.png$", "") + "-osd.png");
    }

    /** Written whole, then moved into place: no half a picture under its name. */
    private static void png(BufferedImage img, Path file) throws IOException {
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            if (!ImageIO.write(img, "png", part.toFile())) throw new IOException("no PNG writer");
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, file);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
