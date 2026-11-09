package de.droiddrone.pc.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the tour's finale leaves on this PC (1.0.52, the pilot: "clean up all the simulated drones and flights after the
 * tour"), noted as it happens so the tour's end can take it all off again and nothing of the pilot's own: the settings
 * that name a tour drone (its name map entry, its mute, its detection ...) as they were before, the files of a flight
 * flown to a tour drone (its link log, track and recordings, each noted by its exact path), the clips
 * of the tour's overwatch events (made in a tour drone's folder after the tour began), and last-position.txt as it was.
 */
final class TourTraces {
    /** Recordings made this long before a tour connection began or after it ended still count as its own. */
    static final long SLACK_MS = 5000;

    private final Set<String> names = new LinkedHashSet<>(); // lower case
    private final List<String> spelled = new ArrayList<>(); // as the drones call themselves
    private final long startedAt;
    /** The settings that name a tour drone, and the chosen phone and its address, as they were when the tour began. */
    private final Map<String, String> before = new HashMap<>();
    private final Set<Path> files = new LinkedHashSet<>();
    private final List<long[]> windows = new ArrayList<>();
    private long openSince;
    private final Path lastPosition;
    private final byte[] lastPositionBefore;
    private boolean flown;

    /**
     * @param settings the settings now (key -> value)
     * @param lastPosition last-position.txt (null: none to look after)
     */
    TourTraces(Collection<String> drones, long startedAt, Map<String, String> settings, Path lastPosition) {
        for (String d : drones) {
            if (names.add(d.toLowerCase(Locale.ROOT))) spelled.add(d);
        }
        this.startedAt = startedAt;
        settings.forEach((k, v) -> {
            if (watched(k)) before.put(k, v);
        });
        this.lastPosition = lastPosition;
        byte[] b = null;
        try {
            if (lastPosition != null && Files.exists(lastPosition)) b = Files.readAllBytes(lastPosition);
        } catch (IOException ignored) {
            // nothing to put back
        }
        lastPositionBefore = b;
    }

    boolean isTour(String drone) {
        return drone != null && names.contains(drone.toLowerCase(Locale.ROOT));
    }

    /** The tour's drones' names, as they spell them. */
    List<String> names() {
        return List.copyOf(spelled);
    }

    /** A key that the tour may change: one naming a tour drone, or the chosen phone and its address. */
    boolean watched(String key) {
        if (key.equals(de.droiddrone.pc.Names.PHONE_NAME_KEY) || key.equals("ip")) return true;
        String k = key.toLowerCase(Locale.ROOT);
        for (String n : names) if (k.contains(n)) return true;
        return false;
    }

    /** A connection to a tour drone began. */
    void connected(long now) {
        if (openSince == 0) openSince = now;
        flown = true;
    }

    /** That connection ended. */
    void disconnected(long now) {
        if (openSince == 0) return;
        windows.add(new long[]{openSince, now});
        openSince = 0;
    }

    boolean inConnection() {
        return openSince != 0;
    }

    /** A file the tour's flight made (its link log, its track). */
    void note(Path file) {
        if (file != null) files.add(file);
    }

    /**
     * The settings to write back: each watched key changed since the tour began, to what it was ("" for one that came
     * with the tour - an empty value is how this app's settings take a key off).
     */
    Map<String, String> settingsToRestore(Map<String, String> now) {
        Map<String, String> out = new LinkedHashMap<>();
        now.forEach((k, v) -> {
            if (!watched(k)) return;
            String was = before.get(k);
            if (was == null) {
                if (!v.isEmpty()) out.put(k, "");
            } else if (!was.equals(v)) {
                out.put(k, was);
            }
        });
        return out;
    }

    /**
     * The files to delete: those noted (the tour flight's link log, track and recordings, each by its exact path) and what the tour's
     * events left in a tour drone's clip folder under clipsRoot after the tour began.
     */
    List<Path> filesToDelete(Path videosDir, Path clipsRoot, long now) {
        if (openSince != 0) disconnected(now);
        Set<Path> out = new LinkedHashSet<>(files);
        if (clipsRoot != null) {
            for (String n : names) {
                Path dir = folder(clipsRoot, n);
                if (dir == null) continue;
                for (Path f : list(dir, true)) if (created(f) >= startedAt - SLACK_MS) out.add(f);
            }
        }
        out.removeIf(p -> !Files.isRegularFile(p));
        return new ArrayList<>(out);
    }

    /** A tour drone's clip folder under the root, as its name is spelled on disk; null when there is none. */
    private static Path folder(Path root, String lowerName) {
        for (Path d : list(root, false, true)) {
            if (d.getFileName().toString().toLowerCase(Locale.ROOT).equals(lowerName)) return d;
        }
        return null;
    }

    /** last-position.txt back as it was before the tour's flight (taken off when there was none). */
    void restoreLastPosition() throws IOException {
        if (!flown || lastPosition == null) return;
        if (lastPositionBefore == null) Files.deleteIfExists(lastPosition);
        else Files.write(lastPosition, lastPositionBefore);
    }

    private static long created(Path f) {
        try {
            return Files.readAttributes(f, BasicFileAttributes.class).creationTime().toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static List<Path> list(Path dir, boolean deep) {
        return list(dir, deep, false);
    }

    private static List<Path> list(Path dir, boolean deep, boolean dirs) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = deep ? Files.walk(dir) : Files.list(dir)) {
            return s.filter(p -> dirs ? Files.isDirectory(p) && !p.equals(dir) : Files.isRegularFile(p)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
