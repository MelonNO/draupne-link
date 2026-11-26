package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-LINK-2: this PC's own Tailscale address is found by its network adapter - an address in Tailscale's ranges on an
 * adapter that is up and says Tailscale - never by the range alone: a LAN that uses 100.64.0.0/10 is not Tailscale.
 */
class OwnTailscaleTest {
    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s); // numbers only: nothing is looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private static OwnTailscale.Adapter adapter(String name, String description, boolean up, String... addresses) {
        List<InetAddress> list = new ArrayList<>();
        for (String a : addresses) list.add(ip(a));
        return new OwnTailscale.Adapter(name, description, up, list);
    }

    private static final OwnTailscale.Adapter WIFI = adapter("wireless_32768", "Example Wi-Fi 6E Adapter", true, "192.0.2.20", "2001:db8::1");
    private static final OwnTailscale.Adapter LOOPBACK = adapter("loopback_0", "Software Loopback Interface 1", true, "127.0.0.1", "::1");
    private static final OwnTailscale.Adapter TAILSCALE = adapter("iftype53_32768", "Tailscale Tunnel", true,
            "fd7a:115c:a1e0::7", "2001:db8::7", "100.64.0.7");

    @Test
    void theTailscaleAdaptersAddressIsFound() {
        List<OwnTailscale.Adapter> pc = List.of(WIFI, LOOPBACK, TAILSCALE);
        assertEquals(ip("100.64.0.7"), OwnTailscale.find(pc, false), "IPv4: in 100.64.0.0/10 on the adapter that says Tailscale");
        assertEquals(ip("fd7a:115c:a1e0::7"), OwnTailscale.find(pc, true), "IPv6: in fd7a:115c:a1e0::/48, not its other one");
        assertEquals(new OwnTailscale.Found(ip("100.64.0.7"), ip("fd7a:115c:a1e0::7")), OwnTailscale.find(pc));
        // Java's own name for the adapter (Windows: its description is the display name) - either says it
        assertEquals(ip("100.64.0.9"), OwnTailscale.find(List.of(adapter("tailscale0", "", true, "100.64.0.9")), false));
        assertTrue(OwnTailscale.isTailscale(adapter("x", "TAILSCALE TUNNEL", true)), "the words in any case");
    }

    @Test
    void aLanIn100_64WithoutTailscalesAdapterIsNotIt() {
        OwnTailscale.Adapter lan = adapter("ethernet_7", "Example Ethernet Controller", true, "100.64.0.21", "100.64.0.20");
        assertNull(OwnTailscale.find(List.of(lan, WIFI, LOOPBACK), false), "the range alone says nothing");
        assertNull(OwnTailscale.find(List.of(lan), true));
        assertEquals(OwnTailscale.Found.NONE, OwnTailscale.find(List.of(lan, WIFI)));
    }

    @Test
    void noAdapterNoAddress() {
        assertNull(OwnTailscale.find(List.of(), false));
        assertNull(OwnTailscale.find(null, false));
        assertNull(OwnTailscale.find(List.of(WIFI, LOOPBACK), false));
        assertNull(OwnTailscale.find(List.of(adapter("iftype53_32768", "Tailscale Tunnel", false, "100.64.0.7")), false),
                "Tailscale's adapter down: no address");
        assertNull(OwnTailscale.find(List.of(adapter("iftype53_32768", "Tailscale Tunnel", true, "192.0.2.30")), false),
                "on Tailscale's adapter but not in its range");
        assertNull(OwnTailscale.find(List.of(adapter("iftype53_32768", "Tailscale Tunnel", true)), false), "no address yet");
    }

    @Test
    void twoAdaptersInTheRange() {
        OwnTailscale.Adapter lan = adapter("ethernet_7", "Example Ethernet Controller", true, "100.64.0.20");
        assertEquals(ip("100.64.0.7"), OwnTailscale.find(List.of(lan, TAILSCALE), false), "a LAN in the range listed first: Tailscale's");
        OwnTailscale.Adapter down = adapter("iftype53_1", "Tailscale Tunnel", false, "100.64.0.30");
        assertEquals(ip("100.64.0.7"), OwnTailscale.find(List.of(down, TAILSCALE), false), "two of Tailscale's: the one up");
        OwnTailscale.Adapter second = adapter("iftype53_2", "Tailscale Tunnel #2", true, "100.64.0.31");
        assertEquals(ip("100.64.0.7"), OwnTailscale.find(List.of(TAILSCALE, second), false), "two up: the first listed");
    }

    @Test
    void thisPcsListHoldsOnlyTailscalesAdapters() {
        // whatever this PC has: only adapters that say Tailscale are asked further (each costs a call to the system)
        for (OwnTailscale.Adapter a : OwnTailscale.systemAdapters()) assertTrue(OwnTailscale.isTailscale(a), a.name());
    }

    @Test
    void theAddressIsLookedAtAgainAndAgain() throws Exception {
        OwnTailscale.Found[] now = {OwnTailscale.Found.NONE};
        try (OwnTailscale own = new OwnTailscale(() -> now[0], 20)) {
            assertNull(own.v4(), "none at first");
            now[0] = new OwnTailscale.Found(ip("100.64.0.7"), null);
            de.droiddrone.testing.Waits.waitFor(() -> ip("100.64.0.7").equals(own.v4()), 3000, "the address seen when it comes");
            now[0] = OwnTailscale.Found.NONE;
            de.droiddrone.testing.Waits.waitFor(() -> own.v4() == null, 3000, "and when it goes");
        }
    }
}
