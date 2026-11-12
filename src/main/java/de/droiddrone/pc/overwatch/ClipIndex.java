package de.droiddrone.pc.overwatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The overwatch clips on this PC (1.0.47): Documents\DRAUPNE Link\Overwatch\&lt;drone&gt;\, one event each -
 * {@code <id>.properties} (when, how long, where the drone stood and looked), {@code <id>-pre-NN.jpg} (the 5 s before
 * the alert), {@code <id>-preview.mkv} (the live preview as it came) and {@code <id>.mp4} (the phone's full-quality
 * clip, once it is here and checked). The id is the event's start, yyyyMMdd-HHmmss. The pilot's rules: nothing is
 * deleted by the app - REMOVE only hides a clip from the list ({@link #HIDDEN_FILE} in the drone's folder), the files
 * stay for Explorer.
 */
public final class ClipIndex {
    public static final String HIDDEN_FILE = "hidden.txt";
    static final String META = ".properties";
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Path root;

    public ClipIndex(Path root) {
        this.root = root;
    }

    /** Documents\DRAUPNE Link\Overwatch (not created until a clip comes). */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Overwatch");
    }

    public Path root() {
        return root;
    }

    /** An event's id: its start in this PC's time zone; another event in the same second gets -2, -3 ... */
    public String newId(String drone, long startedAt) throws IOException {
        String base = ID.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()));
        Path dir = folder(drone);
        String id = base;
        for (int n = 2; Files.exists(dir.resolve(id + META)); n++) id = base + "-" + n;
        return id;
    }

    /** An event's id without the -2, -3 of a second event in the same second: what {@link #newId} starts from. */
    public static String baseId(long startedAt) {
        return ID.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()));
    }

    /** REMOVE was pressed for this clip: off the list (its files stay). */
    public boolean isHidden(String drone, String id) {
        return hidden(root.resolve(safe(drone))).contains(id);
    }

    /** The drone's folder (made when missing). */
    public Path folder(String drone) throws IOException {
        return Files.createDirectories(root.resolve(safe(drone)));
    }

    /** A drone's name as a folder name: letters, digits, space, dot, dash and underscore; the rest becomes _. */
    static String safe(String drone) {
        String s = drone == null || drone.isBlank() ? "drone" : drone.trim();
        s = s.replaceAll("[^A-Za-z0-9 ._-]", "_");
        return s.startsWith(".") ? "_" + s.substring(1) : s;
    }

    /** One clip on the list. */
    public record Clip(String drone, String id, Path folder, Properties meta) {
        public long startedAt() {
            return longOf("startedAt", 0);
        }

        /** The full clip's length when known (every part here, added up), else the preview's, else from start to end. */
        public long durationMs() {
            long d = 0;
            for (Path p : parts()) d += longOf("clipDurationMs" + partSuffix(partOf(p)), 0);
            if (d <= 0) d = longOf("clipDurationMs", 0);
            if (d <= 0) d = longOf("previewDurationMs", 0);
            if (d <= 0) {
                long end = longOf("endedAt", 0);
                if (end > startedAt()) d = end - startedAt();
            }
            return d;
        }

        public double lat() {
            return doubleOf("lat");
        }

        public double lon() {
            return doubleOf("lon");
        }

        public boolean hasPosition() {
            return !Double.isNaN(lat()) && !Double.isNaN(lon());
        }

        public double doubleOf(String key) {
            try {
                return Double.parseDouble(meta.getProperty(key, "NaN"));
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        public long longOf(String key, long fallback) {
            try {
                return Long.parseLong(meta.getProperty(key, ""));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** The phone's full-quality clip, when it is here and checked. */
        public Path fullClip() {
            List<Path> p = parts();
            return p.isEmpty() ? null : p.get(0);
        }

        /**
         * The phone's full-quality clip in its parts (pc.38 records an event in 60 s segments: id.mp4, id-part2.mp4 ...),
         * those here, in order; empty when none came yet.
         */
        public List<Path> parts() {
            List<Path> out = new ArrayList<>();
            Path first = folder.resolve(id + ".mp4");
            if (Files.isRegularFile(first)) out.add(first);
            try (DirectoryStream<Path> s = Files.newDirectoryStream(folder, id + "-part*.mp4")) {
                for (Path p : s) if (partOf(p) > 1) out.add(p);
            } catch (IOException ignored) {
            }
            out.sort(Comparator.comparingInt(ClipIndex::partOf));
            return out;
        }

        /** The live preview this PC recorded while the clip was on its way, or null. */
        public Path preview() {
            Path p = folder.resolve(id + "-preview.mkv");
            return Files.isRegularFile(p) ? p : null;
        }

        /** What to play: the full clip, else the preview. */
        public Path video() {
            Path full = fullClip();
            return full != null ? full : preview();
        }

        /** The pictures of the 5 s before the alert, oldest first. */
        public List<Path> preRoll() {
            List<Path> out = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(folder, id + "-pre-*.jpg")) {
                for (Path p : s) out.add(p);
            } catch (IOException ignored) {
            }
            out.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return out;
        }

        /** The list's picture: the first frame of the alert (the last pre-roll picture), or null. */
        public Path thumbnail() {
            Path t = folder.resolve(id + "-thumb.jpg");
            if (Files.isRegularFile(t)) return t;
            List<Path> pre = preRoll();
            return pre.isEmpty() ? null : pre.get(pre.size() - 1);
        }
    }

    /** A part's file: the first is id.mp4 (as before segments), later ones id-partN.mp4. */
    public static String partFile(String id, int part) {
        return part <= 1 ? id + ".mp4" : id + "-part" + part + ".mp4";
    }

    /** A part's facts carry this after their key (clipSha256.2 ...); nothing for the first. */
    public static String partSuffix(int part) {
        return part <= 1 ? "" : "." + part;
    }

    /** The part number of a part's file (1 for id.mp4). */
    static int partOf(Path p) {
        String n = p.getFileName().toString();
        int at = n.lastIndexOf("-part");
        if (at < 0 || !n.endsWith(".mp4")) return 1;
        try {
            return Integer.parseInt(n.substring(at + 5, n.length() - 4));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The drones that have a folder here, by name. */
    public List<String> drones() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path p : s) out.add(p.getFileName().toString());
        } catch (IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The drone's clips, newest first, without the removed ones. */
    public List<Clip> clips(String drone) {
        return clips(drone, false);
    }

    /**
     * The drone's clips, newest first; the removed ones too with withRemoved (1.0.49: the hub looks an event up among
     * them, so a removed event's clip arriving later doesn't bring it back as a new one).
     */
    public List<Clip> clips(String drone, boolean withRemoved) {
        List<Clip> out = new ArrayList<>();
        Path dir = root.resolve(safe(drone));
        if (!Files.isDirectory(dir)) return out;
        Set<String> hidden = withRemoved ? Set.of() : hidden(dir);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*" + META)) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - META.length());
                if (hidden.contains(id)) continue;
                Properties meta = read(p);
                if (meta != null) out.add(new Clip(drone, id, dir, meta));
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong(Clip::startedAt).reversed().thenComparing(Clip::id, Comparator.reverseOrder()));
        return out;
    }

    public Clip clip(String drone, String id) {
        Path dir = root.resolve(safe(drone));
        Properties meta = read(dir.resolve(id + META));
        return meta == null ? null : new Clip(drone, id, dir, meta);
    }

    /** REMOVE: off the list for good; the files stay. */
    public synchronized void hide(String drone, String id) throws IOException {
        Path dir = folder(drone);
        Set<String> hidden = hidden(dir);
        if (!hidden.add(id)) return;
        Files.writeString(dir.resolve(HIDDEN_FILE), String.join(System.lineSeparator(), hidden) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Set<String> hidden(Path dir) {
        Set<String> out = new HashSet<>();
        Path f = dir.resolve(HIDDEN_FILE);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line.trim());
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Writes (or rewrites) an event's facts. */
    public synchronized void save(String drone, String id, Properties meta) throws IOException {
        Path f = folder(drone).resolve(id + META);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            meta.store(out, "DRAUPNE Link overwatch event");
        }
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** The bytes in a drone's folder (null: all drones), removed clips included - they are still on the disk. */
    public long folderBytes(String drone) {
        Path dir = drone == null ? root : root.resolve(safe(drone));
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Properties read(Path p) {
        if (!Files.isRegularFile(p)) return null;
        Properties meta = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            meta.load(in);
            return meta;
        } catch (IOException e) {
            return null;
        }
    }

    /** "12.3 MB" and the like. */
    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
}
