package de.droiddrone.pc.video;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;

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

    /**
     * @param hardware       the pictures are decoded on the GPU
     * @param pingBaselineMs the link's usual ping, the lowest of the last 15-30 s (-1 before the first)
     * @param pictureAgeMs   how long ago the frame on screen arrived (-1 without one); the view adds its display time
     */
    public record Stats(boolean hevc, int width, int height, int receivedFps, int shownFps, double megabitsPerSecond,
                        double decodeMs, double convertMs, long damaged, long lost, String error, boolean hardware,
                        int bitrateStep, long drawSkipped, long bunched, long recovered, int pingBaselineMs, long pictureAgeMs) {}

    /** Frames arriving closer together than this came bunched up (4G delivers in bursts). */
    static final long BUNCH_MS = 4;
    /**
     * A restarted stream's first key frame held this long for its config and none came: decoded under the old config
     * after all (the phone always sends one; this only keeps a lost config from freezing the picture for good).
     */
    static final long CONFIG_WAIT_MS = 1000;

    private record Config(int width, int height, boolean hevc, boolean front, byte[] codecConfig) {
        boolean sameAs(Config o) {
            return width == o.width && height == o.height && hevc == o.hevc && front == o.front
                    && java.util.Arrays.equals(codecConfig, o.codecConfig);
        }
    }

    /**
     * The same config as the one in hand came this long before a stream start: it was that stream's, come first
     * (see onVideoConfig) - the start is not held for another.
     */
    static final long SAME_CONFIG_MS = 2000;
    /**
     * The oldest frame waiting for the decoder came this long ago: the decoder is behind (slower than the frames
     * come) - the queue is cut back as when it is full. Frames counted alone let the picture drift 1 s late at 30 fps.
     */
    static final long MAX_BACKLOG_AGE_MS = 200;

    private static final int MAX_BACKLOG = 30;
    private static final Object STOP = new Object();
    /** A dropped backlog: the decoder forgets what it holds and waits for a key frame - it is not rebuilt (a black gap). */
    private static final Object FLUSH = new Object();
    /**
     * A key frame is needed (a frame came damaged or lost) and the link counts as congested: the request still goes
     * after this long - on a jittery 4G link "congested" is most of the time, and without it a smeared picture
     * waited for the next periodic key frame (2 s).
     */
    static final long KEY_REQUEST_PENDING_MS = 400;
    /** No frame for this long: the first one after it is the end of a blackout - a key frame is asked for at once. */
    static final long GAP_MS = 500;
    /**
     * A (re)started stream's key frame 1 waited this long for its config: the config is asked for (GetVideoConfig,
     * answered from the phone's cache) rather than waited for up to CONFIG_WAIT_MS and then guessed at.
     */
    static final long CONFIG_ASK_MS = 300;
    /** Frames decoded without a picture coming out, one after another: the decoder is lost; a key frame is asked for. */
    static final int NO_PICTURE_RUN = 10;

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
    /**
     * Stream starts (key frame 1) minus configs seen, -1..1 (guarded by this). +1: a restarted stream's frames came
     * before its config (fragments skip the reliable queue) and wait in earlyFrames - queued under the old config,
     * a size change froze the picture for 1.5-2 s. -1: the config came first, as it does now and then.
     */
    private int startsAhead;
    private long awaitingSince;
    /** When the config in hand came again (guarded by this), 0 = not since the last stream start. */
    private long sameConfigAt;
    /** Backlogs cut back because the decoder fell behind (receive thread); and when that was last logged. */
    private volatile long ageDrops;
    private long ageDropLoggedAt;

    // recording: frames go to their own thread through a queue, so the receive thread never waits for the disk
    private record RecFrame(byte[] data, boolean keyFrame, long arrivedMs) {}
    private static final Object REC_STOP = new Object();
    private static final Object REC_NEW_FILE = new Object();
    private final LinkedBlockingQueue<Object> recordQueue = new LinkedBlockingQueue<>(240);
    private volatile java.nio.file.Path recordDir; // null = not recording
    private volatile java.nio.file.Path recordFile;
    private Thread recordThread;
    /**
     * Watching as a spectator: every frame is drawn (frames come paced, a fixed time behind the pilot), instead of only
     * the newest when two are waiting - the pilot's way, least delay. Smoother motion for the spectator.
     */
    private volatile boolean drawEveryFrame;
    /** A frame came damaged or went lost since the last intact key frame: a key frame is asked for (receive thread). */
    private boolean needKeyFrame;
    private long needKeyFrameSince;
    private final KeyFrameBackoff keyFrames = new KeyFrameBackoff();
    /** Sends the request to the phone: through the link, or a test's counter. */
    private volatile Runnable keyFrameSender = () -> {
        ControlLink l = link;
        if (l != null) l.requestKeyFrame();
    };
    /** Asks the phone for the stream's config again (GetVideoConfig): through the link, or a test's counter. */
    private volatile Runnable configRequester = () -> {
        ControlLink l = link;
        if (l != null) l.requestVideoConfig();
    };
    /** Decoding on the GPU failed once: software from the next decoder on (the GPU path was rebuilt for ever before). */
    private volatile boolean hardwareFailed;
    /** When the last picture was drawn (decode thread), and - after a change of picture size - when the last one at the old size was. */
    private volatile long lastPresentedAt;
    private volatile long resizeFrom = -1;
    /** The last size change: how long the picture stood still, ms (-1 before the first). */
    private volatile long lastResizeGapMs = -1;
    /** Frames that came within BUNCH_MS of the one before (receive thread); and when the last one came. */
    private volatile long bunched;
    private long lastAcceptAt;
    /** Frames decoded but not drawn because a newer one was already waiting (decode thread). */
    private volatile long drawSkipped;
    /** When the last fragment came (System.currentTimeMillis); the stats go to 0 once it is old. */
    private volatile long lastFragmentAt;
    /** When the frame on screen arrived (decode thread); 0 = none yet. The OSD's picture age. */
    private volatile long shownArrivedMs;

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
        this.bitrate = new BitrateControl(new BitrateControl.Sink() {
            @Override
            public void changeBitRate(boolean increase) {
                ControlLink l = link;
                if (l != null) l.sendChangeBitRate(increase);
            }

            @Override
            public void setStep(int step, int from) {
                ControlLink l = link;
                if (l != null) l.sendBitrateStep(step, from);
            }
        });
        decodeThread = new Thread(this::decodeLoop, "videoDecode");
        decodeThread.setDaemon(true);
        decodeThread.setPriority(Thread.MAX_PRIORITY);
        decodeThread.start();
    }

    /** How long the picture stood still at the last change of size, ms; -1 before the first. */
    public long lastResizeGapMs() {
        return lastResizeGapMs;
    }

    /** Spectator: draw every frame (see drawEveryFrame). */
    public void setDrawEveryFrame(boolean on) {
        drawEveryFrame = on;
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
        Config before = currentConfig;
        if (before != null && before.sameAs(config)) {
            // the config in hand once more (GetVideoConfig's cached copy after a late original, or a restarted stream
            // with the same parameter sets): the decoder can go on as it is. Before, it was rebuilt and the picture
            // froze until the next key frame, and startsAhead went to -1 (a later restart's frames then queued
            // under the old config - the freeze at a size change that startsAhead is there to prevent)
            synchronized (this) {
                if (startsAhead > 0) { // a restarted stream waits for this: it goes on to the decoder now
                    startsAhead = 0;
                    earlyFrames.forEach(queue::offer);
                    earlyFrames.clear();
                    synchronized (assembler) {
                        assembler.startedAfresh();
                    }
                } else {
                    sameConfigAt = System.currentTimeMillis(); // perhaps a restart's own, come first: see accept
                }
            }
            return;
        }
        if (before != null && (before.width() != width || before.height() != height) && lastPresentedAt != 0) {
            resizeFrom = lastPresentedAt; // measured when the first picture at the new size is drawn
        }
        synchronized (this) {
            boolean firstConfig = currentConfig == null;
            boolean awaited = startsAhead > 0; // the new stream's frames are waiting in earlyFrames
            startsAhead = Math.max(-1, startsAhead - 1);
            currentConfig = config;
            // a restarted stream whose key frame 1 was queued before this (the config was not told apart, see
            // startsAhead): keep the queue from that frame on, or the picture waits a whole key-frame interval
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
            if (firstConfig || awaited) earlyFrames.forEach(queue::offer);
            kept.forEach(queue::offer);
            earlyFrames.clear();
        }
        if (recordDir != null) recordQueue.offer(REC_NEW_FILE); // a (re)started stream: a file of its own
        FrameTap t = tap;
        if (t != null) t.onConfig(width, height, hevc, codecConfig);
        log("Video: " + (hevc ? "H.265" : "H.264") + " " + width + "x" + height);
    }

    @Override
    public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        long now = System.currentTimeMillis();
        lastFragmentAt = now;
        windowBytes += length;
        FrameAssembler.Frame frame;
        java.util.List<FrameAssembler.Frame> earlier;
        long lost;
        synchronized (assembler) {
            frame = assembler.put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length, now);
            earlier = assembler.takeFlushed(); // older unfinished frames, damaged: decoded first, they are references
            lost = assembler.getLostFrames();
        }
        for (; lastLost < lost; lastLost++) {
            bitrate.onBadFrame(false);
            if (!needKeyFrame) needKeyFrameSince = now;
            needKeyFrame = true;
        }
        for (FrameAssembler.Frame f : earlier) accept(f, now);
        if (frame != null) accept(frame, now);
        if (now - windowStart >= 1000) {
            receivedFps = (int) (windowFrames * 1000 / Math.max(1, now - windowStart));
            megabitsPerSecond = windowBytes * 8.0 / 1000.0 / Math.max(1, now - windowStart);
            bitrate.onReceivedRate(megabitsPerSecond, now);
            ControlLink l = link;
            if (l != null) {
                bitrate.onPhoneTarget(l.getTelemetry().getPhoneTargetBitRate());
                bitrate.setMaxStep(l.maxBitrateStep()); // 1 Mbit/s in bad coverage mode, else the set limit
                boolean robust = l.isRobust();
                keyFrames.setRobust(robust);
                synchronized (assembler) {
                    assembler.setWaitForWholeKeyFrames(robust); // key frames come twice then
                }
            }
            windowFrames = 0;
            windowBytes = 0;
            windowStart = now;
        }
    }

    /** A finished frame (receive thread): recorded, counted, and queued for the decoder. */
    private void accept(FrameAssembler.Frame frame, long now) {
        if (recordDir != null && !recordQueue.offer(new RecFrame(frame.data(), frame.keyFrame(), now))) {
            log("Recording: the disk is too slow, a frame was left out");
        }
        FrameTap t = tap;
        if (t != null) t.onFrame(frame.data(), frame.keyFrame(), now);
        windowFrames++;
        // a damaged or lost frame breaks the pictures after it until a key frame: ask for one (phone app pc.13 on;
        // the link limits how often) rather than wait for the next one - they come every 2-10 s now
        if (frame.damaged()) {
            if (!needKeyFrame) needKeyFrameSince = now;
            needKeyFrame = true;
        } else if (frame.keyFrame()) {
            needKeyFrame = false;
        }
        if (frame.keyFrame()) keyFrames.onKeyFrame(frame.damaged());
        // not while the link is congested: a key frame is the biggest burst there is, and it would come damaged too -
        // asked for once the link has cleared (needKeyFrame stays); but not held longer than KEY_REQUEST_PENDING_MS,
        // and never after a gap: the first frame after a blackout is the moment to start afresh
        boolean afterGap = lastAcceptAt != 0 && now - lastAcceptAt > GAP_MS;
        if (needKeyFrame && (afterGap || !bitrate.isCongested(now) || now - needKeyFrameSince >= KEY_REQUEST_PENDING_MS)) requestKeyFrame();
        if (lastAcceptAt != 0 && now - lastAcceptAt < BUNCH_MS) bunched++;
        lastAcceptAt = now;
        if (frame.damaged()) bitrate.onBadFrame(frame.keyFrame());
        bitrate.onFrame(frame.keyFrame(), now, Math.max(receivedFps, 30), ping.getAsInt());
        synchronized (this) {
            if (frame.streamStart() && currentConfig != null && sameConfigAt != 0 && now - sameConfigAt < SAME_CONFIG_MS) {
                sameConfigAt = 0; // its config came first and is the one in hand: nothing to wait for
                synchronized (assembler) {
                    assembler.startedAfresh(); // it saw this start: only its flag goes
                }
            } else if (frame.streamStart() && currentConfig != null) {
                sameConfigAt = 0;
                startsAhead = Math.min(1, startsAhead + 1);
                if (startsAhead > 0) { // its config hasn't come: hold the new stream for it (see startsAhead)
                    awaitingSince = now;
                    earlyFrames.clear();
                }
            }
            boolean awaiting = startsAhead > 0 && currentConfig != null;
            if (awaiting && now - awaitingSince > CONFIG_ASK_MS) configRequester.run(); // the link limits how often
            if (awaiting && now - awaitingSince > CONFIG_WAIT_MS) { // no config came: under the old one, not frozen
                startsAhead = 0;
                awaiting = false;
                earlyFrames.forEach(queue::offer);
                earlyFrames.clear();
            }
            if (currentConfig == null || awaiting) {
                if (frame.keyFrame()) earlyFrames.clear(); // only the newest key frame on can be decoded anyway
                // full: the oldest P-frame goes, never the key frame at the front (nothing decodes without it)
                if (earlyFrames.size() >= MAX_BACKLOG) earlyFrames.remove(earlyFrames.get(0).keyFrame() && earlyFrames.size() > 1 ? 1 : 0);
                earlyFrames.add(frame);
            } else {
                if (queue.size() >= MAX_BACKLOG) {
                    dropBacklog();
                } else if (decoderBehind(oldestQueuedAge(now))) {
                    ageDrops++;
                    if (now - ageDropLoggedAt > 5000) {
                        ageDropLoggedAt = now;
                        log("Video: the decoder is " + oldestQueuedAge(now) + " ms behind (" + String.format("%.1f", decodeMs)
                                + " ms a frame) - cut back to the newest key frame");
                    }
                    dropBacklog();
                }
                queue.offer(frame);
            }
        }
    }

    /**
     * The decoder is behind for good: the oldest waiting frame is MAX_BACKLOG_AGE_MS old and decoding takes about as
     * long as the frames come apart (it won't catch up) - or it is a whole second old, whatever the cause. A short
     * hold-up of a decoder that keeps up easily is caught up in a few frames: not cut (that would wait for a key frame).
     */
    private boolean decoderBehind(long ageMs) {
        return ageMs > MAX_BACKLOG_AGE_MS && (decodeLoad() > 0.8 || ageMs > 1000);
    }

    /** How long ago the oldest frame waiting for the decoder came, ms (0 without one, or without arrival times). */
    private long oldestQueuedAge(long now) {
        for (Object o : queue) {
            if (o instanceof FrameAssembler.Frame f) return f.arrivedMs() > 0 ? now - f.arrivedMs() : 0;
        }
        return 0;
    }

    /**
     * The decoder's time per frame over the time between frames (1 = just keeps up; above it the decoder falls behind
     * and the backlog is cut back to key frames - a smaller picture or another tier is the way out). 0 without a stream.
     */
    public double decodeLoad() {
        int fps = receivedFps;
        return fps <= 0 ? 0 : decodeMs * fps / 1000.0;
    }

    /** Backlogs cut back because the decoder fell MAX_BACKLOG_AGE_MS behind. */
    public long decoderBehindDrops() {
        return ageDrops;
    }

    /**
     * Hopelessly behind: the queue is cut back to its newest whole key frame (or config), so the decoder starts
     * over there at once. Without one, everything goes and the decoder waits for the next key frame (FLUSH) - before
     * 1.0.33 that was always the case, and the burst that ends a blackout left with the key frame in it.
     */
    private void dropBacklog() {
        java.util.List<Object> queued = new java.util.ArrayList<>();
        queue.drainTo(queued);
        int start = -1;
        for (int i = 0; i < queued.size(); i++) {
            Object o = queued.get(i);
            if (o instanceof Config || (o instanceof FrameAssembler.Frame f && f.keyFrame() && !f.damaged())) start = i;
        }
        if (start < 0) queue.offer(FLUSH);
        for (int i = 0; i < queued.size(); i++) {
            if (i >= start || queued.get(i) == STOP) queue.offer(queued.get(i));
        }
    }

    @Override
    public void onVideoParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
        long now = System.currentTimeMillis();
        FrameAssembler.Frame frame;
        java.util.List<FrameAssembler.Frame> earlier;
        synchronized (assembler) {
            frame = assembler.putParity(keyFrame, frameNum, frameSize, buf, dataOffset, length, now);
            earlier = assembler.takeFlushed();
        }
        for (FrameAssembler.Frame f : earlier) accept(f, now);
        if (frame != null) accept(frame, now);
    }

    @Override
    public void onVideoParityGroup(boolean keyFrame, short frameNum, int frameSize, int group, byte[] buf, int dataOffset, int length) {
        long now = System.currentTimeMillis();
        FrameAssembler.Frame frame;
        java.util.List<FrameAssembler.Frame> earlier;
        synchronized (assembler) {
            frame = assembler.putParityGroup(keyFrame, frameNum, frameSize, group, buf, dataOffset, length, now);
            earlier = assembler.takeFlushed();
        }
        for (FrameAssembler.Frame f : earlier) accept(f, now);
        if (frame != null) accept(frame, now);
    }

    // ------------------------------------------------------------------ decode thread

    private void decodeLoop() {
        VideoDecoder decoder = null;
        Config decoderConfig = null; // the last config the decoder was made for
        boolean waitingForKey = true;
        boolean mirror = false;
        int shown = 0;
        int noPictureRun = 0;
        long shownWindow = System.currentTimeMillis();
        try {
            while (running) {
                Object item;
                try {
                    item = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                try { // nothing ends this thread but STOP: an error is shown on the HUD and the next frame is tried
                    long now = System.currentTimeMillis();
                    // the bitrate control also acts when no frame comes (a dropout), while there is a stream
                    if (item == null && now - lastFragmentAt < 10_000) bitrate.onTick(now, ping.getAsInt());
                    if (now - shownWindow >= 1000) {
                        shownFps = (int) (shown * 1000 / (now - shownWindow));
                        shown = 0;
                        shownWindow = now;
                    }
                    if (item == null) continue;
                    if (item == STOP) break;
                    if (item == FLUSH) {
                        if (decoder != null) decoder.flush();
                        waitingForKey = true;
                        requestKeyFrame();
                        continue;
                    }
                    if (item instanceof Config c) {
                        if (decoder != null) decoder.close();
                        decoder = makeDecoder(c);
                        decoderConfig = c;
                        mirror = c.front();
                        sink.videoStarted(c.width(), c.height(), mirror);
                        waitingForKey = true;
                        noPictureRun = 0;
                        continue;
                    }
                    FrameAssembler.Frame frame = (FrameAssembler.Frame) item;
                    // no decoder (it could not be made at the config): tried again at each key frame, in software
                    // after a GPU failure - before, nothing was shown until the next config
                    if (decoder == null && frame.keyFrame() && decoderConfig != null) decoder = makeDecoder(decoderConfig);
                    if (decoder == null || (waitingForKey && !frame.keyFrame())) {
                        requestKeyFrame(); // a fresh decoder (stream start, or after an error) needs one
                        continue;
                    }
                    waitingForKey = false;
                    try {
                        long t0 = System.nanoTime();
                        boolean picture = decoder.decode(frame.data(), frame.data().length);
                        long t1 = System.nanoTime();
                        decodeMs = smooth(decodeMs, (t1 - t0) / 1e6);
                        if (!picture) {
                            // every frame should give a picture (no B-frames, LOW_DELAY). None from a key frame: the
                            // config in hand isn't the stream's - asked for again, then a key frame to start on.
                            // None from frames in a row: the decoder is lost; a key frame. Before 1.0.33 nothing
                            // happened here, and the picture stood until the next periodic key frame or for good
                            noPictureRun++;
                            if (frame.keyFrame()) {
                                log("Video: a key frame gave no picture - asking for the stream's config and a key frame");
                                configRequester.run();
                                requestKeyFrame();
                                waitingForKey = true;
                            } else if (noPictureRun >= NO_PICTURE_RUN) {
                                requestKeyFrame();
                            }
                            continue;
                        }
                        noPictureRun = 0;
                        if (!drawEveryFrame && !queue.isEmpty()) { // a newer frame is waiting: don't draw this one
                            drawSkipped++;
                            continue;
                        }
                        int[] size = sink.targetSize();
                        if (size[0] <= 0 || size[1] <= 0) continue;
                        int[] pixels = sink.beginFrame(size[0], size[1]);
                        decoder.convert(pixels, size[0], size[1]);
                        decodingOnGpu = decoder.isHardware();
                        sink.presentFrame();
                        long shownAt = System.currentTimeMillis();
                        lastPresentedAt = shownAt;
                        shownArrivedMs = frame.arrivedMs() > 0 ? frame.arrivedMs() : shownAt;
                        if (recordDir != null) recordQueue.offer(osdRow(shownArrivedMs)); // the HUD's figures, for a replay
                        long from = resizeFrom;
                        if (from > 0) { // the first picture at the new size: how long the picture stood still
                            resizeFrom = -1;
                            lastResizeGapMs = shownAt - from;
                            log("Video: new picture size shown " + lastResizeGapMs + " ms after the last one at the old size");
                        }
                        convertMs = smooth(convertMs, (System.nanoTime() - t1) / 1e6);
                        shown++;
                    } catch (Throwable e) {
                        // one bad frame must not end the video: a fresh decoder, from the next key frame
                        error = e.toString();
                        boolean onGpu = decoder.isHardware() || (hardwareDecode && !hardwareFailed);
                        log("Video decode error (decoder restarted" + (onGpu ? ", in software from now on" : "") + "): " + e);
                        if (onGpu) hardwareFailed = true; // the GPU path failed: not rebuilt for ever, as before 1.0.33
                        try {
                            decoder.close();
                        } catch (Throwable ignored) {
                        }
                        decoder = null;
                        Config c = currentConfig;
                        if (c != null) decoder = makeDecoder(c); // else at the next key frame (above)
                        waitingForKey = true;
                        requestKeyFrame();
                    }
                } catch (Throwable e) { // nothing above should get here; if it does, the HUD shows it and the loop goes on
                    error = e.toString();
                    log("Video decode thread error: " + e);
                }
            }
        } finally {
            if (decoder != null) decoder.close();
        }
    }

    /**
     * A decoder for this config (decode thread), on the GPU if wanted and it hasn't failed; a GPU decoder that can't be
     * made marks the GPU failed and software is tried at once. Null if none can be made (the HUD shows why).
     */
    private VideoDecoder makeDecoder(Config c) {
        boolean gpu = hardwareDecode && !hardwareFailed;
        try {
            VideoDecoder d = decoderFactory.make(c.hevc(), c.codecConfig(), gpu);
            error = null;
            return d;
        } catch (Throwable e) { // also a missing native library: the HUD says so
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log("Video decoder" + (gpu ? " (on the GPU; software from now on)" : "") + ": " + e);
            if (!gpu) return null;
            hardwareFailed = true;
            try {
                VideoDecoder d = decoderFactory.make(c.hevc(), c.codecConfig(), false);
                error = null;
                return d;
            } catch (Throwable again) {
                log("Video decoder: " + again);
                return null;
            }
        }
    }

    /** Makes decoders: FFmpeg's, or a test's that fails. */
    interface DecoderFactory {
        VideoDecoder make(boolean hevc, byte[] codecConfig, boolean hardware);
    }

    private volatile DecoderFactory decoderFactory = VideoDecoder::new;

    /** Tests: decoders from here instead. */
    void setDecoderFactory(DecoderFactory factory) {
        decoderFactory = factory;
    }

    private static double smooth(double avg, double sample) {
        return avg == 0 ? sample : avg * 0.9 + sample * 0.1;
    }

    public Stats stats() {
        Config c = currentConfig;
        long damaged;
        long lost;
        long recovered;
        synchronized (assembler) {
            damaged = assembler.getDamagedFrames();
            lost = assembler.getLostFrames();
            recovered = assembler.getRecoveredFrames();
        }
        long now = System.currentTimeMillis();
        boolean flowing = now - lastFragmentAt < 1500; // stopped: no old figures
        long arrived = shownArrivedMs;
        return new Stats(c != null && c.hevc(), c == null ? 0 : c.width(), c == null ? 0 : c.height(), flowing ? receivedFps : 0,
                flowing ? shownFps : 0, flowing ? megabitsPerSecond : 0, decodeMs, convertMs, damaged, lost, error, decodingOnGpu,
                bitrate.step(), drawSkipped, bunched, recovered, bitrate.baselinePingMs(), arrived == 0 ? -1 : now - arrived);
    }

    /** Decode on the GPU from the next stream (re)start or decoder restart; software stays the fallback. */
    public void setHardwareDecode(boolean on) {
        hardwareDecode = on;
        if (on) hardwareFailed = false; // asked for again: one more try
    }

    // ------------------------------------------------------------------ key frame requests

    /**
     * How often to ask the phone for a key frame. A request is answered by the next key frame; when that one comes
     * damaged too, asking again at once only adds bursts to a link that is dropping them, so the spacing doubles
     * (1.5 -> 3 -> 6 s) until a key frame comes whole. In bad coverage mode the phone sends every key frame twice and
     * the assembler waits for the second copy, so a damaged one there means both copies failed: it starts at 3 s.
     */
    static final class KeyFrameBackoff {
        static final long BASE_MS = 1500;
        static final long ROBUST_BASE_MS = 3000;
        static final long MAX_MS = 6000;
        private long spacing = BASE_MS;
        private long lastSentAt = Long.MIN_VALUE / 2;
        /** A request went out and no key frame has come since: the next one answers it. */
        private boolean outstanding;
        private boolean robust;

        synchronized void setRobust(boolean on) {
            if (robust == on) return;
            robust = on;
            spacing = Math.max(base(), Math.min(spacing, MAX_MS));
        }

        private long base() {
            return robust ? ROBUST_BASE_MS : BASE_MS;
        }

        /** True to send a request now (and it counts as sent). */
        synchronized boolean shouldSend(long now) {
            if (now - lastSentAt < spacing) return false;
            lastSentAt = now;
            outstanding = true;
            return true;
        }

        synchronized void onKeyFrame(boolean damaged) {
            if (!damaged) spacing = base();
            else if (outstanding) spacing = Math.min(MAX_MS, spacing * 2);
            outstanding = false;
        }

        synchronized long spacingMs() {
            return spacing;
        }
    }

    /** A key frame from the phone soon (the link limits how often too; nothing while watching or not connected). */
    private void requestKeyFrame() {
        if (keyFrames.shouldSend(System.currentTimeMillis())) keyFrameSender.run();
    }

    /** The current spacing of key frame requests, ms (the link log). */
    public long keyFrameSpacingMs() {
        return keyFrames.spacingMs();
    }

    /** Tests: where key frame requests go instead of the link. */
    void setKeyFrameSender(Runnable sender) {
        keyFrameSender = sender;
    }

    /** Tests: where requests for the stream's config go instead of the link. */
    void setConfigRequester(Runnable requester) {
        configRequester = requester;
    }

    // ------------------------------------------------------------------ the STANAG 4609 stream

    /**
     * Gets every stream config and finished frame as it comes, on the receive thread (1.0.39, the STANAG 4609 stream):
     * it must return at once - hand the frame to a thread of its own, or leave it out.
     */
    public interface FrameTap {
        void onConfig(int width, int height, boolean hevc, byte[] codecConfig);

        void onFrame(byte[] data, boolean keyFrame, long arrivedMs);
    }

    private volatile FrameTap tap;

    /** Sets (null: takes away) the tap; a new one gets the config in hand and a key frame is asked for, to start from. */
    public void setFrameTap(FrameTap t) {
        tap = t;
        Config c = currentConfig;
        if (t != null && c != null) {
            t.onConfig(c.width(), c.height(), c.hevc(), c.codecConfig());
            requestKeyFrame();
        }
    }

    /** The picture size in hand, {width, height}, or null before the first config. */
    public int[] pictureSize() {
        Config c = currentConfig;
        return c == null ? null : new int[]{c.width(), c.height()};
    }

    // ------------------------------------------------------------------ recording

    /** Records the stream into a new MKV file in dir from the next key frame (a new file if the stream restarts). */
    public synchronized void startRecording(java.nio.file.Path dir) {
        if (recordDir != null) return;
        requestKeyFrame(); // a file starts at a key frame: one now rather than in up to 10 s
        recordQueue.clear();
        recordDir = dir;
        recordThread = new Thread(this::recordLoop, "videoRecord");
        recordThread.setDaemon(true);
        recordThread.setPriority(Thread.MIN_PRIORITY);
        recordThread.start();
    }

    /** Finishes the file. Waits for it outside the lock: the receive thread must not stand still for the disk. */
    public void stopRecording() {
        Thread t;
        synchronized (this) {
            if (recordDir == null) return;
            recordDir = null;
            if (!recordQueue.offer(REC_STOP)) { // full (a slow disk): what is in it is not worth the wait
                recordQueue.clear();
                recordQueue.offer(REC_STOP);
            }
            t = recordThread;
            recordThread = null;
        }
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException ignored) {
        }
    }

    public boolean isRecording() {
        return recordDir != null;
    }

    /** The file being written, or null (none yet: waiting for the stream / its first key frame). */
    public java.nio.file.Path recordingFile() {
        return recordFile;
    }

    /** The HUD's figures for the frame on screen (decode thread), for the CSV next to the recording. */
    private StreamRecorder.OsdRow osdRow(long arrivedMs) {
        ControlLink l = link;
        FcTelemetry t = l == null ? null : l.getTelemetry();
        FcTelemetry.Battery b = t == null ? null : t.getBattery();
        FcTelemetry.Gps gps = t == null ? null : t.getGps();
        FcTelemetry.Altitude alt = t == null ? null : t.getAltitude();
        FcTelemetry.Status s = t == null ? null : t.getStatus();
        FcTelemetry.Attitude att = t == null ? null : t.getAttitude();
        return new StreamRecorder.OsdRow(arrivedMs, ping.getAsInt(),
                b == null ? Double.NaN : b.volts(), b == null ? Double.NaN : b.cellVolts(), b == null ? Double.NaN : b.amps(),
                b == null ? -1 : b.mahDrawn(), gps == null ? -1 : gps.sats(), gps == null ? -1 : gps.fix(),
                gps == null ? Double.NaN : gps.latDeg(), gps == null ? Double.NaN : gps.lonDeg(), gps == null ? Double.NaN : gps.speedKmh(),
                gps == null ? -1 : gps.distanceHomeM(), alt == null ? Double.NaN : alt.meters(), alt == null ? Double.NaN : alt.varioMetersPerSecond(),
                s == null ? "" : FcTelemetry.flightMode(s.activeModes()), s != null && s.armed(),
                att == null ? Double.NaN : att.rollDeg(), att == null ? Double.NaN : att.pitchDeg(), att == null ? -1 : att.headingDeg());
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
                if (item instanceof StreamRecorder.OsdRow row) {
                    if (recorder != null) recorder.writeOsd(row);
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
