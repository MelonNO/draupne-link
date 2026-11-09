package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** When the warning beep sounds: the window asks 30 times a second with the warning shown then. */
class AlertSoundTest {
    /** A sound whose beeps are counted, played at once on the caller's thread. */
    private static AlertSound counted(AtomicInteger beeps) {
        AlertSound s = new AlertSound();
        s.beeper = beeps::incrementAndGet;
        s.beepOn = Runnable::run;
        return s;
    }

    @Test
    void aWarningBeepsAtOnceThenEveryThreeSeconds() {
        AtomicInteger beeps = new AtomicInteger();
        AlertSound s = counted(beeps);
        for (long t = 10_000; t < 10_000 + 7_000; t += 33) s.update("LINK LOST", t);
        assertEquals(3, beeps.get(), "at 0, 3 and 6 s");
        s.update(null, 20_000);
        s.update("LINK LOST", 20_100); // back again after a quiet spell: at once
        assertEquals(4, beeps.get());
    }

    /** A link flapping between LINK STALL and nothing every 100 ms for 10 s: every return was "new", a beep each. */
    @Test
    void aFlappingWarningBeepsAtMostEveryGap() {
        AtomicInteger beeps = new AtomicInteger();
        AlertSound s = counted(beeps);
        long t = 50_000;
        for (int i = 0; i < 300; i++, t += 33) s.update((i / 3) % 2 == 0 ? "LINK STALL" : null, t);
        int most = (int) (300 * 33 / AlertSound.MIN_GAP_MS) + 1;
        assertTrue(beeps.get() <= most, beeps.get() + " beeps in 10 s of flapping, at most " + most + " (it was 50)");
        assertTrue(beeps.get() >= 5, "it still beeps while the trouble lasts: " + beeps.get());
    }

    /**
     * The real player, beeps as long as real ones: flapping for 1.5 s queued a beep at each return, and they played on
     * for seconds after the link was back (the phone's sound lowered all that time). Now none waits behind another.
     */
    @Test
    void noBeepIsQueuedBehindOneStillPlaying() throws Exception {
        AtomicInteger beeps = new AtomicInteger();
        AlertSound s = new AlertSound();
        s.beeper = () -> {
            beeps.incrementAndGet();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        long start = System.currentTimeMillis();
        for (int i = 0; System.currentTimeMillis() - start < 1500; i++) {
            s.update((i / 3) % 2 == 0 ? "LINK STALL" : null, System.currentTimeMillis());
            Thread.sleep(33);
        }
        s.update(null, System.currentTimeMillis()); // the link is back
        Thread.sleep(300);
        int afterBack = beeps.get();
        Thread.sleep(700);
        assertEquals(afterBack, beeps.get(), "nothing goes on beeping once the warning is gone");
        assertTrue(afterBack <= 3, afterBack + " beeps for 1.5 s of flapping");
    }

    @Test
    void aWorseWarningJustAfterAnotherIsHeardSoonNotThreeSecondsLater() {
        AtomicInteger beeps = new AtomicInteger();
        AlertSound s = counted(beeps);
        s.update("LINK STALL", 1000);
        for (long t = 1300; t <= 1000 + AlertSound.MIN_GAP_MS + 100; t += 33) s.update("LINK LOST", t);
        assertEquals(2, beeps.get(), "LINK LOST beeped as soon as the gap allowed");
    }

    @Test
    void offMeansSilent() {
        AtomicInteger beeps = new AtomicInteger();
        AlertSound s = counted(beeps);
        s.setEnabled(false);
        for (long t = 0; t < 5000; t += 33) s.update("LINK LOST", t);
        assertEquals(0, beeps.get());
    }
}
