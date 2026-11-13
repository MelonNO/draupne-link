package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which PCs the phone tells its sleep notice, its check-in and an overwatch alert (moved out of the phone's Udp).
 * Written from the code as it stood before the move - Udp.java of phone app pc.52 (commit 2047470): the maps namedBy
 * and knownPcs (lines 1266-1275, filled in answerName, 227-228), alertPcs (1278-1292) and sendSleepNotice's list
 * (1312-1319); each test names the lines it pins, and {@link #anyRunAnswersAsTheOldCodeDid} runs the old loops beside it.
 */
class KnownPcsTest {
    private static final long T0 = 500_000;
    /** The phone's link port, and the port the PC that set the mode gave. */
    private static final int LINK = 6000, OWN = 51_000;

    /** "pc:port pc:port", in order. */
    private static <K> String told(Map<K, Integer> to) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<K, Integer> e : to.entrySet()) s.append(s.length() == 0 ? "" : " ").append(e.getKey()).append(':').append(e.getValue());
        return s.toString();
    }

    /** Lines 1268-1269 and 1275: NOTICE_PC_MS 120 s, NOTICE_PCS 10 (the alerts' limit too), ALERT_PC_MS a day. */
    @Test
    void theLimitsAreTheOldOnes() {
        assertEquals(120_000, KnownPcs.NOTICE_MS);
        assertEquals(24 * 3600_000L, KnownPcs.ALERT_MS);
        assertEquals(10, KnownPcs.MAX);
    }

    /** Lines 1279-1280 and 1312-1313: nobody asked - only the PC this phone last flew with (lastPcIp), if there is one. */
    @Test
    void withNoAskersOnlyThePcItLastFlewWithIsTold() {
        for (KnownPcs<String> pcs : List.of(KnownPcs.<String>forAlerts(), KnownPcs.<String>forNotice())) {
            assertTrue(pcs.toTell(null, 0, LINK, T0).isEmpty(), "before the first flight: nobody");
            assertEquals("pilot:51000", told(pcs.toTell("pilot", OWN, LINK, T0)));
        }
    }

    /** Lines 1283-1288: the alert's PCs newest first (sorted by when they last asked), each at the link port. */
    @Test
    void anAlertGoesToTheNewestAskersFirst() {
        KnownPcs<String> pcs = KnownPcs.forAlerts();
        pcs.asked("a", T0);
        pcs.asked("b", T0 + 1000);
        pcs.asked("c", T0 + 2000);
        assertEquals("c:6000 b:6000 a:6000", told(pcs.toTell(null, 0, LINK, T0 + 3000)));
        pcs.asked("a", T0 + 4000);
        assertEquals("a:6000 c:6000 b:6000", told(pcs.toTell(null, 0, LINK, T0 + 5000)), "asked again: the newest");
    }

    /** Lines 1280 and 1287 (putIfAbsent): the PC that set the mode first, at its own port - and only there. */
    @Test
    void thePcThatSetTheModeComesFirstAtItsOwnPortAndOnlyOnce() {
        KnownPcs<String> pcs = KnownPcs.forAlerts();
        pcs.asked("a", T0);
        pcs.asked("b", T0 + 1000);
        pcs.asked("c", T0 + 2000);
        assertEquals("b:51000 c:6000 a:6000", told(pcs.toTell("b", OWN, LINK, T0 + 3000)), "one of the askers");
        assertEquals("z:51000 c:6000 b:6000 a:6000", told(pcs.toTell("z", OWN, LINK, T0 + 3000)), "a PC that never asked the name");
        KnownPcs<String> notice = KnownPcs.forNotice();
        notice.asked("b", T0);
        assertEquals("b:51000", told(notice.toTell("b", OWN, LINK, T0 + 1000)), "the notice's list too (line 1318)");
    }

    /** Line 1282: knownPcs.values().removeIf(at -> now - at > ALERT_PC_MS) - a day to the ms is still told, a ms more is forgotten. */
    @Test
    void anAlertForgetsAPcADayAfterItLastAsked() {
        KnownPcs<String> pcs = KnownPcs.forAlerts();
        pcs.asked("old", T0);
        pcs.asked("new", T0 + 5000);
        assertEquals("new:6000 old:6000", told(pcs.toTell(null, 0, LINK, T0 + KnownPcs.ALERT_MS)));
        assertEquals("new:6000", told(pcs.toTell(null, 0, LINK, T0 + KnownPcs.ALERT_MS + 1)));
        assertEquals("new:6000", told(pcs.toTell(null, 0, LINK, T0 + KnownPcs.ALERT_MS + 5000)));
        assertEquals("", told(pcs.toTell(null, 0, LINK, T0 + KnownPcs.ALERT_MS + 5001)));
        pcs.asked("old", T0 + KnownPcs.ALERT_MS + 6000);
        assertEquals("old:6000", told(pcs.toTell(null, 0, LINK, T0 + KnownPcs.ALERT_MS + 7000)), "asks again: told again");
        assertEquals("old:51000", told(pcs.toTell("old", OWN, LINK, T0 + 3 * KnownPcs.ALERT_MS)), "the PC that set the mode never ages");
    }

    /** Line 1315: namedBy.values().removeIf(at -> now - at > NOTICE_PC_MS) - two minutes; the alerts' book keeps its own day. */
    @Test
    void theNoticeForgetsAPcTwoMinutesAfterItLastAsked() {
        KnownPcs<String> notice = KnownPcs.forNotice(), alerts = KnownPcs.forAlerts();
        notice.asked("a", T0);
        alerts.asked("a", T0); // answerName notes it in both (lines 227-228)
        assertEquals("a:6000", told(notice.toTell(null, 0, LINK, T0 + KnownPcs.NOTICE_MS)));
        assertEquals("", told(notice.toTell(null, 0, LINK, T0 + KnownPcs.NOTICE_MS + 1)));
        assertEquals("a:6000", told(alerts.toTell(null, 0, LINK, T0 + KnownPcs.NOTICE_MS + 1)), "still told an alert");
        notice.asked("a", T0 + 200_000);
        assertEquals("a:6000", told(notice.toTell(null, 0, LINK, T0 + 200_000 + KnownPcs.NOTICE_MS)));
    }

    /** Lines 1285-1287: at most NOTICE_PCS in all, the first one counted - the newest nine others, or the newest ten. */
    @Test
    void anAlertGoesToTenPcsAtMost() {
        KnownPcs<String> pcs = KnownPcs.forAlerts();
        for (int i = 1; i <= 15; i++) pcs.asked("pc" + i, T0 + i);
        assertEquals("pc15:6000 pc14:6000 pc13:6000 pc12:6000 pc11:6000 pc10:6000 pc9:6000 pc8:6000 pc7:6000 pc6:6000",
                told(pcs.toTell(null, 0, LINK, T0 + 100)));
        assertEquals("pilot:51000 pc15:6000 pc14:6000 pc13:6000 pc12:6000 pc11:6000 pc10:6000 pc9:6000 pc8:6000 pc7:6000",
                told(pcs.toTell("pilot", OWN, LINK, T0 + 100)));
        assertEquals("pc2:51000 pc15:6000 pc14:6000 pc13:6000 pc12:6000 pc11:6000 pc10:6000 pc9:6000 pc8:6000 pc7:6000",
                told(pcs.toTell("pc2", OWN, LINK, T0 + 100)), "an old asker that set the mode: first, and nine more");
        assertEquals("pc14:51000 pc15:6000 pc13:6000 pc12:6000 pc11:6000 pc10:6000 pc9:6000 pc8:6000 pc7:6000 pc6:6000",
                told(pcs.toTell("pc14", OWN, LINK, T0 + 100)), "one of the newest: not counted twice");
    }

    /** Lines 1316-1318: the notice's list in the map's own order (not sorted), ten at most with the first one. */
    @Test
    void theNoticeGoesToTenPcsAtMostInNoPromisedOrder() {
        KnownPcs<String> pcs = KnownPcs.forNotice();
        for (int i = 1; i <= 15; i++) pcs.asked("pc" + i, T0 + i);
        Map<String, Integer> to = pcs.toTell("pilot", OWN, LINK, T0 + 100);
        assertEquals(10, to.size());
        assertEquals("pilot", to.keySet().iterator().next(), "the PC that set the mode is always first");
        assertEquals(OWN, to.get("pilot"));
        for (Map.Entry<String, Integer> e : to.entrySet()) if (!e.getKey().equals("pilot")) assertEquals(LINK, e.getValue());
        assertEquals(10, pcs.toTell(null, 0, LINK, T0 + 100).size());
        KnownPcs<String> few = KnownPcs.forNotice();
        few.asked("a", T0);
        few.asked("b", T0 + 1);
        assertEquals(java.util.Set.of("a", "b"), few.toTell(null, 0, LINK, T0 + 100).keySet());
    }

    // ------------------------------------------------------------------ against the old code

    /** Udp.alertPcs lines 1279-1288 as they were (its map and the phone's reads as arguments). */
    private static LinkedHashMap<InetAddress, Integer> oldAlertPcs(Map<InetAddress, Long> knownPcs, InetAddress lastPcIp, int lastPcPort,
                                                                  int port, long now) {
        LinkedHashMap<InetAddress, Integer> to = new LinkedHashMap<>();
        if (lastPcIp != null) to.put(lastPcIp, lastPcPort);
        knownPcs.values().removeIf(at -> now - at > 24 * 3600_000L);
        List<Map.Entry<InetAddress, Long>> newest = new ArrayList<>(knownPcs.entrySet());
        newest.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<InetAddress, Long> e : newest) {
            if (to.size() >= 10) break;
            to.putIfAbsent(e.getKey(), port);
        }
        return to;
    }

    /** Udp.sendSleepNotice lines 1312-1319 as they were. */
    private static LinkedHashMap<InetAddress, Integer> oldNoticePcs(Map<InetAddress, Long> namedBy, InetAddress lastPcIp, int lastPcPort,
                                                                   int port, long now) {
        LinkedHashMap<InetAddress, Integer> to = new LinkedHashMap<>();
        if (lastPcIp != null) to.put(lastPcIp, lastPcPort);
        namedBy.values().removeIf(at -> now - at > 120_000);
        for (InetAddress pc : namedBy.keySet()) {
            if (to.size() >= 10) break;
            to.putIfAbsent(pc, port);
        }
        return to;
    }

    /**
     * Thirty PCs asking at random through days of made-up time, both books beside the old maps and loops: the same
     * PCs, ports and order every time (the notice's unsorted order too - the same kind of map with the same history).
     */
    @Test
    void anyRunAnswersAsTheOldCodeDid() throws Exception {
        InetAddress[] pc = new InetAddress[30];
        for (int i = 0; i < pc.length; i++) pc[i] = InetAddress.getByAddress(new byte[]{10, 0, (byte) (i / 7), (byte) (i * 37 + 1)});
        for (int seed = 1; seed <= 20; seed++) {
            Random random = new Random(seed);
            Map<InetAddress, Long> namedBy = new ConcurrentHashMap<>(), knownPcs = new ConcurrentHashMap<>();
            KnownPcs<InetAddress> notice = KnownPcs.forNotice(), alerts = KnownPcs.forAlerts();
            long now = T0;
            int compared = 0;
            for (int step = 0; step < 400; step++) {
                int what = random.nextInt(10);
                if (what < 6) { // a name query (answerName): noted in both
                    InetAddress from = pc[random.nextInt(seed % 2 == 0 ? pc.length : 8)];
                    namedBy.put(from, now);
                    knownPcs.put(from, now);
                    notice.asked(from, now);
                    alerts.asked(from, now);
                    now += random.nextInt(3); // often the same ms: the sort's ties
                } else if (what < 8) { // time passes: seconds, minutes, or most of a day
                    now += what == 6 ? random.nextInt(90_000) : random.nextInt(3) == 0 ? random.nextInt(20 * 3600_000) : random.nextInt(600_000);
                } else {
                    InetAddress lastPcIp = random.nextInt(4) == 0 ? null : pc[random.nextInt(pc.length)];
                    int lastPcPort = 50_000 + random.nextInt(1000);
                    String at = "seed " + seed + ", step " + step;
                    if (what == 8) {
                        assertEquals(new ArrayList<>(oldAlertPcs(knownPcs, lastPcIp, lastPcPort, LINK, now).entrySet()),
                                new ArrayList<>(alerts.toTell(lastPcIp, lastPcPort, LINK, now).entrySet()), "alert: " + at);
                    } else {
                        assertEquals(new ArrayList<>(oldNoticePcs(namedBy, lastPcIp, lastPcPort, LINK, now).entrySet()),
                                new ArrayList<>(notice.toTell(lastPcIp, lastPcPort, LINK, now).entrySet()), "notice: " + at);
                    }
                    compared++;
                }
            }
            assertTrue(compared > 40, "seed " + seed + " compared " + compared);
        }
    }
}
