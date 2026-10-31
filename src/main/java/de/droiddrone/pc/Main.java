package de.droiddrone.pc;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.pc.gui.MainWindow;
import de.droiddrone.pc.setup.ApkShare;
import de.droiddrone.pc.setup.PcPrep;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    private static volatile boolean quit = false;

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "gui";
        // run by the installer and its scheduled task as administrator / SYSTEM: no settings file, no window
        switch (command) {
            case "prepare-pc" -> {
                PcPrep.prepare(AppPaths.launcher(), PcConfig.defaults().getPort(), ApkShare.PORT);
                return;
            }
            case "install-tailscale" -> {
                PcPrep.installTailscale();
                return;
            }
            case "cleanup-pc" -> {
                PcPrep.cleanup();
                return;
            }
            default -> {
            }
        }
        Path configFile = AppPaths.configFile();
        switch (command) {
            case "gui" -> gui(load(configFile), configFile);
            case "list" -> listJoysticks();
            case "monitor" -> monitor(PcConfig.load(configFile));
            case "connect" -> {
                PcConfig config = load(configFile);
                if (args.length > 1) config.set("ip", args[1]);
                connect(config);
            }
            case "write-icon" -> de.droiddrone.pc.gui.AppIcon.writeIco(Path.of(args[1])); // build step for the installer
            case "init-config" -> {
                if (Files.exists(configFile)) {
                    System.out.println(configFile.toAbsolutePath() + " already exists, not overwriting.");
                } else {
                    PcConfig.writeTemplate(configFile);
                    System.out.println("Wrote " + configFile.toAbsolutePath());
                }
            }
            default -> printHelp();
        }
    }

    /** Loads the settings and brings a file from an older version up to date. */
    private static PcConfig load(Path configFile) throws java.io.IOException {
        PcConfig config = PcConfig.load(configFile);
        if (config.upgrade(configFile)) de.droiddrone.common.Log.log("Settings updated to the current defaults.");
        return config;
    }

    private static void printHelp() {
        System.out.println("""
                draupne-link - fly a Betaflight drone over 4G from a Windows PC
                (talks to Flight app protocol version %d, i.e. DroidDrone 2.3.0)

                Commands:
                  gui                 open the main window (default)
                  init-config         write draupne-link.properties to edit
                  list                list connected joysticks
                  monitor             live view of joystick inputs and mapped RC channels (no network)
                  connect [address]   connect using linkMode from the settings and send RC from the joystick
                """.formatted(SettingsCommon.versionCompatibleCode));
    }

    /**
     * Running from an all-users install (0.5.3 and later) while an older per-user install is still there: remove the
     * old one in the background, so there is one app and one set of shortcuts. Never from a per-user or dev copy.
     */
    private static void removeOldPerUserInstall() {
        Path launcher = AppPaths.launcher();
        String programFiles = System.getenv("ProgramFiles");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (launcher == null || programFiles == null || localAppData == null || !launcher.startsWith(Path.of(programFiles))) return;
        if (!Files.isDirectory(Path.of(localAppData, "DroidDrone PC"))) return;
        Thread t = new Thread(() -> {
            String removed = PcPrep.removePerUserInstall();
            if (!removed.isEmpty()) de.droiddrone.common.Log.log("Removed the older per-user install: " + removed);
        }, "removeOldInstall");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void initJoystickOrExit() {
        if (!Joystick.init()) {
            System.err.println("Could not initialise GLFW for joystick input.");
            System.exit(1);
        }
    }

    /** GLFW polling stays on this (main) thread; the window runs on the Swing thread and reads snapshots. */
    private static void gui(PcConfig config, Path configFile) {
        initJoystickOrExit();
        WindowsTuning.apply();
        removeOldPerUserInstall();
        if (AppPaths.launcher() != null) {
            Thread t = new Thread(de.droiddrone.pc.setup.WindowsSetup::removeDeadOldShortcuts, "removeOldShortcuts");
            t.setDaemon(true);
            t.start();
        }
        int rcRate;
        try {
            rcRate = config.getRcRate();
        } catch (IllegalArgumentException e) { // no console to say so: a message, and the default
            rcRate = ControlLink.DEFAULT_RC_RATE;
            String why = e.getMessage();
            SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null, "Setting " + why
                    + "; using " + ControlLink.DEFAULT_RC_RATE + ". Fix rcRate in " + configFile + ".", "DRAUPNE Link",
                    javax.swing.JOptionPane.WARNING_MESSAGE));
        }
        JoystickService service = new JoystickService(config.getJoystickFilter(), rcRate);
        SwingUtilities.invokeLater(() -> MainWindow.open(config, configFile, service));
        service.run();
        Joystick.terminate();
        System.exit(0);
    }

    private static void listJoysticks() {
        initJoystickOrExit();
        List<Joystick.Info> joysticks = Joystick.list();
        if (joysticks.isEmpty()) {
            System.out.println("No joysticks found. On the TX16S choose 'USB Joystick (HID)' when plugging in USB.");
        }
        for (Joystick.Info info : joysticks) {
            Joystick.State state = Joystick.read(info.id());
            int axes = state == null ? 0 : state.axes().length;
            int buttons = state == null ? 0 : state.buttons().length;
            System.out.printf("[%d] %s  (%d axes, %d buttons)%n", info.id(), info.name(), axes, buttons);
        }
        Joystick.terminate();
    }

    private static Joystick.Info findJoystick(PcConfig config) {
        Joystick.Info info = Joystick.find(config.getJoystickFilter());
        if (info == null) {
            System.err.println("No joystick matching '" + config.getJoystickFilter() + "'. Run 'list' to see what is connected.");
        }
        return info;
    }

    private static void startQuitListener() {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (in.readLine() != null) {
                    quit = true;
                    return;
                }
            } catch (Exception ignored) {
            }
        }, "quitListener");
        t.setDaemon(true);
        t.start();
    }

    private static void monitor(PcConfig config) throws InterruptedException {
        RcMapper mapper = config.createMapper();
        initJoystickOrExit();
        Joystick.Info joystick = findJoystick(config);
        if (joystick == null) System.exit(1);
        System.out.println("Monitoring " + joystick.name() + ". Move each stick/switch and check the channel moves. Press Enter to stop.");
        startQuitListener();
        while (!quit) {
            Joystick.poll();
            Joystick.State state = Joystick.read(joystick.id());
            if (state == null) {
                System.out.print("\rJoystick disconnected.                                              ");
            } else {
                StringBuilder sb = new StringBuilder("\rAxes:");
                for (int i = 0; i < state.axes().length; i++) sb.append(String.format(" %d:%+.2f", i, state.axes()[i]));
                sb.append(" | Btn:");
                for (int i = 0; i < state.buttons().length; i++) if (state.buttons()[i]) sb.append(' ').append(i);
                sb.append(" | ").append(formatChannels(mapper.map(state.axes(), state.buttons())));
                System.out.print(sb);
            }
            Thread.sleep(50);
        }
        System.out.println();
        Joystick.terminate();
    }

    private static void connect(PcConfig config) throws Exception {
        RcMapper mapper = config.createMapper();
        initJoystickOrExit();
        Joystick.Info joystick = findJoystick(config);
        if (joystick == null) System.exit(1);

        Connection connection;
        try {
            connection = Connection.open(config);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        ControlLink link = connection.getLink();
        Runtime.getRuntime().addShutdownHook(new Thread(connection::close));
        System.out.printf("Joystick: %s%nMode: %s (port %d). Press Enter to disconnect.%n",
                joystick.name(), connection.getMode(), config.getPort()); // not the key: consoles get pasted
        startQuitListener();

        RcSession session = new RcSession(link, mapper, msg -> System.out.println("\n" + msg), ArmSwitch.parse(config.getString(ArmSwitch.KEY)));
        WindowsTuning.apply();
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        Pacer pacer = new Pacer(config.getRcRate());
        ControlLink.State lastState = null;
        long nextStatus = 0;
        while (!quit) {
            Joystick.poll();
            session.onInput(Joystick.readSame(joystick)); // only this radio: never another device under its id

            ControlLink.State state = link.getState();
            if (state != lastState) {
                System.out.println();
                System.out.println(connection.describe(state));
                lastState = state;
            }
            long now = System.currentTimeMillis();
            if (now >= nextStatus) {
                nextStatus = now + 500;
                System.out.print("\r" + statusLine(link, session.isRcEnabled(), session.getLastChannels()));
            }
            pacer.await();
        }
        System.out.println("\nDisconnecting...");
        connection.close();
        Joystick.terminate();
    }

    private static String statusLine(ControlLink link, boolean rcEnabled, short[] channels) {
        FcInfo fc = link.getFcInfo();
        String fcText = fc == null ? "FC ?" : fc.getFcName() + " " + fc.getFcVersionStr();
        int ping = link.getPing();
        String rcText = rcEnabled ? formatChannels(channels) : "RC off (lower throttle)";
        return String.format("[%s] ping %s | %s | tlm %d | rc sent %d | %s   ",
                link.getState(), ping < 0 ? "-" : ping + "ms", fcText,
                link.getTelemetryPacketsReceived(), link.getRcFramesSent(), rcText);
    }

    public static String channelLabel(int index) {
        String name = PcConfig.CHANNEL_NAMES[index];
        return index < 4 ? name.substring(0, 1).toUpperCase() : "a" + name.substring(3);
    }

    private static String formatChannels(short[] channels) {
        if (channels == null) return "mapping incomplete (axis/button missing)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++) sb.append(channelLabel(i)).append(channels[i]).append(' ');
        return sb.toString().trim();
    }
}
