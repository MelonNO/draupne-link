package de.droiddrone.common;

import com.MAVLink.common.msg_command_long;
import com.MAVLink.minimal.msg_heartbeat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The tunnel's frame rules at their edges: MAVLink 2's trimmed payloads, signed frames, MAVLink 1. */
class MavTunnelEdgeTest {
    static byte[] commandLong(int command, int targetSys, int targetComp, boolean v2) {
        return new msg_command_long(1, 5, 0, 0, 0, 0, 0, command, (short) targetSys, (short) targetComp, (short) 0, 255, 190, v2)
                .pack().encodePacket();
    }

    /**
     * MAVLink 2 leaves trailing zero bytes out: a broadcast DO_SET_MODE (targets 0, confirmation 0) carries 29 payload
     * bytes - its command's high byte (176 < 256) is one of the zeros left out. It was refused as "cut short".
     */
    @Test
    void aTrimmedBroadcastModeCommandStillPasses() {
        byte[] f = commandLong(176, 0, 0, true);
        assertEquals(29, f[1] & 0xff, "the library trims as MAVLink says");
        assertEquals(176, MavTunnel.command(f, 0, f.length));
        assertTrue(MavTunnel.toFc(f, 0, f.length));
        // arming trimmed the same way is still refused (400 needs its high byte: 30 bytes)
        byte[] arm = commandLong(400, 0, 0, true);
        assertEquals(400, MavTunnel.command(arm, 0, arm.length));
        assertFalse(MavTunnel.toFc(arm, 0, arm.length));
        // MAVLink 1 sends every byte
        byte[] v1 = commandLong(176, 0, 0, false);
        assertEquals(33, v1[1] & 0xff);
        assertTrue(MavTunnel.toFc(v1, 0, v1.length));
        // a frame cut inside its payload is no command
        assertEquals(-1, MavTunnel.command(f, 0, f.length - 4));
        assertEquals(-1, MavTunnel.command(f, 0, 12));
    }

    @Test
    void neverTheBenchsCommands() {
        int[] never = {400, 241, 209, 246, 410, 0, 65535, 176 + 256};
        for (int c : never) {
            for (boolean v2 : new boolean[]{true, false}) {
                byte[] f = commandLong(c, 1, 1, v2);
                assertFalse(MavTunnel.toFc(f, 0, f.length), c + " v2 " + v2);
            }
        }
        for (int c : MavTunnel.COMMANDS) {
            byte[] f = commandLong(c, 1, 1, true);
            assertTrue(MavTunnel.toFc(f, 0, f.length), "allowed " + c);
        }
    }

    @Test
    void signedAndMixedFramesSplitWhole() {
        byte[] a = commandLong(176, 1, 1, true);
        byte[] b = new msg_heartbeat(0, (short) 2, (short) 3, (short) 0, (short) 4, (short) 3, 1, 1, false).pack().encodePacket();
        byte[] signed = a.clone();
        signed[2] |= 0x01; // incompat flags: signed - 13 bytes of signature follow
        byte[] withSig = new byte[signed.length + 13];
        System.arraycopy(signed, 0, withSig, 0, signed.length);
        byte[] all = new byte[a.length + b.length + withSig.length + 3];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(withSig, 0, all, a.length + b.length, withSig.length);
        all[all.length - 3] = (byte) 0xFD; // a broken tail
        List<int[]> frames = new ArrayList<>();
        int n = MavTunnel.split(all, 0, all.length, (d, off, len) -> frames.add(new int[]{off, len}));
        assertEquals(3, n);
        assertEquals(a.length, frames.get(0)[1]);
        assertEquals(b.length, frames.get(1)[1]);
        assertEquals(withSig.length, frames.get(2)[1]);
        assertEquals(76, MavTunnel.msgId(all, frames.get(0)[0], frames.get(0)[1]));
        assertEquals(0, MavTunnel.msgId(all, frames.get(1)[0], frames.get(1)[1]));
        // a length that claims more than the array holds is cut to it
        assertEquals(3, MavTunnel.split(all, 0, all.length + 1000, (d, off, len) -> { }));
        assertEquals(0, MavTunnel.split(all, all.length + 5, 10, (d, off, len) -> { }));
        assertEquals(-1, MavTunnel.msgId(all, 0, 7));
    }
}
