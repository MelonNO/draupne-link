package de.droiddrone.pc.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioConfigTest {
    /** The AudioInitialFrame's payload as the Flight app writes it: rate i32, channels u8, encoding u8, the ASC. */
    @Test
    void readsThePhonesAudioInitialFrame() {
        byte[] packet = {12, 0, 7, 0, 0, (byte) 0xBB, (byte) 0x80, 1, 2, 0x11, (byte) 0x88};
        AudioConfig c = AudioConfig.parse(packet, 3, packet.length - 3);
        assertNotNull(c);
        assertEquals(48_000, c.sampleRate());
        assertEquals(1, c.channels());
        assertArrayEquals(new byte[]{0x11, (byte) 0x88}, c.asc());
        assertTrue(c.sameAs(AudioConfig.DEFAULT), "the pc.34 phone's stream");
        assertEquals(21.33, c.frameMs(), 0.01);
    }

    @Test
    void nonsenseIsRefused() {
        assertNull(AudioConfig.parse(new byte[]{0, 0, (byte) 0xBB, (byte) 0x80, 1}, 0, 5), "too short");
        assertNull(AudioConfig.parse(new byte[]{0, 0, 0, 0, 1, 2, 0x11}, 0, 7), "rate 0");
        assertNull(AudioConfig.parse(new byte[]{0, 0, (byte) 0xBB, (byte) 0x80, 6, 2, 0x11}, 0, 7), "six channels");
    }

    @Test
    void sameAsComparesTheCodecConfigItself() {
        AudioConfig a = new AudioConfig(48_000, 1, new byte[]{0x11, (byte) 0x88});
        assertTrue(a.sameAs(new AudioConfig(48_000, 1, new byte[]{0x11, (byte) 0x88})));
        assertFalse(a.sameAs(new AudioConfig(48_000, 2, new byte[]{0x11, (byte) 0x90})));
        assertFalse(a.sameAs(new AudioConfig(44_100, 1, new byte[]{0x11, (byte) 0x88})));
        assertFalse(a.sameAs(null));
    }

    /** The gain glides over a frame (a duck that starts doesn't click), and full scale is clipped, not wrapped. */
    @Test
    void pcmWithAGainRamp() {
        float[] s = {1f, 1f, 1f, 1f, 1f};
        byte[] pcm = LiveAudio.toPcm16(s, 1, 1f, 0f);
        assertEquals(10, pcm.length);
        assertEquals(32767, (short) ((pcm[0] & 0xff) | (pcm[1] << 8)), "starts at the old gain");
        assertEquals(0, (short) ((pcm[8] & 0xff) | (pcm[9] << 8)), "ends at the new one");
        short mid = (short) ((pcm[4] & 0xff) | (pcm[5] << 8));
        assertTrue(mid > 15_000 && mid < 18_000, "half way: " + mid);
        byte[] loud = LiveAudio.toPcm16(new float[]{2f, -2f}, 2, 1f, 1f);
        assertEquals(Short.MAX_VALUE, (short) ((loud[0] & 0xff) | (loud[1] << 8)));
        assertEquals(Short.MIN_VALUE, (short) ((loud[2] & 0xff) | (loud[3] << 8)), "stereo: both channels of a frame share the gain");
    }

    /** FFmpeg's own AAC decoder opens with the pc.34 phone's config (no sound device needed). */
    @Test
    void theAacDecoderOpens() {
        try (AacDecoder d = new AacDecoder(AudioConfig.DEFAULT)) {
            assertEquals(1, d.channels());
            assertNull(d.decode(new byte[]{0x01, 0x02}), "a broken frame gives nothing, not an exception");
        }
    }
}
