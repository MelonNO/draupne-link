package de.droiddrone.pc.air;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fleet PRD FD-1 / area E on the wire: one follower group flown from this PC. Its own UDP socket sends the fleet
 * commands (FleetCmd, keyed) to each drone's phone and takes their reports (FleetStatus); {@link GroupController}
 * decides five times a second (tick) from them. The lead is tracked the same way (its report carries its height above
 * sea level, which the pilot link doesn't). What it sends: each follower's GroupOrder every tick, its ExitPlan when
 * made again and every {@link #PLAN_EVERY_MS} besides, the lead's RTL height, a step aside to drones in the way.
 * Events (LINK LOST, GNSS LOST, TRAFFIC ...) go to the window. Nothing here runs on the stick or video path.
 */
public final class GroupLink implements AutoCloseable {
    public static final long TICK_MS = 200, PLAN_EVERY_MS = 10_000, TRACK_S = 20;

    /** What the window hears. */
    public interface Listener {
        void event(String text);

        /** A new lead: the pilot's sticks go to it (DISCARD LEAD, or the lead silent 30 s). */
        void newLead(String drone);
    }

    private final DatagramSocket socket;
    private final String key;
    private final Map<String, InetSocketAddress> phones = new ConcurrentHashMap<>();
    private final Map<String, FleetStatus> last = new ConcurrentHashMap<>();
    private final Map<String, Long> heardAt = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, String> byAddress = new ConcurrentHashMap<>();
    private final Map<String, ExitPlan> plans = new HashMap<>();
    private final Map<String, Long> planSentAt = new HashMap<>();
    private final Listener listener;
    private volatile GroupController group;
    private volatile String lead;
    private double lat0 = Double.NaN, lon0 = Double.NaN;
    private volatile boolean open = true;
    private long trackedAt;

    public GroupLink(String key, Listener listener) throws IOException {
        this(key, listener, new DatagramSocket());
    }

    GroupLink(String key, Listener listener, DatagramSocket socket) {
        this.key = key;
        this.listener = listener;
        this.socket = socket;
        Thread r = new Thread(this::receive, "groupLinkReceive");
        r.setDaemon(true);
        r.start();
        Thread t = new Thread(this::run, "groupLinkTick");
        t.setDaemon(true);
        t.start();
    }

    /** A drone's phone and where it is reached (NameService's address, the link's port). */
    public void phone(String drone, InetSocketAddress at) {
        InetSocketAddress before = phones.put(drone, at);
        if (before != null) byAddress.remove(before);
        byAddress.put(at, drone);
    }

    /** The newest report of a drone, or null. */
    public FleetStatus status(String drone) {
        return last.get(drone);
    }

    public String lead() {
        return lead;
    }

    public GroupController controller() {
        return group;
    }

    /** Asks these drones for their reports (the start of FOLLOW GROUP: where are they?). */
    public void track(List<String> drones) {
        for (String d : drones) send(d, FleetCmd.track((int) TRACK_S));
    }

    /**
     * FOLLOW GROUP (FL-1): the group formed round where the followers are, the lead flown by the pilot. Every drone must
     * have reported (track() first). Returns why not, or null.
     */
    public synchronized String form(String leadDrone, List<String> followers, double groundAmsl) {
        Map<String, Double> batteries = new LinkedHashMap<>();
        double sumLat = 0, sumLon = 0;
        for (String f : followers) {
            FleetStatus s = last.get(f);
            if (s == null) return f + " hasn't reported.";
            if (!s.has(FleetStatus.ARMED)) return f + " is on the ground.";
            if (!s.has(FleetStatus.GNSS)) return f + " has no GPS.";
            batteries.put(f, s.battery == 255 ? 50.0 : s.battery);
            sumLat += s.lat;
            sumLon += s.lon;
        }
        if (followers.isEmpty()) return "No followers.";
        FleetStatus ls = last.get(leadDrone);
        if (ls == null) return "The lead hasn't reported.";
        lat0 = sumLat / followers.size();
        lon0 = sumLon / followers.size();
        GroupController g = new GroupController(AirParams.defaults(), groundAmsl, lat0, lon0,
                de.droiddrone.pc.air.virt.VirtualAirspace.prover(AirParams.defaults(), groundAmsl));
        V2 rally = local(ls.lat, ls.lon);
        g.form(leadDrone, batteries, new V2(0, 0), rally);
        group = g;
        lead = leadDrone;
        plans.clear();
        planSentAt.clear();
        send(leadDrone, FleetCmd.lead(true, 0));
        listener.event("FOLLOW GROUP: " + followers.size() + " followers, lead " + leadDrone);
        return null;
    }

    /** DISCARD LEAD (E4, FL-6): the next lead from the bottom row; the window gives it the pilot's sticks. */
    public synchronized String discardLead(long now) {
        GroupController g = group;
        if (g == null) return null;
        String was = lead;
        String next = g.discardLead(now);
        if (next != null) {
            lead = next;
            if (was != null) send(was, FleetCmd.lead(false, 0));
            send(next, FleetCmd.disband()); // a lead is no follower: its brain lets go, the pilot flies it
            send(next, FleetCmd.lead(true, 0));
            listener.newLead(next);
        }
        return next;
    }

    /** The floor for the group (FL-14), live. */
    public void setFloor(double m) {
        GroupController g = group;
        if (g != null) g.setFloor(m);
    }

    /** The group ends: every follower holds (LOITER) and leaves the group. */
    public synchronized void disband() {
        GroupController g = group;
        group = null;
        if (g == null) return;
        for (String d : phones.keySet()) send(d, FleetCmd.disband());
        listener.event("Group ended: the followers hold where they are.");
    }

    private V2 local(double lat, double lon) {
        return new V2(GeoLocal.east(lat0, lon0, lat, lon), GeoLocal.north(lat0, lon0, lat, lon));
    }

    // ------------------------------------------------------------------ the tick

    private void run() {
        while (open) {
            try {
                Thread.sleep(TICK_MS);
                tick(System.currentTimeMillis());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                listener.event("Group: " + e);
            }
        }
    }

    synchronized void tick(long now) {
        GroupController g = group;
        if (g == null) return;
        if (now - trackedAt > 5000) { // the reports keep coming (each command does that too)
            trackedAt = now;
            track(new ArrayList<>(phones.keySet()));
        }
        Map<String, GroupController.Report> reports = new HashMap<>();
        GroupController.Report leadReport = null;
        List<GroupController.Other> others = new ArrayList<>();
        for (Map.Entry<String, FleetStatus> e : last.entrySet()) {
            FleetStatus s = e.getValue();
            long heard = heardAt.getOrDefault(e.getKey(), 0L);
            GroupController.Report r = new GroupController.Report(e.getKey(), local(s.lat, s.lon), s.altAmsl, new V2(s.velE, s.velN),
                    s.velU, s.battery == 255 ? Double.NaN : s.battery, s.has(FleetStatus.GNSS), s.errM, heard,
                    s.phase != 255, s.has(FleetStatus.LANDED), s.homeAmsl);
            if (e.getKey().equals(lead)) leadReport = r;
            else if (g.members().containsKey(e.getKey())) reports.put(e.getKey(), r);
            else others.add(new GroupController.Other(e.getKey(), r.pos(), r.altAmsl(), r.vel(), false));
        }
        GroupController.Out out = g.tick(now, reports, leadReport, others, new V2(0, 0));
        for (Map.Entry<String, GroupOrder> o : out.orders().entrySet()) send(o.getKey(), FleetCmd.group(o.getValue().encode()));
        plans.putAll(out.plans());
        for (Map.Entry<String, ExitPlan> p : plans.entrySet()) {
            Long at = planSentAt.get(p.getKey());
            if (out.plans().containsKey(p.getKey()) || at == null || now - at >= PLAN_EVERY_MS) {
                send(p.getKey(), FleetCmd.exit(p.getValue().encode()));
                planSentAt.put(p.getKey(), now);
            }
        }
        if (lead != null && out.leadRtlAltRelM() > 0) send(lead, FleetCmd.lead(true, out.leadRtlAltRelM()));
        for (Map.Entry<String, V2> c : out.clear().entrySet()) {
            double[] ll = GeoLocal.offset(lat0, lon0, c.getValue().e(), c.getValue().n());
            FleetStatus s = last.get(c.getKey());
            if (s != null) send(c.getKey(), FleetCmd.clear(ll[0], ll[1], s.altAmsl));
        }
        for (String ev : out.events()) listener.event(ev);
        if (out.newLead() != null && !out.newLead().equals(lead)) { // the lead silent 30 s: taken over (FL-5)
            String was = lead;
            lead = out.newLead();
            if (was != null) send(was, FleetCmd.lead(false, 0));
            send(lead, FleetCmd.disband());
            send(lead, FleetCmd.lead(true, out.leadRtlAltRelM()));
            listener.newLead(lead);
        }
    }

    // ------------------------------------------------------------------ the wire

    void send(String drone, byte[] inner) {
        InetSocketAddress to = phones.get(drone);
        if (to == null || !open) return;
        try {
            byte[] p = FleetCmd.wrap(key, inner);
            socket.send(new DatagramPacket(p, p.length, to.getAddress(), to.getPort()));
        } catch (IOException ignored) {
            // the next tick sends again
        }
    }

    private void receive() {
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (!open) return;
                continue;
            }
            FleetStatus s = FleetStatus.decode(p.getData(), p.getLength());
            if (s == null) continue;
            String drone = byAddress.get(new InetSocketAddress(p.getAddress(), p.getPort()));
            if (drone == null) continue; // a phone this group doesn't know
            last.put(drone, s);
            heardAt.put(drone, System.currentTimeMillis());
        }
    }

    @Override
    public void close() {
        open = false;
        socket.close();
    }
}
