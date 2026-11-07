package de.droiddrone.pc.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OBSERVATION's mix and its speaking highlight (1.0.48): pure arithmetic, no sound device. */
class ObservationAudioTest {
    @Test
    void sourcesAddUpWithTheirOwnGain() {
        float[] mix = new float[4];
        AudioMixer.mixInto(mix, new float[]{0.2f, 0.2f, 0.2f, 0.2f}, 4, 1f, 1f);
        AudioMixer.mixInto(mix, new float[]{0.4f, 0.4f, 0.4f, 0.4f}, 4, 0.5f, 0.5f);
        for (float v : mix) assertEquals(0.4f, v, 1e-6, "0.2 + 0.4 x 0.5");
        AudioMixer.mixInto(mix, new float[]{1f, 1f}, 2, 0f, 0f);
        assertEquals(0.4f, mix[0], 1e-6, "muted: adds nothing");
    }

    @Test
    void theSumIsLimitedAndAShortSourceLeavesTheRest() {
        float[] mix = new float[4];
        AudioMixer.mixInto(mix, new float[]{0.8f, -0.8f, 0.8f, 0.8f}, 4, 1f, 1f);
        AudioMixer.mixInto(mix, new float[]{0.8f, -0.8f}, 2, 1f, 1f);
        assertEquals(1f, mix[0], 1e-6, "clipped, not wrapped");
        assertEquals(-1f, mix[1], 1e-6);
        assertEquals(0.8f, mix[2], 1e-6, "a source with fewer samples (its jitter buffer ran dry) adds silence");
    }

    @Test
    void aVolumeChangeGlidesOverThePeriod() {
        float[] mix = new float[5];
        AudioMixer.mixInto(mix, new float[]{1, 1, 1, 1, 1}, 5, 0f, 1f);
        assertEquals(0f, mix[0], 1e-6);
        assertEquals(0.5f, mix[2], 1e-6);
        assertEquals(1f, mix[4], 1e-6, "no click: from the old gain to the new");
    }

    @Test
    void monoAndResampling() {
        assertArrayEquals(new float[]{0.5f, 0f}, AudioMixer.toMono(new float[]{1f, 0f, 0.5f, -0.5f}, 2), 1e-6f);
        float[] in = {0, 1, 2, 3};
        assertSame(in, AudioMixer.resample(in, 48_000, 48_000));
        float[] up = AudioMixer.resample(in, 24_000, 48_000);
        assertEquals(8, up.length);
        assertEquals(0.5f, up[1], 1e-6);
        assertEquals(1f, up[2], 1e-6);
        assertEquals(2, AudioMixer.resample(in, 48_000, 24_000).length);
    }

    private static float[] tone(double amplitude, int n) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) (amplitude * Math.sin(i * 0.3));
        return s;
    }

    @Test
    void levelInDecibels() {
        assertEquals(VoiceActivity.SILENCE_DB, VoiceActivity.levelDb(new float[1024], 0, 1024));
        assertEquals(-3.0, VoiceActivity.levelDb(tone(1, 4800), 0, 4800), 0.1, "a full sine: -3 dBFS");
        assertEquals(-23.0, VoiceActivity.levelDb(tone(0.1, 4800), 0, 4800), 0.1);
    }

    @Test
    void speakingNeedsTwoLoudFramesAndHoldsAfterTheLast() {
        VoiceActivity v = new VoiceActivity();
        long t = 0;
        double frame = 21.3;
        for (int i = 0; i < 50; i++, t += 21) assertFalse(v.frame(-60, frame, t), "a quiet background");
        assertFalse(v.frame(-20, frame, t), "one loud frame (a click): not yet");
        t += 21;
        assertTrue(v.frame(-20, frame, t), "the second in a row: speaking");
        long lastLoud = t;
        t += 21;
        assertTrue(v.frame(-60, frame, t), "a pause between words: held");
        assertTrue(v.isActive(lastLoud + VoiceActivity.HOLD_MS), "still held at the hold time");
        assertFalse(v.isActive(lastLoud + VoiceActivity.HOLD_MS + 1), "then dark - no flicker in between");
    }

    @Test
    void aSteadyNoiseBecomesTheBackground() {
        VoiceActivity v = new VoiceActivity();
        long t = 0;
        for (int i = 0; i < 20; i++, t += 21) v.frame(-60, 21.3, t);
        boolean lit = false;
        for (int i = 0; i < 40; i++, t += 21) lit = v.frame(-35, 21.3, t);
        assertTrue(lit, "a new sound lights it");
        for (int i = 0; i < 400; i++, t += 21) v.frame(-35, 21.3, t); // eight seconds of the same hum (wind, a motor)
        assertFalse(v.frame(-35, 21.3, t + 21) && v.isActive(t + 21 + VoiceActivity.HOLD_MS + 1),
                "the same level for seconds is the background now");
        assertFalse(new VoiceActivity().frame(-55, 21.3, 0) || new VoiceActivity().frame(-55, 21.3, 21), "hiss under -50 dBFS never lights");
    }

    @Test
    void silenceIsNeverSpeech() {
        VoiceActivity v = new VoiceActivity();
        for (int i = 0; i < 10; i++) assertFalse(v.frame(-80, 21.3, i * 21L));
        v.reset();
        assertEquals(VoiceActivity.SILENCE_DB, v.levelDb());
    }
}
