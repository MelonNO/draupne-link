package de.droiddrone.pc.audio;

import java.util.Arrays;

/**
 * The phone's audio stream as its AudioInitialFrame describes it (upstream Flight Udp.sendAudioInitialFrame):
 * {@code [12][num i16][sampleRate i32][channels u8][Android PCM encoding u8][AAC AudioSpecificConfig]} - AAC-LC in raw
 * frames (no ADTS header), 1024 samples each. DRAUPNE Flight pc.34 sends 48 kHz mono.
 */
public record AudioConfig(int sampleRate, int channels, byte[] asc) {
    /** AAC-LC frames are 1024 samples. */
    public static final int SAMPLES_PER_FRAME = 1024;
    /**
     * What a pc.34 phone sends: 48 kHz, one channel, AudioSpecificConfig 0x11 0x88 (object type 2 = LC, frequency
     * index 3 = 48 kHz, one channel). A recording that starts before the phone's own config has come declares this.
     */
    public static final AudioConfig DEFAULT = new AudioConfig(48_000, 1, new byte[]{0x11, (byte) 0x88});

    /** The payload after the packet's type and number; null when it doesn't make sense. */
    public static AudioConfig parse(byte[] d, int offset, int length) {
        if (length < 7) return null;
        int rate = ((d[offset] & 0xff) << 24) | ((d[offset + 1] & 0xff) << 16) | ((d[offset + 2] & 0xff) << 8) | (d[offset + 3] & 0xff);
        int channels = d[offset + 4] & 0xff;
        if (rate < 8000 || rate > 96_000 || channels < 1 || channels > 2) return null;
        return new AudioConfig(rate, channels, Arrays.copyOfRange(d, offset + 6, offset + length));
    }

    /** How long one frame plays, ms. */
    public double frameMs() {
        return SAMPLES_PER_FRAME * 1000.0 / sampleRate;
    }

    /** The same stream: rate, channels and codec config (a record's equals compares the array by reference). */
    public boolean sameAs(AudioConfig o) {
        return o != null && o.sampleRate == sampleRate && o.channels == channels && Arrays.equals(o.asc, asc);
    }
}
