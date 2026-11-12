package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** FLEET MANAGEMENT's queue (1.0.52): what is sent, when it counts as taken, what is refused, what is kept. */
class FleetManagerTest {
    private final Map<String, String> saved = new HashMap<>();
    private final List<String> log = new ArrayList<>();

    private FleetManager manager() {
        return new FleetManager("key", 7777, saved.get(FleetManager.QUEUE_KEY), saved::putAll, log::add);
    }

    private static Names.Reply reply(String version, FleetProtocol.Status status) {
        return new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "phone", version, null, status == null ? 0 : status.mode, status);
    }

    private static FleetProtocol.Status status(int mode, int token) {
        return new FleetProtocol.Status(80, false, 3, 1, 12, 300, mode, 5, token);
    }

    @Test
    void aQueuedModeGoesAtEachChanceUntilThePhoneSaysItTookIt() {
        FleetManager f = manager();
        assertNull(f.onReply(reply("2.3.0-pc.40", status(0, 0)), 1), "nothing queued: nothing sent");
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_DEEP_SLEEP, 1, 0, 0, 0, 10);
        byte[] p = f.onReply(reply("2.3.0-pc.40", status(0, 0)), 20);
        FleetProtocol.Request r = FleetProtocol.parseRequest(p, p.length, "key");
        assertEquals(w.token(), r.token);
        assertEquals(ControlLink.POWER_DEEP_SLEEP, r.mode);
        assertEquals(1, r.intervalMin);
        assertEquals(7777, r.pcPort, "the phone checks in with this PC's link port from then on");
        assertNotNull(f.onReply(reply("2.3.0-pc.40", status(0, 0)), 30), "not taken yet: again");

        List<FleetManager.Wanted> taken = new ArrayList<>();
        f.setOnTaken(taken::add);
        assertNull(f.onReply(reply("2.3.0-pc.40", status(2, w.token())), 40), "taken: nothing more");
        assertNull(f.wanted("MobileAlpha"));
        assertEquals(List.of(w), taken);
        assertEquals(80, f.heard("MobileAlpha").status().batteryPercent);
    }

    @Test
    void aSleepingPhoneGetsItAtItsCheckIn() {
        FleetManager f = manager();
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_OVERWATCH, 2, 2, 1, 0xabc, 10);
        ControlLink.SleepHello hello = new ControlLink.SleepHello(60, false, 5, 3900, 50, 0, 0, status(2, 0));
        byte[] p = f.onHello("MobileAlpha", hello, 50);
        FleetProtocol.Request r = FleetProtocol.parseRequest(p, p.length, "key");
        assertEquals(ControlLink.POWER_OVERWATCH, r.mode);
        assertEquals(0xabc, r.owToken);
        assertEquals(FleetProtocol.MIN_PC_BUILD, f.pcBuild("MobileAlpha"), "a hello with a status: pc.40 on");
        assertNull(f.onHello("MobileAlpha", new ControlLink.SleepHello(60, false, 2, 3900, 90, 1, 0, status(3, w.token())), 90));
        assertNull(f.wanted("MobileAlpha"));
    }

    @Test
    void anOlderPhoneIsNeverSentIt() {
        FleetManager f = manager();
        f.queue("MobileAlpha", ControlLink.POWER_STANDBY, 5, 0, 0, 0, 1);
        assertNull(f.onReply(reply("2.3.0-pc.39", null), 2), "pc.39 drops it: not sent");
        assertEquals(39, f.pcBuild("MobileAlpha"));
    }

    @Test
    void refusedWhileArmedFlyingConnectedOrTooOld() {
        assertNotNull(FleetManager.refusal(true, false, false, 40), "its drone armed");
        assertNotNull(FleetManager.refusal(null, true, false, 40), "this PC may be flying it");
        assertNotNull(FleetManager.refusal(false, false, true, 40), "connected here: the LINK card");
        assertTrue(FleetManager.refusal(false, false, false, 39).contains("pc.40"));
        assertNull(FleetManager.refusal(false, false, false, 40));
        assertNull(FleetManager.refusal(null, false, false, -1), "not known yet: queued, sent once it says pc.40");
    }

    @Test
    void theQueueSurvivesARestartAndCancelEmptiesIt() {
        FleetManager f = manager();
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_OVERWATCH, 255, 3, 1, 0xfeedbeef, 123);
        FleetManager again = manager();
        assertEquals(w, again.wanted("mobilealpha"));
        again.cancel("MobileAlpha");
        assertNull(manager().wanted("MobileAlpha"));
    }

    private static final long T0 = 1_800_000_000_000L, MIN = 60_000L;

    /**
     * 1.0.64, item 4a: a queued mode never expired and outlived restarts - a DEEP SLEEP queued for a phone out of reach
     * went to it days later, at its first answer. Dropped after 15 min, said in the log; at the phone's first answer
     * nothing is sent any more.
     */
    @Test
    void aQueuedModeIsDroppedWhenItsPhoneIsNotReachedInTime() {
        FleetManager f = manager();
        List<FleetManager.Wanted> dropped = new ArrayList<>();
        f.setOnDropped(dropped::add);
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_DEEP_SLEEP, 5, 0, 0, 0, T0);
        f.expire(T0 + 15 * MIN);
        assertNotNull(f.wanted("MobileAlpha"), "15 min: still waiting");
        assertNotNull(f.onReply(reply("2.3.0-pc.40", status(0, 0)), T0 + 15 * MIN), "and sent when it answers");
        f.expire(T0 + 15 * MIN + 1000);
        assertNull(f.wanted("MobileAlpha"));
        assertEquals(List.of("MobileAlpha: queued DEEP SLEEP dropped - not reached in 15 min."), log);
        assertEquals(List.of(w), dropped);
        assertEquals("", saved.get(FleetManager.QUEUE_KEY), "gone from the settings too");

        // days later, a restart in between: its first answer gets nothing - whether or not a scan dropped it first
        log.clear();
        f.queue("MobileAlpha", ControlLink.POWER_DEEP_SLEEP, 5, 0, 0, 0, T0);
        FleetManager later = manager();
        assertNotNull(later.wanted("MobileAlpha"), "read back as it was");
        assertNull(later.onReply(reply("2.3.0-pc.40", status(0, 0)), T0 + 3 * 24 * 60 * MIN), "never sent days later");
        assertNull(later.wanted("MobileAlpha"));
        assertEquals(1, log.size(), log.toString());
        FleetManager restarted = manager();
        assertNull(restarted.wanted("MobileAlpha"), "and not there after the next start");
    }

    /** ... or after two of the phone's own check-ins, when it sleeps with a longer interval than 7.5 min. */
    @Test
    void aSleepersQueuedModeWaitsTwoOfItsCheckIns() {
        FleetManager f = manager();
        f.queue("MobileAlpha", ControlLink.POWER_STANDBY, 0, 0, 0, 0, T0);
        // asleep, checking in every 30 min (its own last status)
        f.onHello("MobileAlpha", new ControlLink.SleepHello(60, false, 30, 3900, T0, 0, 0, status30(2)), T0 + 1000);
        f.expire(T0 + 59 * MIN);
        assertNotNull(f.wanted("MobileAlpha"), "its second check-in is still to come");
        f.expire(T0 + 61 * MIN);
        assertNull(f.wanted("MobileAlpha"));
        assertEquals("MobileAlpha: queued STANDBY dropped - not reached in 60 min.", log.get(log.size() - 1));
        // this PC's sleep book says how often it checks in (a phone that hasn't said so itself)
        FleetManager g = manager();
        g.setCheckEvery(phone -> phone.equals("MobileBravo") ? 20 : 0);
        g.queue("MobileBravo", ControlLink.POWER_STANDBY, 0, 0, 0, 0, T0);
        g.queue("MobileCharlie", ControlLink.POWER_STANDBY, 0, 0, 0, 0, T0);
        g.expire(T0 + 39 * MIN);
        assertNotNull(g.wanted("MobileBravo"));
        assertNull(g.wanted("MobileCharlie"), "nothing known of it: 15 min");
        g.setCheckEvery(phone -> de.droiddrone.common.SleepCheck.ALWAYS); // listening: a hello a minute - 15 min
        g.expire(T0 + 39 * MIN);
        assertNull(g.wanted("MobileBravo"));
    }

    private static FleetProtocol.Status status30(int mode) {
        return new FleetProtocol.Status(60, false, 3, 1, 12, 300, mode, 30, 0);
    }

    /** 1.0.64, item 4b: a renamed phone's queued mode (and what FLEET knows of it) goes to its new name; it sat under the old one for ever. */
    @Test
    void aRenamedPhonesQueuedModeGoesWithIt() {
        FleetManager f = manager();
        f.onReply(reply("2.3.0-pc.47", status(0, 0)), T0);
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_OVERWATCH, 2, 2, 1, 0xabc, T0);
        f.rename("mobilealpha", "MobileKilo");
        assertNull(f.wanted("MobileAlpha"));
        FleetManager.Wanted moved = f.wanted("MobileKilo");
        assertEquals(new FleetManager.Wanted("MobileKilo", w.token(), w.mode(), 2, 2, 1, 0xabc, T0), moved);
        assertEquals(47, f.pcBuild("MobileKilo"));
        assertNotNull(f.heard("MobileKilo"));
        assertTrue(saved.get(FleetManager.QUEUE_KEY).startsWith("MobileKilo|"), saved.toString());
        // the phone answers with its new name: it gets the mode, and its "taken" counts
        Names.Reply asKilo = new Names.Reply(Names.Kind.PHONE, "MobileKilo", "phone", "2.3.0-pc.47", null, 0, status(0, 0));
        assertNotNull(f.onReply(asKilo, T0 + 1000));
        assertTrue(f.ownToken("MobileKilo", w.token()));
        assertNull(f.onReply(new Names.Reply(Names.Kind.PHONE, "MobileKilo", "phone", "2.3.0-pc.47", null, 3, status(3, w.token())), T0 + 2000));
        assertNull(f.wanted("MobileKilo"));
        assertTrue(f.ownToken("MobileKilo", w.token()), "the mode it took last is this PC's");
        assertFalse(f.ownToken("MobileKilo", 0));
        assertFalse(f.ownToken("MobileKilo", w.token() + 1));
    }

    /**
     * 1.0.64, item 4c: a mode was queued for a phone another PC flies - the phone refuses it while that pilot's link
     * holds and took it the moment the link dropped. Refused at the button, and one queued before is dropped when the
     * phone says a pilot flies it (with or without a GPS fix in its answer).
     */
    @Test
    void noModeForADroneAnotherPcFlies() {
        assertEquals("MobileAlpha is flown by another PC.", FleetManager.refusal("MobileAlpha", false, false, false, true, 47));
        assertEquals("MobileAlpha is flown by another PC.", FleetManager.refusal("MobileAlpha", true, false, false, true, 47), "armed, and not this PC's to land");
        assertEquals("Armed: land and disarm first.", FleetManager.refusal("MobileAlpha", true, true, true, true, 47), "this PC flies it");
        assertEquals("Connected: use the LINK card.", FleetManager.refusal("MobileAlpha", false, false, true, true, 47), "the pilot it says is this PC");
        assertNull(FleetManager.refusal("MobileAlpha", false, false, false, false, 47));

        FleetManager f = manager();
        List<FleetManager.Wanted> dropped = new ArrayList<>();
        f.setOnDropped(dropped::add);
        assertFalse(f.flown("MobileAlpha", T0));
        FleetManager.Wanted w = f.queue("MobileAlpha", ControlLink.POWER_DEEP_SLEEP, 5, 0, 0, 0, T0);
        assertNull(f.onReply(flownReply(true), T0 + 5000), "a pilot flies it: nothing sent");
        assertTrue(f.flown("MobileAlpha", T0 + 6000));
        assertNull(f.wanted("MobileAlpha"), "and nothing left to land on it when that pilot's link drops");
        assertEquals(List.of(w), dropped);
        assertEquals(List.of("MobileAlpha: queued DEEP SLEEP dropped - flown by another PC."), log);
        assertFalse(f.flown("MobileAlpha", T0 + 5000 + FleetManager.FLOWN_FRESH_MS + 1), "its word grows old");
        assertNull(f.onReply(flownReply(false), T0 + 10_000));
        assertFalse(f.flown("MobileAlpha", T0 + 10_000), "the pilot left");
    }

    /** A phone's answer with no GPS fix (no position) that says whether a pilot PC flies it. */
    private static Names.Reply flownReply(boolean flown) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.write(Names.reply("key", Names.Kind.PHONE, "MobileAlpha", "phone", "2.3.0-pc.47"));
            out.writeByte(1);   // block version
            out.writeByte(0);   // no fix
            out.writeInt(0);
            out.writeInt(0);
            out.writeShort(0);
            out.writeByte(0);   // disarmed
            out.writeByte(flown ? 1 : 0);
            out.writeByte(flown ? 4 : 0);
            if (flown) out.write(new byte[]{100, 64, 0, 9});
            out.writeByte(0);   // normal mode
            status(0, 0).writeTo(out);
            byte[] d = bytes.toByteArray();
            Names.Reply r = Names.parseReply(d, d.length, "key");
            assertNull(r.position(), "no fix: no position");
            assertEquals(flown, r.flown(), "but its word on the pilot is read");
            return r;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 1.0.64, item 4d: PILOT UAV on a phone with a mode queued - the Connect and the mode raced (asleep: the mode went
     * ahead of the "wake" at its check-in). This PC's own pilot link to it: the queued mode is dropped, never sent.
     */
    @Test
    void noQueuedModeForThePhoneThisPcIsConnectingTo() {
        FleetManager f = manager();
        boolean[] connected = {false};
        f.setPilotHere(phone -> connected[0] && phone.equals("MobileAlpha"));
        f.queue("MobileAlpha", ControlLink.POWER_STANDBY, 0, 0, 0, 0, T0);
        ControlLink.SleepHello hello = new ControlLink.SleepHello(60, false, 5, 3900, T0, 0, 0, status(2, 0));
        assertNotNull(f.onHello("MobileAlpha", hello, T0 + 1000), "not connected: it goes at the check-in");
        connected[0] = true;
        assertNull(f.onHello("MobileAlpha", hello, T0 + 2500));
        assertNull(f.wanted("MobileAlpha"));
        assertEquals("MobileAlpha: queued STANDBY dropped - this PC flies it.", log.get(log.size() - 1));
        // cancel tells too (a queued OVERWATCH's token is taken back)
        List<FleetManager.Wanted> dropped = new ArrayList<>();
        f.setOnDropped(dropped::add);
        FleetManager.Wanted first = f.queue("MobileBravo", ControlLink.POWER_OVERWATCH, 5, 2, 0, 0x11, T0);
        FleetManager.Wanted second = f.queue("MobileBravo", ControlLink.POWER_OVERWATCH, 1, 2, 0, 0x22, T0);
        assertEquals(List.of(first), dropped, "replaced: the one before is dropped");
        f.cancel("MobileBravo");
        assertEquals(List.of(first, second), dropped);
        f.cancel("MobileBravo");
        assertEquals(2, dropped.size());
    }
}
