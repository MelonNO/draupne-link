package de.droiddrone.bench;

import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The bench's wire to a phone that is not on loopback - the one real phone of a hardware run. Until then every phone
 * of every test was on 127.x, and the wire's own socket was bound there: from such a socket nothing leaves this PC.
 * Stood in for here by this PC's own address on its network: off loopback for the wire, and yet nothing leaves the PC.
 */
class PhoneRigWireTest {
    /** An address of this PC that is not loopback; null when it has none (no network at all). */
    private static InetAddress ownAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress() && !a.isAnyLocalAddress()) return a;
            }
        }
        return null;
    }

    @Test
    void aPhoneOffLoopbackIsReachedAndOnlyItsOwnAddressIsHeardForIt() throws Exception {
        InetAddress own = ownAddress();
        Assumptions.assumeTrue(own != null, "this PC has no address but loopback: nothing to try the wire against");
        byte[] buf = new byte[64];
        try (DatagramSocket phone = new DatagramSocket(0, own); // the "phone": on this PC's network address
             Wire wire = new Wire(own, phone.getLocalPort());
             DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket stranger = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            phone.setSoTimeout(5000);
            pc.setSoTimeout(5000);

            // PC -> wire -> phone
            pc.send(new DatagramPacket(new byte[]{7, 1, 2}, 3, InetAddress.getLoopbackAddress(), wire.port()));
            DatagramPacket atPhone = new DatagramPacket(buf, buf.length);
            phone.receive(atPhone);
            assertArrayEquals(new byte[]{7, 1, 2}, java.util.Arrays.copyOf(atPhone.getData(), atPhone.getLength()));
            assertEquals(wire.pcPortSeenByPhone(), atPhone.getPort(), "the phone sees the wire as its PC");

            // phone -> wire -> PC
            phone.send(new DatagramPacket(new byte[]{8, 3}, 2, atPhone.getSocketAddress()));
            DatagramPacket atPc = new DatagramPacket(buf, buf.length);
            pc.receive(atPc);
            assertArrayEquals(new byte[]{8, 3}, java.util.Arrays.copyOf(atPc.getData(), atPc.getLength()));
            assertEquals(1, wire.toPhone(7));
            assertEquals(1, wire.toPc(8));

            // somebody else who finds the wire's port on the network is not the phone: not passed on to the PC
            stranger.send(new DatagramPacket(new byte[]{9}, 1, InetAddress.getLoopbackAddress(), wire.pcPortSeenByPhone()));
            pc.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> pc.receive(new DatagramPacket(new byte[64], 64)));
            assertEquals(0, wire.fromPhone(9));
        }
    }

    /** As ever on loopback: both of the wire's sockets there, and whoever answers on the phone's side is the phone (the made-up phones answer from 127.0.0.x). */
    @Test
    void onLoopbackTheWireIsAsItWas() throws Exception {
        byte[] buf = new byte[64];
        try (DatagramSocket phone = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             Wire wire = new Wire(InetAddress.getLoopbackAddress(), phone.getLocalPort());
             DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            phone.setSoTimeout(5000);
            pc.setSoTimeout(5000);
            pc.send(new DatagramPacket(new byte[]{7}, 1, InetAddress.getLoopbackAddress(), wire.port()));
            DatagramPacket atPhone = new DatagramPacket(buf, buf.length);
            phone.receive(atPhone);
            assertEquals(InetAddress.getLoopbackAddress(), atPhone.getAddress(), "sent from loopback: nothing of it on the network");
            phone.send(new DatagramPacket(new byte[]{8}, 1, atPhone.getSocketAddress()));
            DatagramPacket atPc = new DatagramPacket(buf, buf.length);
            pc.receive(atPc);
            assertEquals(8, atPc.getData()[0]);
        }
    }
}
