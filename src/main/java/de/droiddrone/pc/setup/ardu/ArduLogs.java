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

    /** Every log the FC has (an empty list when it has none). */
    public static List<Entry> list(MavSession fc, long timeoutMs) throws IOException, InterruptedException {
        List<Entry> out = new ArrayList<>();
        int[] total = {-1};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_log_entry.MAVLINK_MSG_ID_LOG_ENTRY) return;
            msg_log_entry e = new msg_log_entry(p);
            synchronized (got) {
                total[0] = e.num_logs;
                if (e.num_logs > 0 && out.stream().noneMatch(x -> x.id() == e.id)) out.add(new Entry(e.id, e.size, e.time_utc));
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            fc.send(new msg_log_request_list(0, 0xffff, target(fc), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (got) {
                while (total[0] < 0 || out.size() < total[0]) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    got.wait(left);
                }
                if (total[0] < 0) throw new IOException("no answer to the log list");
                return new ArrayList<>(out);
            }
        } finally {
            fc.removePacketListener(l);
        }
    }

    /** The log's bytes, whole; lost stretches asked for again. progress gets 0-100. */
    public static byte[] download(MavSession fc, Entry e, IntConsumer progress, long timeoutMs) throws IOException, InterruptedException {
        if (e.size() <= 0 || e.size() > 512L * 1024 * 1024) throw new IOException("log size " + e.size());
        byte[] data = new byte[(int) e.size()];
        int chunks = (int) ((e.size() + CHUNK - 1) / CHUNK);
        BitSet have = new BitSet(chunks);
        Object got = new Object();
        long[] heard = {System.currentTimeMillis()};
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_log_data.MAVLINK_MSG_ID_LOG_DATA) return;
            msg_log_data d = new msg_log_data(p);
            if (d.id != e.id() || d.ofs % CHUNK != 0 || d.ofs >= e.size()) return;
            int n = (int) Math.min(d.count, e.size() - d.ofs);
            synchronized (got) {
                for (int i = 0; i < n; i++) data[(int) d.ofs + i] = (byte) d.data[i];
                have.set((int) (d.ofs / CHUNK));
                heard[0] = System.currentTimeMillis();
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            long end = System.currentTimeMillis() + timeoutMs;
            int lastPct = -1;
            while (true) {
                int missing;
                synchronized (got) {
                    missing = have.nextClearBit(0);
                }
                if (missing >= chunks) break;
                if (System.currentTimeMillis() > end) throw new IOException("log " + e.id() + ": " + have.cardinality() + " of " + chunks + " parts");
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
                    long until = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < until && have.get(missing) == false) got.wait(200);
                    // a stream under way: let it run while parts keep coming
                    while (System.currentTimeMillis() - heard[0] < 400 && have.nextClearBit(missing) < Math.min(chunks, (from + count + CHUNK - 1) / CHUNK)) got.wait(200);
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
        String prefix = safe(drone) + " ";
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.startsWith(prefix) || !n.toLowerCase(Locale.ROOT).endsWith(".bin")) continue;
                try {
                    long t = java.time.LocalDateTime.parse(n.substring(prefix.length(), n.length() - 4), DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (t >= startMs - 600_000 && t <= (endMs <= 0 ? startMs : endMs) + 600_000) out.add(f);
                } catch (RuntimeException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException ignored) {
            // none then
        }
        out.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return out;
    }

    private static String safe(String drone) {
        return drone.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static short target(MavSession fc) {
        return (short) Math.max(1, fc.fcSysId());
    }
}
