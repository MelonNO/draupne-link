package de.droiddrone.bench;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real phone's own network as the bench reads, switches and puts it back ({@link PhoneNet}, for the network-switch
 * scenario), proved without a phone: Android's dumps as they read, and a made-up phone in adb's door whose switches
 * change as a phone's do - airplane mode taking the Wi-Fi with it and giving it back, a Wi-Fi that is validated a few
 * reads after it is switched on, one that does not come back, mobile data that cannot be read. Everything goes
 * through the rig and its guard, as on the real phone.
 */
class PhoneRigNetworkTest {
    static final String CABLE = PhoneRigGuardTest.CABLE, NETWORK = PhoneRigGuardTest.NETWORK, BENCH = BenchControl.PACKAGE;

    // ------------------------------------------------------------------ what Android says

    /** {@code dumpsys connectivity} as Android 12 and later print it, the parts the bench reads (made-up addresses). */
    static String dump(boolean wifi, boolean wifiValidated, boolean mobile, boolean tailscale, boolean defaultLine) {
        StringBuilder s = new StringBuilder("NetworkProviders for:\n  NetworkProviderInfo{name=WifiNetworkProvider}\n\n");
        int def = wifi ? 105 : mobile ? 106 : 0;
        if (defaultLine) s.append("Active default network: ").append(def == 0 ? "none" : Integer.toString(def)).append("\n");
        s.append("Current network preference: null\n\nCurrent Networks:\n");
        if (wifi) {
            s.append("  NetworkAgentInfo{network{105}  handle{454113144845}  ni{WIFI CONNECTED extra: }  Score(Policies : IS_UNMETERED ; KeepConnected : 0)  ")
                    .append("lp{{InterfaceName: wlan0 LinkAddresses: [ 192.0.2.23/24 ]}}  nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN")
                    .append(wifiValidated ? "&VALIDATED" : "").append("&NOT_ROAMING&FOREGROUND&NOT_CONGESTED&NOT_SUSPENDED LinkUpBandwidth>=25000Kbps SignalStrength: -52]}}\n")
                    .append("    Requests: REQUEST:4 LISTEN:57 BACKGROUND_REQUEST:0 total:61\n");
        }
        if (mobile) {
            s.append("  NetworkAgentInfo{network{106}  handle{458408112141}  ni{MOBILE[LTE] CONNECTED extra: internet}  lp{{InterfaceName: rmnet_data1}}  ")
                    .append("nc{[ Transports: CELLULAR Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING&FOREGROUND LinkUpBandwidth>=15000Kbps]}}\n");
        }
        if (tailscale && (wifi || mobile)) {
            s.append("  NetworkAgentInfo{network{110}  handle{472440426509}  ni{VPN CONNECTED extra: VPN:com.tailscale.ipn}  lp{{InterfaceName: tun0}}  nc{[ Transports: ")
                    .append(wifi ? "WIFI" : "CELLULAR").append("|VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&VALIDATED&NOT_ROAMING&FOREGROUND OwnerUid: 10234]}}\n");
        }
        return s.append("\nStatus for known UIDs:\n  UID=10234 state=2\n\nNetwork Requests:\n  NetworkRequest [ REQUEST id=1, [ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN ] ]\n")
                .toString();
    }

    @Test
    void whatThePhoneIsOnIsReadFromAndroidsOwnWords() {
        PhoneNet.State home = PhoneNet.parse("disabled", "1", "1", dump(true, true, true, true, true));
        assertEquals(PhoneNet.Net.WIFI, home.on());
        assertTrue(home.validated() && home.tailscale() && home.vpn());
        assertEquals(Boolean.FALSE, home.airplane());
        assertEquals(Boolean.TRUE, home.wifi());
        assertEquals(Boolean.TRUE, home.data());
        assertEquals(List.of("Wi-Fi validated", "mobile data validated", "VPN Tailscale"), home.networks());
        assertEquals("on Wi-Fi (validated); Wi-Fi on, mobile data on, airplane mode off; Tailscale up [Wi-Fi validated, mobile data validated, VPN Tailscale]", home.words());
        assertFalse(home.words().contains("192.0.2"), "no address in what is said");

        // Wi-Fi off: mobile data is the default; the VPN's transports name its underlying network and it is never the default
        PhoneNet.State mobile = PhoneNet.parse("disabled", "0", "1", dump(false, false, true, true, true));
        assertEquals(PhoneNet.Net.MOBILE, mobile.on());
        assertTrue(mobile.validated());
        // a Wi-Fi not validated yet (just back): on it, not validated
        PhoneNet.State fresh = PhoneNet.parse("disabled", "1", "1", dump(true, false, true, true, true));
        assertEquals(PhoneNet.Net.WIFI, fresh.on());
        assertFalse(fresh.validated());
        assertTrue(fresh.words().contains("(NOT validated)"), fresh.words());
        // airplane mode: no network at all; Wi-Fi off by airplane mode (3)
        PhoneNet.State air = PhoneNet.parse("enabled", "3", "1", dump(false, false, false, true, true));
        assertEquals(PhoneNet.Net.NONE, air.on());
        assertEquals(Boolean.TRUE, air.airplane());
        assertEquals(Boolean.FALSE, air.wifi());
        assertFalse(air.tailscale());
        // no "Active default network" line: Wi-Fi before mobile data, as Android has it - never the VPN, though it names WIFI too
        assertEquals(PhoneNet.Net.WIFI, PhoneNet.parse("disabled", "1", "1", dump(true, true, true, true, false)).on());
        assertEquals(PhoneNet.Net.MOBILE, PhoneNet.parse("disabled", "0", "1", dump(false, false, true, true, false)).on());
        // Android before 12: NetworkAgentInfo [WIFI () - 100]
        String old = "Active default network: 100\n\nCurrent Networks:\n  NetworkAgentInfo [WIFI () - 100] {  nc{[ Transports: WIFI Capabilities: INTERNET&NOT_VPN&VALIDATED]} }\n"
                + "  NetworkAgentInfo [MOBILE (LTE) - 101] {  nc{[ Transports: CELLULAR Capabilities: INTERNET&NOT_VPN&VALIDATED]} }\n\nNetwork Requests:\n";
        PhoneNet.State older = PhoneNet.parse("disabled", "1", "1", old);
        assertEquals(PhoneNet.Net.WIFI, older.on());
        assertTrue(older.validated());
        assertEquals(List.of("Wi-Fi validated", "mobile data validated"), older.networks());
        // what cannot be read is not known - never guessed
        PhoneNet.State none = PhoneNet.parse("", "", "null", "");
        assertNull(none.airplane());
        assertNull(none.wifi());
        assertNull(none.data());
        assertEquals(PhoneNet.Net.NOT_KNOWN, none.on());
        assertEquals(Boolean.TRUE, PhoneNet.parse("disabled", "2", "0", "").wifi(), "2: on in airplane mode");
        assertEquals(Boolean.FALSE, PhoneNet.parse("disabled", "2", "0", "").data());
        assertNull(PhoneNet.parse("Airplane mode state unknown", "7", "x", "").airplane());
        // a setting that says off while that network is up and carries data (two SIMs: mobile data kept per SIM) is not
        // known - never taken for off: the start is not refused on it, and putting back sends its order once
        PhoneNet.State twoSims = PhoneNet.parse("disabled", "0", "0", dump(true, true, true, true, true));
        assertNull(twoSims.wifi());
        assertNull(twoSims.data());
        assertNull(PhoneNet.normal(true).wrong(twoSims));
        assertTrue(PhoneNet.normal(true).settled(twoSims));
        assertEquals(List.of(PhoneNet.WIFI_ON, PhoneNet.DATA_ON), PhoneNet.normal(true).blind(twoSims));
    }

    @Test
    void thePhoneAsItNormallyIsAndWhatPutsItBack() {
        PhoneNet.Normal wifi = PhoneNet.normal(true), noWifi = PhoneNet.normal(false);
        PhoneNet.State home = PhoneNet.parse("disabled", "1", "1", dump(true, true, true, true, true));
        assertNull(wifi.wrong(home));
        assertTrue(wifi.settled(home));
        assertEquals(List.of(), wifi.orders(home));
        assertEquals(List.of(), wifi.blind(home));
        assertEquals("Wi-Fi on, mobile data on, airplane mode off", wifi.words());

        // a phone whose Wi-Fi is normally off, found with it on: not as found - and putting back switches it off (over the cable only)
        assertEquals("Wi-Fi is on (normally off)", noWifi.wrong(home));
        assertEquals(List.of(PhoneNet.WIFI_OFF), noWifi.orders(home));
        PhoneNet.State mobile = PhoneNet.parse("disabled", "0", "1", dump(false, false, true, true, true));
        assertTrue(noWifi.settled(mobile));
        assertEquals("Wi-Fi is off (normally on)", wifi.wrong(mobile));
        assertEquals(List.of(PhoneNet.WIFI_ON), wifi.orders(mobile));

        // airplane mode first and alone: Wi-Fi is airplane mode's while it is on
        PhoneNet.State air = PhoneNet.parse("enabled", "3", "0", "");
        assertEquals("airplane mode is on, Wi-Fi is off (normally on), mobile data is off", wifi.wrong(air));
        assertEquals(List.of(PhoneNet.AIRPLANE_OFF), wifi.orders(air));
        PhoneNet.State dataOff = PhoneNet.parse("disabled", "1", "0", dump(true, true, false, true, true));
        assertEquals(List.of(PhoneNet.DATA_ON), wifi.orders(dataOff));
        assertFalse(wifi.settled(dataOff));
        // switches right but the phone on another network, or not validated: not settled
        assertFalse(wifi.settled(PhoneNet.parse("disabled", "1", "1", dump(true, false, true, true, true))));
        assertFalse(wifi.settled(PhoneNet.parse("disabled", "1", "1", dump(false, false, true, true, true))));

        // what cannot be read is sent its order once, to be sure - but a Wi-Fi normally off is never switched off on a guess
        PhoneNet.State blind = PhoneNet.parse("", "", "null", dump(true, true, true, true, true));
        assertNull(wifi.wrong(blind));
        assertEquals(List.of(), wifi.orders(blind));
        assertEquals(List.of(PhoneNet.AIRPLANE_OFF, PhoneNet.WIFI_ON, PhoneNet.DATA_ON), wifi.blind(blind));
        assertEquals(List.of(PhoneNet.AIRPLANE_OFF, PhoneNet.DATA_ON), noWifi.blind(blind));

        // the note on the phone: two values, each its normal
        assertEquals("settings put global draupne_bench_net_was wifi-on", PhoneNet.noteWrite(true));
        assertEquals("settings put global draupne_bench_net_was wifi-off", PhoneNet.noteWrite(false));
        assertEquals(wifi, PhoneNet.Normal.ofNote("wifi-on\n"));
        assertEquals(noWifi, PhoneNet.Normal.ofNote("wifi-off"));
        for (String no : List.of("null", "", "1", "wifi", "WIFI-ON")) assertNull(PhoneNet.Normal.ofNote(no), no);
        assertNull(PhoneNet.Normal.ofNote(null));
    }

    // ------------------------------------------------------------------ a made-up phone in adb's door

    /**
     * A phone whose network changes as a phone's does: airplane mode switches the Wi-Fi off and gives it back as it
     * was; Wi-Fi switched on is validated only {@link #wifiLag} reads of its networks later; {@link #wifiStuck}: it does
     * not come on; {@link #dataUnreadable}: its mobile data setting reads "null" (kept per SIM only). Every command it is
     * handed is written down.
     */
    static final class NetPhone implements PhoneAdb {
        boolean airplane, wifi = true, data = true, tailscale = true, wifiStuck, dataUnreadable, gone;
        int wifiLag, battery = 80;
        String note;
        private boolean wifiBeforeAirplane;
        private int lagLeft;
        final List<String> sent = new ArrayList<>();

        @Override
        public synchronized byte[] run(long timeoutS, List<String> args) {
            String c = String.join(" ", args);
            sent.add(c);
            if (gone) throw new IllegalStateException("adb " + c + ": no devices/emulators found");
            return answer(c).getBytes(StandardCharsets.UTF_8);
        }

        private void wifiOn() {
            if (wifiStuck || wifi) return;
            wifi = true;
            lagLeft = wifiLag;
        }

        private String answer(String c) {
            if (c.equals("get-state")) return "device";
            if (c.equals("shell dumpsys battery")) return RecordingAdb.battery(battery, 300, 2, true);
            if (!c.startsWith("shell ")) return "";
            String s = c.substring(6);
            if (s.startsWith("settings put global " + PhoneNet.NOTE + " ")) {
                note = s.substring(("settings put global " + PhoneNet.NOTE + " ").length());
                return "";
            }
            switch (s) {
                case PhoneNet.AIRPLANE:
                    return airplane ? "enabled" : "disabled";
                case PhoneNet.AIRPLANE_ON:
                    if (!airplane) {
                        airplane = true;
                        wifiBeforeAirplane = wifi;
                        wifi = false;
                    }
                    return "";
                case PhoneNet.AIRPLANE_OFF:
                    if (airplane) {
                        airplane = false;
                        if (wifiBeforeAirplane) wifiOn();
                    }
                    return "";
                case PhoneNet.WIFI_ON:
                    wifiOn();
                    return "";
                case PhoneNet.WIFI_OFF:
                    wifi = false;
                    return "";
                case PhoneNet.DATA_ON:
                    data = true;
                    return "";
                case PhoneNet.DATA_OFF:
                    data = false;
                    return "";
                case PhoneNet.WIFI_SETTING:
                    return airplane && wifiBeforeAirplane ? "3" : wifi ? "1" : "0";
                case PhoneNet.DATA_SETTING:
                    return dataUnreadable ? "null" : data ? "1" : "0";
                case PhoneNet.NETWORKS: {
                    boolean validated = lagLeft == 0;
                    if (lagLeft > 0) lagLeft--;
                    return dump(wifi && !airplane, validated, data && !airplane, tailscale, true);
                }
                case PhoneNet.NOTE_READ:
                    return note == null ? "null" : note;
                case PhoneNet.NOTE_DELETE:
                    note = null;
                    return "";
                default:
                    return s.startsWith("settings get global") ? "null" : "";
            }
        }

        @Override
        public Running follow(List<String> args, Path into) {
            return new Running() {
                @Override
                public boolean alive() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public PhoneAdb.Running startEmulator(List<String> args, Path log) {
            throw new IllegalStateException("a real phone has no emulator");
        }

        synchronized List<String> orders() {
            return sent.stream().filter(c -> PhoneNet.ORDERS.stream().anyMatch(o -> c.equals("shell " + o))).toList();
        }
    }

    private final long[] now = {1_000_000};

    private PhoneRig rig(String serial, PhoneAdb adb) {
        return new PhoneRig(null, PhoneRigGuardTest.phone(serial, BENCH, false), adb, ms -> now[0] += ms, () -> now[0], null);
    }

    private PhoneNet.Back putBack(PhoneRig rig, boolean wifi, List<String> said) {
        return PhoneNet.putBack(rig::network, PhoneNet.normal(wifi), ms -> now[0] += ms, () -> now[0], 60_000, said::add);
    }

    @Test
    void airplaneModeLeftOnIsPutBackAndTheWifiComesWithIt() {
        NetPhone p = new NetPhone();
        PhoneRig rig = rig(CABLE, p);
        rig.network(PhoneNet.AIRPLANE_ON);
        p.wifiLag = 2;
        List<String> said = new ArrayList<>();
        PhoneNet.Back back = putBack(rig, true, said);
        assertTrue(back.switches() && back.settled(), back.words());
        assertEquals(List.of(PhoneNet.AIRPLANE_OFF), back.sent(), "airplane mode off, and nothing else: Android gives the Wi-Fi back by itself");
        assertFalse(p.airplane);
        assertTrue(p.wifi && p.data);
        assertEquals(PhoneNet.Net.WIFI, back.state().on());
        assertTrue(said.get(0).startsWith("The phone's network put back: " + PhoneNet.AIRPLANE_OFF), said.toString());
        assertTrue(back.words().startsWith("back as found (sent: " + PhoneNet.AIRPLANE_OFF + ")"), back.words());
    }

    @Test
    void wifiAndMobileDataOffArePutBackAndTheWaitIsForAValidatedWifi() {
        NetPhone p = new NetPhone();
        PhoneRig rig = rig(CABLE, p);
        rig.network(PhoneNet.WIFI_OFF);
        rig.network(PhoneNet.DATA_OFF);
        p.wifiLag = 4; // validated four looks after it is on
        long t0 = now[0];
        PhoneNet.Back back = putBack(rig, true, new ArrayList<>());
        assertTrue(back.settled(), back.words());
        assertEquals(List.of(PhoneNet.WIFI_ON, PhoneNet.DATA_ON), back.sent());
        assertTrue(now[0] - t0 >= 4 * 2000, "waited for the Wi-Fi to be validated: " + (now[0] - t0) + " ms");
        // nothing to do on a phone that is as found: four reads, no order
        int before = p.sent.size();
        PhoneNet.Back again = putBack(rig, true, new ArrayList<>());
        assertTrue(again.settled());
        assertEquals(List.of(), again.sent());
        assertEquals(List.of("shell " + PhoneNet.AIRPLANE, "shell " + PhoneNet.WIFI_SETTING, "shell " + PhoneNet.DATA_SETTING, "shell " + PhoneNet.NETWORKS),
                p.sent.subList(before, p.sent.size()));
        assertTrue(again.words().contains("nothing had to be sent"), again.words());
    }

    @Test
    void aWifiThatDoesNotComeBackIsSaidNotPassedOver() {
        NetPhone p = new NetPhone();
        PhoneRig rig = rig(CABLE, p);
        rig.network(PhoneNet.WIFI_OFF);
        p.wifiStuck = true;
        PhoneNet.Back back = putBack(rig, true, new ArrayList<>());
        assertFalse(back.switches() || back.settled(), back.words());
        assertTrue(back.words().startsWith("NOT back as found"), back.words());
        assertTrue(back.sent().stream().allMatch(PhoneNet.WIFI_ON::equals) && back.sent().size() > 1, "asked again and again: " + back.sent());
        // switches back, but the phone not on its network (out of its Wi-Fi's reach): said as that
        NetPhone far = new NetPhone();
        PhoneRig farRig = rig(CABLE, far);
        farRig.network(PhoneNet.WIFI_OFF);
        far.wifiLag = 1_000_000;
        PhoneNet.Back notYet = putBack(farRig, true, new ArrayList<>());
        assertTrue(notYet.switches() && !notYet.settled(), notYet.words());
        assertTrue(notYet.words().startsWith("its switches back as found, but it is not on its network yet"), notYet.words());
    }

    @Test
    void whatCannotBeReadIsSentItsOrderOnceAndAWifiNormallyOffIsLeftOff() {
        NetPhone p = new NetPhone();
        p.dataUnreadable = true;
        PhoneRig rig = rig(CABLE, p);
        rig.network(PhoneNet.DATA_OFF);
        PhoneNet.Back back = putBack(rig, true, new ArrayList<>());
        assertTrue(back.settled(), back.words());
        assertEquals(List.of(PhoneNet.DATA_ON), back.sent(), "once: whether it took cannot be read");
        assertTrue(p.data);

        // a phone that normally flies on mobile data: its Wi-Fi off stays off, airplane mode goes
        NetPhone mobile = new NetPhone();
        mobile.wifi = false;
        PhoneRig mobileRig = rig(CABLE, mobile);
        mobileRig.network(PhoneNet.AIRPLANE_ON);
        PhoneNet.Back off = putBack(mobileRig, false, new ArrayList<>());
        assertTrue(off.settled(), off.words());
        assertEquals(List.of(PhoneNet.AIRPLANE_OFF), off.sent());
        assertFalse(mobile.wifi);
        assertEquals(PhoneNet.Net.MOBILE, off.state().on());
    }

    @Test
    void theNetworksOwnOrdersNeverWaitForTheBatteryButStillPassTheGuard() {
        NetPhone p = new NetPhone();
        PhoneRig rig = rig(CABLE, p);
        now[0] += 10 * 60_000; // long past the minute after which a command looks at the battery
        rig.network(PhoneNet.AIRPLANE_ON);
        PhoneNet.read(rig::network);
        assertFalse(p.sent.contains("shell dumpsys battery"), "the battery watch never holds a switch up: " + p.sent);
        rig.shell("getprop sys.boot_completed");
        assertTrue(p.sent.contains("shell dumpsys battery"), "an ordinary command still looks at it");

        // a run stopped for the battery's sake: what puts the network back still goes, nothing switches it off
        NetPhone low = new NetPhone();
        low.battery = 5;
        PhoneRig lowRig = rig(CABLE, low);
        lowRig.network(PhoneNet.WIFI_OFF);
        assertThrows(PhoneRig.Stop.class, lowRig::checkBattery);
        lowRig.network(PhoneNet.WIFI_ON);
        lowRig.network(PhoneNet.AIRPLANE_OFF);
        lowRig.network(PhoneNet.NETWORKS);
        assertTrue(low.wifi);
        assertThrows(PhoneRig.Stop.class, () -> lowRig.network(PhoneNet.WIFI_OFF));
        assertThrows(PhoneRig.Stop.class, () -> lowRig.shell("getprop sys.boot_completed"));
        assertFalse(low.sent.stream().filter(c -> c.equals("shell " + PhoneNet.WIFI_OFF)).count() > 1, low.sent.toString());

        // and the guard as ever: over the network nothing is switched off, and what it refuses is kept
        NetPhone wireless = new NetPhone();
        PhoneRig overTcp = rig(NETWORK, wireless);
        for (String off : List.of(PhoneNet.AIRPLANE_ON, PhoneNet.WIFI_OFF, PhoneNet.DATA_OFF)) {
            PhoneRig.Refused r = assertThrows(PhoneRig.Refused.class, () -> overTcp.network(off));
            assertTrue(r.getMessage().contains("adb itself comes over the network"), r.getMessage());
        }
        assertEquals(List.of(), wireless.sent);
        assertEquals(3, overTcp.refusals().size());
        for (String ok : List.of(PhoneNet.AIRPLANE_OFF, PhoneNet.WIFI_ON, PhoneNet.DATA_ON, PhoneNet.AIRPLANE, PhoneNet.NETWORKS, PhoneNet.noteWrite(true), PhoneNet.NOTE_DELETE)) {
            overTcp.network(ok);
        }
        assertEquals(7, wireless.sent.size());
        assertThrows(PhoneRig.Refused.class, () -> overTcp.network("settings put global wifi_on 0"));
    }

    @Test
    void whatTheNetworkOrdersSwitchedOffTheRigsCloseSwitchesOnAgain() {
        NetPhone p = new NetPhone();
        PhoneRig rig = rig(CABLE, p);
        rig.network(PhoneNet.AIRPLANE_ON);
        rig.network(PhoneNet.DATA_OFF);
        rig.data(true); // put back by the scenario itself: not again
        int before = p.sent.size();
        rig.close();
        List<String> closing = p.sent.subList(before, p.sent.size());
        assertTrue(closing.contains("shell " + PhoneNet.AIRPLANE_OFF), closing.toString());
        assertFalse(closing.contains("shell " + PhoneNet.DATA_ON), closing.toString());
        assertFalse(p.airplane);
        assertTrue(p.wifi && p.data);
    }

    @Test
    void everyCommandOfTheNetworkIsOnTheGuardsListAndNothingBeside() {
        PhoneRig.Target cable = PhoneRigGuardTest.phone(CABLE, BENCH, false), wireless = PhoneRigGuardTest.phone(NETWORK, BENCH, false);
        List<String> all = new ArrayList<>(PhoneNet.ORDERS);
        all.addAll(PhoneNet.READS);
        all.addAll(List.of(PhoneNet.noteWrite(true), PhoneNet.noteWrite(false), PhoneNet.NOTE_DELETE, PhoneNet.logSince("11-23 16:03:21.000")));
        for (String c : all) {
            PhoneGuard.Verdict v = PhoneGuard.judge(cable, List.of("shell", c), x -> null);
            assertTrue(v.allowed(), c + ": " + v.refusal());
        }
        for (String c : PhoneNet.READS) assertEquals(PhoneGuard.Kind.READ, PhoneGuard.judge(cable, List.of("shell", c), x -> null).kind(), c);
        assertEquals(PhoneGuard.Kind.READ, PhoneGuard.judge(cable, List.of("shell", PhoneNet.logSince("11-23 16:03:21.000")), x -> null).kind());
        assertEquals(PhoneGuard.Kind.PHONE, PhoneGuard.judge(cable, List.of("shell", PhoneNet.noteWrite(true)), x -> null).kind());
        // the three that switch something off: over the cable only, and each one the rig's close puts back
        for (String off : List.of(PhoneNet.AIRPLANE_ON, PhoneNet.WIFI_OFF, PhoneNet.DATA_OFF)) {
            assertEquals(1, PhoneGuard.judge(cable, List.of("shell", off), x -> null).sets().size(), off);
            assertFalse(PhoneGuard.judge(wireless, List.of("shell", off), x -> null).allowed(), off);
        }
        // the note takes its two values and nothing else; no other setting of the phone's network is written
        for (String no : List.of("settings put global " + PhoneNet.NOTE + " 1", "settings put global " + PhoneNet.NOTE + " wifi", "settings put global " + PhoneNet.NOTE + " wifi-on extra",
                "settings put system " + PhoneNet.NOTE + " wifi-on", "settings delete secure " + PhoneNet.NOTE, "settings put global wifi_on 0", "settings put global mobile_data 0",
                "settings put global airplane_mode_on 1", "settings delete global wifi_on", "cmd wifi set-wifi-enabled disabled", "cmd phone data disable",
                "svc wifi disable now", "svc data", "cmd connectivity airplane-mode on")) {
            assertFalse(PhoneGuard.judge(cable, List.of("shell", no), x -> null).allowed(), no);
        }
    }

    // ------------------------------------------------------------------ after a killed run

    @Test
    void phoneResetPutsTheNetworkBackByTheNoteOfAKilledRunAndTakesTheNoteAway() {
        // killed in the middle of airplane mode, after Wi-Fi and data were switched off in an earlier cell and not put back
        NetPhone p = new NetPhone();
        p.note = "wifi-on";
        p.wifi = false;
        p.data = false;
        p.answer("shell " + PhoneNet.AIRPLANE_ON);
        p.wifiLag = 2;
        List<String> said = new ArrayList<>();
        assertTrue(PhoneReset.run(p, said::add, ms -> now[0] += ms, () -> now[0]), said.toString());
        assertFalse(p.airplane);
        assertTrue(p.wifi && p.data, "Wi-Fi as noted, mobile data on");
        assertNull(p.note, "the note taken away once the network is back");
        assertEquals(List.of("shell " + PhoneNet.AIRPLANE_OFF, "shell " + PhoneNet.WIFI_ON, "shell " + PhoneNet.DATA_ON), p.orders());
        assertTrue(said.contains("done    shell " + PhoneNet.AIRPLANE_OFF) && said.contains("done    shell " + PhoneNet.NOTE_DELETE), said.toString());
        assertTrue(said.stream().anyMatch(l -> l.startsWith("network: as a killed run of the bench left it noted (Wi-Fi on, mobile data on, airplane mode off): back as found")),
                said.toString());

        // without a note nothing of the network is switched: airplane mode may be the pilot's own
        NetPhone own = new NetPhone();
        own.answer("shell " + PhoneNet.AIRPLANE_ON);
        said.clear();
        PhoneReset.run(own, said::add, ms -> now[0] += ms, () -> now[0]);
        assertEquals(List.of(), own.orders());
        assertTrue(own.airplane);
        assertTrue(said.contains("network: the bench has no note of having switched it - left as it is"), said.toString());

        // a Wi-Fi that does not come back: said, the note kept for the next try
        NetPhone stuck = new NetPhone();
        stuck.note = "wifi-on";
        stuck.wifi = false;
        stuck.wifiStuck = true;
        said.clear();
        assertFalse(PhoneReset.run(stuck, said::add, ms -> now[0] += ms, () -> now[0]));
        assertEquals("wifi-on", stuck.note);
        assertTrue(said.stream().anyMatch(l -> l.startsWith("network:") && l.contains("NOT back as found")), said.toString());
        assertEquals("NOT everything was put back: see above.", said.get(said.size() - 1));
    }
}
