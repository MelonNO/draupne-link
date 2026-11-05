package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drone setup's GPS question: the GPS, its UART and the compass, as Betaflight's CLI sees them and the commands that
 * make them so. The GPS goes on as a feature (feature GPS) and as the port's function (serial &lt;id&gt; 2 ...,
 * "Sensor input: GPS" in the Ports tab), with gps_auto_baud on so the module is found at whatever rate it talks. The
 * compass is Betaflight's mag_hardware: AUTO finds one on the board's I2C bus (a GPS module usually carries it), NONE
 * leaves even a fitted one unused - as Betaflight Configurator's Magnetometer switch does.
 */
public final class GpsSetup {
    /** The pilot's answer: "yes" / "no"; missing = not asked yet. */
    public static final String GPS_KEY = "drone.gps";
    /** The GPS's UART as Betaflight numbers it (1 = UART1); default UART3. */
    public static final String UART_KEY = "drone.gpsUart";
    public static final int DEFAULT_UART = 3;
    /** "yes" / "no" (compass); missing = no. */
    public static final String COMPASS_KEY = "drone.compass";

    /** Betaflight's serial port function bit for the GPS (serialPortFunction_e). */
    public static final int FUNCTION_GPS = 2;
    /** Betaflight's serial port identifiers: UART1 = 0 ... UART10 = 9, USB VCP 20, SOFTSERIAL1/2 30/31, LPUART1 40. */
    static final int ID_UART1 = 0, ID_USB = 20, ID_SOFTSERIAL1 = 30, ID_LPUART1 = 40;
    /** The UARTs offered before Betaflight has said which ports the board has. */
    public static final int UARTS_OFFERED = 6;

    private GpsSetup() {
    }

    /** The saved answer: null when not asked yet. */
    public static Boolean parseYesNo(String saved) {
        if (saved == null || saved.isBlank()) return null;
        String s = saved.trim().toLowerCase(Locale.ROOT);
        return s.equals("yes") || s.equals("true") ? Boolean.TRUE : s.equals("no") || s.equals("false") ? Boolean.FALSE : null;
    }

    /** The saved UART number (1 = UART1), the default when missing or broken. */
    public static int parseUart(String saved) {
        if (saved == null || saved.isBlank()) return DEFAULT_UART;
        try {
            int n = Integer.parseInt(saved.trim().replaceFirst("(?i)^uart", ""));
            return n >= 1 && n <= 10 ? n : DEFAULT_UART;
        } catch (NumberFormatException e) {
            return DEFAULT_UART;
        }
    }

    /** One line of the CLI's "serial" output: the port's id, its function mask and the baud fields after them. */
    public record Port(int id, int functions, String rest) {
        /** "UART3", "USB VCP", "SOFTSERIAL1", "LPUART1". */
        public String name() {
            return portName(id);
        }

        public boolean hasGps() {
            return (functions & FUNCTION_GPS) != 0;
        }

        /** 1 = UART1 ... for a hardware UART, 0 for anything else. */
        public int uart() {
            return id >= ID_UART1 && id < ID_UART1 + 10 ? id - ID_UART1 + 1 : 0;
        }
    }

    public static String portName(int id) {
        if (id >= ID_UART1 && id < ID_UART1 + 10) return "UART" + (id - ID_UART1 + 1);
        if (id == ID_USB) return "USB VCP";
        if (id >= ID_SOFTSERIAL1 && id < ID_SOFTSERIAL1 + 2) return "SOFTSERIAL" + (id - ID_SOFTSERIAL1 + 1);
        if (id == ID_LPUART1) return "LPUART1";
        return "port " + id;
    }

    /** "serial 2 2 115200 57600 0 115200" lines: the ports as Betaflight has them. */
    public static List<Port> ports(String serialOutput) {
        List<Port> ports = new ArrayList<>();
        for (String line : serialOutput.split("\\R")) {
            String[] p = line.replaceFirst("^#\\s*", "").trim().split("\\s+");
            if (p.length < 3 || !p[0].equals("serial")) continue;
            try {
                int id = Integer.parseInt(p[1]);
                int functions = Integer.parseInt(p[2]);
                StringBuilder rest = new StringBuilder();
                for (int i = 3; i < p.length; i++) rest.append(i > 3 ? " " : "").append(p[i]);
                ports.add(new Port(id, functions, rest.toString()));
            } catch (NumberFormatException ignored) {
                // not a port line
            }
        }
        return ports;
    }

    /** The UART numbers this board has (1 = UART1), from the CLI's "serial" output; empty if none was understood. */
    public static List<Integer> uarts(String serialOutput) {
        List<Integer> out = new ArrayList<>();
        for (Port p : ports(serialOutput)) if (p.uart() > 0 && !out.contains(p.uart())) out.add(p.uart());
        out.sort(null);
        return out;
    }

    /** The UART the GPS function is on (1 = UART1), or 0 (none, or on a port that isn't a UART). */
    public static int gpsUart(String serialOutput) {
        for (Port p : ports(serialOutput)) if (p.hasGps()) return p.uart();
        return 0;
    }

    /** The name of the port the GPS function is on, or "" for none. */
    public static String gpsPortName(String serialOutput) {
        for (Port p : ports(serialOutput)) if (p.hasGps()) return p.name();
        return "";
    }

    /** What else a port's function mask names, for the plan ("MSP", "serial receiver", ...). */
    static String functionNames(int mask) {
        String[] names = {"MSP", "GPS", "FrSky telemetry", "HoTT telemetry", "LTM telemetry", "SmartPort telemetry",
                "serial receiver", "Blackbox", "", "MAVLink telemetry", "ESC sensor", "SmartAudio", "IBUS telemetry", "Tramp",
                "RunCam control", "Lidar", "FrSky OSD", "VTX MSP"};
        List<String> on = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1 << i)) != 0 && (1 << i) != FUNCTION_GPS) on.add(names[i].isEmpty() ? "function " + (1 << i) : names[i]);
        }
        return String.join(", ", on);
    }

    private static final Pattern MAG_HARDWARE = Pattern.compile("mag_hardware\\s*=\\s*(\\S+)");
    private static final Pattern AUTO_BAUD = Pattern.compile("gps_auto_baud\\s*=\\s*(\\S+)");

    /** The value from `get mag_hardware` ("AUTO", "NONE", a chip's name), or "" if not understood. */
    public static String magHardware(String getOutput) {
        Matcher m = MAG_HARDWARE.matcher(getOutput);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
    }

    /** `get gps_auto_baud` is ON; false when OFF or not understood. */
    public static boolean autoBaudOn(String getOutput) {
        Matcher m = AUTO_BAUD.matcher(getOutput);
        return m.find() && m.group(1).equalsIgnoreCase("ON");
    }

    /** The Betaflight has the setting at all (an older one without it never gets the command). */
    static boolean autoBaudKnown(String getOutput) {
        return AUTO_BAUD.matcher(getOutput).find();
    }

    /**
     * The changes that put the GPS on the UART: feature GPS when off, the port's function (GPS alone on it, the
     * baud fields kept; GPS taken off any other port), gps_auto_baud on. Empty when all is so already.
     * @param uart the UART wanted (1 = UART1)
     */
    public static List<BetaflightPlan.Change> gpsPlan(int featureMask, String serialOutput, String autoBaudOutput, int uart) {
        List<BetaflightPlan.Change> changes = new ArrayList<>();
        if (!BetaflightPlan.hasGps(featureMask)) {
            changes.add(new BetaflightPlan.Change("GPS on (feature GPS)", List.of("feature GPS")));
        }
        List<Port> ports = ports(serialOutput);
        Port target = null;
        for (Port p : ports) if (p.uart() == uart) target = p;
        List<String> commands = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (Port p : ports) {
            if (p.hasGps() && p != target) {
                commands.add("serial " + p.id() + " " + (p.functions() & ~FUNCTION_GPS) + " " + p.rest());
                what.add("off " + p.name());
            }
        }
        if (target == null) {
            // the port isn't in Betaflight's list (an older CLI, or a board without it): asked for all the same
            commands.add("serial " + (ID_UART1 + uart - 1) + " " + FUNCTION_GPS + " 115200 57600 0 115200");
            what.add(0, "GPS on UART" + uart);
        } else if (target.functions() != FUNCTION_GPS) {
            commands.add("serial " + target.id() + " " + FUNCTION_GPS + " " + target.rest());
            String others = functionNames(target.functions());
            what.add(0, "GPS on " + target.name() + (others.isEmpty() ? "" : " (" + others + " taken off it)"));
        }
        if (!commands.isEmpty()) changes.add(new BetaflightPlan.Change(String.join(", ", what), commands));
        if (autoBaudKnown(autoBaudOutput) && !autoBaudOn(autoBaudOutput)) {
            changes.add(new BetaflightPlan.Change("GPS found at any baud rate (gps_auto_baud)", List.of("set gps_auto_baud = ON")));
        }
        return changes;
    }

    /**
     * The compass as chosen: AUTO when wanted (a named chip already set is kept), NONE when not. Null = no change.
     * @param magHardware the value from `get mag_hardware`
     */
    public static BetaflightPlan.Change compassPlan(boolean wanted, String magHardware) {
        if (magHardware.isEmpty()) return null; // not understood: left alone
        if (wanted) {
            return magHardware.equals("NONE") ? new BetaflightPlan.Change("Compass on (mag_hardware AUTO)", List.of("set mag_hardware = AUTO")) : null;
        }
        return magHardware.equals("NONE") ? null : new BetaflightPlan.Change("Compass off (mag_hardware NONE)", List.of("set mag_hardware = NONE"));
    }

    /** MSP_STATUS' sensor bits: the compass (SENSOR_MAG, bit 2) is found. */
    public static boolean magInStatus(byte[] status) {
        return status.length >= 6 && ((((status[4] & 0xff) | (status[5] & 0xff) << 8)) & (1 << 2)) != 0;
    }

    /** MSP_STATUS' sensor bits: the GPS (SENSOR_GPS, bit 3) is talking. */
    public static boolean gpsInStatus(byte[] status) {
        return status.length >= 6 && ((((status[4] & 0xff) | (status[5] & 0xff) << 8)) & (1 << 3)) != 0;
    }

    /** MSP_RAW_IMU: acc x3, gyro x3, mag x3 as int16; the compass's three readings, or null if not in the answer. */
    public static int[] magFromRawImu(byte[] rawImu) {
        if (rawImu.length < 18) return null;
        int[] m = new int[3];
        for (int i = 0; i < 3; i++) m[i] = (short) ((rawImu[12 + 2 * i] & 0xff) | (rawImu[13 + 2 * i] & 0xff) << 8);
        return m;
    }

    private static final Pattern MAG_CALIBRATION = Pattern.compile("mag_calibration\\s*=\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)");

    /** `get mag_calibration` gives offsets that aren't all zero: a calibration has been done. */
    public static boolean calibrated(String getOutput) {
        Matcher m = MAG_CALIBRATION.matcher(getOutput);
        if (!m.find()) return false;
        return !(m.group(1).equals("0") && m.group(2).equals("0") && m.group(3).equals("0"));
    }
}
