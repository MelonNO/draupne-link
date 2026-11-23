package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.setup.ardu.MavBridge;

/**
 * Fleet AP-11: SETUP > Mission Planner bridge, on and off. The bridge belongs to the link it was started on: when that
 * link ends (END LINK, another drone flown, a hand-over), it goes off by itself - left on, it said "On" while Mission
 * Planner talked to a link that was gone, and a second press only turned it off. A port in use says so.
 */
final class MpBridgeSwitch {
    /** The window's side. */
    interface Host {
        /** This PC's own pilot link now, or null. */
        ControlLink flownLink();

        void message(String text);

        void log(String line);
    }

    private final Host host;
    private final int inPort, outPort;
    private MavBridge bridge;
    private ControlLink on;

    MpBridgeSwitch(Host host) {
        this(host, MavBridge.DEFAULT_IN, MavBridge.DEFAULT_OUT);
    }

    MpBridgeSwitch(Host host, int inPort, int outPort) {
        this.host = host;
        this.inPort = inPort;
        this.outPort = outPort;
    }

    boolean isOn() {
        return bridge != null;
    }

    /** The menu's second line. */
    String menuLine() {
        return bridge == null ? "Off: tune ArduPilot over the link" : "On: click to stop";
    }

    /** The menu item: on when off, off when on. Swing's thread. */
    void toggle() {
        if (bridge != null) {
            stop("Mission Planner bridge off.");
            return;
        }
        ControlLink l = host.flownLink();
        if (l == null || l.getFcInfo() == null || l.getFcInfo().getFcVariant() != FcInfo.FC_VARIANT_ARDUPILOT) {
            host.message("Connect to an ArduPilot drone first.");
            return;
        }
        try {
            bridge = new MavBridge(l, inPort, outPort);
            on = l;
            host.log("Mission Planner bridge on: UDP " + outPort + ".");
            host.message("Mission Planner: connect UDP, port " + outPort + ".\nParameters and missions only. Sticks, arming and calibration are not carried.");
        } catch (java.net.BindException e) {
            host.message("UDP port " + inPort + " is in use (another program or DRAUPNE Link): bridge not started.");
        } catch (java.io.IOException e) {
            host.message("Bridge not started: " + e.getMessage());
        }
    }

    /** Each refresh: the bridge's link still this PC's flown one, else off. */
    void follow(ControlLink flownNow) {
        if (bridge != null && flownNow != on) stop("Mission Planner bridge off: the link it was on ended.");
    }

    private void stop(String why) {
        MavBridge b = bridge;
        bridge = null;
        on = null;
        if (b != null) b.close();
        host.log(why);
    }
}
