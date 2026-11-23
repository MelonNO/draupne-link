package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may use which part of the airspace round the cube (PRD AC-17, AC-23, AC-27, AC-28, AC-29, AC-31, AC-9):
 * a side of the outer lane in a layer, a transit shaft, a hop corner, an abort-layer column, a corner exit - one drone
 * each. A {@link HollowCube.Stage} is granted whole or not at all (a drone never holds half a way).
 * <p>
 * Shafts: one drone each; at most three in use by drones that come from outside (a cell, the lane, an approach layer),
 * so one is always free (AC-23). A drone that already holds a shaft may hop into a free one even when that makes four
 * for the moment it takes to leave the old one: that is what the free fourth is for (AC-29). A drone landing at 3 %
 * may take the fourth from outside (K10). Requests in one round are granted in priority order (AC-9: lead first, then
 * landing / 3 %, then in transit, then waiting), the earliest asker first among equals.
 * <p>
 * No lock-up (AC-29): a drone in a shaft can always hop into the free shaft next to it; one that has waited
 * {@link AirParams#transitWaitMs} for its next area is turned the other way round by its owner ({@link #waitedMs}).
 */
public final class TransitScheduler {
    public static final int PRIO_LEAD = 0, PRIO_LANDING = 1, PRIO_TRANSIT = 2, PRIO_WAITING = 3;

    private final Map<String, String> holder = new HashMap<>();
    private final Map<String, Set<String>> held = new HashMap<>();
    private final Map<String, Long> askedSince = new HashMap<>();
    private final List<Request> round = new ArrayList<>();

    private record Request(String who, HollowCube.Stage stage, int prio, long since) {}

    /** Ask for a stage this round; {@link #decide} answers. Asking again each round keeps the place in the queue. */
    public void ask(String who, HollowCube.Stage stage, int prio, long now) {
        long since = askedSince.computeIfAbsent(who, k -> now);
        round.add(new Request(who, stage, prio, since));
    }

    /** How long this drone has been asking without a grant; 0 when it isn't asking. */
    public long waitedMs(String who, long now) {
        Long s = askedSince.get(who);
        return s == null ? 0 : now - s;
    }

    /** Grants this round's requests that can be granted, best first; returns who got theirs. */
    public Set<String> decide() {
        round.sort(Comparator.comparingInt(Request::prio).thenComparingLong(Request::since).thenComparing(Request::who));
        Set<String> granted = new HashSet<>();
        for (Request r : round) {
            if (granted.contains(r.who)) continue;
            if (canGrant(r.who, r.stage)) {
                for (String res : r.stage.resources()) holder.put(res, r.who);
                held.computeIfAbsent(r.who, k -> new HashSet<>()).addAll(r.stage.resources());
                askedSince.remove(r.who);
                granted.add(r.who);
            }
        }
        round.clear();
        return granted;
    }

    private boolean canGrant(String who, HollowCube.Stage stage) {
        for (String res : stage.resources()) {
            String h = holder.get(res);
            if (h != null && !h.equals(who)) return false;
        }
        long newShafts = stage.resources().stream().filter(r -> r.startsWith("shaft:") && !who.equals(holder.get(r))).count();
        if (newShafts == 0) return true;
        boolean holdsShaft = held.getOrDefault(who, Set.of()).stream().anyMatch(r -> r.startsWith("shaft:"));
        if (holdsShaft) return true; // a hop: into the free one (AC-29)
        int inUse = shaftsInUse();
        return inUse + newShafts <= (stage.emergency() ? 4 : 3);
    }

    /** Hold these at once, asking no one (a drone sitting in its cell holds the cell). */
    public void hold(String who, Set<String> resources) {
        for (String r : resources) holder.put(r, who);
        held.computeIfAbsent(who, k -> new HashSet<>()).addAll(resources);
    }

    /** Shafts held by anyone now. */
    public int shaftsInUse() {
        int n = 0;
        for (String r : holder.keySet()) if (r.startsWith("shaft:")) n++;
        return n;
    }

    /** Let go of resources (passed through, or the move ended). */
    public void release(String who, Set<String> resources) {
        Set<String> mine = held.get(who);
        for (String r : resources) {
            if (who.equals(holder.get(r))) holder.remove(r);
            if (mine != null) mine.remove(r);
        }
    }

    /** Let go of everything this drone holds (it left the group, landed, or its move was given up). */
    public void releaseAll(String who) {
        Set<String> mine = held.remove(who);
        if (mine != null) for (String r : mine) if (who.equals(holder.get(r))) holder.remove(r);
        askedSince.remove(who);
    }

    /** Stop counting this drone's wait (it gave up asking for that stage). */
    public void forgetWait(String who) {
        askedSince.remove(who);
    }

    public Set<String> heldBy(String who) {
        return Set.copyOf(held.getOrDefault(who, Set.of()));
    }

    public String holderOf(String resource) {
        return holder.get(resource);
    }
}
