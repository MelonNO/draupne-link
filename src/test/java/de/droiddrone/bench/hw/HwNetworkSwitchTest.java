package de.droiddrone.bench.hw;

import de.droiddrone.bench.PhoneNet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone's own network switched under the link ({@link HwNetworkSwitchScenario}) held before it meets the phone
 * and the board: its cells and their time, its row, how a stream's coming and going is read, Tailscale's lines as the
 * phone's log has them (and no address out of them), when its armed part is skipped. The phone's side - reading,
 * switching, putting back, the guard - is {@code PhoneRigNetworkTest}'s.
 */
class HwNetworkSwitchTest {
    // ------------------------------------------------------------------ the cells

    @Test
    void theCellsAreTheSwitchesAPhoneMeetsAndQuickIsThreeOfThem() {
        List<HwNetworkSwitch.Cell> all = HwNetworkSwitch.cells(false), quick = HwNetworkSwitch.cells(true);
        assertEquals(List.of("Wi-Fi off (to mobile data)", "Wi-Fi on (back to Wi-Fi)", "mobile data off on Wi-Fi", "airplane mode 10 s", "airplane mode 60 s",
                "Wi-Fi off and on within 2 s"), all.stream().map(HwNetworkSwitch.Cell::name).toList());
        assertEquals(List.of("Wi-Fi off (to mobile data)", "Wi-Fi on (back to Wi-Fi)", "airplane mode 10 s"), quick.stream().map(HwNetworkSwitch.Cell::name).toList());
        assertTrue(all.containsAll(quick));
        for (HwNetworkSwitch.Cell c : all) {
            for (HwNetworkSwitch.Order o : c.orders()) assertTrue(PhoneNet.ORDERS.contains(o.command()), c.name() + ": " + o.command() + " is one of the network's own orders");
            for (HwNetworkSwitch.Order o : c.before()) assertTrue(PhoneNet.ORDERS.contains(o.command()), c.name() + ": " + o.command());
            assertEquals(0, c.orders().get(0).atMs(), c.name() + ": the switch begins with its first order");
        }
        // each cell's switch: from where to where
        assertEquals(List.of(PhoneNet.WIFI_OFF), commands(HwNetworkSwitch.WIFI_OFF.orders()));
        assertEquals(PhoneNet.Net.MOBILE, HwNetworkSwitch.WIFI_OFF.to().net(PhoneNet.normal(true)));
        assertEquals(List.of(PhoneNet.WIFI_OFF), commands(HwNetworkSwitch.WIFI_ON.before()), "Wi-Fi on begins on mobile data: Wi-Fi off first");
        assertEquals(PhoneNet.Net.MOBILE, HwNetworkSwitch.WIFI_ON.from().net(PhoneNet.normal(true)));
        assertEquals(List.of(PhoneNet.WIFI_ON), commands(HwNetworkSwitch.WIFI_ON.orders()));
        assertEquals(List.of(PhoneNet.DATA_OFF), commands(HwNetworkSwitch.DATA_OFF.orders()));
        assertEquals(PhoneNet.Net.WIFI, HwNetworkSwitch.DATA_OFF.to().net(PhoneNet.normal(true)), "mobile data off: the phone stays on Wi-Fi");
        HwNetworkSwitch.Cell air = all.get(4);
        assertEquals(List.of(PhoneNet.AIRPLANE_ON, PhoneNet.AIRPLANE_OFF), commands(air.orders()));
        assertEquals(60_000, air.holdMs());
        assertEquals(10_000, all.get(3).holdMs());
        assertEquals(PhoneNet.Net.WIFI, air.to().net(PhoneNet.normal(true)), "airplane mode ends where the phone began: its normal network");
        assertEquals(PhoneNet.Net.MOBILE, air.to().net(PhoneNet.normal(false)));
        HwNetworkSwitch.Cell flap = all.get(5);
        assertEquals(List.of(PhoneNet.WIFI_OFF, PhoneNet.WIFI_ON), commands(flap.orders()));
        assertTrue(flap.holdMs() < HwNetworkSwitch.FLAP_MAX_MS, "Wi-Fi on again within 2 s, adb's own time left for: " + flap.holdMs());

        // the armed part's switch: the same orders, the hold no longer than the window allows
        assertEquals(List.of(new HwNetworkSwitch.Order(0, PhoneNet.AIRPLANE_ON), new HwNetworkSwitch.Order(HwLinkMatrix.ARMED_CUT_MAX_MS, PhoneNet.AIRPLANE_OFF)),
                HwNetworkSwitch.armedOrders(air));
        assertEquals(all.get(3).orders(), HwNetworkSwitch.armedOrders(all.get(3)));
        assertEquals(flap.orders(), HwNetworkSwitch.armedOrders(flap));
    }

    private static List<String> commands(List<HwNetworkSwitch.Order> orders) {
        return orders.stream().map(HwNetworkSwitch.Order::command).toList();
    }

    @Test
    void aPhoneWhoseWifiIsNormallyOffHasOnlyItsAirplaneCellsAndNoWifiIsSwitchedOn() {
        PhoneNet.Normal off = PhoneNet.normal(false), on = PhoneNet.normal(true);
        for (HwNetworkSwitch.Cell c : HwNetworkSwitch.cells(false)) {
            assertNull(HwNetworkSwitch.skip(c, on), c.name());
            boolean airplane = c.orders().get(0).command().equals(PhoneNet.AIRPLANE_ON);
            String why = HwNetworkSwitch.skip(c, off);
            assertEquals(airplane, why == null, c.name() + ": " + why);
            if (why != null) assertTrue(why.contains("phone.wifi=off"), why);
        }
    }

    @Test
    void theRunFitsItsTimeQuickItsFewMinutes() {
        long full = HwNetworkSwitch.estimateMs(HwNetworkSwitch.cells(false), true), linkOnly = HwNetworkSwitch.estimateMs(HwNetworkSwitch.cells(false), false);
        long quick = HwNetworkSwitch.estimateMs(HwNetworkSwitch.cells(true), false), quickArmed = HwNetworkSwitch.estimateMs(HwNetworkSwitch.cells(true), true);
        assertTrue(linkOnly >= 10 * 60_000L && linkOnly < full, "the whole run: " + linkOnly / 60_000 + " min, armed " + full / 60_000);
        assertTrue(full <= 45 * 60_000L, "well inside the scenario's 90 min, the armed part too: " + full / 60_000 + " min");
        assertTrue(quick <= 8 * 60_000L && quickArmed <= 12 * 60_000L, "quick: a few minutes - " + quick / 1000 + " s, armed " + quickArmed / 1000 + " s");
    }

    @Test
    void theArmedPartRunsOnlyOnTheFilesWordAndNeverWithTheBoardsOwnFailsafeLetRun() {
        String no = HwNetworkSwitch.armedSkip(false, null, 2000);
        assertTrue(no != null && no.contains("board.mayArm=true"), no);
        assertTrue(HwNetworkSwitch.armedSkip(false, "the keeper is silent", 0).contains("board.mayArm=true"), "the hardware file's word first");
        assertNull(HwNetworkSwitch.armedSkip(true, null, 2000));
        assertNull(HwNetworkSwitch.armedSkip(true, null, -1), "a keeper that knows no failsafe_delay refuses the window itself, and says why");
        assertTrue(HwNetworkSwitch.armedSkip(true, "the keeper is silent", 2000).contains("the keeper is silent"));
        String letRun = HwNetworkSwitch.armedSkip(true, null, 0);
        assertTrue(letRun != null && letRun.contains("board.letFailsafeRun") && letRun.contains("not known beforehand"), letRun);
        assertEquals(2, HwNetworkSwitch.SESSIONS_AT_MOST, "F-001: one new session when the link goes, one when it is back");
    }

    // ------------------------------------------------------------------ the row

    @Test
    void aRowHasEveryColumnFromTheStartAndItsTableItsOwnFile(@TempDir Path dir) throws Exception {
        assertEquals("hw-netswitch", HwNetworkSwitch.TABLE);
        assertEquals(HwNetworkSwitch.COLUMNS.size(), HwNetworkSwitch.COLUMNS.stream().distinct().count(), "no column twice");
        HwLinkMatrix.Row skipped = new HwLinkMatrix.Row(HwNetworkSwitch.COLUMNS).put("cell", "Wi-Fi off (to mobile data)").put("verdict", "skipped")
                .put("what", "the phone's Wi-Fi is normally off (phone.wifi=off)");
        assertEquals(HwNetworkSwitch.COLUMNS.size(), skipped.line().split("\t", -1).length, "a cell skipped or stopped half way writes a whole row");
        assertEquals("-", skipped.get("link back ms"));
        HwLinkMatrix.Row ran = new HwLinkMatrix.Row(HwNetworkSwitch.COLUMNS).put("cell", "airplane mode 10 s").put("phone before", "on Wi-Fi (validated); Wi-Fi on")
                .put("link gone ms", 11_250).put("link back ms", 2300).put("sound gone ms", "not measured").put("phone put back", "yes").put("verdict", "ok");
        for (String c : List.of("phone during", "phone after", "tailscale before", "tailscale after", "phone no Tailscale", "armed: re-arm", "sticks back ms", "sound back ms")) {
            ran.put(c, "x"); // every column the scenario writes is there
        }
        assertThrows(IllegalArgumentException.class, () -> ran.put("cut ms", 1000), "the bad-network box's columns are not this table's");
        Path file = HwLinkMatrix.append(dir, HwNetworkSwitch.TABLE, skipped);
        HwLinkMatrix.append(dir, HwNetworkSwitch.TABLE, ran);
        assertEquals(dir.resolve("hw-netswitch.tsv"), file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(String.join("\t", HwNetworkSwitch.COLUMNS), lines.get(0));
        assertTrue(lines.get(2).startsWith("airplane mode 10 s\ton Wi-Fi (validated); Wi-Fi on\t"), lines.get(2));
        HwLinkMatrix.Row cut = new HwLinkMatrix.Row(HwLinkMatrix.CUT_COLUMNS).put("cell", "cut 1 s both ways");
        assertThrows(IllegalStateException.class, () -> HwLinkMatrix.append(dir, HwNetworkSwitch.TABLE, cut), "never two tables in one file");
    }

    // ------------------------------------------------------------------ a stream's coming and going

    @Test
    void aStreamThatNeverWentIsSaidSoAndOneThatWentHasItsTimes() {
        HwNetworkSwitch.Outage steady = new HwNetworkSwitch.Outage(300);
        for (long t = 0; t <= 5000; t += 100) steady.look(t, true);
        HwNetworkSwitch.Away never = steady.result(5000);
        assertFalse(never.wentAway() || never.notBack());
        assertEquals("0", never.goneCell());
        assertEquals("-", never.backCell(1000));
        assertEquals("the sticks: never away", never.words("the sticks", 1000));

        // the switch's last order at 1000 ms; gone from 1200 to 4000, a blip of 100 ms at 4500 (under 300: jitter)
        HwNetworkSwitch.Outage sticks = new HwNetworkSwitch.Outage(300);
        for (long t = 0; t <= 8000; t += 100) sticks.look(t, !(t >= 1200 && t < 4000) && t != 4500);
        HwNetworkSwitch.Away a = sticks.result(8000);
        assertTrue(a.wentAway() && !a.notBack());
        assertEquals(1200, a.firstGoneAt());
        assertEquals(2800, a.longestMs());
        assertEquals(4000, a.backAt(), "the blip after it is no going");
        assertEquals(1, a.times());
        assertEquals("2800", a.goneCell());
        assertEquals("3000", a.backCell(1000), "back 3 s after the switch's last order");
        assertEquals("the sticks: away 2800 ms at most, back 3000 ms after the switch's last order", a.words("the sticks", 1000));

        // gone twice: the longest time away, back for good after the second
        HwNetworkSwitch.Outage link = new HwNetworkSwitch.Outage(0);
        for (long t = 0; t <= 20_000; t += 50) link.look(t, !(t >= 500 && t < 1500) && !(t >= 3000 && t < 9000));
        HwNetworkSwitch.Away twice = link.result(20_000);
        assertEquals(500, twice.firstGoneAt());
        assertEquals(6000, twice.longestMs());
        assertEquals(9000, twice.backAt());
        assertEquals(2, twice.times());
        assertTrue(twice.words("LINK READY", 2000).contains("(2 times)"), twice.words("LINK READY", 2000));

        // not back at the end of the watch
        HwNetworkSwitch.Outage picture = new HwNetworkSwitch.Outage(0);
        for (long t = 0; t <= 60_000; t += 100) picture.look(t, t < 700);
        HwNetworkSwitch.Away gone = picture.result(60_000);
        assertTrue(gone.wentAway() && gone.notBack());
        assertEquals(700, gone.firstGoneAt());
        assertEquals(59_300, gone.longestMs());
        assertEquals("not back", gone.goneCell());
        assertEquals("none", gone.backCell(1000));
        assertTrue(gone.words("the picture", 1000).startsWith("the picture: NOT BACK"), gone.words("the picture", 1000));
        assertTrue(picture.away(60_000) && picture.missing());

        // gone since a moment before the look that saw it (the sound's last frame): the time away counts from there
        HwNetworkSwitch.Outage sound = new HwNetworkSwitch.Outage(600);
        sound.look(1000, true, 1000);
        sound.look(1700, false, 1000);
        assertTrue(sound.away(1700), "600 ms without a frame");
        sound.look(2500, true, 2500);
        HwNetworkSwitch.Away s = sound.result(3000);
        assertEquals(1000, s.firstGoneAt());
        assertEquals(1500, s.longestMs());
        // a short gap at the end of the watch is no going
        HwNetworkSwitch.Outage blip = new HwNetworkSwitch.Outage(300);
        blip.look(0, true);
        blip.look(100, false);
        assertTrue(blip.missing() && !blip.away(200));
        assertFalse(blip.result(200).wentAway());
    }

    // ------------------------------------------------------------------ Tailscale, as far as the phone shows it

    /** Lines as {@code logcat -v time} prints them: Tailscale's Go side, the phone app's own, others. Addresses made up (documentation ranges). */
    private static final String LOG = String.join("\n",
            "11-23 16:03:20.004 I/DDLog   ( 8123): Link socket: on Tailscale, 100.64.0.10:6286",
            "11-23 16:03:20.500 I/GoLog   ( 9001): magicsock: home is now derp-4 (fra)",
            "11-23 16:03:20.900 I/GoLog   ( 9001): magicsock: disco: node [pIyGZ] d:9c6b4e0b5d41f2a7 now using 192.0.2.44:41641 mtu=1360 tx=1a2b3c",
            "11-23 16:03:21.100 D/WifiStateMachine( 1200): something else entirely",
            "11-23 16:03:24.000 I/GoLog   ( 9001): netmon: LinkChange: major, rebinding. New state: interfaces.State{defaultRoute=rmnet_data1 ifs={rmnet_data1:[198.51.100.7/32 2001:db8::7/64]}}",
            "11-23 16:03:24.100 I/GoLog   ( 9001): magicsock: ReSTUN(\"link-change-major\")",
            "11-23 16:03:26.400 I/GoLog   ( 9001): magicsock: disco: node [pIyGZ] d:9c6b4e0b5d41f2a7 now using [2001:db8::44]:41641 mtu=1360",
            "11-23 16:03:27.000 I/DDLog   ( 8123): Link socket: no Tailscale - the phone answers nothing (was on Tailscale, 100.64.0.10:6286)");

    @Test
    void tailscalesPathIsReadFromItsOwnLinesAndNoAddressComesOutOfThem() {
        List<String> lines = HwNetworkSwitch.tailscaleLines(LOG);
        assertEquals(5, lines.size(), "Tailscale's own lines - not the phone app's, which mention Tailscale too: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.contains("DDLog") || l.contains("WifiStateMachine")), lines.toString());
        List<String> before = lines.stream().filter(l -> !HwNetworkSwitch.atOrAfter(l, "11-23 16:03:23.000")).toList();
        List<String> after = lines.stream().filter(l -> HwNetworkSwitch.atOrAfter(l, "11-23 16:03:23.000")).toList();
        assertEquals(2, before.size());
        assertEquals(3, after.size());
        assertEquals("direct since 16:03:20.900; home relay derp-4 (fra)", HwNetworkSwitch.tailscalePath(before));
        String moved = HwNetworkSwitch.tailscalePath(after);
        assertEquals("direct since 16:03:26.400; 2 network change(s) seen", moved);
        assertEquals("relayed (derp-4) since 16:04:00.000",
                HwNetworkSwitch.tailscalePath(List.of("11-23 16:04:00.000 I/GoLog ( 9001): magicsock: disco: node [pIyGZ] now using derp-4 mtu=1360")));
        assertEquals("not shown in the phone's log (0 line(s) of Tailscale's)", HwNetworkSwitch.tailscalePath(List.of()));
        assertEquals("not shown in the phone's log (1 line(s) of Tailscale's)",
                HwNetworkSwitch.tailscalePath(List.of("11-23 16:04:00.000 I/GoLog ( 9001): wgengine: Reconfig done")));

        // whatever goes into the table and the cell's log names no phone, PC or network
        for (String l : lines) {
            String m = HwNetworkSwitch.masked(l);
            for (String address : List.of("192.0.2.44", "198.51.100.7", "2001:db8::7", "2001:db8::44", "41641")) assertFalse(m.contains(address), m);
            assertTrue(m.startsWith(l.substring(0, 18)), "the time stays: " + m);
        }
        assertEquals("now using <address> mtu=1360", HwNetworkSwitch.masked("now using 192.0.2.44:41641 mtu=1360"));
        assertEquals("on Tailscale, <address>", HwNetworkSwitch.masked("on Tailscale, 100.64.0.10:6286"));
        assertEquals("at <address>.", HwNetworkSwitch.masked("at 100.64.0.10."));
        assertEquals("v6 <address> and <address>", HwNetworkSwitch.masked("v6 2001:db8:a1e0::1 and [2001:db8::44]:41641"));
        assertEquals("11-23 16:03:21.123 build 2.3.0 at 16:03:21", HwNetworkSwitch.masked("11-23 16:03:21.123 build 2.3.0 at 16:03:21"), "times and versions stay");

        assertEquals("16:03:20.900", HwNetworkSwitch.time(lines.get(1)));
        assertEquals("?", HwNetworkSwitch.time("--------- beginning of main"));
        assertFalse(HwNetworkSwitch.atOrAfter("--------- beginning of main", "11-23 16:03:23.000"));
        assertTrue(HwNetworkSwitch.atOrAfter("11-23 16:03:23.000 I/GoLog: x", "11-23 16:03:23.000"));
        assertFalse(HwNetworkSwitch.atOrAfter("11-23 16:03:22.999 I/GoLog: x", "11-23 16:03:23.000"));
    }
}
