package de.droiddrone.pc.gui;

import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.fleet.VirtualPilotPc;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tour's spectator steps (1.0.56, the pilot): select SPECTATOR, connect to another pilot PC that flies (the
 * virtual fleet's {@link VirtualPilotPc}), see that spectator mode mirrors that PC - map, fleet and camera feeds - and
 * follow it when it lands and starts observing. Its drones live in the tour's fleet (or a fleet of its own when the
 * tour has none running), and go when the tour ends.
 */
final class TourSpectator implements AutoCloseable {
    /** The drones the virtual pilot observes. */
    static final int OBSERVED = 3;

    private final MainWindow main;
    private final Supplier<Fleet> tourFleet;
    private Fleet ownFleet;
    private Fleet fleet;
    private VirtualPilotPc pilot;
    /** The virtual pilot PC's name: a free PC name, chosen at the start (a made-up one was never filed). */
    private volatile String pilotName;
    /** Every name this part brought, once up (the pilot PC's own drone is gone again when it observes). */
    private volatile List<String> traced = List.of();
    private final List<VirtualDrone> watched = new ArrayList<>();
    private volatile boolean started, failed, observing;

    /** What its log lines start with ("Tour: ", DEMO mode's "Demo: "). */
    private final String prefix;

    /** @param tourFleet the tour's own virtual fleet while it runs (its drones' announcement), or null */
    TourSpectator(MainWindow main, Supplier<Fleet> tourFleet) {
        this(main, tourFleet, "Tour: ");
    }

    TourSpectator(MainWindow main, Supplier<Fleet> tourFleet, String prefix) {
        this.main = main;
        this.tourFleet = tourFleet;
        this.prefix = prefix;
    }

    /** The virtual pilot PC's name once chosen, else null. */
    String pilotName() {
        return pilotName;
    }

    /** It observes now (switched by {@link #observe}, back by {@link #flyAgain}). */
    boolean observing() {
        return observing;
    }

    /** The virtual pilot lands its observation and flies again (DEMO mode's card; a thread: the take-off takes a moment). */
    void flyAgain() {
        VirtualPilotPc p = pilot;
        if (p == null || !observing) return;
        observing = false;
        Thread t = new Thread(() -> {
            try {
                p.fly(Fleet.DEFAULT_LAT + 0.0008, Fleet.DEFAULT_LON + 0.0012);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> main.logLine(prefix + "the virtual pilot can't fly - " + e.getMessage()));
            }
        }, "tourPilotPcFly");
        t.setDaemon(true);
        t.start();
    }

    /** The steps, in order, for the end of the tour. */
    List<Tour.Step> steps() {
        List<Tour.Step> s = new ArrayList<>();
        s.add(new Tour.Step("SPECTATOR", "Another pilot is flying. Select SPECTATOR to watch.", main::tourRoleCombo)
                .before(this::start).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(main::tourSpectatorRole, "Select SPECTATOR", true));
        // the only pilot PC is picked by itself: the step then passes at once
        s.add(new Tour.Step("PILOT PC", "Pick the pilot's PC, VIRTUAL.", main::tourPhoneCombo).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .live(main::tourPhoneCombo)
                .waitFor(() -> pilotName != null && pilotName.equalsIgnoreCase(main.config().getString(Names.PHONE_NAME_KEY)),
                        "Pick it", true));
        s.add(new Tour.Step("WATCH", "Press CONNECT.", main::tourConnectButton).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(main::tourSpectating, "Press CONNECT", true));
        s.add(new Tour.Step("MIRROR", "Spectator mirrors the pilot PC: map, fleet and camera feeds. "
                + "If the pilot observes, you see that too.", main::tourVideoArea).live(main::tourVideoArea).skip(() -> failed).of(Tour.FEATURE_SPECTATOR));
        s.add(new Tour.Step("PILOT OBSERVES", "The pilot lands and starts OBSERVATION. Your view follows.", main::tourVideoArea)
                .before(this::observe).live(main::tourVideoArea).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(this::mirroredObserving, "Watch...", false));
        return s;
    }

    /** The virtual pilot PC up and flying (a thread of its own: binding and take-off take a moment). */
    void start() {
        if (started) return;
        started = true;
        Fleet f = tourFleet == null ? null : tourFleet.get();
        if (f == null) {
            if (!VirtualFleet.announcedNow().isEmpty()) { // the pilot's own fleet: not written over
                failed = true;
                main.logLine(prefix + "spectator steps skipped - close the virtual fleet first.");
                return;
            }
            ownFleet = new Fleet(main.config(), VirtualFleet.file(), line -> SwingUtilities.invokeLater(() -> main.logLine(prefix + line)));
            ownFleet.startHeartbeat();
            f = ownFleet;
        }
        fleet = f;
        Fleet use = f;
        String own = main.nameService().ownName();
        String name = main.names().firstFree(Names.Kind.PC, own == null ? List.of() : List.of(own));
        if (name == null) {
            failed = true;
            main.logLine(prefix + "spectator steps skipped - no free PC name.");
            return;
        }
        pilotName = name;
        Thread t = new Thread(() -> {
            try {
                double lat = Fleet.DEFAULT_LAT, lon = Fleet.DEFAULT_LON;
                VirtualPilotPc p = new VirtualPilotPc(use, main.config(), name, line -> SwingUtilities.invokeLater(() -> main.logLine(prefix + line)));
                pilot = p;
                for (int i = 0; i < OBSERVED; i++) {
                    double[] at = use.nextPlace(lat, lon);
                    VirtualDrone d = use.add(use.freeName(), at[0], at[1], at[2]);
                    d.setBeeperless(true);
                    watched.add(d);
                }
                p.fly(lat + 0.0008, lon + 0.0012);
                List<String> names = traceNames(p, watched);
                traced = names;
                SwingUtilities.invokeLater(() -> main.tourHost().begin(names)); // gone with the tour's clean-up
            } catch (Exception e) {
                failed = true;
                SwingUtilities.invokeLater(() -> main.logLine(prefix + "no virtual pilot PC - " + e.getMessage()));
            }
        }, "tourPilotPc");
        t.setDaemon(true);
        t.start();
    }

    /** The virtual pilot lands and observes its drones (a thread: the landing takes a few seconds). */
    void observe() {
        VirtualPilotPc p = pilot;
        if (p == null || observing) return;
        observing = true;
        List<VirtualDrone> list = List.copyOf(watched);
        Thread t = new Thread(() -> {
            try {
                p.observe(list);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> main.logLine(prefix + "the virtual pilot can't observe - " + e.getMessage()));
            }
        }, "tourPilotPcObserve");
        t.setDaemon(true);
        t.start();
    }

    /** This PC shows the pilot PC's observation (its tiles in the video area). */
    boolean mirroredObserving() {
        MirrorController m = main.mirror();
        Mirror.Mode mode = m == null ? null : m.mirroredMode();
        return mode != null && mode.mode() == Mirror.OBSERVER;
    }

    /** The names this part brings: the pilot PC's, its own drone's and the observed ones. */
    private static List<String> traceNames(VirtualPilotPc p, List<VirtualDrone> drones) {
        List<String> n = new ArrayList<>();
        if (p != null) {
            n.add(p.name());
            VirtualDrone own = p.drone();
            if (own != null) n.add(own.name());
        }
        for (VirtualDrone d : drones) n.add(d.name());
        return n;
    }

    @Override
    public void close() {
        VirtualPilotPc p = pilot;
        pilot = null;
        Fleet f = fleet;
        List<VirtualDrone> list = List.copyOf(watched);
        List<String> names = new ArrayList<>(traced);
        for (String n : traceNames(p, list)) if (!names.contains(n)) names.add(n);
        if (pilotName != null && !names.contains(pilotName)) names.add(pilotName);
        traced = List.of();
        if (!names.isEmpty()) main.tourHost().forget(names); // a spectator link to its pilot PC ends here, cleanly
        watched.clear();
        pilotName = null;
        Fleet own = ownFleet;
        ownFleet = null;
        started = false;
        observing = false;
        failed = false;
        Thread t = new Thread(() -> { // the pilot PC's link and drones closed off the Swing thread
            if (p != null) p.close();
            if (f != null && f != own) for (VirtualDrone d : list) f.remove(d);
            if (own != null) own.close();
            // after they stopped answering: out of the name map, the PHONE list, the fleet and the map (the X too)
            if (!names.isEmpty()) SwingUtilities.invokeLater(() -> main.tourHost().forget(names));
        }, "tourPilotPcClose");
        t.setDaemon(true);
        t.start();
    }
}
