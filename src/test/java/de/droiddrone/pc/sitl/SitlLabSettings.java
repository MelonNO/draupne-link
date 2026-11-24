package de.droiddrone.pc.sitl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The SITL lab's settings: this PC's own file ({@code %LOCALAPPDATA%\DraupneBench\sitl-lab.settings}, made from the
 * repo's {@code sitl-lab.settings} the first time), lines {@code name = value}, {@code #} for a note. The script's
 * switches (-Phones, -Drones ...) go over the file's values for one run. What a stronger PC changes: phones,
 * fleetOnly, emulatorMemoryMb, emulatorCores, emulatorWindows.
 */
record SitlLabSettings(String phones, int fleetOnly, int emulatorMemoryMb, int emulatorCores, boolean emulatorWindows,
                       double lat, double lon, double alt, double spacingM, String apk) {
    /** The most of each kind the lab starts (SITL instances 60 - 89, phones 127.0.0.100 - 129). */
    static final int MAX_PHONES = 10, MAX_DRONES = 30;
    /** What auto keeps free for Windows, DRAUPNE Link and the SITL drones; each emulator takes its memory and this much more. */
    static final long KEEP_FREE_MB = 3072, EMULATOR_OVERHEAD_MB = 1024;

    static SitlLabSettings defaults() {
        return new SitlLabSettings("auto", 2, 2048, 2, false, Double.NaN, Double.NaN, Double.NaN, 25, "");
    }

    /** The file read (defaults for what it leaves out), then name=value overrides (the script's switches). */
    static SitlLabSettings read(Path file, Map<String, String> overrides) throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        if (file != null && Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int hash = line.indexOf('#');
                String l = (hash >= 0 ? line.substring(0, hash) : line).trim();
                int eq = l.indexOf('=');
                if (eq <= 0) continue;
                v.put(l.substring(0, eq).trim().toLowerCase(Locale.ROOT), l.substring(eq + 1).trim());
            }
        }
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) v.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim());
        }
        SitlLabSettings d = defaults();
        String phones = v.getOrDefault("phones", d.phones()).toLowerCase(Locale.ROOT);
        if (!phones.equals("auto")) phones = String.valueOf(clamp(integer(v, "phones", 0), 0, MAX_PHONES));
        return new SitlLabSettings(phones,
                clamp(integer(v, "fleetonly", d.fleetOnly()), 0, MAX_DRONES),
                clamp(integer(v, "emulatormemorymb", d.emulatorMemoryMb()), 1536, 16384),
                clamp(integer(v, "emulatorcores", d.emulatorCores()), 1, 16),
                Boolean.parseBoolean(v.getOrDefault("emulatorwindows", String.valueOf(d.emulatorWindows()))),
                number(v, "lat"), number(v, "lon"), number(v, "alt"),
                Math.max(15, number(v, "spacingm", d.spacingM())),
                v.getOrDefault("apk", d.apk()));
    }

    /**
     * How many emulator phones: the number given, or for auto as many as fit in the memory free now (each its own
     * memory and {@link #EMULATOR_OVERHEAD_MB} more, {@link #KEEP_FREE_MB} kept back), at least 1, at most
     * {@link #MAX_PHONES} - and never more than the PC has cores for (4 logical cores each, 4 kept).
     */
    int phoneCount(long freeMb, int logicalCores) {
        if (!phones.equals("auto")) return Integer.parseInt(phones);
        long byMemory = (freeMb - KEEP_FREE_MB) / (emulatorMemoryMb + EMULATOR_OVERHEAD_MB);
        long byCores = (logicalCores - 4) / 4;
        return (int) Math.max(1, Math.min(MAX_PHONES, Math.min(byMemory, byCores)));
    }

    boolean placeGiven() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    private static int integer(Map<String, String> v, String key, int dflt) {
        try {
            return v.containsKey(key) ? Integer.parseInt(v.get(key)) : dflt;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " = " + v.get(key) + " is not a whole number");
        }
    }

    private static double number(Map<String, String> v, String key) {
        return number(v, key, Double.NaN);
    }

    private static double number(Map<String, String> v, String key, double dflt) {
        String s = v.get(key);
        if (s == null || s.isBlank()) return dflt;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " = " + s + " is not a number");
        }
    }

    private static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
