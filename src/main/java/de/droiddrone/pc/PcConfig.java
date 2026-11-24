package de.droiddrone.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PcConfig {
    public static final String FILE_NAME = "draupne-link.properties";
    public static final String TEMPLATE_RESOURCE = "/draupne-link.example.properties";
    // DroidDrone sends RC channels in AETR order followed by AUX channels (see upstream Control Config.rcChannelsMap).
    public static final String[] CHANNEL_NAMES = {
            "roll", "pitch", "yaw", "throttle",
            "aux1", "aux2", "aux3", "aux4", "aux5", "aux6", "aux7",
            "aux8", "aux9", "aux10", "aux11", "aux12", "aux13", "aux14"
    };
    public static final int THROTTLE_INDEX = 3;
    private static final Pattern AXIS_CALIBRATION_KEY = Pattern.compile("axis(\\d+)\\.(min|center|max)");

    private final Properties props;

    private PcConfig(Properties props) {
        this.props = props;
    }

    public static PcConfig load(Path file) throws IOException {
        Properties props = new Properties(loadTemplate());
        if (file != null && Files.exists(file)) {
            for (String line : readLines(file)) {
                String[] kv = parseLine(line);
                if (kv != null) props.setProperty(kv[0], kv[1]);
            }
        }
        return new PcConfig(props);
    }

    /**
     * The file's lines whatever wrote it (1.0.71): UTF-8 as this app writes it; when a byte is not UTF-8 - "Bjørn"
     * saved by Notepad as ANSI, a byte a crash left - Windows' own code page instead, which reads any byte. Up to
     * 1.0.70 one such byte stopped the app starting, before it had a window or a log to say so. A UTF-8 mark at the
     * start (Notepad's "UTF-8 with BOM") is no part of the first key.
     */
    static List<String> readLines(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        return text.lines().toList();
    }

    /**
     * One line of the settings file: {key, value}, or null for a comment or a blank line (1.0.49). This app writes
     * java.util.Properties' own escaping ({@link #escape}), but before 1.0.49 values were written as they came, so a
     * saved Windows path reads C:\Users\me - which java.util.Properties turned into "C:Usersme", and a folder whose name starts with u
     * after the backslash into an exception that stopped the app starting. So a value is decoded only when every backslash in it starts
     * an escape this app writes ({@link #unescape}); otherwise it is taken as it stands. No line goes on onto the
     * next (an old path ending in a backslash would have swallowed the line after it).
     */
    static String[] parseLine(String raw) {
        String line = stripLeading(raw);
        if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') return null;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                i += 2;
                continue;
            }
            if (c == '=' || c == ':' || isBlank(c)) break;
            i++;
        }
        String key = line.substring(0, i);
        while (i < line.length() && isBlank(line.charAt(i))) i++;
        if (i < line.length() && (line.charAt(i) == '=' || line.charAt(i) == ':')) i++;
        return new String[]{unescape(key), unescape(stripLeading(line.substring(i)))};
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && isBlank(s.charAt(i))) i++;
        return s.substring(i);
    }

    /**
     * The text with java.util.Properties escapes decoded, when it is written in the ones this app writes
     * (backslash followed by a backslash, ':', '=', '#', '!', a space, or u and four hex digits); any other backslash (\U, \t, \me ...) means a raw value from before
     * 1.0.49 - a Windows path - and the text is returned as it stands.
     */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= s.length()) return s; // a lone backslash at the end
            char n = s.charAt(++i);
            if (n == '\\' || n == ':' || n == '=' || n == '#' || n == '!' || n == ' ') {
                out.append(n);
            } else if (n == 'u' && isHex(s, i + 1)) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                return s;
            }
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from) {
        if (from + 4 > s.length()) return false;
        for (int i = from; i < from + 4; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /**
     * A key or a value in java.util.Properties' escaping: backslash, ':', '=', '#' and '!' escaped, a key's spaces
     * and a value's first one too, and every control character, line break or line separator as a backslash-u escape -
     * so no value can ever start a line of its own. java.util.Properties reads it back the same.
     */
    static String escape(String s, boolean key) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ':' || c == '=' || c == '#' || c == '!') {
                out.append('\\').append(c);
            } else if (c == ' ' && (key || i == 0)) {
                out.append("\\ ");
            } else if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '\u2028' || c == '\u2029') {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static PcConfig defaults() throws IOException {
        return load(null);
    }

    private static Properties loadTemplate() throws IOException {
        Properties template = new Properties();
        try (InputStream in = PcConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            template.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return template;
    }

    public static void writeTemplate(Path file) throws IOException {
        try (InputStream in = PcConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            Files.copy(in, file);
        }
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    /**
     * Applies updates in memory and writes them to file, keeping its comments and layout:
     * existing "key=" lines are replaced in place, new keys are appended. A missing file starts from the template.
     */
    /** Synchronized: saves can come from the Swing thread and from a background one (the OSD editor's). */
    public synchronized void save(Path file, Map<String, String> updates) throws IOException {
        // 1.0.64: this run has the change whatever the disk says (it was lost from memory too when the write failed)
        apply(updates);
        write(file, updates);
    }

    /**
     * 1.0.64: the updates in memory only, at once - getString sees them from here on. The window sets them so and
     * hands the file to its writer thread ({@link #write}), so no save reads and writes the file on the Swing thread.
     */
    public void apply(Map<String, String> updates) {
        updates.forEach(props::setProperty);
    }

    /**
     * 1.0.64: the updates into the file only (memory has them: {@link #apply}) - a write queued behind a newer apply
     * must not put its older value back in memory. Calls in the order of their applies (one writer thread).
     */
    public synchronized void write(Path file, Map<String, String> updates) throws IOException {
        // Together with what an earlier write could not put in the file (a review of 2026-11-20): a save that failed -
        // the file held by another program through every try, the disk full - was said once in the log and never
        // tried again. Later saves write their own keys only, so that one change (a calibration, say) was back at
        // its old value at the next start
        Map<String, String> all = new LinkedHashMap<>(unwritten);
        all.putAll(updates);
        try {
            writeNow(file, all);
            unwritten.clear();
        } catch (IOException | RuntimeException e) {
            unwritten.clear();
            unwritten.putAll(all);
            throw e;
        }
    }

    /** What a write could not put in the file: it goes with the next one. Guarded by this. */
    private final Map<String, String> unwritten = new LinkedHashMap<>();

    private void writeNow(Path file, Map<String, String> updates) throws IOException {
        List<String> lines;
        if (Files.exists(file)) {
            lines = new ArrayList<>(readLines(file));
        } else {
            try (InputStream in = PcConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
                if (in == null) throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
                lines = new ArrayList<>(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
            }
        }
        Map<String, String> pending = new LinkedHashMap<>(updates);
        for (int i = 0; i < lines.size(); i++) {
            String[] kv = parseLine(lines.get(i));
            if (kv == null) continue;
            if (updates.containsKey(kv[0])) {
                // every line of the key (a hand edit may have it twice: the last is the one read)
                lines.set(i, line(kv[0], updates.get(kv[0])));
                pending.remove(kv[0]);
            } else if (lines.get(i).indexOf('\\') >= 0 && !lines.get(i).trim().equals(line(kv[0], kv[1]))) {
                lines.set(i, line(kv[0], kv[1])); // a raw line from before 1.0.49: escaped now, as it was read
            }
        }
        if (!pending.isEmpty()) {
            lines.add("");
            pending.forEach((key, value) -> lines.add(line(key, value)));
        }
        // whole, then moved into place: a crash or a power cut mid-write never leaves half a settings file
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        // 1.0.64: the move is tried again: on Windows a virus scanner or an editor holding the file a moment refuses it
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        // on the disk before it takes the file's place: after a power cut the new name can be there and its bytes
        // not - a settings file of nothing, read as "all defaults" without a word
        try (java.nio.channels.FileChannel made = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
            made.force(true);
        } catch (IOException ignored) {
            // a disk that cannot promise it: the move goes ahead as before
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(40);
                } catch (InterruptedException i) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private static String line(String key, String value) {
        return escape(key, true) + "=" + escape(value == null ? "" : value, false);
    }

    /** Calibrations written by the calibration wizard (axisN.min / axisN.center / axisN.max). */
    public Map<Integer, AxisCalibration> getAxisCalibrations() {
        Map<Integer, float[]> found = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            Matcher m = AXIS_CALIBRATION_KEY.matcher(key);
            if (!m.matches()) continue;
            float[] values = found.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new float[]{Float.NaN, Float.NaN, Float.NaN});
            int slot = switch (m.group(2)) {
                case "min" -> 0;
                case "center" -> 1;
                default -> 2;
            };
            try {
                values[slot] = Float.parseFloat(getString(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Setting '" + key + "' must be a number");
            }
        }
        Map<Integer, AxisCalibration> calibrations = new HashMap<>();
        found.forEach((axis, v) -> {
            if (!Float.isNaN(v[0]) && !Float.isNaN(v[1]) && !Float.isNaN(v[2])) {
                calibrations.put(axis, new AxisCalibration(v[0], v[1], v[2]));
            }
        });
        return calibrations;
    }

    public RcMapper createMapper() {
        return new RcMapper(getChannelSpecs(), getAxisCalibrations());
    }

    /** Every key with a value: the file's and the template's (the name map's name.* keys among them). */
    public synchronized java.util.Set<String> keys() {
        return props.stringPropertyNames();
    }

    public String getString(String key) {
        String value = props.getProperty(key);
        return value == null ? "" : value.trim();
    }

    public int getInt(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Setting '" + key + "' must be a number, got '" + value + "'");
        }
    }

    public String getIp() { return getString("ip"); }
    public int getPort() { return getInt("port"); }
    public String getKey() { return getString("key"); }
    public String getJoystickFilter() { return getString("joystick"); }
    static final int SETTINGS_VERSION = 5;

    /**
     * Moves a settings file written by an older version to the current defaults, once per version (it then records
     * settingsVersion), so a value changed deliberately afterwards is left alone. Only old defaults are replaced.
     * Version 2: rcRate 100 -> 250 (less stick delay). Version 3: telemetryRate 10 -> 20 (OSD lags half as much).
     * Version 4: nothing left (it moved the camera's frame rate, which is no setting any more).
     * Version 5: bitrateLimitMbps 6 -> 4 (as good a 720p picture at 30 fps, more headroom before a 4G queue fills).
     * <p>
     * A file that cannot be written now - a full disk, a file held by another program - does not stop the start: the
     * app runs with the new values, and they go into the file with the next save that can write it. Until 2026-11-24
     * (the test bench's full disk) the write's exception ended the start, and the app did not open at all.
     * @return true if the settings were brought up to date (in the file, or with the next save)
     */
    public boolean upgrade(Path file) throws IOException {
        if (file == null || !Files.exists(file)) return false;
        int version;
        try {
            version = getString("settingsVersion").isEmpty() ? 1 : Integer.parseInt(getString("settingsVersion"));
        } catch (NumberFormatException e) {
            version = 1;
        }
        if (version >= SETTINGS_VERSION) return false;
        Map<String, String> updates = new LinkedHashMap<>();
        if (version < 2 && "100".equals(getString("rcRate"))) updates.put("rcRate", "250");
        if (version < 3 && "10".equals(getString("telemetryRate"))) updates.put("telemetryRate", "20");
        if (version < 5 && "6".equals(getString("bitrateLimitMbps"))) updates.put("bitrateLimitMbps", "4");
        updates.put("settingsVersion", Integer.toString(SETTINGS_VERSION));
        try {
            save(file, updates);
        } catch (IOException e) {
            // in memory already (save applies first) and kept for the next write (unwritten)
        }
        return true;
    }

    public int getRcRate() {
        int rate = getInt("rcRate");
        if (rate < ControlLink.MIN_RC_RATE || rate > ControlLink.MAX_RC_RATE) {
            throw new IllegalArgumentException("rcRate must be " + ControlLink.MIN_RC_RATE + ".." + ControlLink.MAX_RC_RATE);
        }
        return rate;
    }
    /**
     * radio.frozenStopS: armed and the radio's input unchanged for this many seconds, the sticks stop so Betaflight's
     * failsafe takes over (RcSession.setFrozenStopMs). 0 or missing = never (the default).
     */
    public int getRadioFrozenStopS() {
        return optionalInt("radio.frozenStopS", 0, 0, 600);
    }

    /**
     * rc.maxLagMs: a stick frame that reaches the phone this much later than the link's usual (phone app pc.31 on) is
     * dropped instead of flown - Betaflight fails safe rather than flying seconds behind. 0 or missing = never drop
     * (the default; the OSD still says STICKS LATE). At most 2550 (the frame carries it in 10 ms steps).
     */
    public int getMaxStickLagMs() {
        return optionalInt("rc.maxLagMs", 0, 0, 2550);
    }

    private int optionalInt(String key, int fallback, int min, int max) {
        String value = getString(key);
        if (value.isEmpty()) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The keys whose value was no number, and what was said of each: said once ({@link #takeNotes}). */
    private final java.util.Set<String> badNumbers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Queue<String> notes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile Properties builtIn;

    /**
     * A number the link and the window ask for again and again. The file's value; when that is no number - a slip of
     * the hand in the settings file, "cameraWidth=wide" - the built-in one, and a line for the log, once. F-025
     * (the test bench, 2026-11-19): with such a line PILOT UAV waited for the phone for ever, said nothing, and the
     * link's thread wrote the same error into the app's log 40,000 times a second - 6 MB a second.
     */
    private int linkInt(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            int fallback = 0;
            try {
                Properties template = builtIn;
                if (template == null) builtIn = template = loadTemplate();
                fallback = Integer.parseInt(template.getProperty(key, "0").trim());
            } catch (IOException | NumberFormatException ignored) {
            }
            if (badNumbers.add(key)) notes.add("Settings file: " + key + "=" + value + " is not a number. Using " + fallback + ".");
            return fallback;
        }
    }

    /** What the settings have to tell the pilot since the last call (a value that was no number): for the window's log. */
    public List<String> takeNotes() {
        List<String> taken = new java.util.ArrayList<>();
        for (String note = notes.poll(); note != null; note = notes.poll()) taken.add(note);
        return taken;
    }

    public int getTelemetryRate() { return linkInt("telemetryRate"); }
    public int getSerialBaudRate() { return linkInt("serialBaudRate"); }
    public String getCameraId() { return getString("cameraId"); }
    public int getCameraWidth() { return linkInt("cameraWidth"); }
    public int getCameraHeight() { return linkInt("cameraHeight"); }
    public int getBitrateLimitMbps() { return linkInt("bitrateLimitMbps"); }
    /** The sharp tier's picture size and top bitrate (1.0.34); equal to the normal ones = no sharp tier. */
    public int getSharpWidth() { return linkInt("sharpWidth"); }
    public int getSharpHeight() { return linkInt("sharpHeight"); }
    public int getSharpBitrateLimitMbps() { return linkInt("sharpBitrateLimitMbps"); }

    /** Channel source specs in AETR+AUX order; null where a channel is not configured. */
    public String[] getChannelSpecs() {
        String[] specs = new String[CHANNEL_NAMES.length];
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            String spec = getString(CHANNEL_NAMES[i]);
            specs[i] = spec.isEmpty() ? null : spec;
        }
        return specs;
    }
}
