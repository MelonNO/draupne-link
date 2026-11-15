package de.droiddrone.pc.video;

import java.io.IOException;
import java.util.List;

/**
 * Pictures (0xRRGGBB) in, H.264 out (1.0.68): {@link H264Encoder} in this process, or {@link EncoderProcess}'s, which
 * runs it in a process of its own - a crash in the native encoder there can't take the app down.
 */
public interface PictureEncoder extends AutoCloseable {
    /** Makes encoders: {@code width x height}, at {@code fps}, {@code bitRate} bits a second, a key frame every {@code gop}. */
    interface Factory {
        PictureEncoder open(int width, int height, int fps, int bitRate, int gop) throws IOException;
    }

    /** In this process, as up to 1.0.67. */
    Factory IN_PROCESS = H264Encoder::new;

    /** The codec config (Annex B SPS/PPS). */
    byte[] config();

    /** One picture (width x height pixels); a key frame when asked. */
    List<H264Encoder.Packet> encode(int[] pixels, boolean keyFrame) throws IOException;

    /** The last picture once more, no conversion. */
    List<H264Encoder.Packet> encodeAgain(boolean keyFrame) throws IOException;

    @Override
    void close();
}
