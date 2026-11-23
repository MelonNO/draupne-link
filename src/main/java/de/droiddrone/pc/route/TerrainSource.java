package de.droiddrone.pc.route;

/**
 * Fleet PRD RT-2: the bare-ground height under a place, in metres above sea level, or NaN when it is not known.
 * Asked synchronously by the planner: a source answers from what it has (a {@link TerrainStore} on disk, made-up
 * terrain in the tests), never by waiting on the network.
 */
@FunctionalInterface
public interface TerrainSource {
    double heightM(double lat, double lon);
}
