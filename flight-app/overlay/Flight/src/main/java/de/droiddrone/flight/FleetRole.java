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

import de.droiddrone.common.FcCommand;
import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetCommandGate;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.GroupOrder;

import java.net.InetSocketAddress;

/**
 * DRAUPNE Flight (fleet PRD FD-1, FL-2, FL-7, AC-25, AC-37): this phone's part in a fleet when its PC is not piloting
 * it. Fleet commands (FleetCmd) are taken from the commander only (FleetCommandGate); every one keeps the reports
 * (FleetStatus) going to it twice a second for {@link #REPORT_FOR_MS}. With a group order the drone is a follower:
 * the follower's rules (FollowerBrain: its cell, the floor, its 2 min hold and exit plan without the PC) decide each
 * tenth of a second, and the FC is fed centred sticks with the pilot's last switches - a follower never takes stick
 * input (FL-7), and the pilot's last sticks must not stay on it as overrides. Ticked by the phone's 10 Hz ArduPilot
 * thread; commands come on the receive thread.
 */
final class FleetRole {
    static final long REPORT_EVERY_MS = 500, REPORT_FOR_MS = 15_000;
    /** The follower's hold before its exit plan (AC-37) counts from 1 s of silence, as the solo rule's brake. */
    static final long SILENT_MS = de.droiddrone.common.ArduLinkLossRule.BRAKE_AFTER_MS;

    private volatile InetSocketAddress commander;
    private volatile long commanderHeardAt, reportUntil, reportedAt;
    private volatile FollowerBrain brain;
    private FleetIntent lastIntent = FleetIntent.NOTHING;
    private long lastGuidedAt;
    private volatile double[] clearTo;
    private boolean refusedSaid;

    /** The pilot's PC of the last session is the commander until another takes over by the gate's rule. */
    void pilotWas(InetSocketAddress pc, long now) {
        if (pc == null) return;
        InetSocketAddress c = commander;
        // the address is who; the port stays the one fleet commands came from (the PC's fleet socket), once known
        if (c == null || !c.getAddress().equals(pc.getAddress())) commander = pc;
        commanderHeardAt = now;
    }

    boolean isFollower() {
        return brain != null;
    }

    /** The commander was heard within 3 s, or a follower's own rules run: the FC hears a ground station. */
    boolean keepsGcsAlive(long now) {
        return brain != null || now - commanderHeardAt <= 3000 && commander != null;
    }

    /**
     * A FleetCmd on the receive thread (keyed: checked by Udp).
     * @return true if taken
     */
    boolean onCommand(Udp udp, InetSocketAddress from, FleetCmd c, boolean inSession, boolean fromPilot, boolean fcDisarmed, long now) {
        InetSocketAddress cmd = commander;
        boolean fromCommander = cmd != null && cmd.getAddress().equals(from.getAddress());
        int v = FleetCommandGate.decide(inSession, fromPilot, cmd != null, fromCommander,
                cmd == null ? Long.MAX_VALUE : now - commanderHeardAt, fcDisarmed);
        if (v == FleetCommandGate.REFUSE) {
            if (!refusedSaid) log("Fleet command from " + from.getAddress().getHostAddress() + " refused (not this drone's commander)");
            refusedSaid = true;
            return false;
        }
        refusedSaid = false;
        if (v == FleetCommandGate.NEW_COMMANDER) log("Fleet commander now " + from.getAddress().getHostAddress());
        commander = from; // its port too: the reports go where the commands come from
        commanderHeardAt = now;
        reportUntil = now + REPORT_FOR_MS;
        Mavlink m = udp.mavlinkOfRule();
        switch (c.inner()) {
            case FcCommand.PACKET: {
                FcCommand k = FcCommand.read(java.util.Arrays.copyOfRange(c.data, c.at, c.at + c.length), c.length);
                if (k != null) m.command(k);
                break;
            }
            case MissionBlock.PACKET:
                m.missionBlock(MissionBlock.read(c.data, c.at + 1, c.length - 1));
                break;
            case FleetCmd.GROUP: {
                GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                if (o == null || inSession) break; // the piloted drone is never a follower (FL-7)
                FollowerBrain b = brain;
                if (b == null) {
                    b = new FollowerBrain(SILENT_MS);
                    brain = b;
                    log("A follower now (group order from the PC)");
                }
                b.order(o, now);
                break;
            }
            case FleetCmd.EXIT: {
                ExitPlan p = ExitPlan.decode(c.data, c.at + 1);
                FollowerBrain b = brain;
                if (p != null && b != null) {
                    b.plan(p);
                    m.setRtlAltM(p.returnAltRelM); // its own way home's height, set now (AC-41) - the FC's RTL goes up there
                    m.rallyPoint(p.rallyLat, p.rallyLon, p.returnAltRelM); // AC-10: its RTL to its own rally point (only when it changed)
                }
                break;
            }
            case FleetCmd.LEAD:
                if (c.length >= 4) {
                    boolean lead = c.data[c.at + 1] != 0;
                    // FL-6: a follower told to lead - its commander's pilot session is let in (mayLead)
                    boolean follower = brain != null || leftGroupAt != 0 && now - leftGroupAt <= FleetCommandGate.LEFT_GROUP_MS;
                    if (!lead) leadOrderedAt = 0;
                    else if (!inSession && follower && (leadOrderedAt == 0 || now - leadOrderedAt > FleetCommandGate.LEAD_CONNECT_MS)) leadOrderedAt = now;
                    Udp.setLead(lead);
                    if (lead) m.setRtlAltM(((c.data[c.at + 2] & 0xff) << 8 | (c.data[c.at + 3] & 0xff)) / 10.0);
                }
                break;
            case FleetCmd.CLEAR:
                if (c.length >= 13 && !inSession) {
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(c.data, c.at + 1, 12);
                    clearTo = new double[]{bb.getInt() / 1e7, bb.getInt() / 1e7, bb.getInt() / 10.0};
                }
                break;
            case FleetCmd.DISBAND:
                if (brain != null) {
                    log("No longer a follower");
                    leftGroupAt = now;
                }
                brain = null;
                lastIntent = FleetIntent.NOTHING;
                break;
            case FleetCmd.MARK: // fleet AL-1: SAFE TO AUTOLAUNCH (only on the ground: LaunchRunner checks)
                launch.mark(udp, m, c.data, c.at, c.length);
                break;
            case FleetCmd.LAUNCH: // fleet AL-5 - AL-8: the pilot's call
                if (!inSession) launch.order(udp, m, c.data, c.at, c.length);
                break;
            case FleetCmd.TRAFFIC: // FD-8 / AC-11: the other drones to the FC as ADS-B targets (the lead's too)
                m.adsbTraffic(de.droiddrone.common.group.Traffic.decode(c.data, c.at + 1, c.length - 1));
                break;
            case FleetCmd.TRACK:
                if (c.length >= 2) reportUntil = now + (c.data[c.at + 1] & 0xff) * 1000L;
                break;
            default:
                break;
        }
        return true;
    }

    /** Fleet area F: this phone's own safe autolaunch. */
    final LaunchRunner launch = new LaunchRunner();

    /**
     * Fleet AL-20 / C3: this PC commands the drone now (a command within {@link #REPORT_FOR_MS}, a launch or a follower's
     * group under way) - its camera may go to that PC as an observation, armed or not.
     */
    boolean commandedBy(java.net.InetAddress from, long now) {
        InetSocketAddress c = commander;
        return c != null && c.getAddress().equals(from) && (now < reportUntil || brain != null || launch.underWay());
    }

    /** Commanded now by its commander (see commandedBy). */
    boolean commanded(long now) {
        return commander != null && (now < reportUntil || brain != null || launch.underWay());
    }

    InetSocketAddress commander() {
        return commander;
    }

    /** FL-6: when the commander's order to lead came to this follower (0: none standing), and when it left the group. */
    private volatile long leadOrderedAt, leftGroupAt;

    /**
     * Fleet FL-5 / FL-6, the hand-over (receive thread): a keyed Connect the session rules refused may start the pilot
     * session all the same - from this drone's commander, a follower it ordered to lead moments ago
     * (FleetCommandGate.leadConnect). Taken once: the order is used up.
     */
    boolean mayLead(java.net.InetAddress from, long now) {
        InetSocketAddress c = commander;
        long at = leadOrderedAt;
        if (!FleetCommandGate.leadConnect(at != 0, c != null && c.getAddress().equals(from), now - at)) return false;
        leadOrderedAt = 0;
        return true;
    }

    /** The phone's 10 Hz ArduPilot thread. */
    void tick(Udp udp, Mavlink m, long now, boolean inSession) {
        launch.tick(udp, m, now);
        if (now < reportUntil && now - reportedAt >= REPORT_EVERY_MS) {
            reportedAt = now;
            InetSocketAddress to = commander;
            if (to != null) {
                FleetStatus s = new FleetStatus();
                m.fillStatus(s, now);
                if (inSession) s.flags |= FleetStatus.PILOTED;
                if (now - commanderHeardAt <= 3000) s.flags |= FleetStatus.PC_HEARD;
                FollowerBrain b = brain;
                if (b != null) s.phase = b.phase();
                udp.sendTo(s.encode(), to);
            }
        }
        double[] c = clearTo;
        if (c != null && !inSession) { // an old lead stepped out of the way (the group's order)
            clearTo = null;
            m.guidedTarget(c[0], c[1], c[2], Double.NaN, 0, 0);
        }
        FollowerBrain b = brain;
        if (b == null || inSession) return;
        FleetIntent i = b.tick(now, commanderHeardAt, m.latNow(), m.lonNow(), m.batteryNow(), m.gnssOk());
        act(m, i, now);
        holdSticks(udp, m);
    }

    private void act(Mavlink m, FleetIntent i, long now) {
        boolean changed = i.kind != lastIntent.kind;
        switch (i.kind) {
            case FleetIntent.GUIDED:
                if (changed || now - lastGuidedAt >= 200) { // five targets a second: the group moves on
                    lastGuidedAt = now;
                    m.guidedTarget(i.lat, i.lon, i.altAmsl, i.yawDeg, i.velE, i.velN);
                }
                break;
            case FleetIntent.LOITER:
                if (changed) m.setMode(Mavlink.COPTER_LOITER);
                break;
            case FleetIntent.ALT_HOLD:
                if (changed) m.setMode(de.droiddrone.common.ArduLinkLossRule.MODE_ALT_HOLD);
                break;
            case FleetIntent.LAND:
                if (changed) m.setMode(de.droiddrone.common.ArduLinkLossRule.MODE_LAND);
                break;
            case FleetIntent.RTL:
                if (changed) {
                    m.setRtlAltM(i.rtlAltRelM);
                    m.setMode(de.droiddrone.common.ArduLinkLossRule.MODE_RTL);
                }
                break;
            default:
                break;
        }
        if (changed) log("Follower: " + i);
        lastIntent = i;
    }

    /** Centred sticks, the pilot's last switches (ARM stays on, the mode switch where it was): FL-7. */
    private void holdSticks(Udp udp, Mavlink m) {
        short[] last = udp.lastPilotFrame();
        if (last == null || last.length < 4) return;
        short[] hold = java.util.Arrays.copyOf(last, last.length);
        hold[0] = hold[1] = hold[2] = hold[3] = ArduLinkLoss.CENTRE;
        m.setRcChannelsOverride(hold);
    }
}
