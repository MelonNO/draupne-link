package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** OVERWATCH SLEEP's packets (1.0.47 / pc.36): each one written as the phone or the PC does, and read back. */
class OverwatchProtocolTest {
    private static final int TOKEN = 0xCAFE1234;

    @Test
    void anAlertReadsBackAsWritten() {
        OverwatchProtocol.Alert a = new OverwatchProtocol.Alert(TOKEN, 513, 2, OverwatchProtocol.ALERT_LIGHT, 1_762_500_000_123L,
                0.25f, 0.5f, 0.1f, 0.2f, -12.34f, 68.5f, 599_123_456, 107_654_321, 42, 87, "MobileAlpha");
        byte[] p = OverwatchProtocol.alert(a);
        assertTrue(p.length < 1100);
        assertTrue(OverwatchProtocol.isFromPhone(p, p.length));
        OverwatchProtocol.Alert r = OverwatchProtocol.readAlert(p, p.length);
        assertNotNull(r);
        assertEquals(TOKEN, r.token);
        assertEquals(513, r.event);
        assertEquals(2, r.alertNo);
        assertTrue(r.light());
        assertEquals(1_762_500_000_123L, r.startedAt);
        assertEquals(0.25f, r.x, 1e-4);
        assertEquals(0.5f, r.y, 1e-4);
        assertEquals(0.1f, r.w, 1e-4);
        assertEquals(0.2f, r.h, 1e-4);
        assertEquals(-12.34f, r.angleDeg, 0.01);
        assertEquals(68.5f, r.hfovDeg, 0.01);
        assertEquals(599_123_456, r.latE7);
        assertEquals(107_654_321, r.lonE7);
        assertTrue(r.hasPosition());
        assertEquals(87, r.battery);
        assertEquals("MobileAlpha", r.name);
        assertNull(OverwatchProtocol.readAlert(p, p.length - 4), "cut short: not read");
    }

    @Test
    void anAlertWithoutAPositionSaysSo() {
        OverwatchProtocol.Alert a = new OverwatchProtocol.Alert(TOKEN, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xffff, 255, "");
        byte[] p = OverwatchProtocol.alert(a);
        assertFalse(OverwatchProtocol.readAlert(p, p.length).hasPosition());
    }

    @Test
    void commandsReadBackWithTheirArguments() {
        byte[] get = OverwatchProtocol.command(TOKEN, 7, OverwatchProtocol.CMD_CLIP_GET, 3_000_000_000L, 64);
        OverwatchProtocol.Command c = OverwatchProtocol.readCommand(get, get.length);
        assertEquals(TOKEN, c.token);
        assertEquals(7, c.event);
        assertEquals(OverwatchProtocol.CMD_CLIP_GET, c.cmd);
        assertEquals(3_000_000_000L, c.offset, "an offset over 2 GB stays positive");
        assertEquals(64, c.count);
        byte[] done = OverwatchProtocol.command(TOKEN, 7, OverwatchProtocol.CMD_CLIP_DONE, 1, 0);
        assertEquals(1, OverwatchProtocol.readCommand(done, done.length).offset);
        byte[] dismiss = OverwatchProtocol.command(TOKEN, 65535, OverwatchProtocol.CMD_DISMISS);
        OverwatchProtocol.Command d = OverwatchProtocol.readCommand(dismiss, dismiss.length);
        assertEquals(65535, d.event);
        assertEquals(OverwatchProtocol.CMD_DISMISS, d.cmd);
        assertFalse(OverwatchProtocol.isFromPhone(dismiss, dismiss.length), "the PC's command is not the phone's packet");
    }

    @Test
    void aUnitCutIntoFragmentsComesTogetherInAnyOrder() {
        byte[] frame = new byte[OverwatchProtocol.FRAGMENT * 5 + 77];
        new Random(4).nextBytes(frame);
        List<byte[]> parts = new ArrayList<>(OverwatchProtocol.fragments(TOKEN, 9, 300, OverwatchProtocol.UNIT_KEY_FRAME, frame));
        assertEquals(6, parts.size());
        for (byte[] p : parts) assertTrue(p.length <= 1100, "every datagram under the link's 1100 bytes");
        Collections.shuffle(parts, new Random(1));
        UnitAssembler a = new UnitAssembler();
        UnitAssembler.Unit done = null;
        for (int i = 0; i < parts.size(); i++) {
            byte[] p = parts.get(i);
            OverwatchProtocol.Fragment f = OverwatchProtocol.readFragment(p, p.length);
            assertEquals(TOKEN, f.token);
            UnitAssembler.Unit u = a.add(f);
            if (i < parts.size() - 1) assertNull(u);
            else done = u;
            if (i == 2) assertNull(a.add(f), "a repeat changes nothing");
        }
        assertNotNull(done);
        assertEquals(OverwatchProtocol.UNIT_KEY_FRAME, done.type);
        assertEquals(9, done.event);
        assertEquals(300, done.unit);
        assertArrayEquals(frame, done.data);
    }

    @Test
    void anUnfinishedUnitIsGivenUpWhenTooManyNewerOnesOpen() {
        UnitAssembler a = new UnitAssembler();
        byte[] big = new byte[OverwatchProtocol.FRAGMENT * 2];
        byte[] first = OverwatchProtocol.fragments(TOKEN, 1, 0, OverwatchProtocol.UNIT_FRAME, big).get(0);
        a.add(OverwatchProtocol.readFragment(first, first.length));
        assertTrue(a.isOpen(1, 0));
        for (int u = 1; u <= UnitAssembler.MAX_OPEN; u++) {
            byte[] p = OverwatchProtocol.fragments(TOKEN, 1, u, OverwatchProtocol.UNIT_FRAME, big).get(0);
            a.add(OverwatchProtocol.readFragment(p, p.length));
        }
        assertFalse(a.isOpen(1, 0));
        assertEquals(1, a.givenUp());
    }

    @Test
    void anEmptyUnitAndASmallOneArriveWhole() {
        UnitAssembler a = new UnitAssembler();
        byte[] p = OverwatchProtocol.fragments(TOKEN, 2, 5, OverwatchProtocol.UNIT_CONFIG, new byte[]{0, 0, 0, 1, 0x67}).get(0);
        UnitAssembler.Unit u = a.add(OverwatchProtocol.readFragment(p, p.length));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67}, u.data);
        byte[] e = OverwatchProtocol.fragments(TOKEN, 2, 6, OverwatchProtocol.UNIT_FRAME, new byte[0]).get(0);
        assertEquals(0, a.add(OverwatchProtocol.readFragment(e, e.length)).data.length);
    }

    @Test
    void theClipsPacketsReadBack() {
        byte[] sha = new byte[32];
        for (int i = 0; i < 32; i++) sha[i] = (byte) (i * 7);
        OverwatchProtocol.ClipInfo info = new OverwatchProtocol.ClipInfo(TOKEN, 12, 98_765_432L, sha, 125_000, 1_762_500_000_000L, true, 1280, 720);
        byte[] p = OverwatchProtocol.clipInfo(info);
        assertEquals(OverwatchProtocol.CLIP_INFO, OverwatchProtocol.clipType(p, p.length));
        OverwatchProtocol.ClipInfo r = OverwatchProtocol.readClipInfo(p, p.length);
        assertEquals(12, r.event);
        assertEquals(98_765_432L, r.size);
        assertArrayEquals(sha, r.sha256);
        assertEquals(125_000, r.durationMs);
        assertEquals(1_762_500_000_000L, r.startedAt);
        assertTrue(r.sound);
        assertEquals(1280, r.width);
        assertEquals(720, r.height);

        byte[] data = new byte[2000];
        new Random(2).nextBytes(data);
        byte[] chunk = OverwatchProtocol.clipChunk(TOKEN, 12, 4096, data, 100, OverwatchProtocol.FRAGMENT);
        assertTrue(chunk.length <= 1100);
        assertEquals(OverwatchProtocol.CLIP_CHUNK, OverwatchProtocol.clipType(chunk, chunk.length));
        assertEquals(12, OverwatchProtocol.clipEvent(chunk));
        assertEquals(4096, OverwatchProtocol.chunkOffset(chunk));
        assertEquals(data[100], chunk[OverwatchProtocol.CHUNK_DATA_AT]);

        byte[] ended = OverwatchProtocol.clipEnded(TOKEN, 12, 1_762_500_125_000L, true);
        long[] e = OverwatchProtocol.readClipEnded(ended, ended.length);
        assertEquals(1_762_500_125_000L, e[0]);
        assertEquals(1, e[1]);
        byte[] none = OverwatchProtocol.clipNone(TOKEN, 12, "no encoder");
        assertEquals("no encoder", OverwatchProtocol.readClipNone(none, none.length));
    }

    @Test
    void theStatusAndTheRequestReadBack() {
        byte[] s = OverwatchProtocol.status(TOKEN, OverwatchProtocol.STATE_DROPPED, PowerFloor.REASON_HEAT, 61, -55);
        assertArrayEquals(new int[]{OverwatchProtocol.STATE_DROPPED, PowerFloor.REASON_HEAT, 61, -55}, OverwatchProtocol.readStatus(s, s.length));
        byte[] req = OverwatchProtocol.request((byte) 0x6E, 5, 2, OverwatchProtocol.MODE_FLAG_SOUND, TOKEN);
        assertEquals(0x6E, req[0]);
        assertEquals(OverwatchProtocol.MODE, req[1]);
        assertEquals(5, req[2]);
        assertArrayEquals(new int[]{2, OverwatchProtocol.MODE_FLAG_SOUND, TOKEN}, OverwatchProtocol.readRequestTail(req, 3, req.length));
        assertNull(OverwatchProtocol.readRequestTail(new byte[]{0x6E, 3, 5}, 3, 3), "an old-style request has no tail");
    }
}
