package de.droiddrone.pc.air;

import de.droiddrone.common.group.GeoLocal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD RT-9 / FD-6 / AC-10: the RTL_ALT of every drone the PC sends out on its own (a dispatched drone, a reserve on
 * its way to the group, the pilot's own drone on a route) - planned, sent, and confirmed as ArduPilot answered it.
 * <ul>
 *   <li>Each drone has its own lowest height (RT-9: over the highest ground on its straight way home, by its route
 *   height); {@link SoloReturns} puts ways home that come near each other a layer apart above that. Every drone that
 *   goes out or comes back plans them all again: another's height may change.</li>
 *   <li>A height counts as on the drone only when its report says RTL_ALT is that height (dm). Until then the drone
 *   doesn't leave the ground ({@link #confirmed}).</li>
 * </ul>
 * No I/O and no threads of its own: FleetOps feeds it reports and sends what it says (FleetCmd RETURN). Times from
 * MonoClock.
 */
public final class ReturnHeights {
    /** RETURN again this often until the report has it. */
    public static final long RESEND_MS = 1000;
    /** Not confirmed this long: the pilot is told (once per height). */
    public static final long CONFIRM_WAIT_MS = 10_000;

    private static final class Entry {
        double farLat, farLon, rallyLat, rallyLon, homeAmsl, minM;
        int wantDm = -1;
        boolean confirmed, flew, said;
        long sentAt = Long.MIN_VALUE / 2, waitFrom = Long.MIN_VALUE;
    }

    private final Map<String, Entry> solo = new LinkedHashMap<>();
    private double refLat = Double.NaN, refLon;

    /**
     * A drone goes out on its own (or its way changed): the far end of its way out, its rally point, its home's height
     * above sea level and its own lowest RTL height (m above home, RT-9). Plans every drone again.
     * @return the drones whose height changed (m above home), this one among them when it is new
     */
    public synchronized Map<String, Double> put(String id, double farLat, double farLon, double rallyLat, double rallyLon,
                                                double homeAmsl, double minM, AirParams p) {
        if (Double.isNaN(refLat)) {
            refLat = farLat;
            refLon = farLon;
        }
        Entry e = solo.computeIfAbsent(id, k -> new Entry());
        e.farLat = farLat;
        e.farLon = farLon;
        e.rallyLat = Double.isNaN(rallyLat) ? farLat : rallyLat;
        e.rallyLon = Double.isNaN(rallyLon) ? farLon : rallyLon;
        e.homeAmsl = homeAmsl;
        e.minM = minM;
        return replan(p);
    }

    /** Back on the ground, in a group now, or its launch failed: the others are planned again without it. */
    public synchronized Map<String, Double> remove(String id, AirParams p) {
        if (solo.remove(id) == null) return Map.of();
        return replan(p);
    }

    public synchronized boolean contains(String id) {
        return solo.containsKey(id);
    }

    public synchronized List<String> drones() {
        return new ArrayList<>(solo.keySet());
    }

    /** Its planned RTL height (m above home, to the decimetre), NaN: none. */
    public synchronized double heightM(String id) {
        Entry e = solo.get(id);
        return e == null || e.wantDm < 0 ? Double.NaN : e.wantDm / 10.0;
    }

    /** Its lowest height (RT-9), NaN: none. */
    public synchronized double minM(String id) {
        Entry e = solo.get(id);
        return e == null ? Double.NaN : e.minM;
    }

    /** Its planned height is on the drone, read back. */
    public synchronized boolean confirmed(String id) {
        Entry e = solo.get(id);
        return e != null && e.confirmed;
    }

    /**
     * The drone's report: RTL_ALT as ArduPilot answered (dm; -1 not known), and whether it is armed / on the ground.
     * @return 1 newly confirmed, -1 landed after a flight (the caller removes it), else 0
     */
    public synchronized int report(String id, int rtlAltDm, boolean armed, boolean landed) {
        Entry e = solo.get(id);
        if (e == null) return 0;
        if (armed) e.flew = true;
        if (e.flew && landed && !armed) return -1;
        if (!e.confirmed && e.wantDm >= 0 && rtlAltDm == e.wantDm) {
            e.confirmed = true;
            return 1;
        }
        return 0;
    }

    /** Read back another way (the pilot's own drone: the route window's tunnel). */
    public synchronized void confirm(String id, int dm) {
        Entry e = solo.get(id);
        if (e != null && dm == e.wantDm) e.confirmed = true;
    }

    /** RETURN to be sent to it now (not confirmed, the last one RESEND_MS ago); counts as sent. */
    public synchronized boolean due(String id, long now) {
        Entry e = solo.get(id);
        if (e == null || e.confirmed || e.wantDm < 0 || now - e.sentAt < RESEND_MS) return false;
        e.sentAt = now;
        if (e.waitFrom == Long.MIN_VALUE) e.waitFrom = now;
        return true;
    }

    /** Waited {@link #CONFIRM_WAIT_MS} for this height in vain: true once. */
    public synchronized boolean overdue(String id, long now) {
        Entry e = solo.get(id);
        if (e == null || e.confirmed || e.said || e.waitFrom == Long.MIN_VALUE || now - e.waitFrom < CONFIRM_WAIT_MS) return false;
        e.said = true;
        return true;
    }

    private Map<String, Double> replan(AirParams p) {
        List<SoloReturns.Solo> in = new ArrayList<>();
        for (Map.Entry<String, Entry> x : solo.entrySet()) {
            Entry e = x.getValue();
            in.add(new SoloReturns.Solo(x.getKey(), local(e.farLat, e.farLon), local(e.rallyLat, e.rallyLon), e.homeAmsl, e.minM));
        }
        Map<String, Double> h = SoloReturns.heights(in, p);
        Map<String, Double> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> x : solo.entrySet()) {
            Entry e = x.getValue();
            double m = Math.max(h.getOrDefault(x.getKey(), e.minM), e.minM); // never under RT-9's own
            int dm = (int) Math.ceil(m * 10 - 1e-6);
            if (dm == e.wantDm) continue;
            e.wantDm = dm;
            e.confirmed = false;
            e.said = false;
            e.waitFrom = Long.MIN_VALUE;
            e.sentAt = Long.MIN_VALUE / 2;
            changed.put(x.getKey(), dm / 10.0);
        }
        return changed;
    }

    private V2 local(double lat, double lon) {
        return new V2(GeoLocal.east(refLat, refLon, lat, lon), GeoLocal.north(refLat, refLon, lat, lon));
    }
}
