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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import de.droiddrone.common.TailscaleOnly;

/**
 * Q-006 (the pilot: "Tailscale only"): this phone's own Tailscale address as Android has it, for the link's sockets
 * and the screen ({@link TailscaleOnly}: the rule; this only lists the phone's addresses). Two ways, both counted: the
 * networks Android knows - a VPN's is marked TRANSPORT_VPN - and the phone's interfaces, where every VPN app's is tunN.
 * An address in 100.64.0.0/10 on mobile data or Wi-Fi is the carrier's and never counts. Looked at again at most every
 * {@link #FRESH_MS}: the link asks twice a second, the screen once.
 */
final class PhoneTailscale {
    static final long FRESH_MS = 400;
    private static volatile Context app;
    private static long lookedAt = Long.MIN_VALUE;
    private static InetAddress address;

    private PhoneTailscale() {
    }

    /** The app's context, for Android's list of networks (the app and the service each hand theirs in at their start). */
    static void with(Context c) {
        if (c != null) app = c.getApplicationContext();
    }

    /**
     * Where the link's sockets belong now: on the phone's Tailscale address, else parked. Every address only in the
     * test rig's own build, in the emulator (BenchNet; the released app's says no and does nothing).
     */
    static TailscaleOnly.Place place() {
        if (BenchNet.BUILT_IN && BenchNet.wide()) return TailscaleOnly.Place.EVERYWHERE;
        return TailscaleOnly.Place.at(address());
    }

    /** The PC can reach this phone: it is on Tailscale (or, in the test rig's build in the emulator, on any network). */
    static boolean up() {
        return !place().parked;
    }

    /** This phone's Tailscale address now, or null. */
    static synchronized InetAddress address() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lookedAt != Long.MIN_VALUE && now - lookedAt < FRESH_MS) return address;
        lookedAt = now;
        address = TailscaleOnly.own(seen());
        return address;
    }

    /** Every address the phone has, each with its interface and whether that is a VPN's. */
    private static List<TailscaleOnly.Seen> seen() {
        List<TailscaleOnly.Seen> out = new ArrayList<>();
        Context c = app;
        if (c != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        LinkProperties lp = cm.getLinkProperties(n);
                        if (caps == null || lp == null) continue;
                        boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                        for (LinkAddress la : lp.getLinkAddresses()) out.add(new TailscaleOnly.Seen(lp.getInterfaceName(), vpn, la.getAddress()));
                    }
                }
            } catch (RuntimeException e) {
                // Android would not say (a SecurityException on some phones): the interfaces below say it
            }
        }
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nif = all.nextElement();
                if (!nif.isUp()) continue;
                boolean vpn = TailscaleOnly.vpnName(nif.getName());
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) out.add(new TailscaleOnly.Seen(nif.getName(), vpn, ia.getAddress()));
            }
        } catch (java.net.SocketException | RuntimeException e) {
            // no interfaces to look at
        }
        return out;
    }
}
