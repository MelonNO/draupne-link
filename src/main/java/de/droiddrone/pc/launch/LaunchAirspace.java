package de.droiddrone.pc.launch;

/**
 * Fleet PRD AC-4 / AC-5, as safe autolaunch needs them: each reserve's own approach layer (never shared) and its own
 * cleared launch column. The layer table and the column reservations are the airspace planner's
 * (de.droiddrone.pc.air); this is the small part of it the launch queue asks, so the two can be built and tested apart.
 */
public interface LaunchAirspace {
    /**
     * The approach layer this reserve flies to the group in, in metres above its take-off point - its own, at the
     * floor or above (AC-5) - or NaN when none is free (NO LAYER FREE, AC-13): it stays on the ground.
     */
    double approachLayerFor(String drone, double lat, double lon);

    /**
     * Reserve the vertical column of {@code radiusM} round (lat, lon) from the ground to {@code topM} above it for this
     * drone's climb. False when another drone's predicted position or column overlaps it now: the launch waits (AC-4).
     */
    boolean reserveColumn(String drone, double lat, double lon, double radiusM, double topM);

    /** The climb is over (or never happened): the column is free again. */
    void releaseColumn(String drone);

    /** Its approach layer is no longer needed (it joined, failed or was cancelled). */
    void releaseLayer(String drone);
}
