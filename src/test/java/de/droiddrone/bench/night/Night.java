package de.droiddrone.bench.night;

import de.droiddrone.bench.Sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** What the night's long scenarios share: how long each runs, its table of figures for the report, sizes on disk. */
final class Night {
    private Night() {
    }

    /** This scenario's hours ({@code BENCH_HOURS}); a few minutes when none is given, to try it out. */
    static long runMs() {
        return (long) (Double.parseDouble(System.getProperty("bench.hours", "0.1")) * 3_600_000);
    }

    /** A table in the run's soak folder: the report draws a curve of every column. */
    static final class Table {
        private final Path file;

        Table(String name, String... columns) throws IOException {
            file = Files.createDirectories(Sandbox.runRoot().resolve("soak")).resolve(name + ".tsv");
            Files.writeString(file, String.join("\t", columns) + "\n", StandardCharsets.UTF_8);
        }

        void row(Object... values) {
            StringBuilder s = new StringBuilder();
            for (Object v : values) {
                if (s.length() > 0) s.append('\t');
                s.append(v instanceof Double d ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf(v));
            }
            try {
                Files.writeString(file, s.append('\n').toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // a row less in the table; the scenario goes on
            }
        }
    }

    /** The bytes of the files of a folder that end so (0 when there is none). */
    static long bytes(Path dir, String ending) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            long sum = 0;
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(ending)).toList()) sum += Files.size(p);
            return sum;
        } catch (IOException e) {
            return -1;
        }
    }

    static long count(Path dir, String ending) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(ending)).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /** The least-squares slope of the values, in their unit a step. */
    static double slope(List<Long> values) {
        int n = values.size();
        double meanX = (n - 1) / 2.0, meanY = values.stream().mapToLong(Long::longValue).average().orElse(0), up = 0, down = 0;
        for (int i = 0; i < n; i++) {
            up += (i - meanX) * (values.get(i) - meanY);
            down += (i - meanX) * (i - meanX);
        }
        return down == 0 ? 0 : up / down;
    }

    /** PREVIOUS FLIGHTS opened from the map window's MAP TOOLS > DATA. */
    static java.awt.Window openPreviousFlights(de.droiddrone.bench.Ui ui) {
        if (ui.has("POP OUT MAP")) ui.click("POP OUT MAP");
        ui.until("the map window", 15_000, () -> ui.window("DRAUPNE Link map") != null);
        de.droiddrone.bench.Ui.pause(700);
        if (!ui.has("PREVIOUS FLIGHTS")) {
            ui.click("MAP TOOLS");
            de.droiddrone.bench.Ui.pause(500);
            ui.click("DATA");
            de.droiddrone.bench.Ui.pause(700);
        }
        ui.click("PREVIOUS FLIGHTS");
        ui.until("the Previous flights window", 30_000, () -> ui.window("Previous flights") != null);
        de.droiddrone.bench.Ui.pause(2000);
        return ui.window("Previous flights");
    }

    /** The words after a heading on the showing windows ("DURATION" -> "2:31:07"); "" when it is not there. */
    static String after(de.droiddrone.bench.Ui ui, String heading) {
        List<String> texts = ui.texts();
        for (int i = 0; i + 1 < texts.size(); i++) if (texts.get(i).trim().equalsIgnoreCase(heading)) return texts.get(i + 1).trim();
        return "";
    }

    /** "2:31:07" or "4:05" as seconds; -1 when it is neither. */
    static long seconds(String clock) {
        try {
            String[] p = clock.trim().split(":");
            long s = 0;
            for (String part : p) s = s * 60 + Long.parseLong(part.trim());
            return p.length >= 2 && p.length <= 3 ? s : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
