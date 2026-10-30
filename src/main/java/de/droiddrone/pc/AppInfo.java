package de.droiddrone.pc;

/** This program's version, as stamped into its jar by the build ("dev" when run from the IDE or tests). */
public final class AppInfo {
    private AppInfo() {
    }

    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }
}
