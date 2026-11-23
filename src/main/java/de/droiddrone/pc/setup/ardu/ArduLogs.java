package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.common.msg_log_data;
import com.MAVLink.common.msg_log_entry;
import com.MAVLink.common.msg_log_request_data;
import com.MAVLink.common.msg_log_request_end;
import com.MAVLink.common.msg_log_request_list;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Fleet PRD AP-10: an ArduPilot drone's own flight log (the .bin on its SD card) to this PC after landing - over USB on
 * the bench, or over the link through the phone's tunnel (the phone passes the log messages only while the flight
 * controller is disarmed). LOG_REQUEST_LIST gives each log's id, size and time; LOG_REQUEST_DATA asks for a stretch,
 * LOG_DATA brings 90 bytes at a time; what was lost is asked for again until the file is whole. Saved under
 * Videos\DRAUPNE Link\ArduPilot logs as "drone yyyy-MM-dd HH-mm-ss.bin" (the log's own time), where PREVIOUS FLIGHTS
 * finds a flight's by its drone and time ({@link #forFlight}). Not parsed here.
 */
public final class ArduLogs {
    static final int CHUNK = 90;
    /** Asked for at most this many bytes at a time (ArduPilot streams a request at its own pace). */
    static final int ASK_BYTES = CHUNK * 256;
    static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(ZoneId.systemDefault());

    /** One log on the flight controller: its id, size in bytes, and time (UTC seconds; 0 not known). */
    public record Entry(int id, long size, long timeUtcS) {
        public String word() {
            return "log " + id + ", " + Math.max(1, size / 1024) + " kB" + (timeUtcS > 0 ? ", " + NAME.format(Instant.ofEpochSecond(timeUtcS)) : "");
        }
    }

    private ArduLogs() {
    }

    /** A list entry's answer is waited for this long after the last one before the missing are asked for again. */
    static final long LIST_QUIET_MS = 700;

    /**
     * Every log the FC has (an empty list when it has none), in id order. Entries lost on the way (a busy link drops some
     * of ArduPilot's burst) are asked for again one by one (LOG_REQUEST_LIST start = end = id) until all are in or the
     * time is up - before, the list came back short after the whole wait, and a lost log could not be downloaded.
     */
    public static List<Entry> list(MavSession fc, long timeoutMs) throws IOException, InterruptedException {
        java.util.Map<Integer, Entry> out = new java.util.TreeMap<>();
        int[] total = {-1}, last = {-1};
        long[] heard = {0};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_log_entry.MAVLINK_MSG_ID_LOG_ENTRY) return;
            msg_log_entry e = new msg_log_entry(p);
            synchronized (got) {
                total[0] = e.num_logs;
                last[0] = e.last_log_num;
                heard[0] = de.droiddrone.pc.MonoClock.ms();
                if (e.num_logs > 0) out.putIfAbsent(e.id, new Entry(e.id, e.size, e.time_utc));
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            long end = de.droiddrone.pc.MonoClock.ms() + timeoutMs;
            fc.send(new msg_log_request_list(0, 0xffff, target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
            long asked = de.droiddrone.pc.MonoClock.ms();
            while (true) {
                List<Integer> missing = new ArrayList<>();
                synchronized (got) {
                    // a burst under way: let it come while entries keep arriving
                    while (de.droiddrone.pc.MonoClock.ms() < end && (total[0] < 0 || out.size() < total[0])
                            && de.droiddrone.pc.MonoClock.ms() - Math.max(heard[0], asked) < LIST_QUIET_MS) got.wait(100);
                    if (total[0] >= 0 && out.size() >= total[0]) return new ArrayList<>(out.values());
                    if (de.droiddrone.pc.MonoClock.ms() >= end) {
                        if (total[0] < 0) throw new IOException("no answer to the log list");
                        return new ArrayList<>(out.values()); // what came; the rest didn't, asked as it was
                    }
                    if (total[0] >= 0) {
                        int top = last[0] > 0 ? last[0] : total[0];
                        for (int id = Math.max(1, top - total[0] + 1); id <= top; id++) if (!out.containsKey(id)) missing.add(id);
                    }
                }
                if (missing.isEmpty()) { // nothing at all yet: the whole list again
                    fc.send(new msg_log_request_list(0, 0xffff, target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
                } else {
                    for (int id : missing) {
                        fc.send(new msg_log_request_list(id, id, target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
                    }
                }
                asked = de.droiddrone.pc.MonoClock.ms();
            }
        } finally {
            fc.removePacketListener(l);
        }
    }

    /** Nothing at all for this long: the drone has stopped answering (it was asked again and again all the while). */
    public static final long SILENT_MS = 20_000;

    /** The log's bytes, whole; lost stretches asked for again. progress gets 0-100. */
    public static byte[] download(MavSession fc, Entry e, IntConsumer progress, long timeoutMs) throws IOException, InterruptedException {
        return download(fc, e, progress, timeoutMs, SILENT_MS);
    }

    /**
     * As above; silentMs without a single part: given up (a drone gone off the link, a closed session). Before, it asked
     * for 30 minutes - a big log's whole time - with "Downloading ... 0 %" standing all the while.
     */
    public static byte[] download(MavSession fc, Entry e, IntConsumer progress, long timeoutMs, long silentMs) throws IOException, InterruptedException {
        if (e.size() <= 0 || e.size() > 512L * 1024 * 1024) throw new IOException("log size " + e.size());
        byte[] data = new byte[(int) e.size()];
        int chunks = (int) ((e.size() + CHUNK - 1) / CHUNK);
        BitSet have = new BitSet(chunks);
        Object got = new Object();
        long[] heard = {de.droiddrone.pc.MonoClock.ms()};
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_log_data.MAVLINK_MSG_ID_LOG_DATA) return;
            msg_log_data d = new msg_log_data(p);
            if (d.id != e.id() || d.ofs % CHUNK != 0 || d.ofs >= e.size()) return;
            int n = (int) Math.min(d.count, e.size() - d.ofs);
            synchronized (got) {
                for (int i = 0; i < n; i++) data[(int) d.ofs + i] = (byte) d.data[i];
                have.set((int) (d.ofs / CHUNK));
                heard[0] = de.droiddrone.pc.MonoClock.ms();
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            long end = de.droiddrone.pc.MonoClock.ms() + timeoutMs;
            int lastPct = -1;
            while (true) {
                int missing;
                synchronized (got) {
                    missing = have.nextClearBit(0);
                }
                if (missing >= chunks) break;
                if (de.droiddrone.pc.MonoClock.ms() > end) throw new IOException("log " + e.id() + ": " + have.cardinality() + " of " + chunks + " parts");
                long quiet;
                synchronized (got) {
                    quiet = de.droiddrone.pc.MonoClock.ms() - heard[0];
                }
                if (quiet > silentMs) throw new IOException("no answer from the drone for " + quiet / 1000 + " s (" + have.cardinality() + " of " + chunks + " parts)");
                // ask for the first stretch that is missing, at most ASK_BYTES
                int last;
                synchronized (got) {
                    last = have.nextSetBit(missing);
                }
                long from = (long) missing * CHUNK;
                long upTo = last < 0 ? e.size() : (long) last * CHUNK;
                long count = Math.min(ASK_BYTES, upTo - from);
                fc.send(new msg_log_request_data(from, count, e.id(), target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
                synchronized (got) {
                    long until = de.droiddrone.pc.MonoClock.ms() + 1500;
                    while (de.droiddrone.pc.MonoClock.ms() < until && have.get(missing) == false) got.wait(200);
                    // a stream under way: let it run while parts keep coming
                    while (de.droiddrone.pc.MonoClock.ms() - heard[0] < 400 && have.nextClearBit(missing) < Math.min(chunks, (from + count + CHUNK - 1) / CHUNK)) got.wait(200);
                    int pct = (int) (have.cardinality() * 100L / chunks);
                    if (pct != lastPct && progress != null) progress.accept(lastPct = pct);
                }
            }
            fc.send(new msg_log_request_end(target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
            return data;
        } finally {
            fc.removePacketListener(l);
        }
    }

    /** The file a log is kept in: dir\"drone yyyy-MM-dd HH-mm-ss.bin" (the log's time, else now). */
    public static Path fileFor(Path dir, String drone, Entry e) {
        long s = e.timeUtcS() > 0 ? e.timeUtcS() : System.currentTimeMillis() / 1000;
        return dir.resolve(safe(drone) + " " + NAME.format(Instant.ofEpochSecond(s)) + ".bin");
    }

    public static Path save(Path dir, String drone, Entry e, byte[] data) throws IOException {
        Files.createDirectories(dir);
        Path f = fileFor(dir, drone, e);
        Files.write(f, data);
        return f;
    }

    /** The logs kept for this drone whose time lies within the flight (with 10 min either side), newest first. */
    public static List<Path> forFlight(Path dir, String drone, long startMs, long endMs) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir) || drone == null) return out;
        String own = safe(drone) + " ";
        java.util.Map<Path, Long> times = new java.util.HashMap<>();
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                // a log taken over USB on the bench doesn't know the phone's name ("USB COM7 ..."): it was saved
                // "where PREVIOUS FLIGHTS finds it" and never found. Its own time (the drone's GPS) places it.
                String prefix = n.startsWith(own) ? own : usbPrefix(n);
                if (prefix == null || !n.toLowerCase(Locale.ROOT).endsWith(".bin")) continue;
                try {
                    long t = java.time.LocalDateTime.parse(n.substring(prefix.length(), n.length() - 4), DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (t >= startMs - 600_000 && t <= (endMs <= 0 ? startMs : endMs) + 600_000) {
                        out.add(f);
                        times.put(f, t);
                    }
                } catch (RuntimeException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException ignored) {
            // none then
        }
        out.sort((a, b) -> Long.compare(times.get(b), times.get(a))); // newest first, whatever the name
        return out;
    }

    /** "USB COM7 " of a log taken over USB ("USB COM7 2026-11-23 10-00-00.bin"), else null. */
    static String usbPrefix(String fileName) {
        if (!fileName.startsWith("USB ")) return null;
        int space = fileName.indexOf(' ', 4);
        return space < 0 ? null : fileName.substring(0, space + 1);
    }

    private static String safe(String drone) {
        return drone.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static short target(MavSession fc) {
        return (short) Math.max(1, fc.fcSysId());
    }
}
