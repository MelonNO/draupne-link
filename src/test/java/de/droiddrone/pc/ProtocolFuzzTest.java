package de.droiddrone.pc;

import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PcAudio;
import de.droiddrone.pc.audio.AudioConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every parser that reads a datagram from the network (any Tailscale peer can send one; a phone's can arrive cut
 * short) on random bytes and on every cut of a valid packet: it answers null / -1 / "not one", never throws. A throw
 * on a receive thread drops the packets behind it, or ends the thread.
 */
class ProtocolFuzzTest {
    static final String KEY = "key";

    /** The parsers, each given (bytes, length). */
    static List<Object[]> parsers() {
        List<Object[]> p = new ArrayList<>();
        add(p, "Names.keyOf", (d, n) -> Names.keyOf(d, n));
        add(p, "Names.parseReply", (d, n) -> Names.parseReply(d, n, KEY));
        add(p, "Names.parseAssign", (d, n) -> Names.parseAssign(d, n, KEY));
        add(p, "Spectate.readPilotView", (d, n) -> Spectate.readPilotView(d, n));
        add(p, "Spectate.readKey", (d, n) -> Spectate.readKey(d, n));
        add(p, "Spectate.readPilotAt", (d, n) -> Spectate.readPilotAt(d, n));
        add(p, "Spectate.readPort", (d, n) -> Spectate.readPort(d, n));
        add(p, "Observe.decodeRequest", (d, n) -> Observe.decodeRequest(d, n));
        add(p, "Observe.keyOf", (d, n) -> Observe.keyOf(d, n));
        add(p, "Observe.decodeState", (d, n) -> Observe.decodeState(d, n));
        add(p, "PcAudio.decode", (d, n) -> PcAudio.decode(d, n));
        add(p, "FleetProtocol.parseRequest", (d, n) -> FleetProtocol.parseRequest(d, n, KEY));
        add(p, "FleetProtocol.Where.read", (d, n) -> FleetProtocol.Where.read(d, Math.min(1, n), n, KEY));
        add(p, "FleetProtocol.Status.read", (d, n) -> FleetProtocol.Status.read(d, Math.min(1, n), n));
        add(p, "OverwatchProtocol.isFromPhone", (d, n) -> OverwatchProtocol.isFromPhone(d, n));
        add(p, "OverwatchProtocol.readAlert", (d, n) -> OverwatchProtocol.readAlert(d, n));
        add(p, "OverwatchProtocol.readCommand", (d, n) -> OverwatchProtocol.readCommand(d, n));
        add(p, "OverwatchProtocol.readFragment", (d, n) -> OverwatchProtocol.readFragment(d, n));
        add(p, "OverwatchProtocol.clipType", (d, n) -> OverwatchProtocol.clipType(d, n));
        add(p, "OverwatchProtocol.readClipInfo", (d, n) -> OverwatchProtocol.readClipInfo(d, n));
        add(p, "OverwatchProtocol.readClipEnded", (d, n) -> OverwatchProtocol.readClipEnded(d, n));
        add(p, "OverwatchProtocol.readClipNone", (d, n) -> OverwatchProtocol.readClipNone(d, n));
        add(p, "OverwatchProtocol.readStatus", (d, n) -> OverwatchProtocol.readStatus(d, n));
        add(p, "OverwatchProtocol.readRequestTail", (d, n) -> OverwatchProtocol.readRequestTail(d, Math.min(1, n), n));
        add(p, "ArmRanges.parse", (d, n) -> ArmRanges.parse(java.util.Arrays.copyOf(d, n)));
        add(p, "AudioConfig.parse", (d, n) -> AudioConfig.parse(d, Math.min(3, n), Math.max(0, n - 3)));
        add(p, "ControlLink.SleepHello.read", (d, n) -> {
            if (n >= 6) ControlLink.SleepHello.read(d, n, 0); // the receive thread checks the 6 first
            return null;
        });
        return p;
    }

    interface Parser {
        Object parse(byte[] d, int len) throws Exception;
    }

    static void add(List<Object[]> p, String name, Parser f) {
        p.add(new Object[]{name, f});
    }

    /** Runs every parser over the input; the failures by name. */
    static void feed(byte[] d, int len, Consumer<String> failed) {
        for (Object[] p : parsers()) {
            try {
                ((Parser) p[1]).parse(d, len);
            } catch (Throwable t) {
                failed.accept(p[0] + " on " + len + " bytes [" + (len > 0 ? d[0] : "") + "...]: " + t);
            }
        }
    }

    @Test
    void randomDatagramsAreRefusedWithoutAThrow() {
        Random r = new Random(1);
        List<String> failures = new ArrayList<>();
        byte[] codes = {Names.NAME_QUERY, Names.NAME_REPLY, Names.NAME_ASSIGN, Spectate.PILOT_AT, Spectate.PILOT_VIEW,
                Spectate.HELLO, OverwatchProtocol.ALERT, OverwatchProtocol.PREVIEW, OverwatchProtocol.CLIP,
                OverwatchProtocol.STATUS, FleetProtocol.MODE, PcAudio.PACKET, 0x50, 0x51, 0x52, 0, (byte) 0xff};
        for (int i = 0; i < 20_000; i++) {
            byte[] d = new byte[r.nextInt(80)];
            r.nextBytes(d);
            if (d.length > 0 && r.nextBoolean()) d[0] = codes[r.nextInt(codes.length)];
            if (d.length > 2 && r.nextBoolean()) { // a plausible string length, so the reads get further in
                d[1] = 0;
                d[2] = (byte) Math.min(d.length - 3, KEY.length());
                if (d[2] == KEY.length()) System.arraycopy(KEY.getBytes(), 0, d, 3, KEY.length());
            }
            feed(d, d.length, failures::add);
            if (failures.size() > 20) break;
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void everyCutOfAValidPacketIsRefusedWithoutAThrow() throws Exception {
        List<byte[]> valid = new ArrayList<>();
        valid.add(Spectate.pilotView(new ControlLink.PilotView(true, true, false, false, true, false, "THROTTLE DOWN",
                "LINK BACK", "hint", new short[]{1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000}, "MobileAlpha", true)));
        valid.add(Spectate.pilotAt(java.net.InetAddress.getByName("::1"), 6286));
        valid.add(Spectate.keyed(Spectate.HELLO, KEY));
        valid.add(Observe.encodeStop(KEY));
        valid.add(Observe.encodeRequest(KEY, 640, 360, 15, 800, 3, 2));
        valid.add(FleetProtocol.request(KEY, new FleetProtocol.Request(7, 3, 1, 2, 0, 9, 6286)));
        valid.add(Observe.encodeState(1, 1280, 720, 30, 2));
        valid.add(PcAudio.encode(true, 32, true, true));
        List<String> failures = new ArrayList<>();
        for (byte[] v : valid) {
            for (int n = 0; n <= v.length; n++) {
                byte[] cut = java.util.Arrays.copyOf(v, n);
                feed(cut, n, failures::add);
                feed(v, n, failures::add); // the length short of the array, as a reused receive buffer gives it
            }
        }
        assertEquals(List.of(), failures);
    }
}
