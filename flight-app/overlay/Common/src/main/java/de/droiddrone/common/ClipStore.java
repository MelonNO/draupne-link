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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DRAUPNE Flight pc.38: the overwatch clips on the phone until the PC has them - on disk, not only in memory (the
 * review's M20 and L3). An event records in segments of {@link #SEGMENT_MS} (an MP4 is lost whole if the app dies
 * before it is closed: now at most one segment of it). Every segment has its facts in a small file beside it
 * ({@code <clip>.mp4.meta}), written when it starts and again when it is finished and hashed, so the folder is the
 * list: {@link #scan} at the app's start takes back what a restart left - finished clips as they were, a segment cut
 * off mid-recording only when its MP4 is whole (else it goes).
 * <p>
 * The folder keeps to a quota ({@link #QUOTA_BYTES}, and {@link #KEEP_FREE_BYTES} of the phone left free): over it,
 * clips the PC has already go first, then clips given up, then the oldest waiting. One clip can't block the rest: a
 * clip sent whole without the PC's CLIP_DONE within {@link #DONE_WAIT_MS} (or asked for from the start again - the
 * PC found it damaged) counts an attempt and goes to the back; after {@link #MAX_ATTEMPTS} it is given up (kept on
 * the phone until the quota needs the room, no longer offered).
 * <p>
 * Pure Java, no Android (the PC's tests drive it in a temporary folder); thread-safe - the overwatch thread offers
 * and serves, the hashing thread finishes and scans. Java 11: the phone app's language level.
 */
public final class ClipStore {
    /** An event's recording is cut into files this long (at the next key frame after it). */
    public static final long SEGMENT_MS = 60_000;
    /** The clip folder's most. */
    public static final long QUOTA_BYTES = 2L * 1024 * 1024 * 1024;
    /** The phone's own room: the folder never takes the last of this. */
    public static final long KEEP_FREE_BYTES = 1024L * 1024 * 1024;
    /** Times a clip is sent whole without the PC confirming it before it is given up. */
    public static final int MAX_ATTEMPTS = 5;
    /** A clip sent whole: the PC's CLIP_DONE is due within this (it repeats its INFO every 5 s meanwhile). */
    public static final long DONE_WAIT_MS = 20_000;
    static final String META = ".meta";
    private static final Pattern NAME = Pattern.compile("(\\d{8}_\\d{6})_(\\d+)(?:_(\\d+))?\\.mp4");

    /** One clip (a segment of an event). */
    public static final class Clip {
        public final File file;
        public final int event, part;
        /** This segment's start and its event's (the alert's), wall ms. */
        public final long startedAt, eventStartedAt;
        public final boolean sound;
        public final int width, height;
        private long size, durationMs;
        private byte[] sha256;
        private int attempts, offers;
        private boolean givenUp, delivered;
        private long fullyServedAt = -1;

        Clip(File file, int event, int part, long startedAt, long eventStartedAt, boolean sound, int width, int height) {
            this.file = file;
            this.event = event & 0xffff;
            this.part = part;
            this.startedAt = startedAt;
            this.eventStartedAt = eventStartedAt;
            this.sound = sound;
            this.width = width;
            this.height = height;
        }

        public long size() {
            return size;
        }

        public long durationMs() {
            return durationMs;
        }

        public byte[] sha256() {
            return sha256 == null ? null : sha256.clone();
        }

        public int attempts() {
            return attempts;
        }

        public boolean givenUp() {
            return givenUp;
        }

        public boolean delivered() {
            return delivered;
        }

        private boolean waiting() {
            return sha256 != null && !givenUp && !delivered;
        }
    }

    private final File dir;
    /** Finished clips, in the order they are offered (oldest first; a failed attempt goes to the back). */
    private final List<Clip> queue = new ArrayList<>();
    /** Segments recording now: never scanned, never deleted by the quota. */
    private final Set<File> recording = new HashSet<>();

    public ClipStore(File dir) {
        this.dir = dir;
    }

    public File dir() {
        return dir;
    }

    /** A new segment's file: {@code yyyyMMdd_HHmmss_<event>_<part>.mp4} (local time), never one already there. */
    public File newFile(long wallMs, int event, int part) {
        String base = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(wallMs)) + "_" + (event & 0xffff) + "_";
        File f = new File(dir, base + part + ".mp4");
        for (int n = part + 1000; f.exists() || metaOf(f).exists(); n += 1000) f = new File(dir, base + n + ".mp4");
        return f;
    }

    /** A segment starts recording: its facts are written beside it now, so a restart knows what the file is. */
    public synchronized Clip begin(File file, int event, int part, long startedAt, long eventStartedAt, boolean sound, int width, int height) {
        Clip c = new Clip(file, event, part, startedAt, eventStartedAt, sound, width, height);
        recording.add(file);
        try {
            writeMeta(c);
        } catch (IOException ignored) {
            // the clip still goes to the PC; only a restart wouldn't know it
        }
        return c;
    }

    /** The segment is closed and hashed: it waits for the PC. */
    public synchronized void finish(Clip c, long durationMs, byte[] sha256) {
        recording.remove(c.file);
        c.size = c.file.length();
        c.durationMs = durationMs;
        c.sha256 = sha256.clone();
        try {
            writeMeta(c);
        } catch (IOException ignored) {
        }
        if (!queue.contains(c)) queue.add(c);
    }

    /** The segment came out empty or can't be read: it and its facts go. */
    public synchronized void discard(Clip c) {
        recording.remove(c.file);
        queue.remove(c);
        deleteFiles(c);
    }

    /** The clip to offer the PC now: the first one waiting, or null. */
    public synchronized Clip head() {
        for (Clip c : queue) {
            if (c.waiting()) return c;
        }
        return null;
    }

    /** A clip still to deliver by its event and start (the PC's CLIP_DONE from 1.0.49 names both), or null. */
    public synchronized Clip find(int event, long startedAt) {
        for (Clip c : queue) {
            if (c.event == (event & 0xffff) && c.startedAt == startedAt && !c.delivered) return c;
        }
        return null;
    }

    /** Its INFO went to the PC. */
    public synchronized void offered(Clip c) {
        c.offers++;
    }

    /** Whether the PC was told of it (an old PC's CLIP_DONE, naming the event alone, is taken only then). */
    public synchronized boolean wasOffered(Clip c) {
        return c.offers > 0;
    }

    /**
     * The PC asked for [from, end) of it. From the start again after it went whole: the PC found it damaged - an
     * attempt. Once its last byte went, the PC's CLIP_DONE is due within {@link #DONE_WAIT_MS} of its last ask (it
     * may still be filling gaps).
     */
    public synchronized void served(Clip c, long from, long end, long now) {
        if (from == 0 && c.fullyServedAt >= 0) {
            c.fullyServedAt = -1;
            failed(c, false);
        }
        if (c.givenUp) return;
        if (end >= c.size || c.fullyServedAt >= 0) c.fullyServedAt = now;
    }

    /**
     * Every tick: the head sent whole and neither confirmed nor asked for in time counts an attempt and goes to the
     * back of the queue (given up after {@link #MAX_ATTEMPTS}). @return that clip, or null
     */
    public synchronized Clip tick(long now) {
        Clip c = head();
        if (c == null || c.fullyServedAt < 0 || now - c.fullyServedAt < DONE_WAIT_MS) return null;
        c.fullyServedAt = -1;
        failed(c, true);
        return c;
    }

    private void failed(Clip c, boolean toBack) {
        c.attempts++;
        if (c.attempts >= MAX_ATTEMPTS) c.givenUp = true;
        if (toBack || c.givenUp) {
            queue.remove(c);
            queue.add(c);
        }
        try {
            writeMeta(c);
        } catch (IOException ignored) {
        }
    }

    /** A clip that can't be read any more: given up at once (the file stays until the quota needs the room). */
    public synchronized void giveUp(Clip c) {
        c.givenUp = true;
        c.fullyServedAt = -1;
        try {
            writeMeta(c);
        } catch (IOException ignored) {
        }
    }

    /** The PC has it, checked: deleted here. @return false when the file couldn't be deleted (the quota takes it first) */
    public synchronized boolean delivered(Clip c) {
        c.delivered = true;
        c.fullyServedAt = -1;
        if (deleteFiles(c)) {
            queue.remove(c);
            return true;
        }
        try {
            writeMeta(c);
        } catch (IOException ignored) {
        }
        return false;
    }

    /** Clips waiting for the PC (not given up, not delivered). */
    public synchronized int waitingCount() {
        int n = 0;
        for (Clip c : queue) {
            if (c.waiting()) n++;
        }
        return n;
    }

    /** All clips the folder holds (finished ones). */
    public synchronized List<Clip> clips() {
        return new ArrayList<>(queue);
    }

    /** The bytes of the finished clips (the segments recording now aren't counted). */
    public synchronized long bytes() {
        long n = 0;
        for (Clip c : queue) n += c.file.length();
        return n;
    }

    /**
     * The folder's limit now: the quota, or less when the phone would keep under {@link #KEEP_FREE_BYTES} free.
     * @param usableBytes the room left on the storage; 0 or less: not known (the quota alone)
     */
    public static long limit(long bytes, long usableBytes) {
        if (usableBytes <= 0) return QUOTA_BYTES;
        return Math.max(0, Math.min(QUOTA_BYTES, bytes + usableBytes - KEEP_FREE_BYTES));
    }

    /**
     * Over the limit: clips the PC has first, then given up ones, then the oldest waiting - never a segment recording.
     * @param usableBytes the room left on the phone's storage now
     * @return the clips deleted
     */
    public synchronized List<Clip> enforceQuota(long usableBytes) {
        List<Clip> deleted = new ArrayList<>();
        long bytes = bytes();
        long limit = limit(bytes, usableBytes);
        while (bytes > limit) {
            Clip victim = null;
            for (int rank = 0; rank < 3 && victim == null; rank++) {
                Clip oldest = null;
                for (Clip c : queue) {
                    boolean match = rank == 0 ? c.delivered : rank == 1 ? c.givenUp && !c.delivered : c.waiting();
                    if (match && (oldest == null || c.startedAt < oldest.startedAt)) oldest = c;
                }
                victim = oldest;
            }
            if (victim == null) break;
            long size = victim.file.length();
            queue.remove(victim);
            deleteFiles(victim);
            deleted.add(victim);
            bytes -= size;
        }
        return deleted;
    }

    // ------------------------------------------------------------------ the scan at the app's start

    /**
     * Takes back what is in the folder: every finished clip with its facts (as it was: attempts, given up), a segment
     * cut off mid-recording or not yet hashed only when its MP4 is whole (hashed now), and pc.36/37's clips (no facts:
     * read from the name). An MP4 that isn't whole can't be played: it goes with its facts. Hashes outside the lock;
     * slow on big files - on the hashing thread, never the overwatch thread.
     * @param log one line per thing done
     */
    public void scan(Consumer<String> log) {
        List<File> candidates = new ArrayList<>();
        synchronized (this) {
            File[] files = dir.listFiles();
            if (files == null) return;
            Set<File> known = new HashSet<>(recording);
            for (Clip c : queue) known.add(c.file);
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".mp4" + META)) {
                    File clip = new File(dir, name.substring(0, name.length() - META.length()));
                    if (!clip.isFile() && !known.contains(clip)) {
                        if (!f.delete()) log.accept("Overwatch clips: " + name + " couldn't be deleted");
                    }
                } else if (name.endsWith(".mp4") && f.isFile() && !known.contains(f)) {
                    candidates.add(f);
                }
            }
        }
        List<Clip> found = new ArrayList<>();
        for (File f : candidates) {
            Clip c = load(f, log);
            if (c != null) found.add(c);
        }
        synchronized (this) {
            for (Clip c : found) {
                if (!queue.contains(c) && !recording.contains(c.file)) queue.add(c);
            }
            Collections.sort(queue, (a, b) -> Long.compare(a.startedAt, b.startedAt));
        }
        if (!found.isEmpty()) log.accept("Overwatch clips: " + found.size() + " on the phone from before (" + waitingCount() + " waiting for the PC)");
    }

    private Clip load(File f, Consumer<String> log) {
        File meta = metaOf(f);
        Clip c;
        Properties p = new Properties();
        if (meta.isFile()) {
            try (InputStream in = new FileInputStream(meta)) {
                p.load(in);
                c = new Clip(f, Integer.parseInt(p.getProperty("event", "0")), Integer.parseInt(p.getProperty("part", "1")),
                        Long.parseLong(p.getProperty("startedAt", "0")), Long.parseLong(p.getProperty("eventStartedAt", "0")),
                        Boolean.parseBoolean(p.getProperty("sound")), Integer.parseInt(p.getProperty("width", "0")),
                        Integer.parseInt(p.getProperty("height", "0")));
                c.attempts = Integer.parseInt(p.getProperty("attempts", "0"));
                c.givenUp = Boolean.parseBoolean(p.getProperty("givenUp"));
                c.delivered = Boolean.parseBoolean(p.getProperty("delivered"));
                c.durationMs = Long.parseLong(p.getProperty("durationMs", "-1"));
            } catch (IOException | RuntimeException e) {
                c = null;
            }
        } else {
            c = fromName(f);
        }
        if (c == null) {
            log.accept("Overwatch clips: " + f.getName() + " has no facts that can be read - left alone");
            return null;
        }
        if (c.delivered && deleteFiles(c)) return null;
        String sha = p.getProperty("sha256");
        long size = Long.parseLong(p.getProperty("size", "-1"));
        if (sha != null && sha.length() == 64 && size == f.length()) {
            c.size = size;
            c.sha256 = unhex(sha);
            if (c.durationMs < 0) c.durationMs = 0;
            return c;
        }
        // cut off mid-recording, or not hashed before the app ended
        if (!isWholeMp4(f)) {
            log.accept("Overwatch clips: " + f.getName() + " was cut off mid-recording and can't be played - deleted");
            deleteFiles(c);
            return null;
        }
        try {
            c.sha256 = sha256(f);
        } catch (IOException e) {
            log.accept("Overwatch clips: " + f.getName() + " can't be read: " + e.getMessage());
            return null;
        }
        c.size = f.length();
        if (c.durationMs < 0) c.durationMs = Math.max(0, f.lastModified() - c.startedAt);
        try {
            writeMeta(c);
        } catch (IOException ignored) {
        }
        log.accept("Overwatch clips: " + f.getName() + " taken back after a restart");
        return c;
    }

    /** A clip without facts (pc.36/37 kept them in memory only): event and start from its name. */
    private Clip fromName(File f) {
        Matcher m = NAME.matcher(f.getName());
        if (!m.matches()) return null;
        try {
            long at = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse(m.group(1)).getTime();
            int part = m.group(3) == null ? 1 : Integer.parseInt(m.group(3)) % 1000;
            return new Clip(f, Integer.parseInt(m.group(2)), part, at, at, false, 0, 0);
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether an MP4's top-level boxes run exactly to its end and include the movie box (moov), which MediaMuxer
     * writes last: a file cut off mid-recording has none, or an mdat box that runs past the end.
     */
    public static boolean isWholeMp4(File f) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long len = in.length(), at = 0;
            boolean moov = false;
            while (at < len) {
                if (len - at < 8) return false;
                in.seek(at);
                long size = in.readInt() & 0xffffffffL;
                byte[] type = new byte[4];
                in.readFully(type);
                if (size == 1) {
                    if (len - at < 16) return false;
                    size = in.readLong();
                } else if (size == 0) {
                    size = len - at; // to the end of the file
                }
                if (size < 8 || size > len - at) return false;
                if (type[0] == 'm' && type[1] == 'o' && type[2] == 'o' && type[3] == 'v') moov = true;
                at += size;
            }
            return moov;
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------ the facts beside each clip

    static File metaOf(File clip) {
        return new File(clip.getParentFile(), clip.getName() + META);
    }

    private static void writeMeta(Clip c) throws IOException {
        Properties p = new Properties();
        p.setProperty("event", Integer.toString(c.event));
        p.setProperty("part", Integer.toString(c.part));
        p.setProperty("startedAt", Long.toString(c.startedAt));
        p.setProperty("eventStartedAt", Long.toString(c.eventStartedAt));
        p.setProperty("sound", Boolean.toString(c.sound));
        p.setProperty("width", Integer.toString(c.width));
        p.setProperty("height", Integer.toString(c.height));
        p.setProperty("attempts", Integer.toString(c.attempts));
        if (c.givenUp) p.setProperty("givenUp", "true");
        if (c.delivered) p.setProperty("delivered", "true");
        if (c.sha256 != null) {
            p.setProperty("sha256", hex(c.sha256));
            p.setProperty("size", Long.toString(c.size));
            p.setProperty("durationMs", Long.toString(c.durationMs));
        }
        File meta = metaOf(c.file), tmp = new File(meta.getPath() + ".tmp");
        File parent = meta.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("no folder " + parent);
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        }
        if (!tmp.renameTo(meta)) { // Android's rename replaces; a PC's may not
            if (!meta.delete() || !tmp.renameTo(meta)) throw new IOException("the facts of " + c.file.getName() + " weren't saved");
        }
    }

    private static boolean deleteFiles(Clip c) {
        boolean gone = !c.file.exists() || c.file.delete();
        File meta = metaOf(c.file);
        if (gone && meta.exists() && !meta.delete()) return true; // the clip is gone; a stray facts file goes at the next scan
        return gone;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
