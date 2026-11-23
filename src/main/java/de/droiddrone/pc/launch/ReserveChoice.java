package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.LaunchGeo;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fleet PRD AL-6 (FA9): which marked reserve a CALL RESERVE press sends - the one that would arrive first:
 * <pre>arrival = the wait for its next check-in + waking and arming + the vertical climb to its approach layer
 *          + the flight to the group at cruise speed</pre>
 * The smallest wins: the nearest drone answers only when it would also be first - a farther one whose check-in is due
 * soon can beat it. Only marked drones within {@link #MAX_DISTANCE_M} of the group count, each answers one call, and a
 * drone whose next check-in isn't known (never heard asleep, or overdue) is not promised to anyone.
 */
public final class ReserveChoice {
    /** FA9: the furthest a reserve may be called from. */
    public static final double MAX_DISTANCE_M = 10_000;

    /**
     * One marked drone as the PC knows it now.
     * @param checkInWaitMs until its phone next listens (0: awake, or a listening deep sleep that hears at once);
     *                      -1: not known - not chosen. FLEET's card has it (nextCheckMs; LISTENING = 0)
     * @param approachM its approach layer above its take-off point (the airspace's), NaN: none free
     * @param ready the PC's own view of AL-3 before the call: battery, GPS fix and FC health as last heard, not OUT OF
     *              ACTION. The phone checks again on the spot.
     */
    public record Reserve(String name, boolean marked, int markId, double lat, double lon, long checkInWaitMs,
                          double approachM, boolean ready) {}

    /** How the arrival is counted. Defaults: the pilot's drones (cruise 12 m/s, climb 2.5 m/s, 20 s to wake and arm). */
    public record Pace(double wakeAndArmS, double climbMps, double cruiseMps) {
        public static final Pace DEFAULT = new Pace(20, 2.5, 12);
    }

    /** A drone's estimate: seconds until it reaches the group, its distance, and the parts. */
    public record Arrival(Reserve reserve, double seconds, double distanceM, double waitS, double climbS, double flightS) {}

    private final Pace pace;

    public ReserveChoice(Pace pace) {
        this.pace = pace == null ? Pace.DEFAULT : pace;
    }

    /** Its arrival at the group at (groupLat, groupLon), or empty: it can't be sent (unmarked, too far, unknown wait ...). */
    public Optional<Arrival> arrival(Reserve r, double groupLat, double groupLon) {
        if (r == null || !r.marked() || r.markId() == 0 || !r.ready() || r.checkInWaitMs() < 0 || Double.isNaN(r.approachM())) {
            return Optional.empty();
        }
        double d = LaunchGeo.distanceM(r.lat(), r.lon(), groupLat, groupLon);
        if (Double.isNaN(d) || d > MAX_DISTANCE_M) return Optional.empty();
        double wait = r.checkInWaitMs() / 1000.0;
        double climb = r.approachM() / pace.climbMps();
        double flight = d / pace.cruiseMps();
        return Optional.of(new Arrival(r, wait + pace.wakeAndArmS() + climb + flight, d, wait, climb, flight));
    }

    /** The one to send now, or empty: NO RESERVE. {@code taken}: drones already answering a call (one call each). */
    public Optional<Arrival> pick(Collection<Reserve> reserves, Set<String> taken, double groupLat, double groupLon) {
        return reserves.stream()
                .filter(r -> !taken.contains(r.name()))
                .map(r -> arrival(r, groupLat, groupLon))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(Arrival::seconds).thenComparing(a -> a.reserve().name()));
    }

    /** Every drone that could be sent, first arrival first (for the planner's preview and the tests). */
    public List<Arrival> ranked(Collection<Reserve> reserves, double groupLat, double groupLon) {
        return reserves.stream().map(r -> arrival(r, groupLat, groupLon)).flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(Arrival::seconds).thenComparing(a -> a.reserve().name())).toList();
    }
}
