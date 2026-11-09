/*
 *  This file is part of DroidDrone.
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
 *
 *  Modified for DRAUPNE Link 1.0.53: stamped on a monotonic clock ({@link #nowMs}), not the wall clock. UdpSender's
 *  resend timer and ReceiverBuffer's gap wait and clean-up compare against these stamps: a time sync setting the
 *  clock back stopped every resend (Config, StartVideo, the video config) and every gap skip until the clock caught
 *  up; set forward, it expired every waiting packet at once.
 */

package de.droiddrone.common;

import java.net.InetAddress;

public class SavedPacket {
    public final byte packetName;
    public final short packetNum;
    public final byte[] data;
    public final InetAddress ip;
    public final int port;
    /** When it was made, {@link #nowMs}. */
    public final long timestampCreated;
    /** When it was last sent, {@link #nowMs}. */
    public long timestamp;

    public SavedPacket(byte packetName, short packetNum, byte[] data, InetAddress ip, int port) {
        this.packetName = packetName;
        this.packetNum = packetNum;
        this.data = data;
        this.ip = ip;
        this.port = port;
        timestampCreated = nowMs();
        timestamp = timestampCreated;
    }

    /** The clock the stamps and every comparison with them use: monotonic ms (not the wall clock). */
    public static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }
}
