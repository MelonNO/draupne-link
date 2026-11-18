package de.droiddrone.pc.flights;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which connections' files stay (1.0.71, the pilot, 2026-11-18): "don't log flights where the drone was not armed"
 * - and "always save a log": a flight the app never got to close (the PC restarted for an update, the app killed) is
 * found again at the next start by its {@link FlightRecord} without an end line.
 */
public final class FlightKeeper {
    private FlightKeeper() {
    }

    /**
     * The files of a connection in which the drone was never armed: its track (and notes), every part of its recording
     * with the OSD copy and the figures beside it, and the record itself. The link log stays: it is the app's own
     * diagnosis and is sorted out with the others. Does nothing for a flight that was armed.
     * @param track      the track's file as the app knows it (the record may not have been told yet), or null
     * @param recordings the recording's files as the app knows them, may be empty
     * @return how many files went
     */
    public static int deleteUnarmed(FlightRecord record, Path track, List<Path> recordings) {
        if (record == null || record.wasArmed()) return 0;
        Set<Path> files = new LinkedHashSet<>();
        for (Path t : new Path[]{track, record.track()}) {
            if (t == null) continue;
            files.add(t);
            files.add(beside(t, FlightNotes.SUFFIX));
        }
        List<Path> videos = new ArrayList<>(record.videos());
        if (recordings != null) videos.addAll(recordings);
        for (Path v : videos) {
            files.add(v);
            files.add(beside(v, "-osd.mkv"));
            files.add(beside(v, ".csv"));
        }
        files.add(record.file());
        int gone = 0;
        for (Path f : files) {
            try {
                if (Files.deleteIfExists(f)) gone++;
            } catch (IOException ignored) {
                // still open somewhere: it is tried again at the next start (recover)
            }
        }
        return gone;
    }

    /** x.gpx / x.mkv -> x + suffix, in the same folder. */
    private static Path beside(Path f, String suffix) {
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return f.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + suffix);
    }

    /**
     * At the app's start: the records the app never closed. One whose drone was armed is a flight to keep - it gets its
     * end line ({@link FlightRecord#APP_CLOSED}, at the time it was last seen armed) and is returned, for the map; one
     * never armed is deleted with its files, as it would have been at its DISCONNECT (also one that was closed but
     * whose files could not go then). The record of the connection open now is left alone.
     * Reads and writes files: quick (a few small files), but not for a paint.
     */
    public static List<FlightRecord> recover(Path tracksDir) {
        List<FlightRecord> kept = new ArrayList<>();
        for (FlightRecord r : FlightRecord.readAll(tracksDir)) {
            if (r.isLive()) continue;
            if (!r.wasArmed()) {
                deleteUnarmed(r, null, List.of());
            } else if (!r.finished()) {
                r.end(FlightRecord.APP_CLOSED, Math.max(r.armedLastMs(), r.startMs()));
                kept.add(r);
            }
        }
        return kept;
    }
}
