package de.droiddrone.pc.gui;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The tour's finale on this PC, out of MainWindow: what its drones leave while it runs (TourTraces), taking all of it
 * off again at the end, and its flights and units on the maps. Swing thread only.
 */
final class TourHost {
    /** What the tour's clean-up needs from the window. */
    interface Host {
        /** The phone this PC's link is to, or null with no link. */
        String linkedPhone();

        void disconnect();

        /** Every mark of this drone on this PC off: overwatch, fleet, CRASHED, sleep, standby, possibly flying, name map. */
        void forgetDrone(String name);

        /** The overwatch clips' folder. */
        Path clipsRoot();

        /** Takes the kept flights this (name, file) test picks off the map, and saves the rest if any went. */
        void dropKeptFlights(BiPredicate<String, Path> tour);

        boolean save(Map<String, String> updates);

        void logLine(String text);

        /** The kept flights (and the tour's, after them) onto both maps. */
        void pushKept();

        /** The tour's units onto both maps. */
        void showUnits(List<MapGraphics.Item> units);

        /** The live flight, or null. */
        FlightTrack liveTrack();

        /** The live flight off the maps (its file went with the tour's). */
        void forgetLiveTrack();

        /** This link's log, or null. */
        LinkLog linkLog();

        void updatePhoneList();
    }

    private final PcConfig config;
    private final Host host;
    /** What the tour's finale leaves on this PC, noted while it runs (null: no finale running). */
    private TourTraces traces;
    /** The tour's drones' flights on the maps, after the kept ones; never saved (empty takes them off). */
    private List<MapView.Kept> flights = List.of();

    TourHost(PcConfig config, Host host) {
        this.config = config;
        this.host = host;
    }

    /** The traces being noted, or null while no finale runs (read by the recording's file listener too). */
    TourTraces traces() {
        return traces;
    }

    List<MapView.Kept> flights() {
        return flights;
    }

    /** The tour's drones are up: from here on what they leave is noted (TourFinale.start). */
    void begin(List<String> drones) {
        Map<String, String> now = new HashMap<>();
        for (String k : config.keys()) now.put(k, config.getString(k));
        Path lastPosition = null;
        try {
            lastPosition = FlightTrack.defaultDir().resolve("last-position.txt");
        } catch (IOException ignored) {
            // no Tracks folder: nothing there to look after
        }
        traces = new TourTraces(drones, System.currentTimeMillis(), now, lastPosition);
    }

    /**
     * The tour ended, whichever way (1.0.52, the pilot: "clean up all the simulated drones and flights after the
     * tour"): a link to a tour drone ends, and every trace of the tour's drones goes - the name map and PHONE list, the
     * fleet rows and map signs, their kept flights, CRASHED marks, queued modes, sleep records, overwatch watches and
     * cards, the settings that name them, and the files of the tour's flight and events. The pilot's own is untouched.
     */
    void cleanUp() {
        TourTraces t = traces;
        if (t == null) return;
        String linked = host.linkedPhone();
        if (linked != null && t.isTour(linked)) host.disconnect();
        if (t.inConnection()) t.disconnected(System.currentTimeMillis());
        Set<Path> noted = new HashSet<>();
        for (String n : t.names()) host.forgetDrone(n);
        Path videos = null, clips = null;
        try {
            videos = AppPaths.videosDir();
        } catch (IOException ignored) {
            // no Videos folder: no recordings to look for
        }
        clips = host.clipsRoot();
        List<Path> files = t.filesToDelete(videos, clips, System.currentTimeMillis());
        noted.addAll(files);
        host.dropKeptFlights((name, file) -> (file != null && noted.contains(file))
                || t.isTour(name) || t.names().stream().anyMatch(n -> name != null
                && name.toLowerCase(Locale.ROOT).startsWith(n.toLowerCase(Locale.ROOT))));
        Map<String, String> now = new HashMap<>();
        for (String k : config.keys()) now.put(k, config.getString(k));
        Map<String, String> restore = t.settingsToRestore(now);
        if (!restore.isEmpty()) host.save(restore);
        int deleted = TourTraces.delete(files, (f, e) -> host.logLine("Tour: couldn't delete " + MainWindow.shortPath(f) + ": " + e.getMessage()));
        try {
            t.restoreLastPosition();
        } catch (IOException e) {
            host.logLine("Tour: last-position.txt not put back: " + e.getMessage());
        }
        traces = null;
        flights(List.of());
        host.showUnits(List.of());
        FlightTrack live = host.liveTrack();
        if (live != null && live.file() != null && noted.contains(live.file())) host.forgetLiveTrack();
        host.pushKept();
        host.updatePhoneList();
        host.logLine("Tour: its drones and flights removed" + (deleted > 0 ? " (" + deleted + " file" + (deleted == 1 ? "" : "s") + ")" : "") + ".");
    }

    /** A connection to this drone began: the tour's flight's files are noted (its link log now, its track at the end). */
    void noteConnect(String drone) {
        TourTraces t = traces;
        if (t == null || !t.isTour(drone)) return;
        t.connected(System.currentTimeMillis());
        LinkLog log = host.linkLog();
        if (log != null) t.note(log.file());
    }

    /** The connection ends: a tour flight's track is noted. */
    void noteDisconnect() {
        TourTraces t = traces;
        if (t == null || !t.inConnection()) return;
        FlightTrack live = host.liveTrack();
        if (live != null) t.note(live.file());
        t.disconnected(System.currentTimeMillis());
    }

    /** The tour's drones' flights on the maps (empty takes them off). */
    void flights(List<MapView.Kept> flights) {
        this.flights = List.copyOf(flights);
        host.pushKept();
    }
}
