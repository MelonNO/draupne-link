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
import java.util.function.LongSupplier;
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
        // the rest: this recording's thread only
        /** Writes failed in a row, none a minute after the last (clock); at MAX_WRITE_FAILURES it waits for room. */
        int failures;
        long failedAt;
        /** Stopped for want of room (I-017): no part until tryAt (clock); then the next key frame begins one - the try. */
        boolean waiting;
        long tryAt;
        /** Tries since the stop that found no room: the next wait's length (roomWaitMs). */
        int tries;
        /** When it last recorded again after such a stop (clock); 0: never. */
        long backAt;

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
    /** Told (record thread) when the recording stops or starts again by itself, in the pilot's words; null: the app's log is. */
    private volatile Consumer<String> problemListener;
    /** Told (record thread) of a file the fileListener was told of that got nothing on the disk and was deleted. */
    private volatile Consumer<Path> emptyListener;
    private volatile String label = "";
    /** Nanoseconds: when a write failed, and the waits for room. */
    private final LongSupplier clock;

    VideoRecording(Supplier<VideoPipeline.Config> currentConfig, Runnable requestKeyFrame, Supplier<AudioConfig> defaultAudio) {
        this(currentConfig, requestKeyFrame, defaultAudio, System::nanoTime);
    }

    /** @param clock nanoseconds: when a write failed, and the waits between tries for room */
    VideoRecording(Supplier<VideoPipeline.Config> currentConfig, Runnable requestKeyFrame, Supplier<AudioConfig> defaultAudio,
                   LongSupplier clock) {
        this.currentConfig = currentConfig;
        this.requestKeyFrame = requestKeyFrame;
        this.defaultAudio = defaultAudio;
        this.clock = clock;
    }

    void setFileListener(Consumer<Path> listener) {
        fileListener = listener;
    }

    /**
     * Told of a part of the recording that got not one byte on the disk (a full disk) and was deleted at its end, with
     * its figures' .csv: it is no part of the flight. Each try on a full disk left such files (the test bench, 2026-11-24).
     */
    void setEmptyListener(Consumer<Path> listener) {
        emptyListener = listener;
    }

    /**
     * Closes a part; when it got nothing on the disk it is deleted, its figures' .csv too when that is empty, and the
     * empty listener is told. A file with any byte stays.
     */
    private void closeFile(StreamRecorder recorder) {
        closeFile(recorder, true);
    }

    /**
     * The same; named: the file listener was told of this part (the empty listener is told only then).
     * @return true when it was deleted
     */
    private boolean closeFile(StreamRecorder recorder, boolean named) {
        try {
            recorder.close();
        } catch (RuntimeException e) { // the file is looked at all the same
            log("Recording: closing the file: " + e);
        }
        Path mkv = recorder.file();
        de.droiddrone.pc.DiskSpace.deleteIfEmpty(StreamRecorder.figuresFile(mkv));
        if (!de.droiddrone.pc.DiskSpace.deleteIfEmpty(mkv)) return false;
        log("Recording: " + mkv.getFileName() + " empty, deleted.");
        Consumer<Path> to = emptyListener;
        if (to == null || !named) return true;
        try {
            to.accept(mkv);
        } catch (RuntimeException e) {
            log("Recording: " + e);
        }
        return true;
    }

    /**
     * Told once when the recording stops by itself - a disk that keeps refusing - and once when it records again
     * (I-017). Until 2026-11-24 (the test bench's full disk) only the app's log file said it: the pilot saw the REC
     * mark go without a word.
     */
    void setProblemListener(Consumer<String> listener) {
        problemListener = listener;
    }

    /** What the window says when the recording stopped for a disk that refuses: why, and that it starts again (I-017). */
    static String stoppedLine(Throwable e) {
        return "RECORDING STOPPED: " + why(e) + " - starts again when there is room.";
    }

    /** What the window says when a fault ended the recording for the connection: why, and how to start it again. */
    static String endedLine(Throwable e) {
        return "RECORDING STOPPED: " + why(e) + " - VIDEO > RECORD VIDEO off and on to record again.";
    }

    /** What the window says when the recording came back by itself after a stop for want of room. */
    static final String AGAIN_LINE = "RECORDING AGAIN.";

    private static String why(Throwable e) {
        String why = de.droiddrone.pc.DiskSpace.why(e);
        return why.startsWith("Recording: ") ? why.substring("Recording: ".length()) : why; // StreamRecorder's own words
    }

    /** In the window's log (the listener), or in the app's when there is none. */
    private void say(String line) {
        Consumer<String> to = problemListener;
        if (to != null) {
            try {
                to.accept(line); // the window's log, which writes the app's log too
                return;
            } catch (RuntimeException ignored) {
            }
        }
        log(line);
    }

    /** 1.0.71: the drone's name in each new file's name (flight-DATE-label.mkv); "" or null: none. */
    void setLabel(String label) {
        this.label = label == null ? "" : label;
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

    /** Writes that may fail in a row, none a minute after the last, before the recording stops and waits for room (2026-11-20). */
    static final int MAX_WRITE_FAILURES = 5;
    /** A minute (ns): written that long without a failure, the count starts again - and so do the waits for room. */
    private static final long WELL_NS = 60_000_000_000L;
    /** The waits for room after a stop (I-017): 5 s, then twice as long each try that found none, up to 30 s. */
    static final long ROOM_WAIT_FIRST_MS = 5_000, ROOM_WAIT_MAX_MS = 30_000;

    /** The wait before the next try for room, after this many tries that found none since the stop: 5, 10, 20, 30, 30 ... s. */
    static long roomWaitMs(int failedTries) {
        return Math.min(ROOM_WAIT_MAX_MS, ROOM_WAIT_FIRST_MS << Math.min(Math.max(failedTries, 0), 10));
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
              try {
                Object item = r.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (item == null) {
                    // nothing came for a second: the link is lost or the picture stands. What was recorded up to
                    // here goes to the disk now - it is the picture of where the drone went (StreamRecorder.flush)
                    if (recorder != null) recorder.flush();
                    continue;
                }
                if (item == REC_STOP) break;
                if (item instanceof RecNewFile n) {
                    if (recorder != null) closeFile(recorder);
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
                if (r.waiting && clock.getAsLong() - r.tryAt < 0) continue; // no room at the last try: no part until the next
                if (held != null) {
                    if (f.arrivedMs() - held.get(0).arrivedMs() <= RECORD_HOLD_MS && held.size() < RECORD_QUEUE) {
                        held.add(f);
                        continue;
                    }
                    for (RecFrame h : held) recorder = write(r, recorder, h, fileConfig); // no config came: as the decoder does
                    held = null;
                }
                if (f.streamStart() && f.keyFrame() && !configCame) { // a restart whose config hasn't come: not into this file
                    if (recorder != null) closeFile(recorder);
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
                if (r.failures > 0 && clock.getAsLong() - r.failedAt > WELL_NS) r.failures = 0; // a minute written well
              } catch (IOException | java.io.UncheckedIOException e) {
                // 2026-11-20 (a reader of this code): one write that failed ended the recording for the rest of the
                // connection - a file held a moment by a virus scanner, a disk that was full for a while - and nobody
                // started it again. Now the file in hand is closed whole and the next key frame begins a new one
                if (recorder != null) {
                    closeFile(recorder); // a full disk: nothing of it on the disk, and it is deleted
                    recorder = null;
                    tapEnded(r);
                    file = null;
                }
                held = null;
                askedForKey = false;
                configCame = false;
                failed(r, e);
              }
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException | Error e) { // an Error too: it left "recording" set, and nothing more was recorded in that connection
            say(endedLine(e));
            synchronized (this) {
                if (recording == r) recording = null;
            }
        } finally {
            if (recorder != null) closeFile(recorder); // the tap and the file name are let go all the same
            tapEnded(r);
            file = null;
        }
    }

    /**
     * A write failed; the part in hand is closed. Up to five in a row a new part begins at the next key frame. The
     * fifth stops the recording - said once - and it waits for room (I-017, the pilot, 2026-11-24: until then it
     * stayed off for the rest of the connection): a new part is tried after 5 s, 10 s, 20 s, then every 30 s, at a
     * key frame; a try that finds no room is said in the app's log only. A disk that came back and was full again
     * within a minute goes on with the longer waits.
     */
    private void failed(Recording r, Exception e) {
        long now = clock.getAsLong();
        if (r.waiting) {
            r.tries++;
            r.tryAt = now + roomWaitMs(r.tries) * 1_000_000L;
            log("Recording: no room yet (" + why(e) + ") - next try in " + roomWaitMs(r.tries) / 1000 + " s.");
            return;
        }
        r.failures++;
        r.failedAt = now;
        if (r.failures < MAX_WRITE_FAILURES) {
            log("RECORDING INTERRUPTED: " + e.getMessage() + " - a new file at the next key frame.");
            return;
        }
        if (r.backAt == 0 || now - r.backAt > WELL_NS) r.tries = 0;
        r.failures = 0;
        r.waiting = true;
        r.tryAt = now + roomWaitMs(r.tries) * 1_000_000L;
        say(stoppedLine(e));
    }

    /**
     * One frame into the file; at a key frame a file of config (null: the one in hand) is opened when none is. A new
     * part's first frame goes to the disk at once: its write is the test of room (a disk may say it has room and have
     * none - a network drive, WSL's). Only a part that passed is the file being written, gets its copy with the OSD
     * (the tap) and - after a stop for want of room - is said: RECORDING AGAIN. Until 2026-11-24 each try on a full
     * disk began a copy with the OSD, and the window said so twice a try.
     * <p>
     * A part is told to the file listener (the flight's record) when it is made; a try for room only once its first
     * frame is on the disk (or, refused, when something of it stayed): each try would add a part to the record and
     * take it out again - two lines on the full disk, and when its last room went between them the record named a
     * part that is gone.
     * @return the file (null: none yet - not a key frame, or no config)
     */
    private StreamRecorder write(Recording r, StreamRecorder recorder, RecFrame f, VideoPipeline.Config config) throws IOException {
        if (recorder != null) {
            writeInto(r, recorder, f);
            return recorder;
        }
        VideoPipeline.Config c = config != null ? config : currentConfig.get();
        if (c == null || !f.keyFrame()) return null; // a file starts at a key frame
        Path path = newFile(r.dir, label);
        AudioConfig sound = audioTrackForNewFile();
        StreamRecorder opened;
        try {
            opened = new StreamRecorder(path, c.hevc(), c.width(), c.height(), c.codecConfig(), sound);
        } catch (IOException | RuntimeException e) {
            de.droiddrone.pc.DiskSpace.deleteIfEmpty(path); // made and not begun: no empty file left behind
            throw e;
        }
        boolean trying = r.waiting;
        boolean named = false;
        try {
            if (!trying) {
                named = true;
                started(path);
            }
            opened.write(f.data(), f.keyFrame(), f.arrivedMs());
            opened.flush();
            if (trying) {
                named = true;
                started(path);
            }
        } catch (IOException | RuntimeException e) {
            // the caller never gets it (1.0.53): closed here, or the file stayed open - and locked - for good
            if (!closeFile(opened, named) && !named) { // a try that left something on the disk: the flight's all the same
                try {
                    started(path);
                } catch (RuntimeException again) {
                    log("Recording: " + again);
                }
            }
            throw e;
        }
        file = path;
        log("Recording to " + path + (sound == null ? "" : " (with phone sound if sent)"));
        r.tapOpen = true;
        tap(t -> t.fileStarted(path, c.hevc(), c.width(), c.height(), c.codecConfig()));
        if (sound != null) tap(t -> t.audioTrack(sound));
        tap(t -> t.frame(f.data(), f.keyFrame(), f.arrivedMs()));
        if (r.waiting) {
            r.waiting = false;
            r.backAt = clock.getAsLong();
            say(AGAIN_LINE);
        }
        return opened;
    }

    /** The file listener told of a new part; what it throws fails the part. */
    private void started(Path path) {
        Consumer<Path> to = fileListener;
        if (to != null) to.accept(path);
    }

    private void writeInto(Recording r, StreamRecorder recorder, RecFrame f) throws IOException {
        recorder.write(f.data(), f.keyFrame(), f.arrivedMs());
        if (r.tapOpen) tap(t -> t.frame(f.data(), f.keyFrame(), f.arrivedMs()));
    }

    /** flight-(date)_(time).mkv; a second file within the same second gets -2, -3 ... (it overwrote the first). */
    static Path newFile(Path dir) {
        return newFile(dir, "");
    }

    /** The same with the drone's name after the stamp: flight-(date)_(time)-(label).mkv, then -2, -3 ... */
    static Path newFile(Path dir, String label) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String base = "flight-" + stamp + (label == null || label.isEmpty() ? "" : "-" + label);
        Path file = dir.resolve(base + ".mkv");
        for (int n = 2; Files.exists(file); n++) file = dir.resolve(base + "-" + n + ".mkv");
        return file;
    }
}
