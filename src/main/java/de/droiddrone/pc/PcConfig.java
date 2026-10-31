package de.droiddrone.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return new PcConfig(props);
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
        List<String> lines;
        if (Files.exists(file)) {
            lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } else {
            try (InputStream in = PcConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
                if (in == null) throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
                lines = new ArrayList<>(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
            }
        }
        Map<String, String> pending = new LinkedHashMap<>(updates);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#") || !line.contains("=")) continue;
            String key = line.substring(0, line.indexOf('=')).trim();
            if (pending.containsKey(key)) lines.set(i, key + "=" + pending.remove(key));
        }
        if (!pending.isEmpty()) {
            lines.add("");
            pending.forEach((key, value) -> lines.add(key + "=" + value));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        updates.forEach(props::setProperty);
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

    public LinkMode getLinkMode() { return LinkMode.fromKey(getString("linkMode")); }
    public String getIp() { return getString("ip"); }
    public int getPort() { return getInt("port"); }
    public String getKey() { return getString("key"); }
    public String getJoystickFilter() { return getString("joystick"); }
    static final int SETTINGS_VERSION = 4;

    /**
     * Moves a settings file written by an older version to the current defaults, once per version (it then records
     * settingsVersion), so a value changed deliberately afterwards is left alone. Only old defaults are replaced.
     * Version 2: rcRate 100 -> 250 (less stick delay). Version 3: telemetryRate 10 -> 20 (OSD lags half as much).
     * Version 4: camera 30-60 fps -> a steady 60 (in dim light the camera dropped to 30 and back: jumpy motion).
     * @return true if the file was changed
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
        if (version < 4 && "30".equals(getString("cameraFpsMin")) && "60".equals(getString("cameraFpsMax"))) {
            updates.put("cameraFpsMin", "60");
        }
        updates.put("settingsVersion", Integer.toString(SETTINGS_VERSION));
        save(file, updates);
        return true;
    }

    public int getRcRate() {
        int rate = getInt("rcRate");
        if (rate < ControlLink.MIN_RC_RATE || rate > ControlLink.MAX_RC_RATE) {
            throw new IllegalArgumentException("rcRate must be " + ControlLink.MIN_RC_RATE + ".." + ControlLink.MAX_RC_RATE);
        }
        return rate;
    }
    public int getTelemetryRate() { return getInt("telemetryRate"); }
    public int getSerialBaudRate() { return getInt("serialBaudRate"); }
    public String getCameraId() { return getString("cameraId"); }
    public int getCameraWidth() { return getInt("cameraWidth"); }
    public int getCameraHeight() { return getInt("cameraHeight"); }
    public int getCameraFpsMin() { return getInt("cameraFpsMin"); }
    public int getCameraFpsMax() { return getInt("cameraFpsMax"); }
    public int getBitrateLimitMbps() { return getInt("bitrateLimitMbps"); }

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
