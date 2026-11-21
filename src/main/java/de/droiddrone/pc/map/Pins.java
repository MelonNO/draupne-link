package de.droiddrone.pc.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pins the pilot drops on the map (right-click > Add pin): named Alpha, Bravo ... Zulu, then Alpha2, Bravo2 ...
 * (no "1": Alpha and Alpha1 would be confused). Kept between sessions in Documents\DRAUPNE Link\pins.txt, one per
 * line, its parts split by tabs: name, latitude, longitude - and two that older files don't have: when it was dropped
 * (epoch ms, 0 unknown) and 1 for a rough one. A file with three parts a line reads as before; an older app reads
 * the first three and takes no notice of the rest (and writes three again at its next save). Thread-safe.
 */
public final class Pins {
    /**
     * createdMs: when it was dropped, on the wall clock (0: unknown - a pin from an older file); a flight's marks
     * are the pins dropped while it flew. rough: placed from the video on weak geometry - a place to look around,
     * not a point.
     */
    public record Pin(String name, double lat, double lon, long createdMs, boolean rough) {
        /** A pin whose time isn't known. */
        public Pin(String name, double lat, double lon) {
            this(name, lat, lon, 0, false);
        }
    }

    static final String[] NATO = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"};

    private final Path file;
    private final List<Pin> pins = new ArrayList<>();

    /** @param file where they are kept; null keeps them in memory only */
    public Pins(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            boolean broken = false;
            try {
                for (String line : readLines(file)) {
                    if (line.isBlank()) continue;
                    String[] p = line.split("\t");
                    try {
                        if (p.length < 3) throw new NumberFormatException(line);
                        pins.add(new Pin(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                                p.length > 3 ? createdMs(p[3]) : 0, p.length > 4 && "1".equals(p[4].trim())));
                    } catch (NumberFormatException e) {
                        broken = true; // a line that isn't a pin: kept below, not lost at the next save
                    }
                }
            } catch (java.nio.charset.CharacterCodingException e) {
                broken = true; // not text this app wrote (not UTF-8): nothing read
            } catch (IOException e) {
                // There, but not to be read just now (another program holds it, the folder does not answer): that
                // is not a broken file. Until 2026-11-20 it was moved aside as one and the app started without the
                // pilot's pins; now it is left where it is, never written over, and the pilot is told
                pins.clear();
                notRead = de.droiddrone.pc.DiskSpace.why(e);
                keepOff = true;
            }
            if (broken) {
                // 1.0.49 (M12): the file is moved aside whole before the next save writes over it; the pins read stay
                brokenMovedTo = MapGraphics.moveAside(file);
                keepOff = brokenMovedTo == null;
            }
        }
    }

    /** The fourth part of a line; anything that isn't a time is "unknown" - the pin itself is good. */
    private static long createdMs(String s) {
        try {
            return Math.max(0, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Path brokenMovedTo;
    private boolean keepOff;
    private String notRead;
    private volatile java.util.function.Consumer<String> onProblem;
    private boolean saveFailing;

    /** Why the file, though there, could not be read at the start (no pin is shown, nothing is written over it); null when it was. */
    public String notRead() {
        return notRead;
    }

    /** Told (on the caller's thread) when the pins stop being saved, once, and when they are saved again. */
    public void onProblem(java.util.function.Consumer<String> listener) {
        onProblem = listener;
    }

    /** The file's lines; a read that fails for another reason than its text is tried three times, a moment apart. */
    private static List<String> readLines(Path file) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (java.nio.charset.CharacterCodingException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /** Where a file with lines that couldn't be read was moved at start (pins.txt.broken-...), or null. */
    public Path brokenMovedTo() {
        return brokenMovedTo;
    }

    public static Path defaultFile() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link")).resolve("pins.txt");
    }

    /** The next free name: the alphabet, then Alpha2, Bravo2 ... Alpha3 ... */
    public synchronized String nextName() {
        for (int round = 1; ; round++) {
            for (String n : NATO) {
                String name = round == 1 ? n : n + round; // Alpha, then Alpha2 (never Alpha1)
                if (!has(name)) return name;
            }
        }
    }

    private boolean has(String name) {
        for (Pin p : pins) if (p.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    public synchronized Pin add(String name, double lat, double lon) {
        return add(name, lat, lon, false);
    }

    /** A pin dropped now. @param rough placed from the video on weak geometry */
    public synchronized Pin add(String name, double lat, double lon, boolean rough) {
        Pin p = new Pin(name == null || name.isBlank() ? nextName() : oneLine(name), lat, lon, System.currentTimeMillis(), rough);
        pins.add(p);
        save();
        return p;
    }

    /**
     * A name on one line: pins.txt keeps a pin a line, its parts split by tabs - a KML's name over two lines split the
     * pin, and the next start moved the whole file aside as broken.
     */
    static String oneLine(String name) {
        return name.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    public synchronized Pin rename(Pin pin, String name) {
        int i = pins.indexOf(pin);
        if (i < 0 || name == null || name.isBlank()) return pin;
        Pin renamed = new Pin(oneLine(name), pin.lat(), pin.lon(), pin.createdMs(), pin.rough());
        pins.set(i, renamed);
        save();
        return renamed;
    }

    public synchronized void remove(Pin pin) {
        if (pins.remove(pin)) save();
    }

    /** Every pin off (the map tools' CLEAR ALL). */
    public synchronized void clear() {
        if (pins.isEmpty()) return;
        pins.clear();
        save();
    }

    public synchronized List<Pin> list() {
        return new ArrayList<>(pins);
    }

    /** The pin nearest a place, or null without pins. */
    public synchronized Pin nearest(double lat, double lon) {
        Pin best = null;
        double bestM = Double.MAX_VALUE;
        for (Pin p : pins) {
            double m = Mercator.distanceM(lat, lon, p.lat(), p.lon());
            if (m < bestM) {
                bestM = m;
                best = p;
            }
        }
        return best;
    }

    /** The pins a search box finds, whatever format the list shows their places in: see {@link #matches}. */
    public synchronized List<Pin> matching(String query) {
        return matching(query, null);
    }

    /** The pins a search box finds, in the list's order. @param shown the format the list shows; null: any of them */
    public synchronized List<Pin> matching(String query, Coordinates.Format shown) {
        List<Pin> out = new ArrayList<>();
        for (Pin p : pins) if (matches(p, query, shown)) out.add(p);
        return out;
    }

    /**
     * Whether a search box's text finds this pin: in its name, or in its place as the pins list writes it
     * ({@link Coordinates#format}) - upper or lower case, and a grid with or without its spaces ("32vnm97" finds
     * 32V NM 97254 42617). Nothing typed finds every pin.
     * @param shown the format the list shows; null: any of them
     */
    public static boolean matches(Pin p, String query, Coordinates.Format shown) {
        String q = query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (p.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        String packed = q.replace(" ", "");
        for (Coordinates.Format f : shown == null ? Coordinates.Format.values() : new Coordinates.Format[]{shown}) {
            String place = Coordinates.format(f, p.lat(), p.lon()).toLowerCase(Locale.ROOT);
            if (place.contains(q) || place.replace(" ", "").contains(packed)) return true;
        }
        return false;
    }

    /** The pins as a GPX file's waypoints: for another map program or a phone. */
    public synchronized String toGpx() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"DRAUPNE Link " + de.droiddrone.pc.AppInfo.version() + "\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (Pin p : pins) {
            sb.append(String.format(Locale.ROOT, " <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>%n", p.lat(), p.lon(), FlightTrack.xml(p.name())));
        }
        return sb.append("</gpx>\n").toString();
    }

    /** Writes {@link #toGpx} whole, then moves it into place. */
    public void exportGpx(Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(tmp, toGpx(), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void save() {
        if (file == null || keepOff) return;
        StringBuilder sb = new StringBuilder();
        for (Pin p : pins) sb.append(p.name().replace('\t', ' ')).append('\t')
                .append(String.format(Locale.ROOT, "%.7f", p.lat())).append('\t').append(String.format(Locale.ROOT, "%.7f", p.lon()))
                .append('\t').append(p.createdMs()).append('\t').append(p.rough() ? 1 : 0).append('\n');
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (saveFailing) {
                saveFailing = false;
                tell("Pins saved again.");
            }
        } catch (IOException e) {
            // the pins stay in memory for this session - and the pilot is told, once: they were gone at the next
            // start without a word
            if (!saveFailing) {
                saveFailing = true;
                tell("PINS NOT SAVED: " + de.droiddrone.pc.DiskSpace.why(e));
            }
        }
    }

    private void tell(String text) {
        java.util.function.Consumer<String> to = onProblem;
        if (to == null) return;
        try {
            to.accept(text);
        } catch (RuntimeException ignored) {
        }
    }
}
