package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet AP-11: SETUP > Mission Planner bridge on and off twice, with its port in use, and with the link it was on gone.
 * Loopback ports of the test's own; no link is started.
 */
class MpBridgeSwitchTest {
    static final class ArduLink extends ControlLink {
        ArduLink() throws java.io.IOException {
            super(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 1, 0);
        }

        @Override
        public FcInfo getFcInfo() {
            return new FcInfo(FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static boolean bound(int port) {
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
            return false;
        } catch (java.net.SocketException e) {
            return true;
        }
    }

    @Test
    void onOffTwiceThePortBusyAndTheLinkGone() throws Exception {
        List<String> said = new ArrayList<>(), log = new ArrayList<>();
        ControlLink[] flown = {null};
        int in = freePort(), out = freePort();
        MpBridgeSwitch sw = new MpBridgeSwitch(new MpBridgeSwitch.Host() {
            public ControlLink flownLink() {
                return flown[0];
            }

            public void message(String text) {
                said.add(text);
            }

            public void log(String line) {
                log.add(line);
            }
        }, in, out);
        sw.toggle();
        assertFalse(sw.isOn());
        assertEquals("Connect to an ArduPilot drone first.", said.get(0));
        flown[0] = new ArduLink();
        for (int i = 0; i < 2; i++) { // on, off, on, off
            sw.toggle();
            assertTrue(sw.isOn(), "on, round " + i + ": " + said);
            assertTrue(bound(in));
            assertEquals("On: click to stop", sw.menuLine());
            sw.toggle();
            assertFalse(sw.isOn());
            assertFalse(bound(in), "off: the port free again");
        }
        // the port in use by another program
        try (DatagramSocket other = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), in))) {
            sw.toggle();
            assertFalse(sw.isOn());
            assertTrue(said.get(said.size() - 1).contains("in use"), said.toString());
        }
        // on, then the link it was on ends (END LINK, another drone): off by itself
        sw.toggle();
        assertTrue(sw.isOn());
        sw.follow(flown[0]);
        assertTrue(sw.isOn(), "the same link: stays on");
        flown[0] = new ArduLink();
        sw.follow(flown[0]);
        assertFalse(sw.isOn(), "another link: off");
        assertFalse(bound(in));
        assertTrue(log.get(log.size() - 1).contains("ended"), log.toString());
        sw.toggle();
        assertTrue(sw.isOn(), "one press turns it on again (it took two)");
        sw.follow(null);
        assertFalse(sw.isOn());
    }
}
