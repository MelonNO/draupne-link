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
}
