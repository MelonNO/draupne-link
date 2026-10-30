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

    /** @param hardware the pictures are decoded on the GPU */
    public record Stats(boolean hevc, int width, int height, int receivedFps, int shownFps, double megabitsPerSecond,
                        double decodeMs, double convertMs, long damaged, long lost, String error, boolean hardware) {}

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

    // recording: frames go to their own thread through a queue, so the receive thread never waits for the disk
    private record RecFrame(byte[] data, boolean keyFrame, long arrivedMs) {}
    private static final Object REC_STOP = new Object();
    private static final Object REC_NEW_FILE = new Object();
    private final LinkedBlockingQueue<Object> recordQueue = new LinkedBlockingQueue<>(240);
    private volatile java.nio.file.Path recordDir; // null = not recording
    private volatile java.nio.file.Path recordFile;
    private Thread recordThread;
    /** When the last fragment came (System.currentTimeMillis); the stats go to 0 once it is old. */
    private volatile long lastFragmentAt;

    // decode-thread statistics
    private volatile int shownFps;
    private volatile double decodeMs;
    private volatile double convertMs;
    private volatile String error;
    /** SETTINGS > Hardware video decoding: for decoders made from now on. */
    private volatile boolean hardwareDecode;
    private volatile boolean decodingOnGpu;

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
            // a restarted stream: its first frames often come before this (fragments skip the reliable queue) -
            // keep them from its first key frame on, or the picture waits a whole key-frame interval
            java.util.List<Object> kept = new java.util.ArrayList<>();
            if (!firstConfig) {
                java.util.List<Object> queued = new java.util.ArrayList<>(queue);
                int start = -1;
                for (int i = 0; i < queued.size(); i++) {
                    if (queued.get(i) instanceof FrameAssembler.Frame f && f.streamStart()) start = i;
                }
                if (start >= 0) kept.addAll(queued.subList(start, queued.size()));
                synchronized (assembler) {
                    assembler.startedAfresh(); // keeps the new stream's fragments, drops the old one's
                }
            }
            queue.clear();
            queue.offer(config);
            if (firstConfig) earlyFrames.forEach(queue::offer);
            kept.forEach(queue::offer);
            earlyFrames.clear();
        }
        if (recordDir != null) recordQueue.offer(REC_NEW_FILE); // a (re)started stream: a file of its own
        log("Video: " + (hevc ? "H.265" : "H.264") + " " + width + "x" + height);
    }

    @Override
    public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        long now = System.currentTimeMillis();
        lastFragmentAt = now;
        windowBytes += length;
        FrameAssembler.Frame frame;
        long lost;
        synchronized (assembler) {
            frame = assembler.put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length);
            lost = assembler.getLostFrames();
        }
        for (; lastLost < lost; lastLost++) bitrate.onBadFrame(false);
        if (frame != null) {
            if (recordDir != null && !recordQueue.offer(new RecFrame(frame.data(), frame.keyFrame(), now))) {
                log("Recording: the disk is too slow, a frame was left out");
            }
            windowFrames++;
            if (frame.damaged()) bitrate.onBadFrame(frame.keyFrame());
            bitrate.onFrame(frame.keyFrame(), now, Math.max(receivedFps, 30), ping.getAsInt());
            synchronized (this) {
                if (currentConfig == null) {
                    if (frame.keyFrame()) earlyFrames.clear(); // only the newest key frame on can be decoded anyway
                    if (earlyFrames.size() >= MAX_BACKLOG) earlyFrames.remove(0);
                    earlyFrames.add(frame);
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
                        decoder = new VideoDecoder(c.hevc(), c.codecConfig(), hardwareDecode);
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
                try {
                    long t0 = System.nanoTime();
                    boolean picture = decoder.decode(frame.data(), frame.data().length);
                    long t1 = System.nanoTime();
                    decodeMs = smooth(decodeMs, (t1 - t0) / 1e6);
                    if (!picture || !queue.isEmpty()) continue; // a newer frame is waiting: don't draw this one
                    int[] size = sink.targetSize();
                    if (size[0] <= 0 || size[1] <= 0) continue;
                    int[] pixels = sink.beginFrame(size[0], size[1]);
                    decoder.convert(pixels, size[0], size[1]);
                    decodingOnGpu = decoder.isHardware();
                    sink.presentFrame();
                    convertMs = smooth(convertMs, (System.nanoTime() - t1) / 1e6);
                    shown++;
                } catch (RuntimeException e) {
                    // one bad frame must not end the video: a fresh decoder, from the next key frame
                    error = e.toString();
                    log("Video decode error (decoder restarted): " + e);
                    decoder.close();
                    decoder = null;
                    Config c = currentConfig;
                    if (c != null) {
                        try {
                            decoder = new VideoDecoder(c.hevc(), c.codecConfig(), hardwareDecode);
                        } catch (RuntimeException again) {
                            log("Video decoder: " + again.getMessage());
                        }
                    }
                    waitingForKey = true;
                }
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
        boolean flowing = System.currentTimeMillis() - lastFragmentAt < 1500; // stopped: no old figures
        return new Stats(c != null && c.hevc(), c == null ? 0 : c.width(), c == null ? 0 : c.height(), flowing ? receivedFps : 0,
                flowing ? shownFps : 0, flowing ? megabitsPerSecond : 0, decodeMs, convertMs, damaged, lost, error, decodingOnGpu);
    }

    /** Decode on the GPU from the next stream (re)start or decoder restart; software stays the fallback. */
    public void setHardwareDecode(boolean on) {
        hardwareDecode = on;
    }

    // ------------------------------------------------------------------ recording

    /** Records the stream into a new MKV file in dir from the next key frame (a new file if the stream restarts). */
    public synchronized void startRecording(java.nio.file.Path dir) {
        if (recordDir != null) return;
        recordQueue.clear();
        recordDir = dir;
        recordThread = new Thread(this::recordLoop, "videoRecord");
        recordThread.setDaemon(true);
        recordThread.setPriority(Thread.MIN_PRIORITY);
        recordThread.start();
    }

    /** Finishes the file. */
    public synchronized void stopRecording() {
        if (recordDir == null) return;
        recordDir = null;
        recordQueue.offer(REC_STOP);
        try {
            recordThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        recordThread = null;
    }

    public boolean isRecording() {
        return recordDir != null;
    }

    /** The file being written, or null (none yet: waiting for the stream / its first key frame). */
    public java.nio.file.Path recordingFile() {
        return recordFile;
    }

    private void recordLoop() {
        StreamRecorder recorder = null;
        try {
            while (true) {
                Object item = recordQueue.take();
                if (item == REC_STOP) break;
                if (item == REC_NEW_FILE) {
                    if (recorder != null) recorder.close();
                    recorder = null;
                    continue;
                }
                RecFrame f = (RecFrame) item;
                Config c = currentConfig;
                java.nio.file.Path dir = recordDir;
                if (recorder == null) {
                    if (c == null || dir == null || !f.keyFrame()) continue; // a file starts at a key frame
                    String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                    java.nio.file.Path file = dir.resolve("flight-" + stamp + ".mkv");
                    recorder = new StreamRecorder(file, c.hevc(), c.width(), c.height(), c.codecConfig());
                    recordFile = file;
                    log("Recording to " + file);
                }
                recorder.write(f.data(), f.keyFrame(), f.arrivedMs());
            }
        } catch (InterruptedException ignored) {
        } catch (java.io.IOException | RuntimeException e) {
            log("Recording stopped: " + e.getMessage());
            recordDir = null;
        } finally {
            if (recorder != null) recorder.close();
            recordFile = null;
        }
    }

    public boolean hasStream() {
        return currentConfig != null;
    }

    @Override
    public void close() {
        stopRecording();
        running = false;
        queue.clear();
        queue.offer(STOP);
        try {
            decodeThread.join(1000);
        } catch (InterruptedException ignored) {
        }
    }
}
