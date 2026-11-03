package de.droiddrone.pc;

/** This program's version, as stamped into its jar by the build ("dev" when run from the IDE or tests). */
public final class AppInfo {
    private AppInfo() {
    }

    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    /** Where this copy runs from: the installed exe, or the jar's folder (a portable zip, a build), for messages. */
    public static String runningFrom() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) return app;
        try {
            java.nio.file.Path jar = java.nio.file.Path.of(AppInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return (java.nio.file.Files.isDirectory(jar) ? jar : jar.getParent()).toString();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }
}
