package de.droiddrone.pc.gui;

import de.droiddrone.pc.FleetSwitches;
import de.droiddrone.pc.air.GroupLink;
import de.droiddrone.pc.fleet.ArduShow;
import de.droiddrone.pc.fleet.Fleet;

import javax.swing.JMenuItem;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fleet PRD TO-2 / TO-3: the tour's and DEMO's virtual ArduPilot drones driven by the window's own fleet logic -
 * FleetOps with an {@link ArduShow} as its host (the show's lead is the "flown" drone; nothing goes to a real drone).
 * The tour's steps press the same right-click items a pilot does. The group it flies is drawn on the map.
 */
final class TourArdu {
    private TourArdu() {
    }

    /** The show started in this fleet round (lat, lon), its FleetOps wired; null if it couldn't start. */
    static ArduShow start(Fleet fleet, double lat, double lon, Consumer<String> log) {
        try {
            ArduShow show = new ArduShow(fleet, lat, lon, log);
            FleetOps ops = new FleetOps(new FleetOps.Host() {
                public String flownPhone() {
                    return show.lead();
                }

                public InetSocketAddress addressOf(String phone) {
                    return show.addressOf(phone);
                }

                public String key() {
                    return show.key();
                }

                public void log(String line) {
                    log.accept(line);
                }

                public void alert(String text) {
                    log.accept("! " + text);
                }

                public void flyNow(String phone) {
                    show.newLead(phone); // the show "flies" the new lead
                }

                public long checkInWaitMs(String phone) {
                    return 0;
                }

                public boolean confirm(String question) {
                    return true;
                }

                public de.droiddrone.pc.route.TerrainSource terrain() {
                    return (la, lo) -> 0.0; // the show's drones fly over level ground of their own
                }

                public de.droiddrone.pc.map.Elevation elevation() {
                    return null;
                }
            });
            show.setOps(new ArduShow.Ops() {
                public List<JMenuItem> items(String phone) {
                    return ops.items(phone);
                }

                public void dispatch(double la, double lo) {
                    ops.dispatch(la, lo);
                }

                public void callReserve() {
                    ops.callReserve();
                }

                public void tickReserves() {
                    javax.swing.SwingUtilities.invokeLater(ops::tickReserves);
                }

                public void discardLead() {
                    ops.onSwitch(FleetSwitches.DISCARD_LEAD);
                }

                public GroupLink.Picture picture() {
                    return ops.picture();
                }

                public void close() {
                    ops.close();
                }
            });
            return show;
        } catch (java.io.IOException e) {
            log.accept("ArduPilot show: " + e.getMessage());
            return null;
        }
    }

    /** Presses the right-click item of this drone that starts with this text; false if it has none. */
    static boolean press(ArduShow show, String drone, String startsWith) {
        for (JMenuItem i : show.ops().items(drone)) {
            if (i.getText().startsWith(startsWith)) {
                i.doClick();
                return true;
            }
        }
        return false;
    }

    /** FOLLOW GROUP: the four followers added, then started - as the pilot does it. */
    static boolean formGroup(ArduShow show) {
        for (String f : ArduShow.FOLLOWERS) press(show, f, "FOLLOW GROUP: add");
        return press(show, ArduShow.FOLLOWERS.get(0), "FOLLOW GROUP: start");
    }

    /** SAFE TO AUTOLAUNCH on the two scouts (the DISPATCH step sends every marked drone). */
    static void markScouts(ArduShow show) {
        for (String s : ArduShow.SCOUTS) press(show, s, "SAFE TO AUTOLAUNCH: mark");
    }

    /** SAFE TO AUTOLAUNCH on the reserve (after the scouts have gone). */
    static void markReserve(ArduShow show) {
        press(show, ArduShow.RESERVE, "SAFE TO AUTOLAUNCH: mark");
    }
}
