package de.droiddrone.pc;

import de.droiddrone.pc.setup.Shell;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * The app's doors to the world outside its own process that are not the drone link: web addresses, other programs,
 * Windows' browser and Explorer. Each call here does exactly what the code did before it was moved here - unless the
 * test bench (1.0.71) has put its stand-ins in, so the whole app can be run on a PC without reaching the internet,
 * Tailscale or the pilot's desktop, and so a test can see that "the app opened this folder". The app itself never
 * sets them. Nothing of the stick or video path goes through here.
 */
public final class Outside {
    /** The test bench's stand-ins; every method's default is "not mine": the real thing happens. */
    public interface Doors {
        /** The address to really ask instead of this one (the bench's made-up web server). */
        default String url(String url) {
            return url;
        }

        /** A command the app runs and waits for: its result, or null to run it. */
        default Shell.Result run(List<String> command) {
            return null;
        }

        /** A program the app starts and leaves: true when taken care of (it is not started). */
        default boolean start(List<String> command) {
            return false;
        }

        /** True when taken care of (no browser opens). */
        default boolean browse(URI address) {
            return false;
        }

        /** True when taken care of (no Explorer window opens). */
        default boolean open(File folderOrFile) {
            return false;
        }
    }

    private static volatile Doors doors;

    private Outside() {
    }

    /** The test bench only; null puts the real world back. */
    public static void use(Doors standIns) {
        doors = standIns;
    }

    public static String url(String url) {
        Doors d = doors;
        return d == null ? url : d.url(url);
    }

    /** For Shell: a stand-in's result, or null when the command is to be run. */
    public static Shell.Result run(List<String> command) {
        Doors d = doors;
        return d == null ? null : d.run(command);
    }

    /** Starts the program; null when a stand-in took it instead. */
    public static Process start(ProcessBuilder builder) throws IOException {
        Doors d = doors;
        if (d != null && d.start(List.copyOf(builder.command()))) return null;
        return builder.start();
    }

    public static void browse(URI address) throws IOException {
        Doors d = doors;
        if (d != null && d.browse(address)) return;
        java.awt.Desktop.getDesktop().browse(address);
    }

    public static void open(File folderOrFile) throws IOException {
        Doors d = doors;
        if (d != null && d.open(folderOrFile)) return;
        java.awt.Desktop.getDesktop().open(folderOrFile);
    }
}
