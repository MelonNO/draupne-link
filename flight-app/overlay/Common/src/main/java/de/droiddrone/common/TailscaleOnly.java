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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * DRAUPNE Flight, Q-006 (the pilot, 2026-11-22: "Tailscale only"): the phone answers over Tailscale and nothing else.
 * <p>
 * The link's sockets are bound to the phone's own Tailscale address, not to every address the phone has: what comes in
 * over mobile data, Wi-Fi, a hotspot, USB tethering or from another app on the phone (127.0.0.1) never reaches them. And
 * a packet is taken or sent only from or to a Tailscale address ({@link #peer}). The binding is what holds: a carrier
 * hands out addresses of the same 100.64.0.0/10 on mobile data (its own address translation), so an address in that
 * range alone proves nothing - the phone's own Tailscale address is the one in Tailscale's ranges on a VPN's interface
 * (Tailscale on Android is a VPN; Android names every VPN app's interface tunN and says TRANSPORT_VPN of its network),
 * and a packet that reaches a socket bound to it was sent to that address.
 * <p>
 * No Tailscale (off, not up yet, signed out): the socket is parked - bound to the phone's own loopback on a port of the
 * system's choosing - and takes and sends nothing ({@link Place#parked}); the PC cannot reach the phone. The address is
 * looked at again twice a second, and when it comes, changes or goes the socket is opened anew where it now belongs
 * ({@link Watch}, {@link LinkSocket#reopen}): the session, the camera and the flight controller's port stay.
 * <p>
 * Pure java.net, so the PC's tests check it; the phone's PhoneTailscale lists its addresses from Android.
 */
public final class TailscaleOnly {
    private TailscaleOnly() {
    }

    /** One address the phone has, as Android lists it: on which interface, and whether that is a VPN's. */
    public static final class Seen {
        public final String iface;
        public final boolean vpn;
        public final InetAddress address;

        public Seen(String iface, boolean vpn, InetAddress address) {
            this.iface = iface == null ? "" : iface;
            this.vpn = vpn;
            this.address = address;
        }

        @Override
        public String toString() {
            return iface + (vpn ? " (VPN) " : " ") + (address == null ? "-" : address.getHostAddress());
        }
    }

    /** Android's name for every VPN app's interface (VpnService): tun0, tun1 ... */
    public static boolean vpnName(String iface) {
        if (iface == null || !iface.startsWith("tun") || iface.length() == 3) return false;
        for (int i = 3; i < iface.length(); i++) if (!Character.isDigit(iface.charAt(i))) return false;
        return true;
    }

    /** 100.64.0.0/10: Tailscale's IPv4 addresses - and a carrier's own address translation's too. */
    public static boolean inV4Range(byte[] a) {
        return a != null && a.length == 4 && (a[0] & 0xff) == 100 && (a[1] & 0xc0) == 0x40;
    }

    /** fd7a:115c:a1e0::/48: Tailscale's IPv6 addresses. */
    public static boolean inV6Range(byte[] a) {
        return a != null && a.length == 16 && (a[0] & 0xff) == 0xfd && (a[1] & 0xff) == 0x7a && (a[2] & 0xff) == 0x11
                && (a[3] & 0xff) == 0x5c && (a[4] & 0xff) == 0xa1 && (a[5] & 0xff) == 0xe0;
    }

    /** An address in Tailscale's ranges (IPv4 or IPv6). */
    public static boolean inRange(InetAddress a) {
        return a != null && (inV4Range(a.getAddress()) || inV6Range(a.getAddress()));
    }

    /**
     * Who the link may hear and answer: an address of Tailscale's. Over a socket bound to the phone's Tailscale address
     * only Tailscale delivers anything; this keeps out what would come by another way all the same, and keeps the phone's
     * own sends - a sleep check-in to a PC remembered from before - from leaving by another way than Tailscale.
     */
    public static boolean peer(InetAddress a) {
        return inRange(a);
    }

    /**
     * The phone's own Tailscale address: one in Tailscale's ranges on a VPN's interface. IPv4 first - the address the
     * PC connects to - else IPv6. A 100.64.0.0/10 address on mobile data or Wi-Fi is the carrier's, not Tailscale's.
     *
     * @return null when the phone is not on Tailscale
     */
    public static InetAddress own(Iterable<Seen> seen) {
        InetAddress v6 = null;
        if (seen == null) return null;
        for (Seen s : seen) {
            if (s == null || s.address == null || !s.vpn) continue;
            if (s.address instanceof Inet4Address && inV4Range(s.address.getAddress())) return s.address;
            if (v6 == null && s.address instanceof Inet6Address && inV6Range(s.address.getAddress())) v6 = s.address;
        }
        return v6;
    }

    /** Where a link socket is bound. */
    public static final class Place {
        /** The phone's Tailscale address; null while parked or widened. */
        public final InetAddress address;
        /** No Tailscale: bound to the phone's own loopback, a port of the system's choosing - nothing reaches it. */
        public final boolean parked;
        /** Every address the phone has, as before Q-006: the test rig's build in the emulator alone (never a released app). */
        public final boolean everywhere;

        private Place(InetAddress address, boolean parked, boolean everywhere) {
            this.address = address;
            this.parked = parked;
            this.everywhere = everywhere;
        }

        public static final Place PARKED = new Place(null, true, false);
        public static final Place EVERYWHERE = new Place(null, false, true);

        public static Place at(InetAddress tailscale) {
            return tailscale == null ? PARKED : new Place(tailscale, false, false);
        }

        /** Whether a packet from or to this address may pass a socket bound here. */
        public boolean passes(InetAddress a) {
            if (everywhere) return true;
            return !parked && peer(a);
        }

        /**
         * The socket bound here: to the Tailscale address at the port, parked on loopback (port 0), or (widened) to
         * every address at the port.
         */
        public DatagramSocket open(int port) throws IOException {
            if (everywhere) return new DatagramSocket(port);
            if (parked) return new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return new DatagramSocket(new InetSocketAddress(address, port));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Place)) return false;
            Place p = (Place) o;
            return parked == p.parked && everywhere == p.everywhere && (address == null ? p.address == null : address.equals(p.address));
        }

        @Override
        public int hashCode() {
            return (address == null ? 0 : address.hashCode()) * 4 + (parked ? 1 : 0) + (everywhere ? 2 : 0);
        }

        @Override
        public String toString() {
            return everywhere ? "every address (test rig)" : parked ? "no Tailscale (parked)" : address.getHostAddress();
        }
    }

    /** Where the rule wants the link now: the Tailscale address, else parked; widened (test rig only): everywhere. */
    public static Place place(InetAddress tailscale, boolean widened) {
        return widened ? Place.EVERYWHERE : Place.at(tailscale);
    }

    /**
     * A link socket's binding and when it is to be opened anew: the opener says where it bound ({@link #opened}), the
     * socket's gate asks {@link #passes}, and twice a second {@link #rebindDue} compares that with where the rule wants
     * it now. A reopen that could not bind leaves the old place standing, so it is due again at the next look.
     */
    public static final class Watch implements LinkSocket.Peers {
        private volatile Place bound = Place.PARKED;

        /** The socket was bound there just now. */
        public void opened(Place p) {
            bound = p;
        }

        public Place bound() {
            return bound;
        }

        /** Whether the socket is to be opened anew: it is not bound where the rule wants it. */
        public boolean rebindDue(Place wanted) {
            return wanted != null && !wanted.equals(bound);
        }

        /** Not on Tailscale now: the link takes and sends nothing. */
        public boolean silent() {
            return bound.parked;
        }

        @Override
        public boolean allows(InetAddress a) {
            return bound.passes(a);
        }
    }
}
