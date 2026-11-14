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
 * The first byte of the DRAUPNE packets on the link's port that have no class of their own in Common: one table both
 * apps and the virtual fleet read, so no code is copied by hand. The layouts are written where each packet is sent
 * (ControlLink, Spectate, Names on the PC). The others live with their protocol: UdpCommon (upstream DroidDrone),
 * {@link Observe} 0x50-0x52 (0x53-0x55 are this table's now, 0x56-0x57 unused), {@link FleetProtocol#MODE} 0x58,
 * {@link PcAudio#PACKET} 0x79, {@link OverwatchProtocol} 0x7A, 0x7B, 0x7D-0x7F. PacketIdsTest checks no two share a code.
 */
public final class PacketIds {
    // spectator <-> pilot PC (Spectate)
    public static final byte SPECTATE_PILOT_QUERY = 0x60;
    public static final byte SPECTATE_PILOT_AT = 0x61;
    public static final byte SPECTATE_NO_PILOT = 0x62;
    public static final byte SPECTATE_HELLO = 0x63;
    public static final byte SPECTATE_WELCOME = 0x64;
    public static final byte SPECTATE_REFUSED = 0x65;
    public static final byte SPECTATE_STREAM = 0x66;
    public static final byte SPECTATE_BYE = 0x67;
    public static final byte SPECTATE_KEEP = 0x68;
    public static final byte SPECTATE_SNAPSHOT = 0x69;
    public static final byte SPECTATE_PAUSE = 0x6D;
    public static final byte SPECTATE_PILOT_VIEW = 0x76;

    // spectator <-> pilot PC, the PC's own view (DRAUPNE Link 1.0.56, Mirror): its mode, fleet, map and observation
    public static final byte MIRROR_HELLO = 0x5A, MIRROR_WELCOME = 0x5B, MIRROR_KEEP = 0x5C, MIRROR_STATE = 0x5D,
            MIRROR_TILE = 0x5E, MIRROR_BYE = 0x5F;

    // PC -> phone video (ControlLink)
    public static final byte KEY_FRAME_REQUEST = 0x6A;
    public static final byte VIDEO_PROFILE = 0x6B;
    /** Phone -> PC: a frame's parity. */
    public static final byte VIDEO_PARITY = 0x6C;
    /** Phone -> PC: a group's parity (pc.26). */
    public static final byte VIDEO_PARITY_GROUP = 0x71;
    public static final byte SET_BITRATE = 0x72;

    // the link in poor reception (DRAUPNE Link 1.0.67 / phone app pc.53; the three codes observation left unused)
    /** Phone -> PC: when a frame was taken and sent ({@link CaptureClock}), to a PC that asked by VIDEO_PROFILE bit 3. */
    public static final byte FRAME_TIME = 0x53;
    /** PC -> phone: the parity packets a block the phone is to add ({@link Fec}). */
    public static final byte VIDEO_FEC = 0x54;
    /** Phone -> PC: a block's Reed-Solomon parity ({@link Fec}), to a PC that asked by VIDEO_PROFILE bit 4. */
    public static final byte VIDEO_PARITY_RS = 0x55;

    // power modes (ControlLink, the phone's PowerMode)
    public static final byte POWER_MODE = 0x6E;
    public static final byte SLEEP_HELLO = 0x6F, SLEEP_ANSWER = 0x70;

    // names (Names)
    public static final byte NAME_QUERY = 0x73, NAME_REPLY = 0x74, NAME_ASSIGN = 0x75;

    // PC -> phone (ControlLink)
    public static final byte REMOTE_UPDATE = 0x77;
    public static final byte IDLE_STICKS = 0x78;
    public static final byte DETECTION_SET = 0x7C;

    private PacketIds() {
    }
}
