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

package de.droiddrone.common;

/**
 * DRAUPNE Flight: whether the raw MAVLink a session sends ({@link UdpCommon#MavlinkRawPacket}, 26) goes to the flight
 * controller. Upstream's Control app carries a ground station's MAVLink over the link that way, for its MAVLink UDP
 * bridge in the mode "from the control device" ({@link SettingsCommon.MavlinkUdpBridge#redirectFromControlDevice});
 * upstream's phone app wrote every such packet to the flight controller's port as it came - on a Betaflight drone, which
 * speaks MSP, too, and past everything the sticks go through (their sequence, the idle sticks' guard, the arm checks).
 * <p>
 * Now only where the phone is set up for it: a flight controller that speaks MAVLink, and the phone's own bridge in
 * that mode (a setting on the phone, not one a Config packet changes). Otherwise the packet is dropped. DRAUPNE Link
 * never sends it - its Config asks for MSP and the bridge off - so nothing a DRAUPNE PC does changes.
 * <p>
 * Pure: the phone's Udp asks it on its buffer thread, never for a stick frame; the PC's tests check it.
 */
public final class MavlinkRaw {
    private MavlinkRaw() {
    }

    /**
     * @param fcSpeaksMavlink the flight controller on the port answers in MAVLink (the phone's Mavlink is initialised)
     * @param bridgeMode      the phone's own MAVLink UDP bridge setting ({@link SettingsCommon.MavlinkUdpBridge})
     * @return true when the packet's MAVLink is written to the flight controller
     */
    public static boolean mayWrite(boolean fcSpeaksMavlink, int bridgeMode) {
        return fcSpeaksMavlink && bridgeMode == SettingsCommon.MavlinkUdpBridge.redirectFromControlDevice;
    }
}
