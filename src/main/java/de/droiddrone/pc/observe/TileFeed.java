package de.droiddrone.pc.observe;

import de.droiddrone.pc.audio.ObservationAudio;
import de.droiddrone.pc.video.VideoPipeline;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Consumer;

/** One tile's stream (1.0.48): its observer link and its own video pipeline (one decoder thread, normal priority). */
public final class TileFeed implements ObservationSession.Feed {
    private final ObserverLink link;
    private final VideoPipeline video;
    private final ObserverLink.Quality sharp;

    /**
     * @param sink  the tile's picture
     * @param sharp the selected tile's picture (the PC's NORMAL: its camera size, 30 fps, its bitrate limit)
     */
    public TileFeed(String phone, InetAddress ip, int port, String key, VideoPipeline.FrameSink sink, ObservationAudio audio,
                    ObserverLink.Quality sharp, Consumer<String> events) throws IOException {
        this.sharp = sharp;
        ObserverLink[] ref = new ObserverLink[1];
        video = new VideoPipeline(sink, () -> -1);
        video.observe(() -> {
            ObserverLink l = ref[0];
            if (l != null) l.askVideoConfig();
        }, () -> {
            ObserverLink l = ref[0];
            if (l != null) l.askVideoConfig();
        });
        int audioId = audio == null ? -1 : audio.addSource();
        link = new ObserverLink(phone, ip, port, key, video, audio, audioId, events);
        ref[0] = link;
        try {
            link.start();
        } catch (IOException | RuntimeException e) {
            video.close();
            if (audio != null) audio.removeSource(audioId);
            throw e;
        }
        this.audioId = audioId;
    }

    private final int audioId;

    public int audioId() {
        return audioId;
    }

    public ObserverLink link() {
        return link;
    }

    public VideoPipeline video() {
        return video;
    }

    @Override
    public String phone() {
        return link.phone();
    }

    @Override
    public void setSharp(boolean on) {
        link.setQuality(on ? sharp : ObserverLink.Quality.GRID);
    }

    @Override
    public void stopStream() {
        link.close();
    }

    @Override
    public void release() {
        video.close();
    }
}
