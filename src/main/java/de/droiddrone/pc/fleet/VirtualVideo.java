package de.droiddrone.pc.fleet;

import de.droiddrone.pc.video.H264Encoder;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static de.droiddrone.common.PacketIds.VIDEO_PARITY_GROUP;

/**
 * A virtual drone's camera and encoder (the phone's camera, StreamEncoder and its sending): pictures drawn at the
 * frame rate asked for, H.264 here, sent as the phone sends them - config, fragments, a parity packet per group. Its
 * own thread, a painter thread beside it; the drone's packet thread asks for key frames and configs. Out of
 * VirtualDrone.
 */
final class VirtualVideo {
    /** What the stream reads from its drone, each frame. */
    interface Drone {
        boolean running();

        String name();

        int cameraWidthNow();

        int cameraHeightNow();

        int cameraFpsNow();

        int bitRateNow();

        /** The profile's key frames sent twice. */
        boolean keyFramesTwice();

        /** The profile's parity per group of packets. */
        boolean groupParity();

        /** Observed, no pilot's session: each key frame carries the config again. */
        boolean observedOnly();

        void renderPicture(BufferedImage img, long nowMs);

        /** To the pilot's session, or to each observer. */
        void streamOut(byte[] data);
    }

    private static final int PARITY_GROUP = 8;

    private final Drone drone;
    private final int slot;
    private final Consumer<String> log;
    private volatile Thread videoThread;
    private volatile int videoGen;
    private volatile String videoState = "off";
    private volatile int sentBitRate;
    private volatile boolean keyFrameWanted, configResend;

    VirtualVideo(Drone drone, int slot, Consumer<String> log) {
        this.drone = drone;
        this.slot = slot;
        this.log = log;
    }

    /** The stream runs (started and not stopped). */
    boolean isOn() {
        return videoThread != null;
    }

    /** Its size, rate and bitrate, "off", or why it failed: the panel's readout. */
    String state() {
        return videoState;
    }

    /** What went out over the last second, bit/s. */
    int sentBitRate() {
        return sentBitRate;
    }

    /** A key frame next. */
    void requestKeyFrame() {
        keyFrameWanted = true;
    }

    /** The config again before the next frame. */
    void resendConfig() {
        configResend = true;
    }

    void start() {
        int gen = ++videoGen;
        Thread t = new Thread(() -> videoRun(gen), "fleetVideo" + slot);
        t.setDaemon(true);
        // a phone's camera has a CPU of its own: at the lowest priority on this PC the picture starved behind the PC's
        // decoding and recording (13 fps, frames 33/67/100 ms apart, in the pilot's recording)
        t.setPriority(Thread.NORM_PRIORITY);
        videoThread = t;
        t.start();
    }

    void stop() {
        videoGen++;
        videoThread = null;
        videoState = "off";
    }

    private void videoRun(int gen) {
        H264Encoder enc = null;
        int frameNum = 0;
        long next = System.nanoTime();
        // two pictures: the next one is drawn on its own thread while this one is encoded (1.0.52, the pilot: the
        // virtual picture's rate was too low - drawing and encoding a 1080p frame one after the other took ~32 ms of
        // the 33 a frame has at 30 fps; side by side a frame costs the longer of the two)
        BufferedImage[] img = new BufferedImage[2];
        int cur = 0;
        Future<?> ahead = null;
        ExecutorService painter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fleetVideoPaint " + drone.name());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        long lastReopen = 0;
        long bytesWindow = 0, windowStart = System.currentTimeMillis();
        try {
            while (gen == videoGen && drone.running()) {
                int w = drone.cameraWidthNow(), h = drone.cameraHeightNow(), fps = drone.cameraFpsNow(), bps = drone.bitRateNow();
                long nowMs = System.currentTimeMillis();
                boolean sizeChanged = enc == null || enc.width() != w || enc.height() != h || enc.fps() != fps;
                boolean rateChanged = enc != null && enc.bitRate() != bps && nowMs - lastReopen >= 1500;
                if (sizeChanged || rateChanged) {
                    if (ahead != null) waitFor(ahead); // drawn into a picture of the old size: dropped
                    ahead = null;
                    if (enc != null) enc.close();
                    enc = new H264Encoder(w, h, fps, bps);
                    lastReopen = nowMs;
                    if (sizeChanged) frameNum = 0; // a restarted stream: frame 1 next
                    if (img[0] == null || img[0].getWidth() != w || img[0].getHeight() != h) {
                        img[0] = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        img[1] = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    }
                    sendVideoConfig(enc);
                    keyFrameWanted = true;
                    videoState = w + "x" + h + " " + fps + " fps " + bps / 1000 + " kbit/s";
                }
                if (configResend) {
                    configResend = false;
                    sendVideoConfig(enc);
                }
                if (ahead != null) waitFor(ahead); // this frame's picture, drawn while the last one was encoded
                else drone.renderPicture(img[cur], nowMs);
                BufferedImage now = img[cur], following = img[1 - cur];
                // the next picture shows the world and the camera at one moment: when its painting starts (the vehicle
                // placed a frame ahead while the camera was read at the paint's own moment made them disagree by up to
                // a frame - the vehicle wobbled against the ground)
                ahead = painter.submit(() -> drone.renderPicture(following, System.currentTimeMillis()));
                boolean key = keyFrameWanted;
                keyFrameWanted = false;
                for (H264Encoder.Packet p : enc.encode(Scene.pixels(now), key)) {
                    if (gen != videoGen) break;
                    if (frameNum >= Short.MAX_VALUE) frameNum = 1;
                    frameNum++;
                    if (p.keyFrame() && drone.observedOnly()) sendVideoConfig(enc);
                    int copies = p.keyFrame() && drone.keyFramesTwice() ? 2 : 1;
                    for (int c = 0; c < copies; c++) {
                        sendFragments(p.keyFrame() ? UdpCommon.KeyFrame : UdpCommon.VideoFrame, p.data(), p.keyFrame(), frameNum, fps, gen);
                    }
                    bytesWindow += p.data().length;
                }
                cur = 1 - cur;
                if (nowMs - windowStart >= 1000) {
                    sentBitRate = (int) (bytesWindow * 8 * 1000 / Math.max(1, nowMs - windowStart));
                    bytesWindow = 0;
                    windowStart = nowMs;
                }
                next += 1_000_000_000L / fps;
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -500_000_000L) next = System.nanoTime(); // fell far behind: no catching up in a burst
            }
        } catch (IOException | RuntimeException e) {
            log.accept("video stopped: " + e.getMessage());
            videoState = "failed: " + e.getMessage();
        } finally {
            painter.shutdownNow();
            if (enc != null) enc.close();
        }
    }

    /** The picture drawn ahead, waited for; a failure there is the video thread's. */
    private static void waitFor(Future<?> f) {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof RuntimeException r ? r : new IllegalStateException(c);
        }
    }

    private void sendVideoConfig(H264Encoder enc) throws IOException {
        UdpPacketData p = new UdpPacketData(UdpCommon.VideoInitialFrame);
        p.daos.writeShort(enc.width());
        p.daos.writeShort(enc.height());
        p.daos.writeBoolean(false); // H.264: no H.265 encoder here (a phone without one falls back the same way)
        p.daos.writeBoolean(false); // the back camera
        p.daos.write(enc.config());
        drone.streamOut(p.getData());
    }

    /**
     * A frame's packets as the phone sends them ([type][frameNum i16][offset i32][size i32 on the first][data]), spread
     * over at most half a frame time (a key frame over up to three), with a parity packet per group of 8 when the
     * profile asks.
     */
    private void sendFragments(byte type, byte[] buf, boolean keyFrame, int frameNum, int fps, int gen) throws IOException {
        int size = buf.length;
        int packets = Math.max(1, (size + UdpCommon.packetLength - 1) / UdpCommon.packetLength);
        long frameNs = 1_000_000_000L / Math.max(1, fps);
        long gapNs = packets <= 2 ? 0 : (keyFrame ? 3 * frameNs : frameNs / 2) / packets;
        byte[] parity = drone.groupParity() ? new byte[UdpCommon.packetLength - 9] : null;
        int offset = 0, packet = 0;
        while (offset < size && gen == videoGen) {
            UdpPacketData p = new UdpPacketData(type);
            p.daos.writeShort(frameNum);
            p.daos.writeInt(offset);
            int header = 9;
            if (offset == 0) {
                p.daos.writeInt(size);
                header += 4;
            }
            int n = Math.min(UdpCommon.packetLength - header, size - offset);
            p.daos.write(buf, offset, n);
            if (parity != null) for (int i = 0; i < n; i++) parity[i] ^= buf[offset + i];
            offset += n;
            drone.streamOut(p.getData());
            packet++;
            if (parity != null && (packet % PARITY_GROUP == 0 || offset >= size)) {
                UdpPacketData q = new UdpPacketData(VIDEO_PARITY_GROUP);
                q.daos.writeShort(frameNum);
                q.daos.writeInt(size);
                q.daos.writeByte(keyFrame ? 1 : 0);
                q.daos.writeByte((packet - 1) / PARITY_GROUP);
                q.daos.write(parity, 0, parity.length);
                drone.streamOut(q.getData());
                Arrays.fill(parity, (byte) 0);
            }
            if (gapNs > 0 && offset < size) LockSupport.parkNanos(Math.min(gapNs, 5_000_000L));
        }
    }
}
