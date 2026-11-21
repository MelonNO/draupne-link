package de.droiddrone.bench;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.spi.MixerProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bench's loudspeaker: a Java Sound device that makes no noise on this PC and keeps count of what was played -
 * so warning sounds, the overwatch ring and the drones' microphones can run for hours beside the pilot, and a test
 * can say "the alert sounded". Only there when a bench task asks for it ({@code -Dbench.silentSound=true} with
 * {@code -Djavax.sound.sampled.SourceDataLine=de.droiddrone.bench.SilentSound}); in every other JVM this provider
 * offers nothing and Java uses the PC's sound card as always. A line plays in real time: write() waits as a sound
 * card's does when its buffer is full.
 */
public final class SilentSound extends MixerProvider {
    /** One sound line the app opened and what went through it. */
    public static final class Played {
        public final AudioFormat format;
        public final String thread;
        public final long openedMs = System.currentTimeMillis();
        public volatile long bytes;
        public volatile int peak;
        public volatile long firstLoudMs;

        Played(AudioFormat format) {
            this.format = format;
            this.thread = Thread.currentThread().getName();
        }

        public double seconds() {
            return bytes / (double) (format.getFrameSize() * format.getFrameRate());
        }

        /** Something that isn't silence went through it. */
        public boolean audible() {
            return peak > 300;
        }

        @Override
        public String toString() {
            return String.format("%.1f s at %.0f Hz x%d, peak %d [%s]", seconds(), format.getSampleRate(), format.getChannels(), peak, thread);
        }
    }

    private static final List<Played> PLAYED = new CopyOnWriteArrayList<>();
    private static final Mixer.Info INFO = new Mixer.Info("Bench silent loudspeaker", "DRAUPNE test bench", "Plays nothing, counts everything", "1") {
    };
    private static final SilentMixer MIXER = new SilentMixer();

    public static List<Played> played() {
        return List.copyOf(PLAYED);
    }

    public static void forget() {
        PLAYED.clear();
    }

    static boolean on() {
        return Boolean.getBoolean("bench.silentSound");
    }

    @Override
    public Mixer.Info[] getMixerInfo() {
        return on() ? new Mixer.Info[]{INFO} : new Mixer.Info[0];
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (on() && info == INFO) return MIXER;
        throw new IllegalArgumentException("Not a bench mixer: " + info);
    }

    private static final class SilentMixer implements Mixer {
        private final Line.Info lineInfo = new Line.Info(SourceDataLine.class);

        @Override
        public Mixer.Info getMixerInfo() {
            return INFO;
        }

        @Override
        public Line.Info[] getSourceLineInfo() {
            return new Line.Info[]{lineInfo};
        }

        @Override
        public Line.Info[] getTargetLineInfo() {
            return new Line.Info[0];
        }

        @Override
        public Line.Info[] getSourceLineInfo(Line.Info info) {
            return isLineSupported(info) ? new Line.Info[]{info} : new Line.Info[0];
        }

        @Override
        public Line.Info[] getTargetLineInfo(Line.Info info) {
            return new Line.Info[0];
        }

        @Override
        public boolean isLineSupported(Line.Info info) {
            return SourceDataLine.class.isAssignableFrom(info.getLineClass()) && info.getLineClass().isAssignableFrom(SilentLine.class);
        }

        @Override
        public Line getLine(Line.Info info) throws LineUnavailableException {
            if (!isLineSupported(info)) throw new LineUnavailableException("The bench plays to source data lines only: " + info);
            AudioFormat format = null;
            if (info instanceof DataLine.Info d && d.getFormats().length > 0) format = d.getFormats()[0];
            return new SilentLine(format);
        }

        @Override
        public int getMaxLines(Line.Info info) {
            return javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        }

        @Override
        public Line[] getSourceLines() {
            return new Line[0];
        }

        @Override
        public Line[] getTargetLines() {
            return new Line[0];
        }

        @Override
        public void synchronize(Line[] lines, boolean maintainSync) {
            throw new IllegalArgumentException("no synchronization");
        }

        @Override
        public void unsynchronize(Line[] lines) {
        }

        @Override
        public boolean isSynchronizationSupported(Line[] lines, boolean maintainSync) {
            return false;
        }

        @Override
        public Line.Info getLineInfo() {
            return new Line.Info(Mixer.class);
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }

        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("no controls");
        }

        @Override
        public void addLineListener(LineListener listener) {
        }

        @Override
        public void removeLineListener(LineListener listener) {
        }
    }

    /** A line that swallows what is written at the speed a sound card would play it. */
    private static final class SilentLine implements SourceDataLine {
        private AudioFormat format;
        private int bufferBytes;
        private volatile boolean open, running;
        private Played played;
        /** Bytes written, and the time (ns) at which the "card" will have played all of them. */
        private long written;
        private long playedUntilNs;

        SilentLine(AudioFormat format) {
            this.format = format;
        }

        @Override
        public void open(AudioFormat f, int bufferSize) {
            format = f;
            bufferBytes = Math.max(f.getFrameSize(), bufferSize);
            played = new Played(f);
            PLAYED.add(played);
            open = true;
            playedUntilNs = System.nanoTime();
        }

        @Override
        public void open(AudioFormat f) {
            open(f, (int) (f.getFrameSize() * f.getFrameRate() / 2)); // half a second, a sound card's usual default
        }

        @Override
        public void open() throws LineUnavailableException {
            if (format == null) throw new LineUnavailableException("no format");
            open(format);
        }

        private double bytesPerNs() {
            return format.getFrameSize() * format.getFrameRate() / 1e9;
        }

        /** Bytes written and not yet "played". */
        private synchronized int queued() {
            long ns = playedUntilNs - System.nanoTime();
            return ns <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.round(ns * bytesPerNs()));
        }

        @Override
        public int write(byte[] b, int off, int len) {
            if (!open) return 0;
            note(b, off, len);
            int done = 0;
            while (done < len && open) {
                int room = bufferBytes - queued();
                if (room <= 0) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                int n = Math.min(room, len - done);
                synchronized (this) {
                    long now = System.nanoTime();
                    if (playedUntilNs < now) playedUntilNs = now;
                    playedUntilNs += Math.round(n / bytesPerNs());
                    written += n;
                }
                done += n;
            }
            return done;
        }

        private void note(byte[] b, int off, int len) {
            Played p = played;
            if (p == null) return;
            p.bytes += len;
            if (format.getSampleSizeInBits() == 16 && format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
                int peak = p.peak;
                boolean big = format.isBigEndian();
                for (int i = off; i + 1 < off + len; i += 2) {
                    int s = big ? (short) ((b[i] << 8) | (b[i + 1] & 0xFF)) : (short) ((b[i + 1] << 8) | (b[i] & 0xFF));
                    if (s < 0) s = -s;
                    if (s > peak) peak = s;
                }
                if (peak > 300 && p.firstLoudMs == 0) p.firstLoudMs = System.currentTimeMillis();
                p.peak = peak;
            }
        }

        @Override
        public void drain() {
            while (open && queued() > 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public synchronized void flush() {
            playedUntilNs = System.nanoTime();
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running && queued() > 0;
        }

        @Override
        public boolean isActive() {
            return running;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public int getBufferSize() {
            return bufferBytes;
        }

        @Override
        public int available() {
            return Math.max(0, bufferBytes - queued());
        }

        @Override
        public int getFramePosition() {
            return (int) getLongFramePosition();
        }

        @Override
        public synchronized long getLongFramePosition() {
            return format == null ? 0 : (written - queued()) / format.getFrameSize();
        }

        @Override
        public long getMicrosecondPosition() {
            return format == null ? 0 : (long) (getLongFramePosition() / format.getFrameRate() * 1_000_000);
        }

        @Override
        public float getLevel() {
            return javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        }

        @Override
        public Line.Info getLineInfo() {
            return new DataLine.Info(SourceDataLine.class, format);
        }

        @Override
        public void close() {
            open = false;
            running = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }

        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("The bench's line has no controls: " + control);
        }

        @Override
        public void addLineListener(LineListener listener) {
        }

        @Override
        public void removeLineListener(LineListener listener) {
        }
    }
}
