package de.droiddrone.pc;

import de.droiddrone.common.PositionFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What this PC last knew of every phone, kept on disk (1.0.71; the pilot, 2026-11-18: "always store a last known grid
 * for a phone so that if a drone is lost and runs out of battery it is still possible to find it" - and "show last
 * mode when unknown"). Per phone: its last place and when, its phone's battery and its drone's pack as last read, and
 * the last mode it was confirmed in. Before, a phone's place lived in memory only: a restart, or a phone that had gone
 * quiet before the app started, had none.
 * The file is %APPDATA%\DraupneLink\last-known.txt, one phone a line, written whole (to a file beside it, then moved
 * over) a few seconds after something changed. Made-up drones are never given to it.
 * Times are the wall clock's milliseconds.
 */
public final class LastKnown {
    /** Changes reach the disk at most this long after they were made. */
    static final long SAVE_AFTER_MS = 3000;

    /**
     * One phone. lat / lon NaN: no place known; phonePercent -1, cellVolts NaN: not known; mode "": none confirmed yet.
     * @param mode the word the pilot reads: PILOTED, IDLE, STANDBY, DEEP SLEEP, OVERWATCH
     */
    public record Entry(String name, double lat, double lon, long placeAt, int phonePercent, double cellVolts, long batteryAt,
                        String mode, long modeAt) {
        public boolean hasPlace() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }

        static Entry of(String name) {
            return new Entry(name, Double.NaN, Double.NaN, 0, -1, Double.NaN, 0, "", 0);
        }
    }

    private final Path file;
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private boolean dirty;
    private long dirtySince;

    /** @param file where it is kept; null: in memory only (tests) */
    public LastKnown(Path file) {
        this.file = file;
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Entry e = parse(line);
                if (e != null) byId.put(id(e.name()), e);
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: it fills again as the phones are heard
        }
    }

    private static String id(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private Entry entry(String name) {
        Entry e = byId.get(id(name));
        return e != null ? e : Entry.of(name.trim());
    }

    private void put(Entry e, long now) {
        byId.put(id(e.name()), e);
        if (!dirty) dirtySince = now;
        dirty = true;
    }

    /** A place heard for this phone. One without a fix (0/0, off the globe) or older than the one held is left out. */
    public synchronized void place(String name, double lat, double lon, long atMs) {
        if (name == null || name.isBlank() || !PositionFilter.onGlobe(lat, lon)) return;
        Entry e = entry(name);
        if (e.hasPlace() && atMs <= e.placeAt()) return;
        if (e.hasPlace() && e.lat() == lat && e.lon() == lon && atMs - e.placeAt() < 60_000) return; // standing still: once a minute
        put(new Entry(e.name(), lat, lon, atMs, e.phonePercent(), e.cellVolts(), e.batteryAt(), e.mode(), e.modeAt()), atMs);
    }

    /** The batteries as last read; -1 / NaN leaves what is held. */
    public synchronized void battery(String name, int phonePercent, double cellVolts, long atMs) {
        if (name == null || name.isBlank() || (phonePercent < 0 && Double.isNaN(cellVolts))) return;
        Entry e = entry(name);
        int phone = phonePercent >= 0 ? phonePercent : e.phonePercent();
        double cell = Double.isNaN(cellVolts) ? e.cellVolts() : Math.round(cellVolts * 100) / 100.0;
        if (phone == e.phonePercent() && Double.compare(cell, e.cellVolts()) == 0) return;
        put(new Entry(e.name(), e.lat(), e.lon(), e.placeAt(), phone, cell, atMs, e.mode(), e.modeAt()), atMs);
    }

    /** The mode the phone is confirmed in now (never UNKNOWN or LOST). The time moves on at most once a minute. */
    public synchronized void mode(String name, String mode, long atMs) {
        if (name == null || name.isBlank() || mode == null || mode.isBlank()) return;
        Entry e = entry(name);
        if (mode.equals(e.mode()) && atMs - e.modeAt() < 60_000) return;
        if (atMs < e.modeAt()) return;
        put(new Entry(e.name(), e.lat(), e.lon(), e.placeAt(), e.phonePercent(), e.cellVolts(), e.batteryAt(), mode, atMs), atMs);
    }

    /** What is known of this phone, or null: nothing. */
    public synchronized Entry get(String name) {
        return name == null ? null : byId.get(id(name));
    }

    /** Every phone known, the one heard of last first. */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingLong((Entry e) -> Math.max(e.placeAt(), Math.max(e.modeAt(), e.batteryAt()))).reversed());
        return out;
    }

    /** A phone renamed: what was known of it goes with it. */
    public synchronized void rename(String from, String to, long now) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        Entry e = byId.remove(id(from));
        if (e == null) return;
        put(new Entry(to.trim(), e.lat(), e.lon(), e.placeAt(), e.phonePercent(), e.cellVolts(), e.batteryAt(), e.mode(), e.modeAt()), now);
    }

    public synchronized void forget(String name, long now) {
        if (name != null && byId.remove(id(name)) != null) {
            if (!dirty) dirtySince = now;
            dirty = true;
        }
    }

    /** Once a second or so: what changed {@link #SAVE_AFTER_MS} ago or longer is written (off the caller's thread). */
    public void saveIfDue(long now) {
        List<String> lines;
        synchronized (this) {
            if (file == null || !dirty || now - dirtySince < SAVE_AFTER_MS) return;
            dirty = false;
            lines = lines();
        }
        Thread t = new Thread(() -> write(lines), "lastKnownWriter");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Written now, on this thread: at the app's end. */
    public void flush() {
        List<String> lines;
        synchronized (this) {
            if (file == null || !dirty) return;
            dirty = false;
            lines = lines();
        }
        write(lines);
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Entry e : byId.values()) out.add(format(e));
        return out;
    }

    private synchronized void write(List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            synchronized (this) {
                dirty = true; // tried again at the next change
            }
        }
    }

    static String format(Entry e) {
        return String.join("\t", e.name().replace('\t', ' '), num(e.lat(), 7), num(e.lon(), 7), Long.toString(e.placeAt()),
                Integer.toString(e.phonePercent()), num(e.cellVolts(), 2), Long.toString(e.batteryAt()), e.mode().replace('\t', ' '), Long.toString(e.modeAt()));
    }

    private static String num(double v, int decimals) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    static Entry parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] c = line.split("\t", -1);
        if (c.length < 9 || c[0].isBlank()) return null;
        try {
            return new Entry(c[0].trim(), c[1].isEmpty() ? Double.NaN : Double.parseDouble(c[1]), c[2].isEmpty() ? Double.NaN : Double.parseDouble(c[2]),
                    Long.parseLong(c[3]), Integer.parseInt(c[4]), c[5].isEmpty() ? Double.NaN : Double.parseDouble(c[5]), Long.parseLong(c[6]),
                    c[7], Long.parseLong(c[8]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "12 MIN", "3 H", "2 D"; under a minute "NOW". */
    public static String age(long ms) {
        long min = Math.max(0, ms) / 60_000;
        if (min < 1) return "NOW";
        if (min < 60) return min + " MIN";
        if (min < 48 * 60) return min / 60 + " H";
        return min / (24 * 60) + " D";
    }

    /** "WAS OVERWATCH 12 MIN": the last mode and how long since the phone was heard; null without a mode. */
    public static String wasText(String mode, long sinceHeardMs) {
        if (mode == null || mode.isBlank()) return null;
        return "WAS " + mode + (sinceHeardMs < 0 ? "" : " " + age(sinceHeardMs));
    }

    /** The mode's word as the pilot reads it, from FLEET's badge: null for a badge that confirms no mode. */
    public static String modeWord(String badge) {
        if (badge == null) return null;
        return switch (badge) {
            case "CONNECTED" -> "PILOTED";
            case "ACTIVE" -> "IDLE";
            case "STANDBY" -> "STANDBY";
            case "ASLEEP" -> "DEEP SLEEP";
            case "OVERWATCH" -> "OVERWATCH";
            default -> null;
        };
    }
}
