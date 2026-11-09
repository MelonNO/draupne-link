/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

/**
 * "This phone hears something" for OBSERVATION's speaking highlight (1.0.48), like a video call's coloured frame: the
 * level of each decoded frame against the phone's own background (a floor that follows the quietest frames at once
 * and creeps up slowly, so a steady wind or engine hum is not "speaking"). Sound needs {@link #ATTACK_FRAMES} frames in
 * a row over the floor to start the highlight, and it holds {@link #HOLD_MS} after the last such frame - a word's
 * pauses don't make it flicker. Not thread-safe: the audio thread alone.
 * <p>
 * pc.45: in Common, compiled into both apps - the phone runs it on its own microphone for an observer that muted it
 * (Observe.STATE_SOUND_HEARD), the PC on the sound it hears. Java 11: the phone app's language level.
 */
public final class VoiceActivity {
    /** Over the background by this much, dB. */
    static final double OVER_FLOOR_DB = 9;
    /** And at least this loud, dBFS: a silent room's hiss stays dark. */
    static final double MIN_DB = -50;
    /** The background rises at most this fast, dB a second (it falls at once). */
    static final double FLOOR_RISE_DB_PER_S = 3;
    static final int ATTACK_FRAMES = 2;
    public static final long HOLD_MS = 600;
    public static final double SILENCE_DB = -120;

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

    /** pc.45: the level of 16-bit little-endian PCM (the phone's microphone; channels averaged alike), dBFS. */
    public static double levelDbPcm16(byte[] pcm, int length) {
        int n = pcm == null ? 0 : Math.min(length, pcm.length) / 2;
        if (n <= 0) return SILENCE_DB;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / n);
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
