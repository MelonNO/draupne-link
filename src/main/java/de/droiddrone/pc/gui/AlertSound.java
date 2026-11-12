package de.droiddrone.pc.gui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static de.droiddrone.common.Log.log;

/**
 * A warning you hear: a short falling two-tone beep when something that stops the sticks starts (LINK LOST, LINK
 * STALL, FAILSAFE, NO FLIGHT CONTROLLER), again every REPEAT_MS while it lasts. The pilot's eyes are on the picture;
 * the ears catch it even when the OSD is off. Played on its own thread, so nothing on the stick or video path waits.
 */
final class AlertSound {
    static final String KEY = "alertSounds";
    private static final long REPEAT_MS = 3000;
    private static final int RATE = 44100;

    private final ExecutorService player = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alertSound");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean enabled = true;
    private String current; // the warning sounding now, or null
    private long lastPlayed;
    /** A new warning came while a beep was too recent or still playing: it beeps once it may. */
    private boolean owed;
    /** A beep is playing (1.0.45: the phone's live sound is lowered meanwhile, so the warning is heard). */
    private static volatile boolean playing;

    /** A warning beep is playing now. */
    static boolean isPlaying() {
        return playing;
    }

    void setEnabled(boolean on) {
        enabled = on;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Two beeps start at least this far apart, a new warning or not. */
    static final long MIN_GAP_MS = 700;
    /** The beep, and where it plays (tests count instead, at once). */
    Runnable beeper = AlertSound::beep;
    java.util.concurrent.Executor beepOn = player;
    /** A beep waits or plays: another isn't queued behind it. */
    private final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Called from the window's refresh (Swing thread) with the warning shown now, or null. A new warning beeps at once,
     * one that stays every REPEAT_MS - but never closer than MIN_GAP_MS, and never queued behind one not yet played: a
     * link flapping between LINK STALL and nothing (weak 5G) made each return "new", the beeps piled up behind each other
     * and went on, and the phone's sound stayed lowered, long after the link was back.
     */
    void update(String warning, long now) {
        if (warning == null) {
            current = null;
            owed = false;
            return;
        }
        boolean fresh = !warning.equals(current);
        current = warning;
        if (!enabled) return;
        boolean due = fresh || owed || now - lastPlayed >= REPEAT_MS;
        if (!due) return;
        // a new warning held back by the gap (LINK LOST just after LINK STALL) beeps as soon as the gap allows
        if (now - lastPlayed < MIN_GAP_MS || !pending.compareAndSet(false, true)) {
            owed = true;
            return;
        }
        owed = false;
        lastPlayed = now;
        Runnable b = beeper;
        beepOn.execute(() -> {
            try {
                b.run();
            } finally {
                pending.set(false);
            }
        });
    }

    /** 1.0.64: the overwatch ring's own setting (LINK > Overwatch ring, on unless turned off). */
    static final String RING_KEY = "overwatchRing";
    private volatile boolean ringEnabled = true;
    /** The ring, and where it plays (tests count instead, at once). */
    Runnable ringer = AlertSound::playRing;
    java.util.concurrent.Executor ringOn = player;

    void setRingEnabled(boolean on) {
        ringEnabled = on;
    }

    boolean isRingEnabled() {
        return ringEnabled;
    }

    /**
     * An overwatch alert (1.0.47): three rising notes, twice - unlike the falling warning, so it isn't taken for one
     * about the drone in the air. Once per alert; the caller leaves it out while this PC flies. 1.0.64: its own
     * setting - Warning sounds off silenced the overwatch alerts too.
     */
    void ring() {
        if (!ringEnabled) return;
        ringOn.execute(ringer);
    }

    private static void playRing() {
        playing = true;
        try (SourceDataLine line = AudioSystem.getSourceDataLine(new AudioFormat(RATE, 16, 1, true, false))) {
            line.open();
            line.start();
            for (int round = 0; round < 2; round++) {
                for (double hz : new double[]{660, 880, 1100}) {
                    byte[] pcm = tone(hz, 110);
                    line.write(pcm, 0, pcm.length);
                }
                byte[] gap = new byte[RATE / 5 * 2];
                line.write(gap, 0, gap.length);
            }
            line.drain();
        } catch (Exception | LinkageError e) {
            log("Overwatch sound: " + e);
        } finally {
            playing = false;
        }
    }

    /** 880 Hz then 660 Hz, 120 ms each: short enough not to mask the motors, unlike a Windows sound. */
    private static void beep() {
        playing = true;
        try (SourceDataLine line = AudioSystem.getSourceDataLine(new AudioFormat(RATE, 16, 1, true, false))) {
            line.open();
            line.start();
            byte[] pcm = tone(880, 120);
            line.write(pcm, 0, pcm.length);
            pcm = tone(660, 120);
            line.write(pcm, 0, pcm.length);
            line.drain();
        } catch (Exception | LinkageError e) {
            log("Alert sound: " + e); // no audio device: the OSD still shows it
        } finally {
            playing = false;
        }
    }

    /** A sine tone with 5 ms fades, 16-bit little-endian mono. */
    static byte[] tone(double hz, int ms) {
        int n = RATE * ms / 1000, fade = RATE * 5 / 1000;
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double envelope = Math.min(1, Math.min(i, n - 1 - i) / (double) fade);
            short v = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * 0.35 * envelope * Short.MAX_VALUE);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        return pcm;
    }
}
