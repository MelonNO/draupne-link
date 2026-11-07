package de.droiddrone.pc.observe;

import de.droiddrone.common.Observe;

/**
 * Which way an observed drone looks (1.0.48), for its view cone on the map: the flight controller's compass when it
 * has one, else the phone's own rotation vector sensor. Approximate - the tile says so - while the FC's compass isn't
 * known to be calibrated, or the phone's sensor says its accuracy is low (or doesn't say, or estimates its error over
 * {@link #PHONE_ERROR_MAX_DEG}).
 */
public final class HeadingChoice {
    public enum Source { FC_COMPASS, PHONE, NONE }

    /** @param deg degrees from north, NaN with Source.NONE */
    public record Heading(double deg, Source source, boolean approximate) {
        public static final Heading NONE = new Heading(Double.NaN, Source.NONE, true);
    }

    /** Android's SENSOR_STATUS_ACCURACY_MEDIUM: below it the phone's compass wants calibrating. */
    static final int PHONE_ACCURACY_GOOD = 2;
    /** The sensor's own heading error estimate past this: approximate. */
    static final int PHONE_ERROR_MAX_DEG = 25;

    private HeadingChoice() {
    }

    /**
     * @param p                 the observer record (null: no heading)
     * @param compassCalibrated the FC's compass is known calibrated (Drone setup read its offsets on this PC)
     */
    public static Heading choose(Observe.Position p, boolean compassCalibrated) {
        if (p == null) return Heading.NONE;
        if (p.fcCompass() && !Double.isNaN(p.fcHeadingDeg)) return new Heading(p.fcHeadingDeg, Source.FC_COMPASS, !compassCalibrated);
        if (!Double.isNaN(p.phoneAzimuthDeg)) {
            boolean low = p.phoneAccuracy < PHONE_ACCURACY_GOOD || p.phoneErrorDeg > PHONE_ERROR_MAX_DEG;
            return new Heading(p.phoneAzimuthDeg, Source.PHONE, low);
        }
        return Heading.NONE;
    }

    /** The settings key that says this phone's drone has a calibrated compass ("true"; Drone setup writes it). */
    public static String calibratedKey(String phone) {
        return "compass.calibrated." + phone;
    }
}
