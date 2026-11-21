package de.droiddrone.bench;

import de.droiddrone.pc.PcConfig;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What must be so before a hardware run's app is started ({@link AppRig.Plan#hardware}), and what is looked at when
 * it has ended. A run that reaches a real phone - and through it a real flight controller - starts only on a PC where
 * nothing else can be talking to them, with the stick settings the radio's limits were made for; everything in the
 * way is said, and nothing is started.
 */
final class HardwareRun {
    /** The installed app's program, as Windows names its process. */
    static final String APP_EXE = "DRAUPNE Link.exe";
    private static final Pattern AXIS_SETTING = Pattern.compile("axis\\d+\\.(min|center|max)");

    private HardwareRun() {
    }

    /**
     * The settings a hardware scenario does not get to make: where the link goes (the hardware file's), and what turns
     * the radio's axes into the sticks sent - the radio's throttle ceiling and its ARM switch are only what they say
     * with the rig's own channel map and measured ranges.
     */
    static List<String> notForAScenario(Collection<String> settingKeys) {
        List<String> no = new ArrayList<>();
        for (String key : settingKeys) {
            if (key.equals("ip") || key.equals("port") || key.equals("key") || AXIS_SETTING.matcher(key).matches()
                    || Arrays.asList(PcConfig.CHANNEL_NAMES).contains(key)) {
                no.add(key);
            }
        }
        return no;
    }

    /** The first of these running programs that is a DRAUPNE Link (the installed app, also as the virtual fleet); null: none. */
    static String draupneLinkAmong(Stream<String> programs) {
        return programs.filter(p -> {
            String name = p.replace('/', '\\');
            return name.substring(name.lastIndexOf('\\') + 1).equalsIgnoreCase(APP_EXE);
        }).findFirst().orElse(null);
    }

    /** Whether this PC's UDP port is free - a DRAUPNE Link started any way (also from source) holds its link port while it runs. */
    static boolean udpPortFree(int port) {
        try (DatagramSocket probe = new DatagramSocket(port)) {
            return probe.isBound();
        } catch (IOException e) {
            return false;
        }
    }

    /** What the plan itself has that a hardware run does not take. */
    static void plan(List<String> no, int drones, boolean firstStart, boolean ownFiles, boolean sameUser, Collection<String> settingKeys) {
        if (drones > 0) no.add("it asks for " + drones + " made-up drone(s): a hardware run has the real one only");
        if (firstStart) no.add("it is a first start: a hardware run starts as a pilot's everyday start");
        if (ownFiles) no.add("it puts files of its own into the user's folders before the start: a hardware run starts from the rig's own settings");
        if (sameUser) no.add("it takes another rig's user: a hardware run starts from the rig's own settings");
        List<String> settings = notForAScenario(settingKeys);
        if (!settings.isEmpty()) {
            no.add("it sets " + settings + ": where the link goes and how the radio's axes become sticks are the rig's own in a hardware run");
        }
    }

    /** What on this PC, now, stands in the way of reaching the real phone. */
    static void thisPc(List<String> no, Hardware hw) {
        if (!Fence.Reach.hardwareRun()) {
            no.add("this is no hardware task's JVM (-D" + Fence.Reach.PROPERTY + "=true): start it with gradlew benchHw or .\\bench.ps1 hw");
        }
        String app = draupneLinkAmong(ProcessHandle.allProcesses().map(p -> p.info().command().orElse("")));
        if (app != null) no.add("a DRAUPNE Link is running on this PC (" + app + "): close it first - never while it has a drone's link open");
        if (Sandbox.realAppData() == null) {
            no.add("the bench was not told where the real DRAUPNE Link keeps its logs (bench.realAppData): whether it has a link open is not known");
        } else {
            ArmedGuard.State real = ArmedGuard.now();
            if (real != ArmedGuard.State.NO_LINK) {
                no.add("a DRAUPNE Link on this PC has a drone's link open (" + real.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            }
        }
        if (!udpPortFree(hw.phonePort())) {
            no.add("this PC's UDP port " + hw.phonePort() + " is taken - by a DRAUPNE Link started some other way, or another program: "
                    + "the app under test needs the phone's link port on this PC too");
        }
        if (de.droiddrone.pc.setup.Tailscale.exe() == null) {
            no.add("Tailscale is not installed on this PC: the app then asks nobody which phones there are (it looks for Tailscale's program "
                    + "before it asks it, so the bench's answer is never heard) and the phone never comes into its PHONE list");
        }
    }

    /**
     * The most throttle the app sent in this run, as its link logs have it (column "throttle": the sticks actually
     * sent, ten rows a second while a link is up); -1 when no sticks were sent. The radio's ceiling is on the radio;
     * this is what came out of the app.
     *
     * @throws IOException a link log that cannot be read, or has no such column: then nothing is known
     */
    static int mostThrottleSent(Path logsDir) throws IOException {
        int most = -1;
        if (!Files.isDirectory(logsDir)) return most;
        List<Path> logs;
        try (Stream<Path> files = Files.list(logsDir)) {
            logs = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("link-") && n.endsWith(".csv");
            }).toList();
        }
        for (Path f : logs) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty()) continue;
            int column = Arrays.asList(lines.get(0).split(",", -1)).indexOf("throttle");
            if (column < 0) throw new IOException(f.getFileName() + " has no column \"throttle\"");
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",", -1);
                if (cells.length <= column || cells[column].isBlank()) continue; // an event's row, or sticks not sent
                try {
                    most = Math.max(most, Integer.parseInt(cells[column].trim()));
                } catch (NumberFormatException e) {
                    throw new IOException(f.getFileName() + ": a throttle that is no number, \"" + cells[column] + "\"");
                }
            }
        }
        return most;
    }
}
