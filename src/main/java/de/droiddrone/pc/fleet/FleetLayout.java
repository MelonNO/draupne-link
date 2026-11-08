package de.droiddrone.pc.fleet;

import de.droiddrone.pc.map.Coordinates;

import java.util.List;

/**
 * Where the virtual drones stand and look (the pilot, 1.0.52): one layout for the tour's drones and the virtual
 * fleet's - four round a launch point, three looking out over their sectors, one beside the launch point. The fleet
 * moves the whole set with START PLACE (the launch point goes there, the rest keep their place round it); drones past
 * the four stand near the launch point.
 */
public final class FleetLayout {
    private FleetLayout() {
    }

    /** Where all of them took off (the pilot's). */
    public static final String LAUNCH = "32V PN 49401 50639";

    /** One drone's place: where it sits, where its camera looks (null: away from the launch point), its tour mode. */
    public record Place(String at, String lookAt, int mode) {}

    /** The pilot's layout: the first one sees the tour's BTR. */
    public static final List<Place> PLACES = List.of(
            new Place("32V PN 48978 50762", "32V PN 49199 50956", VirtualDrone.OVERWATCH),
            new Place("32V PN 48686 51068", "32V PN 48496 51269", VirtualDrone.OVERWATCH),
            new Place("32V PN 49177 50442", "32V PN 48991 50241", VirtualDrone.OVERWATCH),
            new Place("32V PN 49411 50763", null, VirtualDrone.DEEP_SLEEP));

    /** Past the layout's drones: this far from the launch point, round it. */
    static final double EXTRA_RING_M = 15;

    /** The launch point {lat, lon}. */
    public static double[] launch() {
        return Coordinates.parse(LAUNCH);
    }

    /**
     * Drone i's place {lat, lon, heading degrees} with the launch point moved to (lat, lon): the layout's offsets from
     * its launch point, kept. Past the layout: on a ring round the launch point, looking away from it.
     */
    public static double[] place(int i, double lat, double lon) {
        double[] launch = launch();
        double cos = Math.cos(Math.toRadians(lat));
        double n, e, heading;
        if (i < PLACES.size()) {
            Place p = PLACES.get(i);
            double[] at = Coordinates.parse(p.at());
            n = (at[0] - launch[0]) * 111_320.0;
            e = (at[1] - launch[1]) * 111_320.0 * Math.cos(Math.toRadians(launch[0]));
            heading = p.lookAt() != null ? bearing(at, Coordinates.parse(p.lookAt())) : bearing(launch, at);
        } else {
            double a = Math.toRadians((i - PLACES.size()) * 60.0 + 30);
            n = Math.cos(a) * EXTRA_RING_M;
            e = Math.sin(a) * EXTRA_RING_M;
            heading = (Math.toDegrees(a) + 360) % 360;
        }
        return new double[]{lat + n / 111_320.0, lon + e / (111_320.0 * cos), heading};
    }

    /** Degrees clockwise from north, from a to b ({lat, lon}). */
    public static double bearing(double[] a, double[] b) {
        double dn = (b[0] - a[0]) * 111_320.0, de = (b[1] - a[1]) * 111_320.0 * Math.cos(Math.toRadians(a[0]));
        return (Math.toDegrees(Math.atan2(de, dn)) + 360) % 360;
    }
}
