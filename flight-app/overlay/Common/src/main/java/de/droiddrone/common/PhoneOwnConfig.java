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
 * DRAUPNE Flight: the settings of upstream's Config packet that stay the phone's own - which serial port the flight
 * controller is on (the phone's own serial port or USB, and that port's path) and the MAVLink UDP bridge (its mode, the
 * address and the port it sends to). Upstream's phone app took them from every Config a session sent and saved them
 * for good: another path, or "use the phone's own port" on a drone whose flight controller is on USB, cut the drone off
 * its flight controller; the bridge sent the flight controller's MAVLink to whatever address the packet named. Nothing
 * on the phone's screen sets them.
 * <p>
 * Now a Config from the network is read past them as before (its layout is upstream's) and they stay as they are on the
 * phone. DRAUPNE Link always sends upstream's defaults for them (USB, "/dev/ttyS0", the bridge off), the values the
 * phone has, so nothing changes for a DRAUPNE PC; a Config that asks for other values is said once in the log, by the
 * names of the settings, never their values.
 * <p>
 * Pure: the phone's Config hands in what it has and what the packet said; the PC's tests check it.
 */
public final class PhoneOwnConfig {
    public final boolean useNativeSerialPort;
    public final String nativeSerialPort;
    public final int mavlinkUdpBridge;
    public final String mavlinkUdpBridgeIp;
    public final int mavlinkUdpBridgePort;

    public PhoneOwnConfig(boolean useNativeSerialPort, String nativeSerialPort, int mavlinkUdpBridge, String mavlinkUdpBridgeIp,
                          int mavlinkUdpBridgePort) {
        this.useNativeSerialPort = useNativeSerialPort;
        this.nativeSerialPort = nativeSerialPort;
        this.mavlinkUdpBridge = mavlinkUdpBridge;
        this.mavlinkUdpBridgeIp = mavlinkUdpBridgeIp;
        this.mavlinkUdpBridgePort = mavlinkUdpBridgePort;
    }

    /** Upstream's defaults: what a phone has that no earlier app version was told otherwise, and what DRAUPNE Link sends. */
    public static PhoneOwnConfig defaults() {
        return new PhoneOwnConfig(SettingsCommon.useNativeSerialPort, SettingsCommon.nativeSerialPort, SettingsCommon.mavlinkUdpBridge,
                SettingsCommon.mavlinkUdpBridgeIp, SettingsCommon.mavlinkUdpBridgePort);
    }

    /**
     * What a Config from the network asked for of these settings that the phone has otherwise - none of it is taken.
     * @param asked the values the packet carried
     * @return null when it asked for what the phone has; else the settings it asked to change, by name, for one log line
     */
    public String notTaken(PhoneOwnConfig asked) {
        StringBuilder sb = new StringBuilder();
        if (asked.useNativeSerialPort != useNativeSerialPort || !same(asked.nativeSerialPort, nativeSerialPort)) sb.append("the FC's serial port");
        if (asked.mavlinkUdpBridge != mavlinkUdpBridge || !same(asked.mavlinkUdpBridgeIp, mavlinkUdpBridgeIp)
                || asked.mavlinkUdpBridgePort != mavlinkUdpBridgePort) {
            sb.append(sb.length() > 0 ? " and " : "").append("the MAVLink bridge");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** An empty text in the packet reads as null (upstream's DataReader.readUTF): the same as "". */
    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
