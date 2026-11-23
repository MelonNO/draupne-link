package de.droiddrone.pc.gui;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;

/** The window's FleetOps for tests in other packages (the SITL gates): the menu items pressed as the pilot presses them. */
public final class FleetOpsAccess {
    private final FleetOps ops;
    private volatile String flown;

    public FleetOpsAccess(String flown, Function<String, InetSocketAddress> addresses, List<String> log) {
        this(flown, addresses, log, (lat, lon) -> Double.NaN);
    }

    /** @param terrain the ground a dispatch plans over (flat ground in the SITL tests) */
    public FleetOpsAccess(String flown, Function<String, InetSocketAddress> addresses, List<String> log,
                          de.droiddrone.pc.route.TerrainSource terrain) {
        this.flown = flown;
        ops = new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return FleetOpsAccess.this.flown;
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
                FleetOpsAccess.this.flown = phone;
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
                .findFirst().orElseThrow(() -> new AssertionError("no item '" + startsWith + "' for " + drone + ": "
                        + ops.items(drone).stream().map(javax.swing.JMenuItem::getText).toList())).doClick());
    }

    public boolean grouped() {
        return ops.picture() != null;
    }

    /** The group's lead as the map shows it, or null. */
    public String groupLead() {
        de.droiddrone.pc.air.GroupLink.Picture p = ops.picture();
        return p == null ? null : p.lead();
    }

    /** The drone this PC flies now (the window's flownPhone; a hand-over changes it). */
    public String flown() {
        return flown;
    }

    public void setFlown(String drone) {
        flown = drone;
    }

    /** FLEET's word for a drone. */
    public String stateOf(String drone) throws Exception {
        String[] s = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> s[0] = ops.stateOf(drone));
        return s[0];
    }

    public de.droiddrone.pc.air.GroupController.State memberState(String drone) {
        return ops.memberStateForTests(drone);
    }

    public void callReserve() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(ops::callReserve);
    }

    /** FOLLOW GROUP: end (all hold). */
    public void end() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(ops::end);
    }

    /** DISPATCH the marked drones to a place (the map's right-click). */
    public void dispatch(double lat, double lon) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.dispatch(lat, lon));
    }

    public void tick() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
    }

    // ---- the pilot's radio: DISCARD LEAD on AUX5, CALL RESERVE on AUX6, through the stick gate (RcSession)

    private de.droiddrone.pc.ControlLink radioLink;
    private de.droiddrone.pc.RcSession radio;
    private final float[] axes = new float[10];

    /** Flips a fleet switch on the radio (5: DISCARD LEAD, 6: CALL RESERVE): down a while, up, as the pilot does. */
    public void flip(int aux) throws Exception {
        if (radio == null) {
            radioLink = new de.droiddrone.pc.ControlLink(de.droiddrone.pc.PcConfig.defaults(), java.net.InetAddress.getLoopbackAddress(), 9, 0);
            String[] specs = new String[10];
            for (int i = 0; i < specs.length; i++) specs[i] = "axis" + i;
            radio = new de.droiddrone.pc.RcSession(radioLink, new de.droiddrone.pc.RcMapper(specs), e -> { }, null);
            radio.setFleetSwitches(new de.droiddrone.pc.FleetSwitches(5, 6), ops::onSwitch);
        }
        int ch = 3 + aux;
        axes[ch] = -1;
        for (int i = 0; i < 20; i++) { // down 400 ms
            radio.onInput(new de.droiddrone.pc.Joystick.State(axes.clone(), new boolean[0]));
            Thread.sleep(20);
        }
        axes[ch] = 1;
        for (int i = 0; i < 5; i++) {
            radio.onInput(new de.droiddrone.pc.Joystick.State(axes.clone(), new boolean[0]));
            Thread.sleep(20);
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> { }); // its action ran on Swing's thread
    }

    public void close() {
        ops.close();
        if (radioLink != null) radioLink.close();
    }
}
