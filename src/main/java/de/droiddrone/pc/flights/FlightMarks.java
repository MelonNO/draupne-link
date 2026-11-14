package de.droiddrone.pc.flights;

import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.overwatch.ClipIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What happened during a flight besides its figures, for the bar and the report: the pilot's bookmarks
 * ({@link FlightNotes}), the pins dropped while it flew (a pin's createdMs) and the overwatch clips that started
 * meanwhile, whichever phone they came from - each at its time from the flight's start, in the order they came.
 */
public final class FlightMarks {
    private FlightMarks() {
    }

    public enum Kind {
        BOOKMARK("BOOKMARK"), PIN("PIN"), CLIP("CLIP");

        /** As a list writes it. */
        public final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    /**
     * One mark. offsetMs: from the flight's start. label: the bookmark's text, the pin's name, the clip's phone.
     * What it was made from is the one of bookmark / pin / clip that isn't null.
     */
    public record Mark(long offsetMs, Kind kind, String label, FlightNotes.Bookmark bookmark, Pins.Pin pin, ClipIndex.Clip clip) {
        /** "1:23", as the bar writes times. */
        public String timeText() {
            return FlightLibrary.durationText(offsetMs);
        }

        /** Where it is on the map: the pin's place, the place the clip's phone stood; NaN for a bookmark or a clip without one. */
        public double lat() {
            return pin != null ? pin.lat() : clip != null && clip.hasPosition() ? clip.lat() : Double.NaN;
        }

        public double lon() {
            return pin != null ? pin.lon() : clip != null && clip.hasPosition() ? clip.lon() : Double.NaN;
        }
    }

    /**
     * The flight's marks, earliest first.
     * @param notes the flight's own, or null
     * @param pins  the app's pins: those dropped between the flight's start and its end are its marks (a pin whose time
     *              isn't known - an older file's - is nobody's)
     * @param clips overwatch clips: those that started between the flight's start and its end ({@link #clipsIn})
     */
    public static List<Mark> of(FlightLibrary.Flight f, FlightNotes notes, List<Pins.Pin> pins, List<ClipIndex.Clip> clips) {
        long start = f.startMs(), end = f.endMs();
        List<Mark> out = new ArrayList<>();
        if (notes != null) {
            for (FlightNotes.Bookmark b : notes.bookmarks()) {
                out.add(new Mark(b.offsetMs(), Kind.BOOKMARK, b.text().isEmpty() ? Kind.BOOKMARK.title : b.text(), b, null, null));
            }
        }
        if (pins != null) {
            for (Pins.Pin p : pins) {
                if (p.createdMs() <= 0 || p.createdMs() < start || p.createdMs() > end) continue;
                out.add(new Mark(p.createdMs() - start, Kind.PIN, p.name(), null, p, null));
            }
        }
        if (clips != null) {
            for (ClipIndex.Clip c : clips) {
                long at = c.startedAt();
                if (at <= 0 || at < start || at > end) continue;
                out.add(new Mark(at - start, Kind.CLIP, phoneOf(c), null, null, c));
            }
        }
        out.sort(Comparator.comparingLong(Mark::offsetMs).thenComparing(Mark::kind).thenComparing(Mark::label));
        return out;
    }

    /** The phone a clip came from, as its facts name it (its folder's name is that made safe for a folder). */
    static String phoneOf(ClipIndex.Clip c) {
        String name = c.meta().getProperty("drone");
        return name == null || name.isBlank() ? c.drone() : name.trim();
    }

    /**
     * Every phone's clips on this PC that started in this time (removed ones left out, as on the clips list). Reads
     * their files: not on the Swing thread.
     * @param index the app's clips, or null
     */
    public static List<ClipIndex.Clip> clipsIn(ClipIndex index, long startMs, long endMs) {
        List<ClipIndex.Clip> out = new ArrayList<>();
        if (index == null) return out;
        for (String drone : index.drones()) {
            for (ClipIndex.Clip c : index.clips(drone)) if (c.startedAt() >= startMs && c.startedAt() <= endMs) out.add(c);
        }
        return out;
    }
}
