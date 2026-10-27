package de.droiddrone.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the app keeps its files: %APPDATA%\DroidDronePC, so it works no matter where it is started from. */
public final class AppPaths {
    private AppPaths() {
    }

    public static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        return base.resolve("DroidDronePC");
    }

    /** The settings file; a droiddrone-pc.properties in the working directory (older versions) is copied over once. */
    public static Path configFile() throws IOException {
        Path dir = Files.createDirectories(dataDir());
        Path file = dir.resolve(PcConfig.FILE_NAME);
        Path legacy = Path.of(PcConfig.FILE_NAME).toAbsolutePath();
        if (!Files.exists(file) && Files.exists(legacy)) Files.copy(legacy, file);
        return file;
    }

    public static Path downloadsDir() throws IOException {
        return Files.createDirectories(dataDir().resolve("downloads"));
    }

    /**
     * What a shortcut should start: the packaged DroidDrone PC.exe (jpackage), or the .bat from installDist.
     * null when running from Gradle or an IDE.
     */
    public static Path launcher() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) return Path.of(app);
        try {
            Path jar = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path bat = jar.getParent().getParent().resolve("bin").resolve("droiddrone-pc.bat");
            if (Files.isRegularFile(bat)) return bat;
        } catch (Exception ignored) {
        }
        return null;
    }
}
