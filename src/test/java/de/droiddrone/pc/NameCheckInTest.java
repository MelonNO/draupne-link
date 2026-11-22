package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sleeping phone's check-in (its name and position, pc.40) no longer moves a name the map has at another address to
 * the check-in's sender, nor saves it there: only that phone's answer to a scan from the new address does, or the
 * pilot's own word (the name removed in the NAME MAP, and the next check-in files it anew). Before, a check-in with the
 * key moved the name and saved it - the next PILOT UAV and the name's overwatch alerts went to the new address.
 */
class NameCheckInTest {
    private static final String AT = "100.64.0.5", ELSEWHERE = "100.64.0.66";
    private final Map<String, String> saved = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();

    private NameService service(Names names) throws Exception {
        return new NameService(PcConfig.defaults(), names, () -> null, 1, false, saved::putAll, log::add);
    }

    /** MobileAlpha as a scan filed it: at AT, its drone at (59.9, 10.7). */
    private Names filed() {
        Names names = new Names(null, saved::putAll);
        Names.Position p = new Names.Position(false, 59.9, 10.7, 3, Boolean.FALSE, false, "", 2);
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.55", p), AT, "pixel-8");
        saved.clear();
        return names;
    }

    @Test
    void aCheckInFromAnotherAddressLeavesTheNameWhereItIs() throws Exception {
        Names names = filed();
        NameService svc = service(names);
        boolean listChanged = svc.heardAsleep(ELSEWHERE, new FleetProtocol.Where("MobileAlpha", 2, 60.5, 11.5, 0), 2);
        assertFalse(listChanged);
        assertEquals(AT, names.ipOf("MobileAlpha"), "PILOT UAV goes where the scan found it");
        assertFalse(saved.containsKey("name.MobileAlpha"), "nothing saved: " + saved);
        assertTrue(svc.dronePositions().stream().noneMatch(d -> d.position().lat() == 60.5), "nor drawn where that check-in says");
        assertNull(names.nameAt(ELSEWHERE));
        assertEquals(1, log.stream().filter(l -> l.contains("MobileAlpha") && l.contains(ELSEWHERE)).count(), "said once: " + log);
        svc.heardAsleep(ELSEWHERE, new FleetProtocol.Where("MobileAlpha", 2, 60.5, 11.5, 0), 2);
        assertEquals(1, log.stream().filter(l -> l.contains("MobileAlpha") && l.contains(ELSEWHERE)).count(), "and not at every check-in");
    }

    @Test
    void aCheckInFromItsOwnAddressOrOfANewNameIsFiledAsBefore() throws Exception {
        Names names = filed();
        NameService svc = service(names);
        svc.heardAsleep(AT, new FleetProtocol.Where("MobileAlpha", 2, 59.95, 10.72, 0), 2);
        assertEquals(AT, names.ipOf("MobileAlpha"));
        assertTrue(svc.dronePositions().stream().anyMatch(d -> d.name().equals("MobileAlpha") && d.position().lat() == 59.95), "drawn where it is now");
        // a phone asleep since before any scan reached it (the tour's drones): listed and drawn from its check-in alone
        assertTrue(svc.heardAsleep(ELSEWHERE, new FleetProtocol.Where("MobileBravo", 2, 60.1, 10.9, 0), 2));
        assertEquals(ELSEWHERE, names.ipOf("MobileBravo"));
    }

    @Test
    void aScanAnswerFromTheNewAddressOrThePilotsWordMovesIt() throws Exception {
        Names names = filed();
        // the phone's own answer to this PC's scan, from the new address: the newest claim wins, as ever
        assertEquals(AT, names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.55"), ELSEWHERE, "pixel-8"));
        assertEquals(ELSEWHERE, names.ipOf("MobileAlpha"));
        assertTrue(saved.get("name.MobileAlpha").contains(ELSEWHERE));
        // the pilot removes the name in the NAME MAP: the next check-in files it where it comes from
        Names again = filed();
        NameService svc = service(again);
        again.remove("MobileAlpha");
        assertTrue(svc.heardAsleep(ELSEWHERE, new FleetProtocol.Where("MobileAlpha", 2, 60.5, 11.5, 0), 2));
        assertEquals(ELSEWHERE, again.ipOf("MobileAlpha"));
    }
}
