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

    void setEnabled(boolean on) {
        enabled = on;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Called from the window's refresh (Swing thread) with the warning shown now, or null. */
    void update(String warning, long now) {
        if (warning == null) {
            current = null;
            return;
        }
        boolean fresh = !warning.equals(current);
        current = warning;
        if (!enabled || (!fresh && now - lastPlayed < REPEAT_MS)) return;
        lastPlayed = now;
        player.execute(AlertSound::beep);
    }

    /** 880 Hz then 660 Hz, 120 ms each: short enough not to mask the motors, unlike a Windows sound. */
    private static void beep() {
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
