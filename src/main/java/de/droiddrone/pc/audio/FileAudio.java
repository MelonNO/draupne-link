package de.droiddrone.pc.audio;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.PointerPointer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * PREVIOUS FLIGHTS' sound: a recording's AAC track (1.0.45: the phone's microphone, in the MKV beside the picture)
 * played from a place in it on the default sound device. Its own thread at the lowest priority, its own reader of the
 * file - never the live audio's or the video's. {@link #play} starts at a time; {@link #stop} ends it (seek = stop +
 * play). A file without sound, or a PC without a sound device, plays nothing.
 */
public final class FileAudio implements AutoCloseable {
    private final Path file;
    private volatile Thread thread;

    public FileAudio(Path file) {
        this.file = file;
    }

    /** Plays from ms (the file's own clock) until stop() or the end. */
    public synchronized void play(long fromMs) {
        stop();
        Thread t = new Thread(() -> run(fromMs), "flightAudio");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(long fromMs) {
        AVFormatContext fmt = new AVFormatContext(null);
        AVPacket pkt = av_packet_alloc();
        AacDecoder decoder = null;
        SourceDataLine line = null;
        boolean opened = false;
        try {
            if (avformat_open_input(fmt, file.toString(), null, (AVDictionary) null) < 0) return;
            opened = true;
            if (avformat_find_stream_info(fmt, (PointerPointer<?>) null) < 0) return;
            int stream = -1;
            for (int i = 0; i < fmt.nb_streams(); i++) {
                AVCodecParameters par = fmt.streams(i).codecpar();
                if (par.codec_type() == AVMEDIA_TYPE_AUDIO && par.codec_id() == AV_CODEC_ID_AAC) {
                    stream = i;
                    break;
                }
            }
            if (stream < 0) return; // a recording without sound
            AVStream st = fmt.streams(stream);
            AVCodecParameters par = st.codecpar();
            byte[] asc = new byte[Math.max(0, par.extradata_size())];
            if (asc.length > 0) par.extradata().get(asc);
            int rate = par.sample_rate() > 0 ? par.sample_rate() : 48_000;
            int channels = Math.max(1, Math.min(2, par.ch_layout().nb_channels()));
            decoder = new AacDecoder(new AudioConfig(rate, channels, asc.length > 0 ? asc : AudioConfig.DEFAULT.asc()));
            AVRational tb = st.time_base();
            long start = st.start_time() == AV_NOPTS_VALUE ? 0 : av_rescale_q(st.start_time(), tb, av_make_q(1, 1000));
            if (fromMs > 0) av_seek_frame(fmt, stream, av_rescale_q(fromMs + start, av_make_q(1, 1000), tb), AVSEEK_FLAG_BACKWARD);
            while (thread == Thread.currentThread() && av_read_frame(fmt, pkt) >= 0) {
                try {
                    if (pkt.stream_index() != stream || pkt.size() <= 0) continue;
                    long ts = pkt.pts() != AV_NOPTS_VALUE ? pkt.pts() : pkt.dts();
                    long ms = ts == AV_NOPTS_VALUE ? 0 : av_rescale_q(ts, tb, av_make_q(1, 1000)) - start;
                    byte[] data = new byte[pkt.size()];
                    pkt.data().get(data);
                    float[] samples = decoder.decode(data);
                    if (samples == null || ms < fromMs - 30) continue; // the frames before the place asked for: decoded unheard
                    int ch = decoder.channels();
                    if (line == null) {
                        AudioFormat format = new AudioFormat(rate, 16, ch, true, false);
                        line = AudioSystem.getSourceDataLine(format);
                        line.open(format, rate / 5 * ch * 2); // 200 ms
                        line.start();
                    }
                    byte[] pcm = LiveAudio.toPcm16(samples, ch, 1f, 1f);
                    line.write(pcm, 0, pcm.length); // blocks while the card's buffer is full: this thread's pace
                } finally {
                    av_packet_unref(pkt);
                }
            }
            if (line != null && thread == Thread.currentThread()) line.drain();
        } catch (Exception | LinkageError ignored) {
            // no sound device, a broken file: the picture plays on without
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (decoder != null) decoder.close();
            av_packet_free(pkt);
            if (opened) avformat_close_input(fmt);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
