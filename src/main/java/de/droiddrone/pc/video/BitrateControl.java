package de.droiddrone.pc.video;

/**
 * Decides when to ask the phone's encoder for a lower or higher bitrate (port of upstream Control
 * Udp.processBitRateChange). Lower as soon as frames go missing, arrive late or the ping climbs - on 4G a too-high
 * bitrate builds queues, and queues are latency. Raise again after 10 s without trouble. Decreases are 2 s apart.
 */
public class BitrateControl {
    public interface Sink {
        void changeBitRate(boolean increase);
    }

    private static final int PAUSE_MS = 2000;
    private static final int FRAME_GAP_MS = 150;
    private static final int KEY_FRAME_GAP_MS = 1500;
    private static final int MAX_PING_MS = 300;
    private static final int INCREASE_AFTER_MS = 10_000;

    private final Sink sink;
    private int badFrames;
    private long lastFrame;
    private long lastKeyFrame;
    private long lastChange;
    private long lastTrouble;
    private long windowStart;
    private boolean started;

    public BitrateControl(Sink sink) {
        this.sink = sink;
    }

    /** A frame was lost or arrived damaged (key frames weigh 5, as upstream). */
    public void onBadFrame(boolean keyFrame) {
        badFrames += keyFrame ? 5 : 1;
    }

    /**
     * A frame was received.
     * @param fps   current camera frame rate (allowed bad frames per second scale with it)
     * @param ping  round trip in ms, -1 if unknown
     */
    public void onFrame(boolean keyFrame, long now, int fps, int ping) {
        if (!started) {
            started = true;
            lastFrame = lastKeyFrame = lastChange = lastTrouble = windowStart = now;
        }
        if (now - lastChange > PAUSE_MS) {
            boolean trouble = badFrames > Math.round(Math.max(fps, 1) / 30f)
                    || now > lastFrame + FRAME_GAP_MS
                    || now > lastKeyFrame + KEY_FRAME_GAP_MS
                    || ping < 0 || ping > MAX_PING_MS;
            if (trouble) {
                badFrames = 0;
                lastTrouble = now;
                lastChange = now;
                sink.changeBitRate(false);
            } else if (now - lastTrouble >= INCREASE_AFTER_MS) {
                // no pause after raising: if the higher rate causes trouble, back off at once
                lastTrouble = now;
                sink.changeBitRate(true);
            }
        }
        lastFrame = now;
        if (keyFrame) lastKeyFrame = now;
        if (now - windowStart >= 1000) {
            badFrames = 0;
            windowStart = now;
        }
    }
}
