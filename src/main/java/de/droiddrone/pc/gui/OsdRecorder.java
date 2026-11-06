package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.OsdVideoWriter;
import de.droiddrone.pc.video.VideoDecoder;
import de.droiddrone.pc.video.VideoPipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The second file of a recording (1.0.42): the video with the OSD drawn in, as the pilot saw it, next to the stream as
 * it came ("flight-...-osd.mkv" beside "flight-....mkv"). Its own thread at the lowest priority, its own decoder in
 * software on that thread, the OSD painted with the figures the screen painted last, OpenH264 on that thread too - so
 * nothing of it runs beside the live video's own threads, and the live picture never waits for it. Behind (a slow
 * PC): pictures are left out of this file (never out of the raw one); far behind: frames up to the next key frame.
 */
final class OsdRecorder implements VideoPipeline.RecordingTap {
    /** About three seconds of frames waiting; more and frames are dropped up to the next key frame. */
    static final int QUEUE = 90;
    /** Frames waiting beyond this: pictures are decoded (they must be) but not drawn and encoded, to catch up. */
    static final int BEHIND = 6;
    /** The OSD's text needs some size: pictures smaller than this height are scaled up to it. */
    static final int MIN_HEIGHT = 720;

    private record Start(Path rawFile, boolean hevc, int width, int height, byte[] codecConfig) {}

    private record Frame(byte[] data, boolean keyFrame, long arrivedMs) {}

    private static final Object END = new Object();

    /** What the screen shows, for this file: the OSD's figures as last painted, its layout, the picture's way round. */
    interface Source {
        OsdRenderer.Data osdData();

        OsdLayout osdLayout();

        boolean mirror();

        boolean upsideDown();

        double displayMs();

        /** A file with the OSD is being written: the screen takes the OSD's figures even with the HUD hidden. */
        void recording(boolean on);

        static Source of(VideoView v) {
            return new Source() {
                public OsdRenderer.Data osdData() { return v.lastOsdData(); }
                public OsdLayout osdLayout() { return v.getOsdLayout(); }
                public boolean mirror() { return v.isMirror(); }
                public boolean upsideDown() { return v.isUpsideDown(); }
                public double displayMs() { return v.displayMs(); }
                public void recording(boolean on) { v.setOsdRecording(on); }
            };
        }
    }

    private final Source view;
    private final Consumer<String> log;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE);
    private volatile boolean dropping; // the queue overflowed: frames left out up to the next key frame
    private Thread thread;

    OsdRecorder(Source view, Consumer<String> log) {
        this.view = view;
        this.log = log;
    }

    /** The file next to the raw one: "flight-x.mkv" -> "flight-x-osd.mkv". */
    static Path osdFile(Path rawFile) {
        String name = rawFile.getFileName().toString().replaceFirst("\\.mkv$", "");
        return rawFile.resolveSibling(name + "-osd.mkv");
    }

    /** The size of the file with the OSD for a w x h stream: at least MIN_HEIGHT high, even sides, the same shape. */
    static int[] outputSize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{0, 0};
        int oh = Math.max(h, MIN_HEIGHT);
        int ow = (int) Math.round(w * (double) oh / h);
        return new int[]{ow & ~1, oh & ~1};
    }

    // ------------------------------------------------------------------ the record thread (must not wait)

    @Override
    public synchronized void fileStarted(Path rawFile, boolean hevc, int width, int height, byte[] codecConfig) {
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::run, "videoRecordOsd");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
        view.recording(true);
        dropping = false;
        Start s = new Start(rawFile, hevc, width, height, codecConfig == null ? null : codecConfig.clone());
        if (!queue.offer(s)) { // far behind with the last file: that one ends here
            queue.clear();
            queue.offer(END);
            queue.offer(s);
        }
    }

    @Override
    public void frame(byte[] data, boolean keyFrame, long arrivedMs) {
        if (dropping) {
            if (!keyFrame) return;
            dropping = false;
        }
        if (!queue.offer(new Frame(data, keyFrame, arrivedMs))) dropping = true;
    }

    @Override
    public void fileEnded() {
        view.recording(false);
        if (!queue.offer(END)) {
            queue.clear();
            queue.offer(END);
        }
    }

    // ------------------------------------------------------------------ its own thread

    private VideoDecoder decoder;
    private OsdVideoWriter writer;
    private Path file;
    private boolean failed;
    private long firstMs = -1;
    private long left, written;
    private final OsdRenderer osd = new OsdRenderer();
    private BufferedImage picture, canvas;

    private void run() {
        while (true) {
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                close();
                return;
            }
            try {
                if (item instanceof Start s) {
                    close();
                    file = osdFile(s.rawFile());
                    failed = false;
                    firstMs = -1;
                    left = written = 0;
                    decoder = VideoDecoder.oneThread(s.hevc(), s.codecConfig());
                } else if (item == END) {
                    close();
                } else if (item instanceof Frame f && decoder != null && !failed) {
                    if (firstMs < 0) firstMs = f.arrivedMs(); // the raw file's first key frame: the same clock
                    if (!decoder.decode(f.data(), f.data().length)) continue;
                    if (queue.size() > BEHIND) { // behind: this picture is left out, the next ones keep up
                        left++;
                        continue;
                    }
                    draw(f.arrivedMs() - firstMs);
                }
            } catch (Throwable e) { // this file only: the raw recording goes on regardless
                failed = true;
                log.accept("Recording with the OSD stopped: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                closeWriter();
            }
        }
    }

    private void draw(long ptsMs) throws java.io.IOException {
        int[] size = outputSize(decoder.width(), decoder.height());
        int w = size[0], h = size[1];
        if (w <= 0 || h <= 0) return;
        if (writer == null) {
            writer = new OsdVideoWriter(file, w, h, 30);
            canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            log.accept("Recording with the OSD to " + file + " (" + w + "x" + h + ", " + writer.codecName() + ").");
        }
        if (canvas.getWidth() != w || canvas.getHeight() != h) return; // the stream changed size: a new file comes with it
        int[] out = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        boolean flipX = view.mirror() != view.upsideDown(), flipY = view.upsideDown(); // as the screen shows it
        Graphics2D g = canvas.createGraphics();
        try {
            if (!flipX && !flipY) {
                decoder.convert(out, w, h);
            } else {
                if (picture == null || picture.getWidth() != w || picture.getHeight() != h) picture = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                decoder.convert(((DataBufferInt) picture.getRaster().getDataBuffer()).getData(), w, h);
                g.drawImage(picture, flipX ? w : 0, flipY ? h : 0, flipX ? 0 : w, flipY ? 0 : h, 0, 0, w, h, null);
            }
            OsdRenderer.Data data = view.osdData();
            if (data != null && data.connected()) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                osd.setDisplayMs(view.displayMs());
                osd.paint(g, 0, 0, w, h, data, view.osdLayout());
            }
        } finally {
            g.dispose();
        }
        writer.write(out, ptsMs);
        written++;
    }

    private void closeWriter() {
        if (writer != null) {
            writer.close();
            log.accept("Recording with the OSD: " + file.getFileName() + " closed, " + written + " pictures"
                    + (left > 0 ? " (" + left + " left out to keep up)" : "") + ".");
            writer = null;
        }
    }

    private void close() {
        closeWriter();
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        canvas = null;
        picture = null;
    }
}
