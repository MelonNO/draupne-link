package de.droiddrone.pc.audio;

/**
 * "This phone hears something" for OBSERVATION's speaking highlight (1.0.48), like a video call's coloured frame: the
 * level of each decoded frame against the phone's own background (a floor that follows the quietest frames at once
 * and creeps up slowly, so a steady wind or engine hum is not "speaking"). Sound needs {@link #ATTACK_FRAMES} frames in
 * a row over the floor to start the highlight, and it holds {@link #HOLD_MS} after the last such frame - a word's
 * pauses don't make it flicker. Not thread-safe: the audio thread alone.
 */
public final class VoiceActivity {
    /** Over the background by this much, dB. */
    static final double OVER_FLOOR_DB = 9;
    /** And at least this loud, dBFS: a silent room's hiss stays dark. */
    static final double MIN_DB = -50;
    /** The background rises at most this fast, dB a second (it falls at once). */
    static final double FLOOR_RISE_DB_PER_S = 3;
    static final int ATTACK_FRAMES = 2;
    static final long HOLD_MS = 600;
    static final double SILENCE_DB = -120;

    private double floorDb = Double.NaN;
    private int loudFrames;
    private long lastLoudAt = Long.MIN_VALUE;
    private boolean active;
    private double levelDb = SILENCE_DB;

    /** The level of one frame, dBFS (RMS of samples -1..1). */
    public static double levelDb(float[] samples, int from, int count) {
        if (samples == null || count <= 0) return SILENCE_DB;
        double sum = 0;
        for (int i = from; i < from + count; i++) sum += samples[i] * (double) samples[i];
        double rms = Math.sqrt(sum / count);
        return rms <= 1e-6 ? SILENCE_DB : Math.max(SILENCE_DB, 20 * Math.log10(rms));
    }

    /**
     * One frame heard.
     * @param db    its level, dBFS
     * @param ms    how long it plays
     * @param nowMs its time
     * @return speaking now
     */
    public boolean frame(double db, double ms, long nowMs) {
        levelDb = db;
        if (Double.isNaN(floorDb) || db < floorDb) floorDb = db;
        else floorDb = Math.min(db, floorDb + FLOOR_RISE_DB_PER_S * ms / 1000.0);
        boolean loud = db >= MIN_DB && db >= floorDb + OVER_FLOOR_DB;
        loudFrames = loud ? loudFrames + 1 : 0;
        if (loudFrames >= ATTACK_FRAMES) {
            lastLoudAt = nowMs;
            active = true;
        }
        return isActive(nowMs);
    }

    /** No sound comes (muted, a gap): the highlight still ends HOLD_MS after the last loud frame. */
    public boolean isActive(long nowMs) {
        if (active && nowMs - lastLoudAt > HOLD_MS) active = false;
        return active;
    }

    public double levelDb() {
        return levelDb;
    }

    public void reset() {
        floorDb = Double.NaN;
        loudFrames = 0;
        active = false;
        lastLoudAt = Long.MIN_VALUE;
        levelDb = SILENCE_DB;
    }
}
