package de.droiddrone.pc.setup.ardu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD AS-6: an ArduPilot drone's serial ports for DRAUPNE - the phone's USB (SERIAL0) as MAVLink, the GPS on the
 * port it is wired to, and the drone-to-drone radio (a MAVLink telemetry radio) where one is fitted. ArduPilot gives
 * each port one function (SERIALn_PROTOCOL) and one speed (SERIALn_BAUD, in ArduPilot's short codes: 57 = 57600).
 * <p>
 * Each port keeps what else was on it unless the pilot says otherwise: a port that already does the wanted job is left
 * as it is; a free port (None) is taken; a port that does something else (RC input, ESC telemetry, DisplayPort ...) is
 * never taken silently - the plan is blocked with the reason until the pilot ticks REPLACE. Ports not named are never
 * written. A board's UART labels map to SERIALn differently per board (its ArduPilot page says which), so the pilot
 * picks SERIALn. Changes take effect after the board restarts.
 */
public final class ArduPorts {
    public static final int NONE = -1, MAVLINK1 = 1, MAVLINK2 = 2, GPS = 5;
    /** The highest SERIALn the page offers. */
    public static final int MAX_SERIAL = 8;
    /** The radio speeds offered: ArduPilot's codes (SiK radios ship at 57). */
    public static final int[] RADIO_BAUDS = {57, 115, 230, 460};

    /**
     * The pilot's choices.
     * @param gpsSerial the GPS's SERIALn (1..8), 0: no GPS port to set
     * @param radioSerial the drone-to-drone radio's SERIALn (1..8), 0: none fitted
     * @param radioBaud ArduPilot's code for the radio's speed (57, 115 ...)
     * @param replace the pilot said a chosen port may lose what it does now
     */
    public record Choice(int gpsSerial, int radioSerial, int radioBaud, boolean replace) {
        public static Choice none() {
            return new Choice(0, 0, 57, false);
        }
    }

    /** One port as the board has it now. */
    public record Port(int n, int protocol, int baud) {}

    /** What the choices want written (with reasons), what blocks the plan, and notes for the pilot. */
    public record Plan(Map<String, ArduPlan.Wanted> wanted, List<String> blocked, List<String> notes) {
        public boolean ok() {
            return blocked.isEmpty();
        }
    }

    private ArduPorts() {
    }

    /** The board's ports (SERIAL0..8 that it has). */
    public static List<Port> ports(Map<String, Float> now) {
        List<Port> out = new ArrayList<>();
        for (int n = 0; n <= MAX_SERIAL; n++) {
            Float p = now.get("SERIAL" + n + "_PROTOCOL");
            if (p == null) continue;
            Float b = now.get("SERIAL" + n + "_BAUD");
            out.add(new Port(n, Math.round(p), b == null ? 0 : Math.round(b)));
        }
        return out;
    }

    /** A port's function in a few words. */
    public static String name(int protocol) {
        return switch (protocol) {
            case -1, 0 -> "nothing";
            case 1, 2 -> "MAVLink";
            case 3, 4, 10 -> "FrSky telemetry";
            case 5 -> "GPS";
            case 9 -> "a rangefinder";
            case 16 -> "ESC telemetry";
            case 18 -> "optical flow";
            case 23 -> "RC input";
            case 28 -> "scripting";
            case 29 -> "CRSF VTX";
            case 32 -> "MSP";
            case 33 -> "DJI FPV";
            case 35 -> "ADS-B";
            case 37 -> "SmartAudio";
            case 42 -> "DisplayPort";
            case 44 -> "IRC Tramp";
            default -> "function " + protocol;
        };
    }

    static boolean free(int protocol) {
        return protocol == NONE || protocol == 0;
    }

    static boolean mavlink(int protocol) {
        return protocol == MAVLINK1 || protocol == MAVLINK2;
    }

    /** The plan for these choices on a board with these parameters. */
    public static Plan plan(Choice c, Map<String, Float> now) {
        Map<String, ArduPlan.Wanted> w = new LinkedHashMap<>();
        List<String> blocked = new ArrayList<>(), notes = new ArrayList<>();
        Map<Integer, Port> ports = new LinkedHashMap<>();
        for (Port p : ports(now)) ports.put(p.n(), p);

        // the phone's USB: MAVLink (1 or 2 kept as they are)
        Port usb = ports.get(0);
        if (usb == null) notes.add("No SERIAL0 (USB) on this firmware: left out.");
        else if (!mavlink(usb.protocol())) take(w, blocked, c.replace(), usb, MAVLINK2, -1, "the phone talks MAVLink on USB");

        if (c.gpsSerial() > 0 && c.gpsSerial() == c.radioSerial()) blocked.add("GPS and radio on one port: pick two.");
        if (c.gpsSerial() > 0) {
            Port p = ports.get(c.gpsSerial());
            if (p == null) blocked.add("SERIAL" + c.gpsSerial() + " isn't on this board.");
            else if (p.protocol() != GPS) take(w, blocked, c.replace(), p, GPS, -1, "GPS here (ArduPilot finds its speed)");
            for (Port o : ports.values()) {
                if (o.n() != c.gpsSerial() && o.protocol() == GPS) notes.add("SERIAL" + o.n() + " keeps its GPS too.");
            }
        }
        if (c.radioSerial() > 0) {
            Port p = ports.get(c.radioSerial());
            if (p == null) blocked.add("SERIAL" + c.radioSerial() + " isn't on this board.");
            else if (!mavlink(p.protocol()) || p.baud() != c.radioBaud()) {
                if (mavlink(p.protocol())) w.put("SERIAL" + p.n() + "_BAUD", new ArduPlan.Wanted(c.radioBaud(), "radio at " + c.radioBaud() + "k"));
                else take(w, blocked, c.replace(), p, MAVLINK2, c.radioBaud(), "drone-to-drone radio");
            }
        }
        if (!w.isEmpty()) notes.add("Ports change after a restart.");
        return new Plan(w, blocked, notes);
    }

    private static void take(Map<String, ArduPlan.Wanted> w, List<String> blocked, boolean replace, Port p, int protocol,
                             int baud, String why) {
        String port = "SERIAL" + p.n();
        if (!free(p.protocol()) && !replace) {
            blocked.add(port + " has " + name(p.protocol()) + " now: tick REPLACE to use it.");
            return;
        }
        String was = free(p.protocol()) ? "" : " (was " + name(p.protocol()) + ")";
        w.put(port + "_PROTOCOL", new ArduPlan.Wanted(protocol, why + was));
        if (baud > 0) w.put(port + "_BAUD", new ArduPlan.Wanted(baud, "at " + baud + "k"));
    }
}
