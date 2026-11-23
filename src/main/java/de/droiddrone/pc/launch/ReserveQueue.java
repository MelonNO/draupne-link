package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Fleet PRD AL-5 / AL-6 / AL-17 (FA4, FA10, FA17, FA20): CALL RESERVE on the PC. Each press queues one more drone -
 * the marked one that would arrive first ({@link ReserveChoice}) - so several presses call several drones; the OSD
 * lists them in arrival order ("+3 RESERVES: Sentry-3 1:20 ..."); the last call can be cancelled until that drone arms.
 * A drone that fails its checks or its launch is replaced at once by the next one (FA17). A call already flying when
 * it is cancelled, or when the group is lost, joins whoever is the lead now (FA10).
 * <p>
 * Each call gets its own approach layer and launch column from {@link LaunchAirspace} (AC-5): a launch whose column
 * can't be reserved yet waits, and its order is held back until it can (overlapping columns go one after the other).
 * Several may climb at once (FA20). The queue makes the orders; the wiring sends each at its drone's next check-in
 * (AL-7). Pure state on one thread (the GUI's or the link's), times on the monotonic clock in ms.
 */
public final class ReserveQueue {
    /** The OSD says NO RESERVE this long after a press found none. */
    public static final long NO_RESERVE_MS = 5000;
    /** The launch column's radius (AC-4: 10 m). */
    public static final double COLUMN_RADIUS_M = 10;

    /** Where the queue's calls go. */
    public enum Phase { WAITING_COLUMN, ORDERED, FLYING, DONE }

    /** One call. status: the phone's last word (null until it answers). */
    public static final class Call {
        public final int callId;
        public final String name;
        public final int markId;
        public final double lat, lon, approachM;
        public final long calledAt;
        long arrivalAt;
        Phase phase = Phase.WAITING_COLUMN;
        LaunchWire.Status status;
        LaunchWire.Order order;

        Call(int callId, String name, int markId, double lat, double lon, double approachM, long calledAt, long arrivalAt) {
            this.callId = callId;
            this.name = name;
            this.markId = markId;
            this.lat = lat;
            this.lon = lon;
            this.approachM = approachM;
            this.calledAt = calledAt;
            this.arrivalAt = arrivalAt;
        }

        public Phase phase() {
            return phase;
        }

        public LaunchWire.Status status() {
            return status;
        }

        public long arrivalAt() {
            return arrivalAt;
        }

        /** The state word for FLEET and the OSD: CALLED until the phone answers, then its own. */
        public String word() {
            if (status == null) return phase == Phase.WAITING_COLUMN ? "WAITING FOR COLUMN" : "CALLED";
            String w = LaunchSequence.stateWord(status.state);
            String r = LaunchSequence.reasonWord(status.reason);
            return r.isEmpty() ? w : w + " - " + r;
        }

        /** The motors may run (or were told to): no cancel any more. */
        public boolean armedOrArming() {
            return status != null && (LaunchSequence.motorsMayRun(status.state) || status.armed);
        }
    }

    /** What a press, a status or a tick gives: orders to send (in this order) and what to tell the pilot. */
    public record Out(List<LaunchWire.Order> orders, List<String> events) {
        static Out empty() {
            return new Out(new ArrayList<>(), new ArrayList<>());
        }
    }

    private final ReserveChoice choice;
    private final LaunchAirspace airspace;
    private final List<Call> calls = new ArrayList<>();
    /** name#markId of every drone that has answered a call: each answers one (a new mark makes it callable again). */
    private final Set<String> answered = new HashSet<>();
    private int nextCallId = de.droiddrone.pc.route.RouteSender.randomId(); // new at every start: the phone answers a call it knows with its status
    private long noReserveAt = Long.MIN_VALUE / 2; // (MIN_VALUE itself: now - it overflowed, NO RESERVE for good)
    private int leadSysId = LaunchWire.NONE;
    private double floorM = 40;
    private int minBatteryPercent;

    public ReserveQueue(ReserveChoice choice, LaunchAirspace airspace) {
        this.choice = choice;
        this.airspace = airspace;
    }

    /** The follower floor as set now (FL-14: a reserve called later climbs to the floor as it is at that moment). */
    public void setFloor(double floorM) {
        this.floorM = floorM;
    }

    /** The lead now (FOLL_SYSID); FA10: calls on their way join whoever this is. */
    public void setLead(int sysId) {
        leadSysId = sysId;
    }

    public int lead() {
        return leadSysId;
    }

    /** The battery a reserve needs for a launch (its phone checks it, AL-3); 0 = the phone's default. */
    public void setMinBattery(int percent) {
        minBatteryPercent = percent;
    }

    private static String key(String name, int markId) {
        return name.toLowerCase(Locale.ROOT) + "#" + markId;
    }

    /**
     * CALL RESERVE pressed: one more drone. Empty orders and the event NO RESERVE when none is left.
     * @param reserves every marked drone as the PC knows it now
     */
    public Out press(long now, Collection<ReserveChoice.Reserve> reserves, double groupLat, double groupLon) {
        Out out = Out.empty();
        Set<String> taken = new HashSet<>();
        for (ReserveChoice.Reserve r : reserves) if (answered.contains(key(r.name(), r.markId()))) taken.add(r.name());
        for (Call c : calls) if (c.phase != Phase.DONE) taken.add(c.name); // a new mark makes a drone callable again
        Optional<ReserveChoice.Arrival> a = choice.pick(reserves, taken, groupLat, groupLon);
        if (a.isEmpty()) {
            noReserveAt = now;
            out.events().add("NO RESERVE");
            return out;
        }
        ReserveChoice.Reserve r = a.get().reserve();
        double approach = airspace.approachLayerFor(r.name(), r.lat(), r.lon());
        if (Double.isNaN(approach) || approach < floorM) {
            // no layer of its own: it stays down; it doesn't answer this call (AC-13: NO LAYER FREE)
            noReserveAt = now;
            out.events().add("NO LAYER FREE");
            return out;
        }
        for (Call c : calls) {
            if (c.phase != Phase.DONE && Math.abs(c.approachM - approach) < 1e-6) {
                noReserveAt = now;
                out.events().add("NO LAYER FREE"); // AC-5: reserves on their way never share a layer
                return out;
            }
        }
        answered.add(key(r.name(), r.markId()));
        Call c = new Call(nextCallId(), r.name(), r.markId(), r.lat(), r.lon(), approach, now, now + Math.round(a.get().seconds() * 1000));
        calls.add(c);
        out.events().add("CALLED " + r.name());
        tryOrder(c, out);
        return out;
    }

    private int nextCallId() {
        int id = nextCallId;
        nextCallId = nextCallId % 65535 + 1;
        return id;
    }

    /** Hold the column and make the order, or keep waiting for the column (AC-4). */
    private void tryOrder(Call c, Out out) {
        if (c.phase != Phase.WAITING_COLUMN) return;
        if (!airspace.reserveColumn(c.name, c.lat, c.lon, COLUMN_RADIUS_M, c.approachM)) return;
        LaunchWire.Order o = new LaunchWire.Order();
        o.kind = LaunchWire.KIND_LAUNCH;
        o.markId = c.markId;
        o.callId = c.callId;
        o.approachHeightM = c.approachM;
        o.floorM = floorM;
        o.columnLat = c.lat;
        o.columnLon = c.lon;
        o.leadSysId = leadSysId;
        o.minBatteryPercent = minBatteryPercent;
        c.order = o;
        c.phase = Phase.ORDERED;
        out.orders().add(o);
    }

    /** Now and then: calls waiting for their column try again. */
    public Out tick(long now) {
        Out out = Out.empty();
        for (Call c : calls) tryOrder(c, out);
        return out;
    }

    /**
     * Cancel the last call (AL-5), until that drone arms. The cancel order goes to its phone; a call still waiting
     * for its column just goes. Empty when there is nothing left to cancel.
     */
    public Out cancelLast(long now) {
        Out out = Out.empty();
        for (int i = calls.size() - 1; i >= 0; i--) {
            Call c = calls.get(i);
            if (c.phase == Phase.DONE) continue;
            if (c.armedOrArming() || c.phase == Phase.FLYING) return out; // the last one flies: no cancel
            if (c.order != null) {
                LaunchWire.Order o = new LaunchWire.Order();
                o.kind = LaunchWire.KIND_CANCEL;
                o.markId = c.markId;
                o.callId = c.callId;
                out.orders().add(o);
            }
            end(c);
            answered.remove(key(c.name, c.markId)); // it never armed: still marked, it may answer a later call
            out.events().add("CANCELLED " + c.name);
            return out;
        }
        return out;
    }

    /** The last call can be cancelled now. */
    public boolean canCancel() {
        for (int i = calls.size() - 1; i >= 0; i--) {
            Call c = calls.get(i);
            if (c.phase == Phase.DONE) continue;
            return !c.armedOrArming() && c.phase != Phase.FLYING;
        }
        return false;
    }

    /**
     * The phone's launch status. A drone that didn't launch or failed is replaced at once by the next reserve (FA17,
     * {@code reserves} as {@link #press}); the column is freed once it passed the floor, its layer once it is in the
     * group or gone.
     */
    public Out onStatus(long now, String name, LaunchWire.Status s, Collection<ReserveChoice.Reserve> reserves,
                        double groupLat, double groupLon) {
        Out out = Out.empty();
        if (s == null) return out;
        Call c = find(name, s.callId);
        if (c == null || c.phase == Phase.DONE) return out;
        c.status = s;
        switch (s.state) {
            case LaunchSequence.CLIMBING, LaunchSequence.ARMING -> {
                if (s.armed || s.state == LaunchSequence.CLIMBING) c.phase = Phase.FLYING;
                if (s.passedFloor) airspace.releaseColumn(c.name);
            }
            case LaunchSequence.ON_THE_WAY, LaunchSequence.WAITING_FOR_TRANSIT, LaunchSequence.IN_TRANSIT -> {
                c.phase = Phase.FLYING;
                airspace.releaseColumn(c.name);
            }
            case LaunchSequence.IN_GROUP -> {
                end(c);
                out.events().add("RESERVE ARRIVED " + c.name);
            }
            case LaunchSequence.NOT_LAUNCHED, LaunchSequence.LAUNCH_FAILED -> {
                end(c);
                String why = LaunchSequence.reasonWord(s.reason);
                out.events().add((s.state == LaunchSequence.LAUNCH_FAILED ? "LAUNCH FAILED " : "NOT LAUNCHED ") + c.name
                        + (why.isEmpty() ? "" : " - " + why));
                Out next = press(now, reserves, groupLat, groupLon); // FA17: call the next reserve
                out.orders().addAll(next.orders());
                out.events().addAll(next.events());
            }
            case LaunchSequence.CANCELLED -> end(c);
            default -> {
            }
        }
        return out;
    }

    /** The group is gone or the call was cancelled while it flies (FA10): it joins whoever is the lead now. */
    public int joinWhom() {
        return leadSysId;
    }

    private void end(Call c) {
        c.phase = Phase.DONE;
        airspace.releaseColumn(c.name);
        airspace.releaseLayer(c.name);
    }

    private Call find(String name, int callId) {
        for (Call c : calls) if (c.callId == callId && c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    /** The calls under way (not done), first arrival first. */
    public List<Call> active() {
        return calls.stream().filter(c -> c.phase != Phase.DONE).sorted(Comparator.comparingLong(Call::arrivalAt)).toList();
    }

    /** Every call made (for PREVIOUS FLIGHTS' launch log, AL-12). */
    public List<Call> all() {
        return List.copyOf(calls);
    }

    /**
     * The OSD's reserve list (AL-5, GU-5): "+3 RESERVES: Sentry-3 1:20  Sentry-1 2:05" in arrival order with the time
     * left; "NO RESERVE" for a while after a press found none; "" when there is nothing to say.
     */
    public String osdLine(long now) {
        List<Call> a = active();
        if (a.isEmpty()) return now - noReserveAt < NO_RESERVE_MS ? "NO RESERVE" : "";
        StringBuilder sb = new StringBuilder("+").append(a.size()).append(a.size() == 1 ? " RESERVE:" : " RESERVES:");
        for (Call c : a) sb.append("  ").append(c.name).append(' ').append(mmss(c.arrivalAt - now));
        return sb.toString();
    }

    static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }
}
