package de.droiddrone.pc;

import de.droiddrone.common.TailscaleOnly;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static de.droiddrone.common.Log.log;

/**
 * S-LINK-2: this PC's own Tailscale address, as its network adapters list it - where its sockets for phones and other
 * PCs are bound ({@link LinkPlace}, {@link PeerSocket}).
 * <p>
 * It is an address in Tailscale's ranges (IPv4 100.64.0.0/10, IPv6 fd7a:115c:a1e0::/48) on an adapter that is up and
 * whose name or description says Tailscale (on Windows: "Tailscale Tunnel"). The range alone is not enough - a LAN or a
 * carrier may use 100.64.0.0/10 as well. Read from java.net.NetworkInterface; tailscale.exe is never run for it.
 * <p>
 * Looked at every {@link #LOOK_MS} on a thread of its own from the first ask on (the adapter list costs a few ms):
 * every socket that follows it ({@link #follow}) is opened anew where it belongs when the address comes, changes or
 * goes.
 */
public final class OwnTailscale implements AutoCloseable {
    /** One network adapter: its name, its description, whether it is up, and its addresses. */
    public record Adapter(String name, String description, boolean up, List<InetAddress> addresses) {
    }

    /** This PC's Tailscale addresses as found: IPv4, IPv6; null where there is none. */
    public record Found(InetAddress v4, InetAddress v6) {
        public static final Found NONE = new Found(null, null);
    }

    /** How often the adapters are looked at, ms. */
    static final long LOOK_MS = 500;

    /** An adapter of Tailscale's: its name or its description says so. */
    public static boolean isTailscale(Adapter a) {
        return a != null && (says(a.name()) || says(a.description()));
    }

    private static boolean says(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("tailscale");
    }

    /**
     * This PC's Tailscale address among these adapters: the first in Tailscale's IPv4 (or, v6, IPv6) range on an
     * adapter of Tailscale's that is up. Null when there is none.
     */
    public static InetAddress find(List<Adapter> adapters, boolean v6) {
        if (adapters == null) return null;
        for (Adapter a : adapters) {
            if (a == null || !a.up() || !isTailscale(a) || a.addresses() == null) continue;
            for (InetAddress x : a.addresses()) {
                if (!v6 && x instanceof Inet4Address && TailscaleOnly.inV4Range(x.getAddress())) return x;
                if (v6 && x instanceof Inet6Address && TailscaleOnly.inV6Range(x.getAddress())) return x;
            }
        }
        return null;
    }

    /** Both: {@link #find} for IPv4 and for IPv6. */
    public static Found find(List<Adapter> adapters) {
        return new Found(find(adapters, false), find(adapters, true));
    }

    /** This PC's adapters whose name or description says Tailscale (only theirs are asked whether up, and for addresses). */
    static List<Adapter> systemAdapters() {
        List<Adapter> out = new ArrayList<>();
        Enumeration<NetworkInterface> all;
        try {
            all = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException | RuntimeException e) {
            return out;
        }
        if (all == null) return out;
        for (NetworkInterface nif : Collections.list(all)) {
            try {
                String name = nif.getName(), description = nif.getDisplayName();
                if (!says(name) && !says(description)) continue;
                out.add(new Adapter(name, description, nif.isUp(), Collections.list(nif.getInetAddresses())));
            } catch (SocketException | RuntimeException e) {
                // gone while asked: not listed
            }
        }
        return out;
    }

    private static final OwnTailscale PC = onAdapters(OwnTailscale::systemAdapters, LOOK_MS);

    /** This PC's own: its real adapters. */
    public static OwnTailscale pc() {
        return PC;
    }

    private final Supplier<Found> source;
    private final long lookMs;
    private volatile InetAddress v4, v6;
    private volatile boolean looked, closed;
    private final List<PeerSocket> following = new CopyOnWriteArrayList<>();
    private Thread looker;

    /** The address found among these adapters ({@link #find}), asked for them every lookMs. */
    public static OwnTailscale onAdapters(Supplier<List<Adapter>> adapters, long lookMs) {
        return new OwnTailscale(() -> find(adapters.get()), lookMs);
    }

    /** @param source the addresses as they are now, asked every lookMs */
    public OwnTailscale(Supplier<Found> source, long lookMs) {
        this.source = source;
        this.lookMs = lookMs;
    }

    /** This PC's Tailscale IPv4 address as last looked at; null: none. */
    public InetAddress v4() {
        ensure();
        return v4;
    }

    public InetAddress v6() {
        ensure();
        return v6;
    }

    public InetAddress of(boolean v6) {
        return v6 ? v6() : v4();
    }

    /** The adapters looked at now. */
    void look() {
        Found f;
        try {
            f = source.get();
        } catch (RuntimeException e) {
            f = null;
        }
        if (f == null) f = Found.NONE;
        v4 = f.v4();
        v6 = f.v6();
        looked = true;
    }

    private void ensure() {
        if (!looked) {
            synchronized (this) {
                if (!looked) look();
            }
        }
        startLooker();
    }

    /** From now on this socket is opened anew where it belongs whenever the address comes, changes or goes. */
    void follow(PeerSocket s) {
        following.add(s);
        ensure();
    }

    void unfollow(PeerSocket s) {
        following.remove(s);
    }

    /** How many sockets follow the address now. */
    int following() {
        return following.size();
    }

    private synchronized void startLooker() {
        if (looker != null || closed) return;
        Thread t = new Thread(this::lookRun, "tailscaleLook");
        t.setDaemon(true);
        looker = t;
        t.start();
    }

    private void lookRun() {
        while (!closed) {
            try {
                Thread.sleep(lookMs);
            } catch (InterruptedException e) {
                if (closed) return;
            }
            try {
                look();
                for (PeerSocket s : following) s.follow();
            } catch (RuntimeException e) {
                log("Tailscale address: " + e);
            }
        }
    }

    /** No more looks (this PC's own never ends). */
    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            closed = true;
            t = looker;
        }
        if (t != null) t.interrupt();
    }
}
