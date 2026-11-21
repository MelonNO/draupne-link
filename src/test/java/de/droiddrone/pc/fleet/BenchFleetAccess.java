package de.droiddrone.pc.fleet;

/** What the test bench reads of a made-up drone's flight controller (this package's own fields). */
public final class BenchFleetAccess {
    private BenchFleetAccess() {
    }

    public static boolean armed(VirtualDrone d) {
        return d.fc.isArmed();
    }

    public static String failsafe(VirtualDrone d) {
        return d.fc.failsafe().name();
    }

    public static String lastDisarm(VirtualDrone d) {
        return d.fc.lastDisarm();
    }

    public static boolean hadSticks(VirtualDrone d) {
        return d.fc.hadSticks();
    }

    public static double homeDistanceM(VirtualDrone d) {
        return d.fc.homeDistanceM();
    }

    /** Home this far north of where the drone sits (as a drone that flew out from there): kept through its next arming. */
    public static void launchedFromNorth(VirtualDrone d, double metres) {
        d.fc.setLaunch(d.fc.latDeg() + metres / 111_320.0, d.fc.lonDeg());
    }

    public static double altitudeM(VirtualDrone d) {
        return d.fc.altitudeM();
    }

    /** A made-up drone on loopback at a port of the system's choice, started; its own network delay off (the bench's bad link is the network). */
    public static VirtualDrone start(int slot, String name, String key, java.util.function.Consumer<String> log) throws java.io.IOException {
        VirtualDrone d = new VirtualDrone(slot, name, key, java.net.InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log);
        d.link().setBaseMs(0);
        d.start(0);
        return d;
    }

    public static void stop(VirtualDrone d) {
        d.stop();
    }
}
