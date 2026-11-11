package de.droiddrone.pc.gui;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.FleetManager;
import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.PossiblyFlying;
import de.droiddrone.pc.gui.PowerController.Asleep;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FLEET MANAGEMENT (1.0.52) out of MainWindow: modes queued for phones without connecting (FleetManager), sent ahead
 * of a sleeping phone's check-in answer, and the FLEET tab's cards in the map window.
 */
final class FleetController {
    /** What the fleet needs from the window. */
    interface Host {
        /** This PC's link, or null. */
        Connection connection();

        /** The drone's motion detection level (0 OFF .. 3 HIGH). */
        int detectionFor(String phone);

        /** The drone's sound is muted on this PC. */
        boolean mutedFor(String phone);

        /** The overwatched drones into the settings. */
        void saveWatched();

        void logLine(String text);

        /** The map window, or null before it was first opened (never opens it). */
        MapWindow mapWindowIfOpened();

        /** 1.0.56: the cards to show - a spectator shows the pilot PC's (the mirror). */
        default List<FleetCards.Card> fleetShown(List<FleetCards.Card> own) {
            return own;
        }

        /** The phone's camera pose when this PC was last connected to it, standing (null: none known). */
        default CameraFootprint.Pose lastPose(String phone) {
            return null;
        }
    }

    /** The drone stands this far (m) from where its known pose was: that view is gone, none rather than a wrong one. */
    static final double MOVED_M = 25;

    /**
     * FLEET's OVERWATCH: the camera's view for the map - from this PC's last connection to it, else from its last
     * overwatch - and none when the drone now stands elsewhere (1.0.62: the old view pointed where it no longer looks).
     */
    static CameraFootprint.Pose overwatchPose(CameraFootprint.Pose lastConnected, CameraFootprint.Pose lastWatched, Names.Position now) {
        CameraFootprint.Pose p = lastConnected != null ? lastConnected : lastWatched;
        if (p == null || now == null) return p;
        double dn = (now.lat() - p.lat()) * 111_320.0, de = (now.lon() - p.lon()) * 111_320.0 * Math.cos(Math.toRadians(p.lat()));
        return Math.hypot(dn, de) > MOVED_M ? null : p;
    }

    private final FleetManager fleet;
    private final PcConfig config;
    private final Names names;
    private final NameService nameService;
    private final OverwatchHub overwatch;
    private final PowerController power;
    /** MainWindow's phones left in STANDBY and drones marked CRASHED (shared, read here). */
    private final Map<String, String> standbyPhones;
    private final Map<String, Long> crashed;
    private final Host host;
    /** The drone's battery per cell as this PC last read it while connected: name -> {volts per cell, wall ms}. */
    private final Map<String, double[]> lastCell = new ConcurrentHashMap<>();
    /** Overwatch clips per drone (count, last start), read off the Swing thread every 15 s. */
    private volatile Map<String, long[]> fleetClips = Map.of();
    private long fleetClipsAt;

    FleetController(FleetManager fleet, PcConfig config, Names names, NameService nameService, OverwatchHub overwatch,
                    PowerController power, Map<String, String> standbyPhones, Map<String, Long> crashed, Host host) {
        this.fleet = fleet;
        this.config = config;
        this.names = names;
        this.nameService = nameService;
        this.overwatch = overwatch;
        this.power = power;
        this.standbyPhones = standbyPhones;
        this.crashed = crashed;
        this.host = host;
    }

    FleetManager manager() {
        return fleet;
    }

    /** A sleeping phone's check-in (any thread): its queued fleet mode to send ahead of the answer, or null. */
    byte[] hello(String ip, byte[] hello) {
        String name = names.nameAt(ip);
        FleetManager f = fleet;
        if (name == null || f == null) return null;
        long now = System.currentTimeMillis();
        return f.onHello(name, ControlLink.SleepHello.read(hello, hello.length, now), now);
    }

    /** The phone took a queued mode: the lists follow at once, not at its next word. */
    void taken(FleetManager.Wanted w) {
        SwingUtilities.invokeLater(() -> {
            String ip = names.ipOf(w.phone());
            if (ip == null) return;
            if (w.mode() == ControlLink.POWER_STANDBY) {
                power.clear(ip);
                standbyPhones.put(ip, w.phone());
            } else {
                standbyPhones.remove(ip);
                power.remember(new Asleep(w.phone(), ip, System.currentTimeMillis(), w.intervalMin(), w.mode() == ControlLink.POWER_OVERWATCH));
            }
            host.logLine(w.phone() + " is in " + FleetManager.modeName(w.mode()) + " (fleet).");
            refresh();
        });
    }

    /** True when this PC's pilot link is to that phone. */
    boolean connectedTo(String phone) {
        Connection c = host.connection();
        return c != null && !c.getLink().isSpectator() && phone.equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY));
    }

    /** A card's button: the mode queued for the phone - or refused, said beside the button. */
    void request(String phone, int mode, int intervalMin, JComponent near) {
        if (fleet == null) return;
        NameService.DronePosition pos = null;
        for (NameService.DronePosition d : nameService.dronePositions()) if (d.name().equalsIgnoreCase(phone)) pos = d;
        String reason = PossiblyFlying.reasonNow();
        boolean flyingHere = PossiblyFlying.now() && (connectedTo(phone)
                || reason != null && reason.toLowerCase(Locale.ROOT).contains(phone.toLowerCase(Locale.ROOT)));
        String no = FleetManager.refusal(pos == null ? null : pos.position().armed(), flyingHere, connectedTo(phone), fleet.pcBuild(phone));
        if (no != null) {
            new LockedNotice(near, near, "NOT NOW", no).pop();
            return;
        }
        int sensitivity = 0, flags = 0, owToken = 0;
        if (mode == ControlLink.POWER_OVERWATCH) {
            // as the LINK card's OVERWATCH: the drone's own detection level (OFF counts as NORMAL), its sound in the clip
            // unless muted here, and a new token - the camera's view on the map is the one it had last time, if any
            sensitivity = host.detectionFor(phone);
            if (sensitivity == 0) sensitivity = 2;
            flags = host.mutedFor(phone) ? 0 : OverwatchProtocol.MODE_FLAG_SOUND;
            do {
                owToken = new SecureRandom().nextInt();
            } while (owToken == 0);
            CameraFootprint.Pose watched = null;
            for (OverwatchHub.Watched w : overwatch.watched()) if (w.drone().equalsIgnoreCase(phone)) watched = w.pose();
            CameraFootprint.Pose pose = overwatchPose(host.lastPose(phone), watched, pos == null ? null : pos.position());
            overwatch.watch(phone, owToken, pose);
            host.saveWatched();
        }
        fleet.queue(phone, mode, intervalMin, sensitivity, flags, owToken, System.currentTimeMillis());
        host.logLine("Fleet: " + FleetManager.modeName(mode) + " queued for " + phone + " - sent when it listens.");
        nameService.scanSoon(); // an awake phone gets it now
        refresh();
    }

    void cancel(String phone) {
        if (fleet == null) return;
        fleet.cancel(phone);
        host.logLine("Fleet: queued mode for " + phone + " dropped.");
        refresh();
    }

    /** A drone's pack per cell as if this PC had read it at that wall time (the tour's drones). */
    void lastCell(String drone, double cellVolts, long atWall) {
        lastCell.put(drone.toLowerCase(Locale.ROOT), new double[]{cellVolts, atWall});
    }

    void forgetCell(String drone) {
        lastCell.remove(drone.toLowerCase(Locale.ROOT));
    }

    /** Once a second: every phone's card for the map window (only while it is open). */
    void refresh() {
        MapWindow w = host.mapWindowIfOpened();
        if (fleet == null || w == null || !w.isShown()) return;
        w.setFleet(host.fleetShown(cards()));
    }

    /** Every phone's card now (null: no fleet); also for the spectators' mirror (1.0.56), map window open or not. */
    List<FleetCards.Card> cards() {
        if (fleet == null) return null;
        long now = System.currentTimeMillis();
        if (now - fleetClipsAt > 15_000) {
            fleetClipsAt = now;
            CompletableFuture.runAsync(() -> {
                Map<String, long[]> m = new HashMap<>();
                ClipIndex clipIndex = overwatch.index();
                for (String d : clipIndex.drones()) {
                    long last = 0;
                    List<ClipIndex.Clip> list = clipIndex.clips(d);
                    for (ClipIndex.Clip c : list) last = Math.max(last, c.startedAt());
                    m.put(d.toLowerCase(Locale.ROOT), new long[]{list.size(), last});
                }
                fleetClips = m;
            });
        }
        // the flown drone's battery per cell, while this PC reads it
        Connection c = host.connection();
        String flown = config.getString(Names.PHONE_NAME_KEY);
        FcTelemetry live = c != null && !c.getLink().isSpectator() && c.getLink().getState() == ControlLink.State.READY ? c.getLink().getTelemetry() : null;
        if (live != null && live.getBattery() != null && live.getBattery().cellCountKnown() && !flown.isEmpty()) {
            lastCell.put(flown.toLowerCase(Locale.ROOT), new double[]{live.getBattery().cellVolts(), now});
        }
        Map<String, NameService.DronePosition> positions = new HashMap<>();
        for (NameService.DronePosition d : nameService.dronePositions()) positions.put(d.name().toLowerCase(Locale.ROOT), d);
        Map<String, int[]> alerts = new HashMap<>();
        for (OverwatchHub.View v : overwatch.views()) {
            alerts.computeIfAbsent(v.drone().toLowerCase(Locale.ROOT), k -> new int[1])[0]++;
        }
        List<FleetCards.Card> cards = new ArrayList<>();
        for (Names.Entry e : names.list(Names.Kind.PHONE)) {
            String id = e.name().toLowerCase(Locale.ROOT);
            boolean here = live != null && connectedTo(e.name());
            Asleep sleeping = e.ip() == null ? null : power.asleep(e.ip());
            ControlLink.SleepHello hello = e.ip() == null ? null : power.hello(e.ip());
            FleetManager.Heard heard = fleet.heard(e.name());
            NameService.DronePosition pos = positions.get(id);
            long heardAt = Math.max(e.lastSeen(), Math.max(hello == null ? 0 : hello.receivedAt(), heard == null ? 0 : heard.at()));
            if (here) heardAt = now;
            FleetProtocol.Status st = heard == null ? null : heard.status();
            int said = st != null && st.mode >= 0 ? st.mode : pos != null ? pos.position().powerMode() : -1;
            long saidAge = st != null ? now - heard.at() : pos != null ? now - pos.at() : -1;
            boolean wasFlying = pos != null && (Boolean.TRUE.equals(pos.position().armed()) || pos.position().flown());
            FleetCards.Badge badge = FleetCards.badge(here, sleeping != null, sleeping != null && sleeping.overwatch(),
                    sleeping == null ? 0 : power.checkInOverdue(sleeping, now), e.ip() != null && standbyPhones.containsKey(e.ip()),
                    said, saidAge, e.online() || now - heardAt < 30_000, wasFlying);
            if (crashed.containsKey(e.name()) && !here) badge = new FleetCards.Badge(MapView.Kind.CRASHED, "CRASHED");
            // the drone's battery per cell: live here, else what this PC last read
            double cell = Double.NaN;
            long cellAge = -1;
            double[] lc = lastCell.get(id);
            if (lc != null) {
                cell = lc[0];
                cellAge = here ? 0 : now - (long) lc[1];
            }
            // the phone's battery: its newest word
            int pct = -1;
            boolean charging = false;
            if (here && live.getPhoneBatteryPercent() >= 0) {
                pct = live.getPhoneBatteryPercent();
            } else if (hello != null && (st == null || hello.receivedAt() >= heard.at()) && hello.batteryPercent() >= 0) {
                pct = hello.batteryPercent();
                charging = hello.charging();
            } else if (st != null && st.batteryPercent >= 0) {
                pct = st.batteryPercent;
                charging = st.charging;
            }
            long next = -1;
            int interval = st != null && st.intervalMin > 0 ? st.intervalMin : 0;
            if (sleeping != null) {
                interval = sleeping.intervalMin();
                next = sleeping.listening() ? FleetCards.LISTENING : power.nextCheck(sleeping, now) - now;
            }
            int signal = st == null ? -1 : st.signal, fix = st == null ? -1 : st.gpsFix, sats = st == null ? -1 : st.sats;
            if (here && live.getGps() != null) {
                fix = live.getGps().fix() >= 2 ? 1 : 0;
                sats = live.getGps().sats();
            }
            int phoneTemp = st != null && st.tempKnown() ? st.batteryTempDeci : FleetCards.NO_TEMP;
            int fcTemp = here && live.getStatus() != null && live.getStatus().coreTempKnown() ? live.getStatus().coreTempC() : FleetCards.NO_TEMP;
            long[] clips = fleetClips.get(id);
            int[] views = alerts.get(id);
            int events = (int) Math.max(clips == null ? 0 : clips[0], views == null ? 0 : views[0]);
            FleetManager.Wanted wanted = fleet.wanted(e.name());
            if (wanted != null && !FleetCards.showsQueued(badge)) wanted = null;
            int build = fleet.pcBuild(e.name());
            if (build < 0) build = FleetProtocol.pcBuild(e.version());
            // the phone this PC flies takes its modes too (2026-11-10, the pilot: "not possible to select standby from the map")
            String note = !here && build >= 0 && build < FleetProtocol.MIN_PC_BUILD ? "Needs phone app pc." + FleetProtocol.MIN_PC_BUILD : null;
            cards.add(new FleetCards.Card(e.name(), e.isVirtual(), badge.kind(), badge.text(), cell, cellAge, pct, charging, next, interval,
                    heardAt <= 0 ? -1 : now - heardAt, signal, fix, sats, events, clips == null ? 0 : clips[1], phoneTemp, fcTemp,
                    wanted == null ? null : FleetManager.modeName(wanted.mode()), note,
                    wanted == null || wanted.queuedAt() <= 0 ? -1 : Math.max(0, now - wanted.queuedAt())));
        }
        return cards;
    }
}
