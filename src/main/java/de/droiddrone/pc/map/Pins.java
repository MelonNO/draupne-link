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
 * line: name, latitude, longitude. Thread-safe.
 */
public final class Pins {
    public record Pin(String name, double lat, double lon) {}

    static final String[] NATO = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"};

    private final Path file;
    private final List<Pin> pins = new ArrayList<>();

    /** @param file where they are kept; null keeps them in memory only */
    public Pins(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t");
                    if (p.length < 3) continue;
                    try {
                        pins.add(new Pin(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
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
        Pin p = new Pin(name == null || name.isBlank() ? nextName() : name.trim(), lat, lon);
        pins.add(p);
        save();
        return p;
    }

    public synchronized Pin rename(Pin pin, String name) {
        int i = pins.indexOf(pin);
        if (i < 0 || name == null || name.isBlank()) return pin;
        Pin renamed = new Pin(name.trim(), pin.lat(), pin.lon());
        pins.set(i, renamed);
        save();
        return renamed;
    }

    public synchronized void remove(Pin pin) {
        if (pins.remove(pin)) save();
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

    private void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Pin p : pins) sb.append(p.name().replace('\t', ' ')).append('\t')
                .append(String.format(Locale.ROOT, "%.7f", p.lat())).append('\t').append(String.format(Locale.ROOT, "%.7f", p.lon())).append('\n');
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // the pins stay in memory for this session
        }
    }
}
