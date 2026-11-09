package de.droiddrone.pc.video;

import de.droiddrone.pc.audio.AudioConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static de.droiddrone.common.Log.log;

/**
 * The recording side of a {@link VideoPipeline} (out of it in 1.0.54): frames go to their own thread through a queue,
 * so the receive thread never waits for the disk. The pipeline hands in each finished frame, each (re)started
 * stream's config and the HUD's figures; this writes the MKV (StreamRecorder) and feeds the {@link
 * VideoPipeline.RecordingTap}.
 * <p>
 * Locking: its own monitor guards which recording runs (start / stop / a failed recording's end) and is taken alone -
 * nothing else is locked under it but the key frame request (KeyFrameBackoff's, a leaf). Everything the pipeline
 * calls while it holds its own locks only offers to a queue and never waits.
 */
final class VideoRecording {
    private record RecFrame(byte[] data, boolean keyFrame, long arrivedMs, boolean streamStart) {}
    /** One frame of the live audio (1.0.45), of the stream config describes. */
    private record RecAudio(AudioConfig config, byte[] data, long arrivedMs) {}
    private static final Object REC_STOP = new Object();
    /** A (re)started stream's config came: the file ends, the next one is of this config. */
    private record RecNewFile(VideoPipeline.Config config) {}
    /** A restarted stream goes on under the config in hand (the same came again, or none came): no new file. */
    private static final Object REC_SAME_CONFIG = new Object();
    /** About 3 s of video and its sound (47 AAC frames a second beside 30 pictures). */
    static final int RECORD_QUEUE = 480;
    /**
     * A restarted stream's first frames wait for its config this long at most (by their arrival), then go into the
     * file in hand: the decoder doesn't wait longer either (CONFIG_WAIT_MS).
     */
    static final long RECORD_HOLD_MS = VideoPipeline.CONFIG_WAIT_MS + 1000;

    /**
     * One recording: its own queue and thread (1.0.49). Before, a new recording started while the last one still
     * wrote shared the queue with it, and both files broke; now the new thread waits for the last one to finish.
     */
    private static final class Recording {
        final Path dir;
        final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(RECORD_QUEUE);
        Thread thread;
        /** The recording before this one, if its thread was still writing: waited for first. */
        Thread before;
        /** Frames left out because the queue was full, since the last log line (receive thread). */
        int leftOut;
        long leftOutLoggedAt = Long.MIN_VALUE / 2;
        /** The tap was told of a file and not yet of its end (this recording's thread only). */
        boolean tapOpen;

        Recording(Path dir) {
            this.dir = dir;
        }
    }

    /** The stream's config in hand: a file whose own config hasn't come is of this one. */
    private final Supplier<VideoPipeline.Config> currentConfig;
    /** Asks the phone for a key frame (a file starts at one). */
    private final Runnable requestKeyFrame;
    /** The sound track a file gets before the stream's own config came: the pc.34 default when the phone can send, or null. */
    private final Supplier<AudioConfig> defaultAudio;

    /**
     * The live audio's config as last seen (1.0.45); a file started while it is known gets a sound track of it, and
     * one started before it came gets the pc.34 phone's (AudioConfig.DEFAULT) when the phone can send sound.
     */
    private volatile AudioConfig audioConfig;
    private volatile Recording recording; // null = not recording
    private volatile Path file;
    /** The last recording's thread (guarded by this): a new recording waits for it. */
    private Thread lastThread;
    private volatile VideoPipeline.RecordingTap tap;
    /** Told each new recording file as it is created (record thread), or null - the tour notes its own files by path. */
    private volatile Consumer<Path> fileListener;

    VideoRecording(Supplier<VideoPipeline.Config> currentConfig, Runnable requestKeyFrame, Supplier<AudioConfig> defaultAudio) {
        this.currentConfig = currentConfig;
        this.requestKeyFrame = requestKeyFrame;
        this.defaultAudio = defaultAudio;
    }

    void setFileListener(Consumer<Path> listener) {
        fileListener = listener;
    }

    void setTap(VideoPipeline.RecordingTap t) {
        tap = t;
    }

    /** Records into a new MKV file in dir from the next key frame (a new file if the stream restarts). */
    synchronized void start(Path dir) {
        if (recording != null) return;
        requestKeyFrame.run(); // a file starts at a key frame: one now rather than in up to 10 s
        Recording r = new Recording(dir);
        Thread last = lastThread;
        r.before = last != null && last.isAlive() ? last : null; // still finishing its file: this one waits for it
        r.thread = new Thread(() -> recordLoop(r), "videoRecord");
        r.thread.setDaemon(true);
        r.thread.setPriority(Thread.MIN_PRIORITY);
        lastThread = r.thread;
        recording = r;
        r.thread.start();
    }

    /** Finishes the file. Waits for it outside the lock: the receive thread must not stand still for the disk. */
    void stop() {
        Recording r;
        synchronized (this) {
            r = recording;
            if (r == null) return;
            recording = null;
            if (!r.queue.offer(REC_STOP)) { // full (a slow disk): what is in it is not worth the wait
                r.queue.clear();
                r.queue.offer(REC_STOP);
            }
        }
        try {
            r.thread.join(2000);
        } catch (InterruptedException ignored) {
        }
    }

    boolean isRecording() {
        return recording != null;
    }

    /** The file being written, or null (none yet: waiting for the stream / its first key frame). */
    Path file() {
        return file;
    }

    /** A finished frame (receive thread): into the queue; full, left out and logged once in a while. */
    void frame(FrameAssembler.Frame frame, long now) {
        Recording r = recording;
        if (r != null && !r.queue.offer(new RecFrame(frame.data(), frame.keyFrame(), now, frame.streamStart()))) {
            r.leftOut++; // logged once in a while: not a line per frame on this thread while the disk is slow
            if (now - r.leftOutLoggedAt >= 5000) {
                log("Recording: disk too slow, " + r.leftOut + " frame" + (r.leftOut == 1 ? "" : "s") + " dropped");
                r.leftOut = 0;
                r.leftOutLoggedAt = now;
            }
        }
    }

    /** A (re)started stream's config came: a file of its own. */
    void newStream(VideoPipeline.Config config) {
        record(new RecNewFile(config));
    }

    /** A restarted stream goes on under the config in hand: its held frames go into the file of it. */
    void sameConfig() {
        record(REC_SAME_CONFIG);
    }

    /** The HUD's figures for a frame shown (decode thread), for the CSV next to the recording. */
    void osd(StreamRecorder.OsdRow row) {
        record(row);
    }

    /** The live audio's stream config (link threads): files started from now on declare their sound track with it. */
    void audioConfig(AudioConfig config) {
        audioConfig = config;
    }

    /** One frame of the live audio (link threads): into the recording, if one runs. Never waits. */
    void audio(AudioConfig config, byte[] data, long arrivedMs) {
        record(new RecAudio(config, data, arrivedMs)); // full: the frames come first
    }

    /** Into the recording's queue, if one runs; full: left out (never waits). */
    private void record(Object item) {
        Recording r = recording;
        if (r != null) r.queue.offer(item);
    }

    /** The sound track a file starting now gets: the stream's, the pc.34 default before it came, or none. */
    private AudioConfig audioTrackForNewFile() {
        AudioConfig c = audioConfig;
        return c != null ? c : defaultAudio.get();
    }

    private void tap(Consumer<VideoPipeline.RecordingTap> call) {
        VideoPipeline.RecordingTap t = tap;
        if (t == null) return;
        try {
            call.accept(t);
        } catch (RuntimeException e) {
            log("Recording with OSD: " + e);
        }
    }

    private void tapEnded(Recording r) {
        if (!r.tapOpen) return;
        r.tapOpen = false;
        tap(VideoPipeline.RecordingTap::fileEnded);
    }

    /**
     * The record thread. A file starts at a key frame. A restarted stream (its key frame 1) ends the file in hand at
     * once, and its first frames are held until its config comes (1.0.49): a new file of that config starts with
     * them - or, when the config in hand stays (REC_SAME_CONFIG, or none came in RECORD_HOLD_MS), they go into a file
     * of it. Before, the new stream's key frame went into the old file (another config) and the new file waited for
     * the next key frame, 2-10 s.
     */
    private void recordLoop(Recording r) {
        StreamRecorder recorder = null;
        VideoPipeline.Config fileConfig = null; // the config the next file is of: the last RecNewFile's (null: the one in hand)
        boolean configCame = false; // a RecNewFile came since the file in hand started (or the recording did)
        List<RecFrame> held = null; // a restarted stream's first frames, waiting for its config
        boolean askedForKey = false;
        try {
            Thread before = r.before;
            r.before = null;
            if (before != null) before.join(); // the last recording finishes its file first; this queue holds ~3 s
            while (true) {
                Object item = r.queue.take();
                if (item == REC_STOP) break;
                if (item instanceof RecNewFile n) {
                    if (recorder != null) recorder.close();
                    recorder = null;
                    tapEnded(r);
                    fileConfig = n.config();
                    if (held != null) { // the restart's frames, held for this: the new file starts with them
                        for (RecFrame h : held) recorder = write(r, recorder, h, fileConfig);
                        held = null;
                    } else {
                        configCame = true; // the stream's key frame 1 is on its way; if a later frame comes first, one is asked for
                        askedForKey = false;
                    }
                    continue;
                }
                if (item == REC_SAME_CONFIG) {
                    if (held != null) for (RecFrame h : held) recorder = write(r, recorder, h, fileConfig);
                    held = null;
                    continue;
                }
                if (item instanceof StreamRecorder.OsdRow row) {
                    if (recorder != null) recorder.writeOsd(row);
                    continue;
                }
                if (item instanceof RecAudio a) {
                    long pts = recorder == null ? -1 : recorder.writeAudio(a.data(), a.config(), a.arrivedMs());
                    if (pts >= 0 && r.tapOpen) tap(t -> t.audio(a.data(), pts));
                    continue;
                }
                RecFrame f = (RecFrame) item;
                if (held != null) {
                    if (f.arrivedMs() - held.get(0).arrivedMs() <= RECORD_HOLD_MS && held.size() < RECORD_QUEUE) {
                        held.add(f);
                        continue;
                    }
                    for (RecFrame h : held) recorder = write(r, recorder, h, fileConfig); // no config came: as the decoder does
                    held = null;
                }
                if (f.streamStart() && f.keyFrame() && !configCame) { // a restart whose config hasn't come: not into this file
                    if (recorder != null) recorder.close();
                    recorder = null;
                    tapEnded(r);
                    held = new ArrayList<>();
                    held.add(f);
                    continue;
                }
                if (recorder == null && !f.keyFrame()) {
                    if (!askedForKey) requestKeyFrame.run(); // waiting for a key frame to start the file on
                    askedForKey = true;
                    continue;
                }
                boolean opening = recorder == null;
                recorder = write(r, recorder, f, fileConfig);
                if (opening && recorder != null) {
                    configCame = false;
                    askedForKey = false;
                }
            }
        } catch (InterruptedException ignored) {
        } catch (IOException | RuntimeException e) {
            log("Recording stopped: " + e.getMessage());
            synchronized (this) {
                if (recording == r) recording = null;
            }
        } finally {
            if (recorder != null) {
                try {
                    recorder.close();
                } catch (RuntimeException e) { // the tap and the file name are let go all the same
                    log("Recording: closing the file: " + e);
                }
            }
            tapEnded(r);
            file = null;
        }
    }

    /**
     * One frame into the file; at a key frame a file of config (null: the one in hand) is opened when none is.
     * @return the file (null: none yet - not a key frame, or no config)
     */
    private StreamRecorder write(Recording r, StreamRecorder recorder, RecFrame f, VideoPipeline.Config config) throws IOException {
        if (recorder == null) {
            VideoPipeline.Config c = config != null ? config : currentConfig.get();
            if (c == null || !f.keyFrame()) return null; // a file starts at a key frame
            Path path = newFile(r.dir);
            AudioConfig sound = audioTrackForNewFile();
            StreamRecorder opened = new StreamRecorder(path, c.hevc(), c.width(), c.height(), c.codecConfig(), sound);
            try {
                file = path;
                Consumer<Path> started = fileListener;
                if (started != null) started.accept(path);
                log("Recording to " + path + (sound == null ? "" : " (with phone sound if sent)"));
                r.tapOpen = true;
                tap(t -> t.fileStarted(path, c.hevc(), c.width(), c.height(), c.codecConfig()));
                if (sound != null) tap(t -> t.audioTrack(sound));
                writeInto(r, opened, f);
            } catch (IOException | RuntimeException e) {
                // the caller never gets it (1.0.53): closed here, or the file stayed open - and locked - for good
                opened.close();
                throw e;
            }
            return opened;
        }
        writeInto(r, recorder, f);
        return recorder;
    }

    private void writeInto(Recording r, StreamRecorder recorder, RecFrame f) throws IOException {
        recorder.write(f.data(), f.keyFrame(), f.arrivedMs());
        if (r.tapOpen) tap(t -> t.frame(f.data(), f.keyFrame(), f.arrivedMs()));
    }

    /** flight-(date)_(time).mkv; a second file within the same second gets -2, -3 ... (it overwrote the first). */
    static Path newFile(Path dir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path file = dir.resolve("flight-" + stamp + ".mkv");
        for (int n = 2; Files.exists(file); n++) file = dir.resolve("flight-" + stamp + "-" + n + ".mkv");
        return file;
    }
}
