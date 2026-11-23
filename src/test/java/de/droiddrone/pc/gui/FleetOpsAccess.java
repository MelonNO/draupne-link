package de.droiddrone.pc.gui;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;

/** The window's FleetOps for tests in other packages (the SITL gates): the menu items pressed as the pilot presses them. */
public final class FleetOpsAccess {
    private final FleetOps ops;

    public FleetOpsAccess(String flown, Function<String, InetSocketAddress> addresses, List<String> log) {
        this(flown, addresses, log, (lat, lon) -> Double.NaN);
    }

    /** @param terrain the ground a dispatch plans over (flat ground in the SITL tests) */
    public FleetOpsAccess(String flown, Function<String, InetSocketAddress> addresses, List<String> log,
                          de.droiddrone.pc.route.TerrainSource terrain) {
        ops = new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return flown;
            }

            public InetSocketAddress addressOf(String phone) {
                return addresses.apply(phone);
            }

            public String key() {
                return "DD";
            }

            public void log(String line) {
                log.add(line);
            }

            public void alert(String text) {
                log.add("! " + text);
            }

            public void flyNow(String phone) {
            }

            public long checkInWaitMs(String phone) {
                return 0;
            }

            public boolean confirm(String question) {
                return true;
            }

            public de.droiddrone.pc.route.TerrainSource terrain() {
                return terrain;
            }
        });
    }

    /** Presses the right-click item of this drone that starts with the text. */
    public void click(String drone, String startsWith) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.items(drone).stream().filter(i -> i.getText().startsWith(startsWith))
                .findFirst().orElseThrow(() -> new AssertionError("no item '" + startsWith + "' for " + drone)).doClick());
    }

    public boolean grouped() {
        return ops.picture() != null;
    }

    public void callReserve() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(ops::callReserve);
    }

    /** DISPATCH the marked drones to a place (the map's right-click). */
    public void dispatch(double lat, double lon) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.dispatch(lat, lon));
    }

    public void tick() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
    }

    public void close() {
        ops.close();
    }
}
