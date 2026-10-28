package de.droiddrone.pc.video;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Glass-to-glass latency test. The PC paints a QR code holding the time it was painted; the drone's camera films the
 * screen; when the code comes back in the video, the time that frame reaches the screen minus the time in the code is
 * the whole delay a pilot sees: this PC's display, camera, phone encoder, 4G, decode and display again.
 * (The code goes through the PC display once on the way out, just like any real-world scene goes through it once on
 * the way back, so the result is the true scene-to-screen delay.)
 * <p>
 * Frames are read on a worker thread from a half-size copy taken only while the worker is idle, so the video path
 * barely notices. Readings are only as fine as the code's repaint interval (about one screen refresh).
 */
public final class LatencyMeter implements AutoCloseable {
    static final String PREFIX = "DDPC:";
    private static final int MAX_SAMPLES = 60;
    private static final double MAX_PLAUSIBLE_MS = 3000;

    public record Result(int count, double medianMs, double minMs, double maxMs) {}

    private final long originNs = System.nanoTime();
    private final Object lock = new Object();
    private final ArrayDeque<Double> samples = new ArrayDeque<>();
    private final Thread worker;
    private int[] pending;
    private int pendingWidth;
    private int pendingHeight;
    private long pendingShownNs;
    private boolean busy;
    private volatile boolean running = true;
    private volatile Result result = new Result(0, Double.NaN, Double.NaN, Double.NaN);

    public LatencyMeter() {
        worker = new Thread(this::work, "latencyMeter");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** The code to paint now: a QR matrix without quiet zone (paint a white border of 4 modules around it). */
    public BitMatrix codeNow() {
        String text = PREFIX + (System.nanoTime() - originNs) / 100_000; // 0.1 ms units
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L));
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A video frame was handed to the screen. Copies it (every 2nd pixel of large frames) when the worker is free,
     * otherwise returns at once. Call from the decode thread.
     * @param shownNs when the frame is expected on screen (System.nanoTime)
     */
    public void offer(int[] pixels, int width, int height, long shownNs) {
        synchronized (lock) {
            if (busy || !running) return;
            busy = true;
        }
        int step = width > 1000 ? 2 : 1;
        int w = width / step;
        int h = height / step;
        int[] copy = new int[w * h];
        for (int y = 0; y < h; y++) {
            int src = y * step * width;
            int dst = y * w;
            for (int x = 0; x < w; x++) copy[dst + x] = pixels[src + x * step];
        }
        synchronized (lock) {
            pending = copy;
            pendingWidth = w;
            pendingHeight = h;
            pendingShownNs = shownNs;
            lock.notifyAll();
        }
    }

    private void work() {
        while (running) {
            int[] frame;
            int w;
            int h;
            long shownNs;
            synchronized (lock) {
                while (pending == null && running) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) return;
                frame = pending;
                w = pendingWidth;
                h = pendingHeight;
                shownNs = pendingShownNs;
                pending = null;
            }
            double ms = read(frame, w, h, shownNs);
            if (!Double.isNaN(ms)) add(ms);
            synchronized (lock) {
                busy = false;
            }
        }
    }

    /** Looks for a code in one frame. @return the delay in ms, or NaN if no (plausible) code was found */
    double read(int[] pixels, int width, int height, long shownNs) {
        String text;
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
            text = new QRCodeReader().decode(bitmap).getText();
        } catch (ReaderException | RuntimeException e) {
            return Double.NaN;
        }
        if (text == null || !text.startsWith(PREFIX)) return Double.NaN;
        long paintedTenthMs;
        try {
            paintedTenthMs = Long.parseLong(text.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        double ms = (shownNs - originNs) / 1e6 - paintedTenthMs / 10.0;
        return ms > 0 && ms < MAX_PLAUSIBLE_MS ? ms : Double.NaN; // codes from an earlier test run are ignored
    }

    private synchronized void add(double ms) {
        samples.addLast(ms);
        if (samples.size() > MAX_SAMPLES) samples.removeFirst();
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        result = new Result(sorted.length, sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1]);
    }

    /** Readings so far (the last 60). */
    public Result result() {
        return result;
    }

    @Override
    public void close() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** For tests: the paint time a code stands for, so a test can build frames with known delays. */
    long originNs() {
        return originNs;
    }

    @Override
    public String toString() {
        Result r = result;
        return r.count() == 0 ? "no readings" : String.format(java.util.Locale.ROOT,
                "median %.0f ms (min %.0f, max %.0f, %d readings)", r.medianMs(), r.minMs(), r.maxMs(), r.count());
    }
}
