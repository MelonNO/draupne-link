package de.droiddrone.pc.flights;

import de.droiddrone.common.group.GeoLocal;

import java.util.Collection;
import java.util.Locale;

/**
 * Fleet PRD AC-14: how close another DRAUPNE drone came to the one flown, for PREVIOUS FLIGHTS. Asked once a second
 * while this PC flies a drone and it is armed; the nearest so far goes into the flight's record
 * ({@link FlightRecord#closest}). Other drones count while they are in the air: those this PC's fleet tracks (their own
 * reports, with their height) and those the network says are armed (their place only - no height).
 */
public final class ClosestApproach {
    private ClosestApproach() {
    }

    /** Another drone in the air: where it is, its height above sea level (NaN: not known). */
    public record Other(String name, double lat, double lon, double altAmsl) {}

    /** The nearest: how far sideways and in height (NaN: not known), metres. */
    public record Near(String drone, double sideM, double heightM) {
        /** Straight-line distance; sideways alone when the height isn't known. */
        public double distanceM() {
            return Double.isNaN(heightM) ? sideM : Math.hypot(sideM, heightM);
        }

        /** "14 M MobileB (12 M SIDE, 7 M HEIGHT)", or "14 M MobileB (SIDE)" without a height. */
        public String text() {
            String d = Math.round(distanceM()) + " M " + drone;
            if (Double.isNaN(heightM)) return d + " (SIDE)";
            return d + String.format(Locale.ROOT, " (%d M SIDE, %d M HEIGHT)", Math.round(sideM), Math.round(Math.abs(heightM)));
        }
    }

    /**
     * The nearest of the others to the flown drone at (lat, lon, altAmsl - NaN: not known), itself left out by name;
     * null when there is none. A drone without a height counts sideways only.
     */
    public static Near nearest(String flown, double lat, double lon, double altAmsl, Collection<Other> others) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || others == null) return null;
        Near best = null;
        for (Other o : others) {
            if (o == null || o.name() == null || o.name().equalsIgnoreCase(flown) || Double.isNaN(o.lat()) || Double.isNaN(o.lon())) continue;
            double side = GeoLocal.distance(lat, lon, o.lat(), o.lon());
            double height = Double.isNaN(altAmsl) || Double.isNaN(o.altAmsl()) ? Double.NaN : o.altAmsl() - altAmsl;
            Near n = new Near(o.name(), side, height);
            if (best == null || n.distanceM() < best.distanceM()) best = n;
        }
        return best;
    }
}
