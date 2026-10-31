package de.droiddrone.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Where the app keeps its files: %APPDATA%\DraupneLink, so it works no matter where it is started from. */
public final class AppPaths {
    /** The settings file's name up to 1.0.15, when the app was called DroidDrone PC. */
    static final String OLD_FILE_NAME = "droiddrone-pc.properties";

    private AppPaths() {
    }

    private static Path appDataBase() {
        String appData = System.getenv("APPDATA");
        return appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
    }

    public static Path dataDir() {
        return appDataBase().resolve("DraupneLink");
    }

    /**
     * The settings file. The first time, the settings of DroidDrone PC (up to 1.0.15: %APPDATA%\DroidDronePC, or
     * even older, the working directory) are copied over, so the setup isn't lost with the new name. The old folder
     * is left as it was.
     */
    public static Path configFile() throws IOException {
        Path dir = Files.createDirectories(dataDir());
        Path file = dir.resolve(PcConfig.FILE_NAME);
        if (Files.exists(file)) return file;
        for (Path old : List.of(appDataBase().resolve("DroidDronePC").resolve(OLD_FILE_NAME),
                Path.of(OLD_FILE_NAME).toAbsolutePath())) {
            if (Files.isRegularFile(old)) {
                Files.copy(old, file);
                break;
            }
        }
        return file;
    }

    /** Videos\DRAUPNE Link: recorded flights. */
    public static Path videosDir() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Videos", "DRAUPNE Link"));
    }

    /** Documents\DRAUPNE Link\Betaflight backups: Betaflight's settings, saved before Drone setup changes them. */
    public static Path betaflightBackupsDir() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Betaflight backups"));
    }

    public static Path downloadsDir() throws IOException {
        return Files.createDirectories(dataDir().resolve("downloads"));
    }

    /**
     * What a shortcut should start: the packaged DRAUPNE Link.exe (jpackage), or the .bat from installDist.
     * null when running from Gradle or an IDE.
     */
    public static Path launcher() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) return Path.of(app);
        try {
            Path jar = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path bat = jar.getParent().getParent().resolve("bin").resolve("draupne-link.bat");
            if (Files.isRegularFile(bat)) return bat;
        } catch (Exception ignored) {
        }
        return null;
    }
}
