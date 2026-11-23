/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import de.droiddrone.common.ArduLinkLossRule;

/**
 * DRAUPNE Flight (fleet PRD AP-5): the phone carries out {@link ArduLinkLossRule} for an ArduPilot drone - ten times a
 * second on a thread of its own, never on the stick path. While the rule is in charge the flight controller is fed
 * the pilot's last frame with the four sticks centred (throttle at mid: LOITER and ALT_HOLD hold the height there)
 * and every other channel as the pilot left it - the mode and ARM switches don't move, and ArduPilot's own RC
 * override timeout never runs out into a radio failsafe meanwhile. Betaflight drones are not touched (their own
 * failsafe as before).
 */
final class ArduLinkLoss {
    static final long TICK_MS = 100;
    static final short CENTRE = 1500;

    private final ArduLinkLossRule rule = new ArduLinkLossRule();
    private final java.util.function.Supplier<Udp> udp;
    private volatile boolean lead;
    private volatile boolean stop;

    /** @param udp the service's Udp now (it is replaced when the link's settings change; one rule for the app) */
    ArduLinkLoss(java.util.function.Supplier<Udp> udp) {
        this.udp = udp;
    }

    /** This drone leads a follower group (the PC says so): RTL, not Smart RTL, after 20 s (AC-41). */
    void setLead(boolean lead) {
        this.lead = lead;
    }

    boolean running() {
        return rule.running();
    }

    void start() {
        Thread t = new Thread(this::run, "arduLinkLoss");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        stop = true;
    }

    private void run() {
        while (!stop) {
            try {
                Thread.sleep(TICK_MS);
                tick();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log("ArduPilot link-loss rule error: " + e);
            }
        }
    }

    private void tick() {
        Udp u = udp.get();
        if (u == null) return;
        Mavlink mavlink = u.mavlinkOfRule();
        if (mavlink == null || !mavlink.isArduPilot()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        mavlink.tick100(now); // fleet RT-8: a route's upload under way (this is the phone's 10 Hz ArduPilot thread)
        // fleet FD-1: the fleet role (reports to the commander, a follower's own rules); a follower's link loss is its own
        boolean inSession = u.inSession();
        if (inSession) Udp.fleet.pilotWas(u.pilotAddress(), now);
        Udp.fleet.tick(u, mavlink, now, inSession);
        if (!inSession && Udp.fleet.launch.underWay()) {
            // a reserve's launch: the climb is the phone's own (AL-14); on its way to the group the commander's silence is a
            // follower's link loss (AC-37): LOITER, home by RTL after 2 min (LaunchSequence.linkLossApplies; LOITER after 12 s: TRACK comes every 5 s)
            boolean applies = de.droiddrone.common.launch.LaunchSequence.linkLossApplies(Udp.fleet.launch.state());
            act(launchRule.tick(now, applies ? Udp.fleet.commanderHeardAt() : now, mavlink.armedNow(now), mavlink.customModeNow(),
                    mavlink.gnssOk(), true), launchRule, mavlink, u, " (reserve on its way)");
            return;
        }
        if (Udp.fleet.isFollower() && !inSession) return; // its own rules
        act(rule.tick(now, u.pcHeardAt(), mavlink.armedNow(now), mavlink.customModeNow(), mavlink.gnssOk(), lead), rule, mavlink, u,
                lead ? " (lead)" : "");
    }

    /** A reserve on its way to the group: its commander's silence (AC-37), home after 2 min. */
    private final ArduLinkLossRule launchRule = new ArduLinkLossRule(
            de.droiddrone.common.launch.LaunchSequence.LOST_BRAKE_AFTER_MS, de.droiddrone.common.launch.LaunchSequence.LOST_HOME_AFTER_MS);

    private void act(int action, ArduLinkLossRule rule, Mavlink mavlink, Udp u, String who) {
        if (action != ArduLinkLossRule.NONE) {
            log("The PC is silent: " + ArduLinkLossRule.name(action) + who);
            mavlink.setMode(ArduLinkLossRule.modeFor(action));
        }
        if (rule.running()) {
            short[] last = u.lastPilotFrame();
            if (last != null && last.length >= 4) {
                short[] hold = java.util.Arrays.copyOf(last, last.length);
                hold[0] = CENTRE; // roll
                hold[1] = CENTRE; // pitch
                hold[2] = CENTRE; // yaw
                hold[3] = CENTRE; // throttle: mid holds the height in LOITER / ALT_HOLD
                mavlink.setRcChannelsOverride(hold);
            }
        }
    }
}
