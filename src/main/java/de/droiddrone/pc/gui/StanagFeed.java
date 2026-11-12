package de.droiddrone.pc.gui;

import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.overwatch.ClipPlayer;
import de.droiddrone.pc.video.H264Encoder;
import de.droiddrone.pc.video.Klv;
import de.droiddrone.pc.video.StanagStream;
import de.droiddrone.pc.video.VideoDecoder;
import de.droiddrone.pc.video.VideoPipeline;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * One drone's STANAG 4609 stream (1.0.65, the pilot: "one stream for each drone"): always made here on the PC - the
 * phone sends nothing for it - as H.264 at {@link #W} x {@link #H} in one transport stream that never restarts,
 * whatever it shows ({@link StanagShow#pick}): the video with the OSD drawn in while this PC pilots or observes the
 * drone, an overwatch alert's picture, the event's clip, or the page with the logo and the mode.
 * <p>
 * Latency first: its own thread at the lowest priority does all of it - a second decoder (software, one thread), the
 * OSD, the encoder. The video's receive thread only puts a frame on a queue ({@link #onFrame}); when this thread is
 * behind, pictures are left out of the STANAG stream, never of the screen. What to show is set from the Swing thread
 * (volatile fields); the thread looks at them at each step.
 */
final class StanagFeed implements VideoPipeline.FrameTap, AutoCloseable {
    static final int W = 1280, H = 720;
    /** A still picture (the page, a video that stands still) goes out this often. */
    static final long STILL_MS = 200;
    /** Key frames: a player that joins waits this long at most for its first picture. */
    static final long PAGE_KEY_MS = 4000, VIDEO_KEY_MS = 2000;
    /** No new picture of the video for this long: the page. Until then the last one, with the OSD as it is now (LINK LOST). */
    static final long VIDEO_GONE_MS = 10_000;
    /** As {@link OsdRecorder}: about 3 s of frames waiting, then left out up to a key frame; behind: decoded, not drawn. */
    static final int QUEUE = 90, BEHIND = 6;
    static final int PAGE_FPS = 5, ALERT_FPS = 8, CLIP_FPS = 15, TILE_FPS = 10, PILOT_FPS = 30;
    static final int PAGE_BPS = 200_000, ALERT_BPS = 800_000;

    /** The OSD over this drone's video, read on the feed's thread at each picture. */
    interface Osd {
        /** The figures now, or null for no OSD. */
        OsdRenderer.Data data();

        OsdLayout layout();

        boolean flipX();

        boolean flipY();

        double displayMs();
    }

    /**
     * This PC has the drone's video (the feed is the pipeline's tap).
     * @param tile an OBSERVATION tile's (fewer pictures a second, an observer's OSD) rather than the piloted drone's
     * @param klv the metadata for a picture stamped at that time, or null: the drone's place alone
     */
    record Live(Osd osd, boolean tile, LongFunction<Klv.Frame> klv) {}

    /** An overwatch alert's picture as the alert card has it now. */
    record Alert(BufferedImage picture, float[][] boxes, String bar) {}

    /** An event's clip on this PC's disk: its parts as they are there when asked, in order. */
    record Clip(long id, Supplier<List<Path>> parts, String bar) {}

    private record Config(boolean hevc, byte[] codecConfig) {}

    private record Frame(byte[] data, boolean keyFrame) {}

    private static final Object STOP = new Object();
    private static final long NONE = Long.MIN_VALUE;

    private final String name;
    private final boolean virtual;
    private final int bitrate;
    private final BooleanSupplier upsideDown;
    private final LongConsumer clipEnded;
    private final Consumer<String> log;
    private final StanagStream stream;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE);
    private final Thread thread;
    private volatile boolean dropping, closed;

    private volatile StanagShow.Page page = new StanagShow.Page("", "", StanagShow.Tone.PLAIN);
    private volatile Live live;
    private volatile Alert alert;
    private volatile Clip clip;
    private volatile StanagKlv.Place place = StanagKlv.Place.UNKNOWN;
    private volatile String mission = "", country = "NOR";
    private volatile StanagShow.Kind showing = StanagShow.Kind.PAGE;
    private volatile long sentVideo, sentAlert, sentClip, sentPage, leftOut;
    /** Tests: a shorter wait before a video that stands still gives way to the page. */
    volatile long videoGoneMs = VIDEO_GONE_MS;

    /**
     * @param target "host:port" (or, for the tests, a .ts file)
     * @param bitrate the video's, bits a second (a tile's video gets half)
     * @param upsideDown whether this phone's pictures are shown turned on this PC (an alert's picture, a clip)
     * @param clipEnded told (on the feed's thread) when the clip with that id played to its end, or could not be played
     */
    StanagFeed(String name, boolean virtual, String target, int bitrate, BooleanSupplier upsideDown, LongConsumer clipEnded,
               Consumer<String> log) {
        this.name = name;
        this.virtual = virtual;
        this.bitrate = Math.max(300_000, bitrate);
        this.upsideDown = upsideDown;
        this.clipEnded = clipEnded;
        this.log = log;
        // a stream that simply goes is not said (the owner says once that the streams are on - a line per drone was
        // a screenful); its trouble is, and that it goes again
        stream = new StanagStream(name, target, this::metadata, line -> {
            if (line.contains(": sending to ") && !line.endsWith(" again.")) return;
            log.accept(line);
        }, StanagStream.KLV_EVERY_MS);
        thread = new Thread(this::run, "stanag-" + name);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // ------------------------------------------------------------------ what to show (any thread)

    void setPage(StanagShow.Page p) {
        if (p != null) page = p;
    }

    /** The drone's video comes to this feed from now (the caller makes it the pipeline's tap), or - null - no more. */
    void setLive(Live l) {
        live = l;
    }

    Live live() {
        return live;
    }

    void setAlert(Alert a) {
        alert = a;
    }

    /** The clip to play (the same id again: it plays on), or null for none - one that plays stops at once. */
    void setClip(Clip c) {
        clip = c;
    }

    void setPlace(StanagKlv.Place p) {
        place = p == null ? StanagKlv.Place.UNKNOWN : p;
    }

    void setIds(String mission, String country) {
        this.mission = mission == null ? "" : mission;
        this.country = country == null ? "" : country;
    }

    String name() {
        return name;
    }

    String url() {
        return stream.url();
    }

    /** What went out last. */
    StanagShow.Kind showing() {
        return showing;
    }

    long sent(StanagShow.Kind kind) {
        return switch (kind) {
            case VIDEO -> sentVideo;
            case ALERT -> sentAlert;
            case CLIP -> sentClip;
            case PAGE -> sentPage;
        };
    }

    /** Pictures of the video left out of this stream because its thread was behind. */
    long leftOut() {
        return leftOut;
    }

    // ------------------------------------------------------------------ the video's receive thread (must not wait)

    @Override
    public void onConfig(int width, int height, boolean hevc, byte[] codecConfig) {
        Config c = new Config(hevc, codecConfig == null ? null : codecConfig.clone());
        if (!queue.offer(c)) { // far behind: what waits belongs to the stream before
            queue.clear();
            queue.offer(c);
        }
        dropping = false;
    }

    @Override
    public void onFrame(byte[] data, boolean keyFrame, long arrivedMs) {
        if (dropping) {
            if (!keyFrame) {
                leftOut++;
                return;
            }
            dropping = false;
        }
        if (!queue.offer(new Frame(data, keyFrame))) {
            dropping = true;
            leftOut++;
        }
    }

    // ------------------------------------------------------------------ the metadata (the stream's thread)

    private volatile long slowKlvAt = Long.MIN_VALUE / 2;

    /** Ten a second with the video (the stream's own pace); once a second beside a picture that says nothing new. */
    private Klv.Frame metadata(long ms) {
        Live l = live;
        if (showing == StanagShow.Kind.VIDEO && l != null && l.klv() != null) {
            Klv.Frame f = l.klv().apply(ms);
            if (f != null) return f;
        }
        if (showing != StanagShow.Kind.VIDEO) {
            if (ms - slowKlvAt < 1000 && ms >= slowKlvAt) return null;
            slowKlvAt = ms;
        }
        return StanagKlv.frame(ms, name, place, mission, country);
    }

    // ------------------------------------------------------------------ its own thread

    private H264Encoder enc;
    private int encFps, encBps;
    private boolean forceKey = true;
    private long lastKeyAt, lastSentAt;
    private StanagShow.Kind lastKind;
    /** The page whose picture is in the encoder (it may send it again without converting), or null. */
    private Object inEncoder;

    private VideoDecoder decoder;
    private boolean needKey, hasPicture, newPicture;
    private long lastPictureAt;
    private final OsdFrame frame = new OsdFrame();

    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    /** The page the canvas holds, or null: an alert's picture or a clip's was drawn on it since. */
    private StanagShow.Page painted;
    private Alert alertShown;

    private ClipPlayer player;
    private long clipId = NONE, clipDone = NONE, clipBase, pending = NONE;
    private int clipPart;
    private BufferedImage clipPicture;
    private int[] clipAt;

    private boolean failSaid;

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private void run() {
        frame.size(W, H);
        try {
            while (!closed) {
                Object item;
                try {
                    long wait = waitMs(MonoClock.ms());
                    item = wait <= 0 ? queue.poll() : queue.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (item == STOP) break;
                long now = MonoClock.ms();
                try {
                    if (item instanceof Config c) config(c);
                    else if (item instanceof Frame f) decode(f, now);
                    step(now);
                    failSaid = false;
                } catch (IOException | RuntimeException e) {
                    // this stream only; again from a clean encoder at the next step
                    if (!failSaid) log.accept("STANAG 4609 " + name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    failSaid = true;
                    closeEncoder();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        } finally {
            closeClip();
            closeDecoder();
            closeEncoder();
        }
    }

    /** How long to wait for a frame of the video before the next picture is due anyway. */
    private long waitMs(long now) {
        if (player != null) {
            if (pending == NONE) return 0;
            return Math.max(0, Math.min(STILL_MS, clipBase + pending - now));
        }
        long every = showing == StanagShow.Kind.ALERT ? 1000 / ALERT_FPS : STILL_MS;
        return Math.max(1, Math.min(STILL_MS, lastSentAt + every - now));
    }

    private void config(Config c) {
        closeDecoder();
        try {
            decoder = VideoDecoder.oneThread(c.hevc(), c.codecConfig());
            needKey = true;
        } catch (RuntimeException e) {
            log.accept("STANAG 4609 " + name + ": the video can't be decoded for the stream (" + e.getMessage() + ").");
        }
    }

    private void decode(Frame f, long now) {
        if (live == null || decoder == null) return;
        if (needKey) {
            if (!f.keyFrame()) return;
            needKey = false;
        }
        if (!decoder.decode(f.data(), f.data().length)) return;
        if (decoder.width() <= 0 || decoder.height() <= 0) return;
        hasPicture = true;
        newPicture = true;
        lastPictureAt = now;
    }

    private void step(long now) throws IOException {
        Live l = live;
        Alert a = alert;
        Clip c = clip;
        if (l == null) { // the video is no longer this feed's: nothing of it shown again
            if (decoder != null) closeDecoder();
            lastPictureAt = 0;
        }
        boolean videoFresh = l != null && lastPictureAt != 0 && now - lastPictureAt < videoGoneMs;
        StanagShow.Kind kind = StanagShow.pick(videoFresh, a != null && a.picture() != null, c != null && c.id() != clipDone);
        if (kind != StanagShow.Kind.CLIP && player != null) closeClip();
        if (c == null) {
            clipDone = NONE;
            clipId = NONE;
        }
        switch (kind) {
            case VIDEO -> video(l, now);
            case ALERT -> {
                if (now - lastSentAt >= (a != alertShown ? 1000 / ALERT_FPS - 5 : STILL_MS)) {
                    alertShown = a;
                    painted = null; // the canvas is the alert's now: the page after it is painted again, the same one too
                    StanagPagePainter.picture(canvas, a.picture(), a.boxes(), upsideDown.getAsBoolean(), a.bar(), StanagShow.Tone.ALARM);
                    send(kind, ALERT_FPS, ALERT_BPS, VIDEO_KEY_MS, pixels(canvas), null, now);
                }
            }
            case CLIP -> clip(c, now);
            case PAGE -> {
                if (now - lastSentAt >= STILL_MS - 5) {
                    StanagShow.Page p = page;
                    if (!p.equals(painted)) {
                        StanagPagePainter.page(canvas, name, virtual, p);
                        painted = p;
                        inEncoder = null;
                    }
                    send(kind, PAGE_FPS, PAGE_BPS, PAGE_KEY_MS, pixels(canvas), p, now);
                }
            }
        }
        newPicture = false;
    }

    private void video(Live l, long now) throws IOException {
        int fps = l.tile() ? TILE_FPS : PILOT_FPS;
        if (newPicture) {
            if (queue.size() > BEHIND) { // behind: this picture is left out, the next ones keep up
                leftOut++;
                return;
            }
            if (now - lastSentAt < 1000 / fps - 8) return; // a tile's stream: ten a second
        } else if (now - lastSentAt < STILL_MS - 5) {
            return;
        }
        if (decoder != null && hasPicture) {
            Osd o = l.osd();
            frame.renderer().setObserver(l.tile()); // nothing flies from the grid: no sticks, no arming warnings
            if (o == null) frame.draw(decoder, false, false, null, null, 0);
            else frame.draw(decoder, o.flipX(), o.flipY(), o.data(), o.layout(), o.displayMs());
        } // else: between two streams (a new size): the last picture drawn, as it is
        send(StanagShow.Kind.VIDEO, fps, l.tile() ? bitrate / 2 : bitrate, VIDEO_KEY_MS, frame.pixels(), null, now);
    }

    /** The clip's next picture when it is due, at its own pace; its end told to the owner. */
    private void clip(Clip c, long now) throws IOException {
        if (clipId != c.id()) {
            closeClip();
            clipId = c.id();
            clipPart = 0;
            if (!openPart(c, now)) {
                clipOver(c);
                return;
            }
        }
        for (int skipped = 0; player != null && skipped < 4; ) {
            if (pending == NONE) {
                pending = player.next(pixels(clipPicture), clipAt[2], clipAt[3]);
                if (pending < 0) { // this part is over: the next, when it is here
                    pending = NONE;
                    player.close();
                    player = null;
                    clipPart++;
                    if (!openPart(c, now)) clipOver(c);
                    return;
                }
            }
            long due = clipBase + pending;
            if (now < due) return;
            if (now - due > 1000) clipBase = now - pending; // far behind: it plays on from here, no race to catch up
            if (now - lastSentAt >= 1000 / CLIP_FPS - 5) {
                painted = null; // as the alert's picture: the page after the clip is painted again
                Graphics2D g = canvas.createGraphics();
                try {
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, W, H);
                    boolean flip = upsideDown.getAsBoolean();
                    CameraTurn.draw(g, clipPicture, clipAt[0], clipAt[1], clipAt[2], clipAt[3], flip, flip);
                } finally {
                    g.dispose();
                }
                StanagPagePainter.bar(canvas, c.bar(), StanagShow.Tone.WARN);
                send(StanagShow.Kind.CLIP, CLIP_FPS, bitrate, VIDEO_KEY_MS, pixels(canvas), null, now);
                pending = NONE;
                return;
            }
            pending = NONE; // too soon after the last one sent: decoded, not shown
            skipped++;
        }
    }

    private boolean openPart(Clip c, long now) {
        List<Path> parts;
        try {
            parts = c.parts().get();
        } catch (RuntimeException e) {
            parts = List.of();
        }
        if (parts == null || clipPart >= parts.size()) return false;
        try {
            player = new ClipPlayer(parts.get(clipPart));
            clipAt = StanagPagePainter.underBar(player.width(), player.height(), W, H);
            clipPicture = new BufferedImage(clipAt[2], clipAt[3], BufferedImage.TYPE_INT_RGB);
            clipBase = now;
            pending = NONE;
            return true;
        } catch (IOException | RuntimeException e) {
            log.accept("STANAG 4609 " + name + ": clip not played (" + e.getMessage() + ").");
            player = null;
            return false;
        }
    }

    private void clipOver(Clip c) {
        clipDone = c.id();
        closeClip();
        try {
            clipEnded.accept(c.id());
        } catch (RuntimeException ignored) {
            // the owner's trouble: the stream goes on
        }
    }

    private void closeClip() {
        if (player != null) player.close();
        player = null;
        clipPicture = null;
        pending = NONE;
        clipId = NONE;
    }

    /**
     * One picture out: through an encoder made for this kind's pace and bitrate (a change of kind swaps it - the
     * stream goes on, see StanagStream), a key frame when one is due or the kind changed.
     * @param still the page whose picture this is (the same one again costs no conversion), or null
     */
    private void send(StanagShow.Kind kind, int fps, int bps, long keyEveryMs, int[] pixels, Object still, long now) throws IOException {
        if (enc == null || encFps != fps || encBps != bps) {
            closeEncoder();
            enc = new H264Encoder(W, H, fps, bps, fps * 3600); // key frames when asked here, never by its own count
            encFps = fps;
            encBps = bps;
            stream.onConfig(W, H, false, enc.config());
            forceKey = true;
        }
        if (kind != lastKind) forceKey = true;
        boolean key = forceKey || now - lastKeyAt >= keyEveryMs;
        List<H264Encoder.Packet> out = still != null && still == inEncoder ? enc.encodeAgain(key) : enc.encode(pixels, key);
        inEncoder = still;
        for (H264Encoder.Packet p : out) {
            stream.onFrame(p.data(), p.keyFrame(), now);
            if (p.keyFrame()) {
                lastKeyAt = now;
                forceKey = false;
            }
        }
        lastSentAt = now;
        lastKind = kind;
        showing = kind;
        switch (kind) {
            case VIDEO -> sentVideo++;
            case ALERT -> sentAlert++;
            case CLIP -> sentClip++;
            case PAGE -> sentPage++;
        }
    }

    private void closeEncoder() {
        if (enc != null) enc.close();
        enc = null;
        inEncoder = null;
    }

    private void closeDecoder() {
        if (decoder != null) decoder.close();
        decoder = null;
        hasPicture = false;
        newPicture = false;
    }

    /** Ends the stream; waits for its thread (up to 2 s) so the encoder and the socket are closed. */
    @Override
    public void close() {
        closed = true;
        queue.clear();
        queue.offer(STOP);
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stream.close();
    }
}
