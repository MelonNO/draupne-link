/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

import de.droiddrone.common.MediaCommon;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.36: an overwatch event in full quality on the phone - an H.264 encoder of its own on a surface in the camera's
 * session (the camera's size, several Mbit/s), and the microphone in AAC when the PC asked for sound, into one MP4.
 * The live preview to the PC is the stream encoder's, at 1 Mbit/s; this is the clip that follows it once the motion
 * has ended. Upstream's Mp4Recorder records only on the pilot's command and names no file; this one is the event's.
 * <p>
 * The muxer starts once the encoders have said their formats; both tracks' times count from their own first sample
 * (the camera's clock and the microphone's differ), so the sound sits within a frame or two of the picture.
 * <p>
 * pc.38 (the review's L3): the event is written in segments - {@link #cut} closes the file at the next key frame and
 * goes on in a new one, the encoders and the camera's session untouched (no gap, no new surface). An MP4 is unplayable
 * until it is closed, so the app dying mid-event now loses at most the segment being written.
 */
final class OverwatchRecorder {
    /** A segment closed by {@link #cut}: its file (null when it couldn't be closed - deleted), start and length. */
    static final class Segment {
        final File file;
        final long startedAt, durationMs;

        Segment(File file, long startedAt, long durationMs) {
            this.file = file;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
        }
    }

    private final int width, height, fps, bitrate;
    private final AudioSource audioSource;
    private MediaCodec video, audio;
    private Surface surface;
    private MediaMuxer muxer;
    private File file;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxing, wantAudio;
    private volatile boolean running;
    private long videoFirstUs = -1, audioFirstUs = -1, audioSamples;
    private volatile long startedAt, stoppedAt;
    private Thread audioThread;
    private final Object lock = new Object();
    // ---- pc.38: segments (under lock)
    private MediaFormat audioFormat;
    private File nextFile;
    private final java.util.ArrayDeque<Segment> finished = new java.util.ArrayDeque<>();

    OverwatchRecorder(int width, int height, int fps, AudioSource audioSource) {
        this.width = width & ~1;
        this.height = height & ~1;
        this.fps = Math.max(10, Math.min(60, fps));
        // about 0.1 bit a pixel at the frame rate: 720p30 ~2.8, 1080p30 ~6.2 Mbit/s, within 3 to 12
        this.bitrate = (int) Math.max(3_000_000, Math.min(12_000_000, (long) this.width * this.height * this.fps / 10));
        this.audioSource = audioSource;
    }

    /** The encoder's input surface for the camera's session, or null (no H.264 encoder takes this size). */
    Surface prepare() {
        try {
            String name = MediaCommon.getCodecName(MediaCommon.avcCodecMime, true);
            if (name == null) return null;
            video = MediaCodec.createByCodecName(name);
            MediaFormat f = MediaFormat.createVideoFormat(MediaCommon.avcCodecMime, width, height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = video.createInputSurface();
            video.start(); // running before the camera's session has its surface: no frame waits on a codec not started
            running = true;
            Thread t = new Thread(this::drainVideo, "overwatchRecorder");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            t.start();
            return surface;
        } catch (Exception e) {
            log("Overwatch recorder: no encoder for " + width + "x" + height + ": " + e);
            release();
            return null;
        }
    }

    private MediaFormat videoFormat;

    private void requestKeyFrame() {
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            video.setParameters(b);
        } catch (Exception ignored) {
            // the next key frame comes within 2 s anyway
        }
    }

    /** Starts writing to the file (the encoder's frames before this aren't kept; it starts at a key frame). */
    boolean start(File file, boolean withSound) {
        if (video == null) return false;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            this.file = file;
            synchronized (lock) {
                muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                if (videoFormat != null) videoTrack = muxer.addTrack(videoFormat);
            }
            wantAudio = withSound && startAudio();
            startedAt = System.currentTimeMillis();
            requestKeyFrame();
            log("Overwatch recording: " + width + "x" + height + " at " + bitrate / 1000 + " kbit/s" + (wantAudio ? " with sound" : "") + " to " + file.getName());
            return true;
        } catch (Exception e) {
            log("Overwatch recording didn't start: " + e);
            running = false;
            release();
            return false;
        }
    }

    private boolean startAudio() {
        try {
            if (!audioSource.initialize(MediaCommon.mp4AudioConsumerId)) return false;
            String name = MediaCommon.getCodecName(MediaCommon.audioCodecMime, true);
            if (name == null) return false;
            audio = MediaCodec.createByCodecName(name);
            MediaFormat f = MediaFormat.createAudioFormat(MediaCommon.audioCodecMime, 48000, 1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(4096, audioSource.getBufferSize() * 2));
            audio.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.start();
            audioThread = new Thread(this::runAudio, "overwatchSound");
            audioThread.setDaemon(true);
            audioThread.start();
            return true;
        } catch (Exception e) {
            log("Overwatch recording: no sound (" + e + ")");
            if (audio != null) audio.release();
            audio = null;
            audioSource.stop(MediaCommon.mp4AudioConsumerId);
            return false;
        }
    }

    /** The microphone's PCM into the AAC encoder, its output into the file. */
    private void runAudio() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                byte[] pcm = audioSource.getBufferData(MediaCommon.mp4AudioConsumerId);
                if (pcm != null) {
                    int in = audio.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = audio.getInputBuffer(in);
                        b.clear();
                        int n = Math.min(b.remaining(), pcm.length);
                        b.put(pcm, 0, n);
                        long us = audioSamples * 1_000_000L / 48000; // counted, not the clock: no jumps
                        audioSamples += n / 2;
                        audio.queueInputBuffer(in, 0, n, us, 0);
                    }
                } else {
                    Thread.sleep(5);
                }
                int out;
                while ((out = audio.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized (lock) {
                            audioFormat = audio.getOutputFormat(); // pc.38: each segment's file gets the track too
                            if (muxer != null) audioTrack = muxer.addTrack(audioFormat);
                            startMuxer();
                        }
                    } else if (out >= 0) {
                        write(audio.getOutputBuffer(out), info, false);
                        audio.releaseOutputBuffer(out, false);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("Overwatch recording's sound stopped: " + e);
                return;
            }
        }
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int out = video.dequeueOutputBuffer(info, 100_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) {
                        videoFormat = video.getOutputFormat();
                        if (muxer != null && videoTrack < 0) {
                            videoTrack = muxer.addTrack(videoFormat);
                            startMuxer();
                        }
                    }
                } else if (out >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) write(video.getOutputBuffer(out), info, true);
                    video.releaseOutputBuffer(out, false);
                }
            } catch (Exception e) {
                if (running) log("Overwatch recording's picture stopped: " + e);
                return;
            }
        }
    }

    /** The muxer starts when every track it will have is known; samples before that are dropped (a few ms). */
    private void startMuxer() {
        if (muxing || muxer == null || videoTrack < 0 || wantAudio && audioTrack < 0) return;
        muxer.start();
        muxing = true;
    }

    private void write(ByteBuffer data, MediaCodec.BufferInfo info, boolean isVideo) {
        synchronized (lock) {
            if (!muxing || info.size <= 0) return;
            if (isVideo && nextFile != null && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && videoFirstUs >= 0) {
                rollOver(); // pc.38: the new segment starts with this key frame
                if (!muxing) return;
            }
            long us = info.presentationTimeUs;
            if (isVideo) {
                if (videoFirstUs < 0 && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return; // the file starts at a key frame
                if (videoFirstUs < 0) videoFirstUs = us;
                us -= videoFirstUs;
            } else {
                if (audioFirstUs < 0) audioFirstUs = us;
                us -= audioFirstUs;
            }
            if (us < 0) return;
            MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
            copy.set(info.offset, info.size, us, info.flags);
            muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, copy);
        }
    }

    /**
     * pc.38: the segment ends at the next key frame (asked for now) and the recording goes on in next. The closed one
     * comes from {@link #pollFinished}. @return false when nothing is being written yet (no cut)
     */
    boolean cut(File next) {
        synchronized (lock) {
            if (!muxing) return false;
            nextFile = next;
        }
        requestKeyFrame();
        return true;
    }

    /** Whether the file is being written (the muxer started). */
    boolean writing() {
        synchronized (lock) {
            return muxing;
        }
    }

    /** The next segment closed by a cut, oldest first, or null. */
    Segment pollFinished() {
        synchronized (lock) {
            return finished.poll();
        }
    }

    /** Under lock, on a key frame: the file closed (it is whole now), a new one started with the same tracks. */
    private void rollOver() {
        long now = System.currentTimeMillis();
        File done = file;
        boolean ok = true;
        try {
            muxer.stop();
        } catch (Exception e) {
            log("Overwatch recording: a segment didn't close cleanly: " + e);
            ok = false;
        }
        try {
            muxer.release();
        } catch (Exception ignored) {
        }
        muxer = null;
        muxing = false;
        if (!ok && done != null && !done.delete()) log("Overwatch recording: a broken segment stays: " + done.getName());
        finished.add(new Segment(ok ? done : null, startedAt, now - startedAt));
        file = nextFile;
        nextFile = null;
        startedAt = now;
        videoFirstUs = -1;
        audioFirstUs = -1;
        try {
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrack = muxer.addTrack(videoFormat);
            audioTrack = wantAudio && audioFormat != null ? muxer.addTrack(audioFormat) : -1;
            if (wantAudio && audioTrack < 0) wantAudio = false; // no sound format: the picture goes on alone
            muxer.start();
            muxing = true;
        } catch (Exception e) {
            log("Overwatch recording: the next segment didn't start: " + e);
            try {
                if (muxer != null) muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
    }

    /** Ends the file. @return it when it holds a picture, else null (deleted) */
    File stop() {
        if (!running) {
            release();
            return null;
        }
        running = false;
        stoppedAt = System.currentTimeMillis();
        try {
            if (audioThread != null) audioThread.join(500);
        } catch (InterruptedException ignored) {
        }
        boolean ok;
        synchronized (lock) {
            ok = muxing;
            try {
                if (muxing) muxer.stop();
            } catch (Exception e) {
                log("Overwatch recording: the file didn't close cleanly: " + e);
                ok = false;
            }
            muxing = false;
        }
        release();
        if (!ok && file != null) {
            if (!file.delete()) log("Overwatch recording: an empty file stays: " + file.getName());
            return null;
        }
        return file;
    }

    /** The segment being written: its length so far (or until stop). */
    long durationMs() {
        return Math.max(0, (stoppedAt > 0 ? stoppedAt : System.currentTimeMillis()) - startedAt);
    }

    /** pc.38: when the segment being written started (wall ms). */
    long segmentStartedAt() {
        synchronized (lock) {
            return startedAt;
        }
    }

    boolean hasSound() {
        return wantAudio;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    private void release() {
        try {
            if (video != null) {
                try {
                    video.stop();
                } catch (Exception ignored) {
                }
                video.release();
            }
        } catch (Exception ignored) {
        }
        video = null;
        try {
            if (audio != null) {
                try {
                    audio.stop();
                } catch (Exception ignored) {
                }
                audio.release();
                audioSource.stop(MediaCommon.mp4AudioConsumerId);
            }
        } catch (Exception ignored) {
        }
        audio = null;
        if (surface != null) surface.release();
        surface = null;
        try {
            if (muxer != null) muxer.release();
        } catch (Exception ignored) {
        }
        muxer = null;
    }
}
