package de.droiddrone.pc.video;

import de.droiddrone.pc.ControlLink;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import static de.droiddrone.common.Log.log;

/**
 * Network fragments -> whole frames -> decoder -> screen, with the least possible waiting:
 * frames are handed to a dedicated decode thread the moment they are complete; every frame is decoded (each depends
 * on the previous one), but a picture is only converted and drawn if no newer frame is already waiting, so the screen
 * never shows a backlog. After a decoder (re)start or a dropped backlog, frames are skipped until the next key frame.
 */
public class VideoPipeline implements ControlLink.VideoListener, AutoCloseable {
    /** Where decoded pictures go: the video view. Called on the decode thread. */
    public interface FrameSink {
        /** The stream (re)started with this picture size and orientation. */
        void videoStarted(int width, int height, boolean mirror);

        /** Size to scale the next picture to (the on-screen video area); w,h <= 0 means nothing visible. */
        int[] targetSize();

        /** A 0xRRGGBB buffer of width * height to fill; then {@link #presentFrame()}. */
        int[] beginFrame(int width, int height);

        void presentFrame();
    }

    public record Stats(boolean hevc, int width, int height, int receivedFps, int shownFps, double megabitsPerSecond,
                        double decodeMs, double convertMs, long damaged, long lost, String error) {}

    private record Config(int width, int height, boolean hevc, boolean front, byte[] codecConfig) {}

    private static final int MAX_BACKLOG = 30;
    private static final Object STOP = new Object();

    private final FrameSink sink;
    private final IntSupplier ping;
    private final FrameAssembler assembler = new FrameAssembler();
    private final BitrateControl bitrate;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final java.util.List<FrameAssembler.Frame> earlyFrames = new java.util.ArrayList<>();
    private final Thread decodeThread;
    private volatile ControlLink link;
    private volatile boolean running = true;

    // receive-thread statistics, published once a second
    private long windowStart;
    private int windowFrames;
    private long windowBytes;
    private long lastLost;
    private volatile int receivedFps;
    private volatile double megabitsPerSecond;
    private volatile Config currentConfig;

    // decode-thread statistics
    private volatile int shownFps;
    private volatile double decodeMs;
    private volatile double convertMs;
    private volatile String error;

    public VideoPipeline(FrameSink sink, IntSupplier ping) {
        this.sink = sink;
        this.ping = ping;
        windowStart = System.currentTimeMillis();
        this.bitrate = new BitrateControl(increase -> {
            ControlLink l = link;
            if (l != null) l.sendChangeBitRate(increase);
        });
        decodeThread = new Thread(this::decodeLoop, "videoDecode");
        decodeThread.setDaemon(true);
        decodeThread.setPriority(Thread.MAX_PRIORITY);
        decodeThread.start();
    }

    /** The link to send bitrate requests on. */
    public void attach(ControlLink link) {
        this.link = link;
    }

    // ------------------------------------------------------------------ receive thread

    /**
     * Called on the link's buffer thread. Fragments bypass that thread, so the first frames (key frame included) can
     * be complete before this runs: they wait in earlyFrames instead of being dropped, which would cost a whole
     * key-frame interval before the first picture.
     */
    @Override
    public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
        Config config = new Config(width, height, hevc, frontCamera, codecConfig);
        synchronized (this) {
            boolean firstConfig = currentConfig == null;
            currentConfig = config;
            queue.clear();
            queue.offer(config);
            if (firstConfig) {
                earlyFrames.forEach(queue::offer);
            } else {
                synchronized (assembler) {
                    assembler.reset(); // a restarted stream: fragments of the old one are useless
                }
            }
            earlyFrames.clear();
        }
        log("Video: " + (hevc ? "H.265" : "H.264") + " " + width + "x" + height);
    }

    @Override
    public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        long now = System.currentTimeMillis();
        windowBytes += length;
        FrameAssembler.Frame frame;
        long lost;
        synchronized (assembler) {
            frame = assembler.put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length);
            lost = assembler.getLostFrames();
        }
        for (; lastLost < lost; lastLost++) bitrate.onBadFrame(false);
        if (frame != null) {
            windowFrames++;
            if (frame.damaged()) bitrate.onBadFrame(frame.keyFrame());
            bitrate.onFrame(frame.keyFrame(), now, Math.max(receivedFps, 30), ping.getAsInt());
            synchronized (this) {
                if (currentConfig == null) {
                    if (earlyFrames.size() < MAX_BACKLOG) earlyFrames.add(frame);
                } else {
                    if (queue.size() >= MAX_BACKLOG) {
                        queue.clear(); // hopelessly behind: start over at the next key frame
                        queue.offer(currentConfig);
                    }
                    queue.offer(frame);
                }
            }
        }
        if (now - windowStart >= 1000) {
            receivedFps = (int) (windowFrames * 1000 / Math.max(1, now - windowStart));
            megabitsPerSecond = windowBytes * 8.0 / 1000.0 / Math.max(1, now - windowStart);
            windowFrames = 0;
            windowBytes = 0;
            windowStart = now;
        }
    }

    // ------------------------------------------------------------------ decode thread

    private void decodeLoop() {
        VideoDecoder decoder = null;
        boolean waitingForKey = true;
        boolean mirror = false;
        int shown = 0;
        long shownWindow = System.currentTimeMillis();
        try {
            while (running) {
                Object item = queue.poll(200, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (now - shownWindow >= 1000) {
                    shownFps = (int) (shown * 1000 / (now - shownWindow));
                    shown = 0;
                    shownWindow = now;
                }
                if (item == null) continue;
                if (item == STOP) break;
                if (item instanceof Config c) {
                    if (decoder != null) decoder.close();
                    decoder = null;
                    try {
                        decoder = new VideoDecoder(c.hevc(), c.codecConfig());
                        error = null;
                    } catch (RuntimeException e) {
                        error = e.getMessage();
                        log("Video decoder: " + e.getMessage());
                    }
                    mirror = c.front();
                    sink.videoStarted(c.width(), c.height(), mirror);
                    waitingForKey = true;
                    continue;
                }
                FrameAssembler.Frame frame = (FrameAssembler.Frame) item;
                if (decoder == null || (waitingForKey && !frame.keyFrame())) continue;
                waitingForKey = false;
                long t0 = System.nanoTime();
                boolean picture = decoder.decode(frame.data(), frame.data().length);
                long t1 = System.nanoTime();
                decodeMs = smooth(decodeMs, (t1 - t0) / 1e6);
                if (!picture || !queue.isEmpty()) continue; // a newer frame is waiting: don't draw this one
                int[] size = sink.targetSize();
                if (size[0] <= 0 || size[1] <= 0) continue;
                int[] pixels = sink.beginFrame(size[0], size[1]);
                decoder.convert(pixels, size[0], size[1]);
                sink.presentFrame();
                convertMs = smooth(convertMs, (System.nanoTime() - t1) / 1e6);
                shown++;
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException e) {
            error = e.toString();
            log("Video decode error: " + e);
        } finally {
            if (decoder != null) decoder.close();
        }
    }

    private static double smooth(double avg, double sample) {
        return avg == 0 ? sample : avg * 0.9 + sample * 0.1;
    }

    public Stats stats() {
        Config c = currentConfig;
        long damaged;
        long lost;
        synchronized (assembler) {
            damaged = assembler.getDamagedFrames();
            lost = assembler.getLostFrames();
        }
        return new Stats(c != null && c.hevc(), c == null ? 0 : c.width(), c == null ? 0 : c.height(), receivedFps,
                shownFps, megabitsPerSecond, decodeMs, convertMs, damaged, lost, error);
    }

    public boolean hasStream() {
        return currentConfig != null;
    }

    @Override
    public void close() {
        running = false;
        queue.clear();
        queue.offer(STOP);
        try {
            decodeThread.join(1000);
        } catch (InterruptedException ignored) {
        }
    }
}
