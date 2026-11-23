package de.droiddrone.pc.flights;

import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * A flight in a few figures, for the debrief: how long, how far, how high, the battery's lowest, what the link did.
 * Worked out from what the flight kept - its track, its recording's figures, its link log - and each figure only as
 * far as those tell it: a flight without a position has no distance, one without a recording no battery, one whose
 * link log is gone no losses. Such a figure is "not known" ({@link #UNKNOWN} as text), never 0.
 * <p>
 * "In the air" is "armed" here, as the files have nothing better: the distance, the time armed and the battery's
 * lowest count what was recorded while the flight controller said armed.
 */
public final class FlightSummary {
    /** What a figure not known is written as, as elsewhere in the app. */
    public static final String UNKNOWN = "-";

    /** Which altitude the highest one is. */
    public enum AltitudeKind {
        /**
         * The flight controller's own altitude (baro and / or GPS, as Betaflight has them), counted from where it
         * armed: the OSD's ALT. From the recording's figures - so of the recorded part of the flight only.
         */
        ABOVE_TAKE_OFF("MAX HEIGHT", "above take-off (flight controller)"),
        /** The drone's GPS altitude as the receiver gave it, above sea level. From the track. */
        GPS("MAX GPS ALT", "GPS altitude"),
        /** The phone's GPS altitude. From the track, when the phone's GPS made it. */
        PHONE_GPS("MAX PHONE GPS ALT", "the phone's GPS altitude");

        /** The figure's heading. */
        public final String label;
        /** A few words on what it is, for a tooltip or the report. */
        public final String meaning;

        AltitudeKind(String label, String meaning) {
            this.label = label;
            this.meaning = meaning;
        }
    }

    /** One figure as a screen or the report shows it: a short heading, the value with its unit ({@link #UNKNOWN} when not known). */
    public record Figure(String label, String value, boolean known) {}

    /** From the flight's first position or picture to its last: what the flights list shows. Always known. */
    public final long durationMs;
    /** The time the flight controller said armed, as far as the track or the recording saw it; -1 not known. */
    public final long armedMs;
    /**
     * The way flown, m: the track's length while armed (the whole track's when it never says armed - the flights list
     * counts the whole track always, the ground too); NaN without positions.
     */
    public final double distanceM;
    /**
     * The furthest from the take-off point, m. The take-off point is where the track saw the drone arm; a track that
     * starts armed (a connection made in the air) doesn't know it - then the flight controller's own distance to its
     * home, from the recording's figures. NaN when neither is there.
     */
    public final double furthestM;
    /** The highest altitude, m, and which altitude that is; NaN / null when not known. */
    public final double maxAltM;
    public final AltitudeKind maxAltKind;
    /** The lowest voltage of a cell while armed, from the recording's figures; NaN when not known (no recording, or the cell count wasn't known). */
    public final double minCellVolts;
    /** The lowest voltage of the pack while armed; NaN when not known. For when the cells aren't. */
    public final double minPackVolts;
    /** How often the link went down during the flight (the link log's link column leaving READY); -1 without a link log. */
    public final int linkLosses;
    /** How often Betaflight went into FAILSAFE during the flight, as far as the PC heard it (see {@link #of}); -1 not known. */
    public final int failsafes;
    /** The highest ping during the flight, ms: the link log's, else the recording's; -1 not known. */
    public final int worstPingMs;
    /**
     * AC-14: the nearest another DRAUPNE drone came while this one was armed (its record's), or null; with
     * {@link #othersWatched} null means none came near, without it nothing is known (a flight from before, a spectator's).
     */
    public final ClosestApproach.Near closest;
    public final boolean othersWatched;

    private FlightSummary(long durationMs, long armedMs, double distanceM, double furthestM, double maxAltM, AltitudeKind maxAltKind,
                          double minCellVolts, double minPackVolts, int linkLosses, int failsafes, int worstPingMs,
                          ClosestApproach.Near closest, boolean othersWatched) {
        this.durationMs = durationMs;
        this.armedMs = armedMs;
        this.distanceM = distanceM;
        this.furthestM = furthestM;
        this.maxAltM = maxAltM;
        this.maxAltKind = maxAltKind;
        this.minCellVolts = minCellVolts;
        this.minPackVolts = minPackVolts;
        this.linkLosses = linkLosses;
        this.failsafes = failsafes;
        this.worstPingMs = worstPingMs;
        this.closest = closest;
        this.othersWatched = othersWatched;
    }

    /** Reads the flight's files, then {@link #of(FlightData)}: not on the Swing thread. */
    public static FlightSummary of(FlightLibrary.Flight f) {
        return of(FlightData.load(f));
    }

    /**
     * The figures of a flight read already. A file that is missing, cut short or half written takes its figures with
     * it and no more. A loss or a failsafe counts once, where it begins, however long it lasts; one that began before
     * the flight's first position doesn't count. While the link is down the PC hears nothing from Betaflight: a
     * failsafe that began and ended inside a loss isn't seen, one still on when the link is back is.
     */
    public static FlightSummary of(FlightData d) {
        List<FlightTrack.Point> path = d.path();
        long start = d.startMs(), end = d.endMs();

        long armedMs = Math.max(armedMs(d.track, FlightTrack.Point::timeMs, FlightTrack.Point::armed),
                armedMs(d.telemetry, FlightData.Sample::timeMs, FlightData.Sample::armed));

        // the way flown, and the furthest from where the track saw it arm
        double distance = Double.NaN, furthest = Double.NaN;
        if (path.size() >= 2) {
            boolean everArmed = false;
            for (FlightTrack.Point p : path) everArmed |= p.armed();
            distance = 0;
            for (int i = 1; i < path.size(); i++) {
                FlightTrack.Point a = path.get(i - 1), b = path.get(i);
                if (!everArmed || (a.armed() && b.armed())) distance += Mercator.distanceM(a.lat(), a.lon(), b.lat(), b.lon());
            }
        }
        int takeOff = -1;
        for (int i = 0; i < path.size(); i++) {
            if (!path.get(i).armed()) continue;
            if (i > 0) takeOff = i; // armed after a point that wasn't: seen arming
            break;
        }
        if (takeOff >= 0) {
            FlightTrack.Point home = path.get(takeOff);
            furthest = 0;
            for (int i = takeOff; i < path.size(); i++) {
                furthest = Math.max(furthest, Mercator.distanceM(home.lat(), home.lon(), path.get(i).lat(), path.get(i).lon()));
            }
        } else {
            int fc = 0; // Betaflight's 0 is also "no home yet": not a distance
            for (FlightData.Sample s : d.telemetry) if (s.armed()) fc = Math.max(fc, s.homeM());
            if (fc > 0) furthest = fc;
        }

        // the highest: the flight controller's altitude where there is a recording, else the track's GPS altitude
        double maxAlt = Double.NaN;
        AltitudeKind kind = null;
        for (FlightData.Sample s : d.telemetry) {
            if (Double.isNaN(s.altM())) continue;
            if (Double.isNaN(maxAlt) || s.altM() > maxAlt) maxAlt = s.altM();
            kind = AltitudeKind.ABOVE_TAKE_OFF;
        }
        if (kind == null) {
            for (FlightTrack.Point p : d.track) {
                if (!Double.isNaN(maxAlt) && p.altM() <= maxAlt) continue;
                maxAlt = p.altM();
                kind = p.phone() ? AltitudeKind.PHONE_GPS : AltitudeKind.GPS;
            }
        }

        // the battery's lowest while armed (on the ground a pack pulled off reads anything on its way to nothing)
        double minCell = Double.NaN, minPack = Double.NaN;
        for (FlightData.Sample s : d.telemetry) {
            if (!s.armed()) continue;
            if (s.cellVolts() > 0 && !(s.cellVolts() >= minCell)) minCell = s.cellVolts();
            if (s.volts() > 0 && !(s.volts() >= minPack)) minPack = s.volts();
        }

        // the link: where a loss and a failsafe begin, and the worst ping
        int losses = -1, failsafes = -1, worstPing = -1;
        boolean up = false, failsafe = false;
        for (FlightData.LinkSample s : d.link) {
            boolean in = s.timeMs() >= start && s.timeMs() <= end;
            if (in) {
                if (losses < 0) losses = 0;
                worstPing = Math.max(worstPing, s.pingMs());
                if (s.down() && up) losses++;
            }
            up = !s.down();
            if (!s.fcKnown()) continue; // nothing heard from Betaflight in this row: as the row before
            if (in) {
                if (failsafes < 0) failsafes = 0;
                if (s.failsafe() && !failsafe) failsafes++;
            }
            failsafe = s.failsafe();
        }
        if (worstPing < 0) for (FlightData.Sample s : d.telemetry) worstPing = Math.max(worstPing, s.pingMs());

        // AC-14: the closest approach, from the flight's record
        FlightRecord record = d.flight.record() == null ? null : FlightRecord.read(d.flight.record());
        return new FlightSummary(d.flight.durationMs(), armedMs, distance, furthest, maxAlt, kind, minCell, minPack, losses, failsafes,
                Math.max(-1, worstPing), record == null ? null : record.closest(), record != null && record.watchedOthers());
    }

    /** The time between samples that both say armed, added up; -1 without samples. */
    private static <T> long armedMs(List<T> samples, ToLongFunction<T> time, Predicate<T> armed) {
        if (samples.isEmpty()) return -1;
        long ms = 0;
        for (int i = 1; i < samples.size(); i++) {
            T a = samples.get(i - 1), b = samples.get(i);
            if (armed.test(a) && armed.test(b)) ms += Math.max(0, time.applyAsLong(b) - time.applyAsLong(a));
        }
        return ms;
    }

    // ------------------------------------------------------------------ as text

    /** "12:34" or "1:02:03". */
    public String durationText() {
        return FlightLibrary.durationText(durationMs);
    }

    public String armedText() {
        return armedMs < 0 ? UNKNOWN : FlightLibrary.durationText(armedMs);
    }

    /** "245 M" / "1.24 KM", as the map writes distances. */
    public String distanceText() {
        return Double.isNaN(distanceM) ? UNKNOWN : MapGraphics.distanceText(distanceM);
    }

    public String furthestText() {
        return Double.isNaN(furthestM) ? UNKNOWN : MapGraphics.distanceText(furthestM);
    }

    /** "147 M"; which altitude it is, is {@link #maxAltLabel}. */
    public String maxAltText() {
        return Double.isNaN(maxAltM) ? UNKNOWN : Math.round(maxAltM) + " M";
    }

    /** The altitude figure's heading, saying which altitude it is: MAX HEIGHT (above take-off), MAX GPS ALT, MAX PHONE GPS ALT. */
    public String maxAltLabel() {
        return maxAltKind == null ? "MAX ALT" : maxAltKind.label;
    }

    /** The lowest cell's "3.52 V"; the pack's "21.1 V" when the cells aren't known. */
    public String batteryText() {
        if (!Double.isNaN(minCellVolts)) return String.format(Locale.ROOT, "%.2f V", minCellVolts);
        return Double.isNaN(minPackVolts) ? UNKNOWN : String.format(Locale.ROOT, "%.1f V", minPackVolts);
    }

    /** MIN CELL, or MIN PACK when only the pack's voltage is known. */
    public String batteryLabel() {
        return Double.isNaN(minCellVolts) && !Double.isNaN(minPackVolts) ? "MIN PACK" : "MIN CELL";
    }

    public String linkLossesText() {
        return linkLosses < 0 ? UNKNOWN : Integer.toString(linkLosses);
    }

    public String failsafesText() {
        return failsafes < 0 ? UNKNOWN : Integer.toString(failsafes);
    }

    /** "412 MS". */
    public String worstPingText() {
        return worstPingMs < 0 ? UNKNOWN : worstPingMs + " MS";
    }

    /** AC-14: "14 M" (who and how: {@link ClosestApproach.Near#text}); NONE when the others were watched and none came; {@link #UNKNOWN} not known. */
    public String closestText() {
        if (closest != null) return Math.round(closest.distanceM()) + " M";
        return othersWatched ? "NONE" : UNKNOWN;
    }

    /** Every figure in the order a screen or the report shows them. */
    public List<Figure> figures() {
        return List.of(new Figure("DURATION", durationText(), true),
                new Figure("ARMED", armedText(), armedMs >= 0),
                new Figure("DISTANCE", distanceText(), !Double.isNaN(distanceM)),
                new Figure("FURTHEST", furthestText(), !Double.isNaN(furthestM)),
                new Figure(maxAltLabel(), maxAltText(), !Double.isNaN(maxAltM)),
                new Figure(batteryLabel(), batteryText(), !Double.isNaN(minCellVolts) || !Double.isNaN(minPackVolts)),
                new Figure("LINK LOSSES", linkLossesText(), linkLosses >= 0),
                new Figure("FAILSAFES", failsafesText(), failsafes >= 0),
                new Figure("WORST PING", worstPingText(), worstPingMs >= 0),
                new Figure("CLOSEST", closestText(), closest != null || othersWatched));
    }
}
