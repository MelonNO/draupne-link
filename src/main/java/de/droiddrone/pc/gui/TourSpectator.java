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
    private final List<VirtualDrone> watched = new ArrayList<>();
    private volatile boolean started, failed, observing;

    /** @param tourFleet the tour's own virtual fleet while it runs (its drones' announcement), or null */
    TourSpectator(MainWindow main, Supplier<Fleet> tourFleet) {
        this.main = main;
        this.tourFleet = tourFleet;
    }

    /** The steps, in order, for the end of the tour. */
    List<Tour.Step> steps() {
        List<Tour.Step> s = new ArrayList<>();
        s.add(new Tour.Step("SPECTATOR", "Another pilot is flying. Select SPECTATOR to watch.", main::tourRoleCombo)
                .before(this::start).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(main::tourSpectatorRole, "Select SPECTATOR", true));
        s.add(new Tour.Step("PILOT PC", "Pick " + VirtualPilotPc.NAME + ".", main::tourPhoneCombo).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(() -> VirtualPilotPc.NAME.equalsIgnoreCase(main.config().getString(Names.PHONE_NAME_KEY)),
                        "Pick " + VirtualPilotPc.NAME, true));
        s.add(new Tour.Step("WATCH", "Press CONNECT.", main::tourConnectButton).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(main::tourSpectating, "Press CONNECT", true));
        s.add(new Tour.Step("MIRROR", "Spectator mirrors the pilot PC: map, fleet and camera feeds. "
                + "If the pilot observes, you see that too.", main::tourVideo).live(main::tourVideo).skip(() -> failed).of(Tour.FEATURE_SPECTATOR));
        s.add(new Tour.Step("PILOT OBSERVES", "The pilot lands and starts OBSERVATION. Your view follows.", main::tourVideo)
                .before(this::observe).live(main::tourVideo).skip(() -> failed).of(Tour.FEATURE_SPECTATOR)
                .waitFor(this::mirroredObserving, "Watch...", false));
        return s;
    }

    /** The virtual pilot PC up and flying (a thread of its own: binding and take-off take a moment). */
    private void start() {
        if (started) return;
        started = true;
        Fleet f = tourFleet == null ? null : tourFleet.get();
        if (f == null) {
            if (!VirtualFleet.announcedNow().isEmpty()) { // the pilot's own fleet: not written over
                failed = true;
                main.logLine("Tour: spectator steps skipped - close the virtual fleet first.");
                return;
            }
            ownFleet = new Fleet(main.config(), VirtualFleet.file(), line -> SwingUtilities.invokeLater(() -> main.logLine("Tour: " + line)));
            ownFleet.startHeartbeat();
            f = ownFleet;
        }
        fleet = f;
        Fleet use = f;
        Thread t = new Thread(() -> {
            try {
                double lat = Fleet.DEFAULT_LAT, lon = Fleet.DEFAULT_LON;
                VirtualPilotPc p = new VirtualPilotPc(use, main.config(), line -> SwingUtilities.invokeLater(() -> main.logLine("Tour: " + line)));
                pilot = p;
                for (int i = 0; i < OBSERVED; i++) {
                    double[] at = use.nextPlace(lat, lon);
                    watched.add(use.add(use.freeName(), at[0], at[1], at[2]));
                }
                p.fly(lat + 0.0008, lon + 0.0012);
            } catch (Exception e) {
                failed = true;
                SwingUtilities.invokeLater(() -> main.logLine("Tour: no virtual pilot PC - " + e.getMessage()));
            }
        }, "tourPilotPc");
        t.setDaemon(true);
        t.start();
    }

    /** The virtual pilot lands and observes its drones (a thread: the landing takes a few seconds). */
    private void observe() {
        VirtualPilotPc p = pilot;
        if (p == null || observing) return;
        observing = true;
        List<VirtualDrone> list = List.copyOf(watched);
        Thread t = new Thread(() -> {
            try {
                p.observe(list);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> main.logLine("Tour: the virtual pilot can't observe - " + e.getMessage()));
            }
        }, "tourPilotPcObserve");
        t.setDaemon(true);
        t.start();
    }

    /** This PC shows the pilot PC's observation (its tiles in the video area). */
    private boolean mirroredObserving() {
        MirrorController m = main.mirror();
        Mirror.Mode mode = m == null ? null : m.mirroredMode();
        return mode != null && mode.mode() == Mirror.OBSERVER;
    }

    @Override
    public void close() {
        VirtualPilotPc p = pilot;
        pilot = null;
        Fleet f = fleet;
        List<VirtualDrone> list = List.copyOf(watched);
        watched.clear();
        Fleet own = ownFleet;
        ownFleet = null;
        started = false;
        observing = false;
        failed = false;
        Thread t = new Thread(() -> { // the pilot PC's link and drones closed off the Swing thread
            if (p != null) p.close();
            if (f != null && f != own) for (VirtualDrone d : list) f.remove(d);
            if (own != null) own.close();
        }, "tourPilotPcClose");
        t.setDaemon(true);
        t.start();
    }
}
