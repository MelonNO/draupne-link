package de.droiddrone.pc.gui;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FleetManager;
import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import de.droiddrone.pc.setup.Tailscale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64, the books tied together (FleetController holds them all): a phone's own answer to the name scan reaches the
 * sleep book, a rename moves what is kept by name, a queued OVERWATCH's token takes over only once it is taken, a
 * resting phone's badge holds between its answers, and one question says what is wrong with a drone's overwatch.
 */
class FleetBooksTest {
    private static final String IP = "127.0.0.1";

    @TempDir
    Path dir;

    private PcConfig config;
    private Names names;
    private NameService nameService;
    private FleetManager fleet;
    private OverwatchHub hub;
    private PowerController power;
    private FleetController fleets;
    private final Map<String, String> standby = new ConcurrentHashMap<>();
    private final Map<String, Long> crashed = new ConcurrentHashMap<>();
    private final Map<String, String> saved = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private int watchedSaves;
    /** The sleep book's wall clock, when a test sets one (0: the real one). */
    private volatile long clock;
    private DatagramSocket phone;
    private volatile int phoneMode;
    private Tailscale.Status network = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc", List.of());

    @BeforeEach
    void setUp() throws Exception {
        config = PcConfig.defaults();
        names = new Names(config, m -> m.forEach(config::set));
        phone = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        phone.setSoTimeout(100);
        nameService = new NameService(config, names, () -> network, phone.getLocalPort(), false, m -> m.forEach(config::set), log::add);
        fleet = new FleetManager(config.getKey(), 7777, "", saved::putAll, log::add);
        hub = new OverwatchHub(new ClipIndex(dir));
        power = new PowerController(new PowerController.Host() {
            @Override
            public String nameAt(String ip) {
                return names.nameAt(ip);
            }

            @Override
            public boolean observed(String name) {
                return false;
            }

            @Override
            public void observedCheckIn(String ip, String name) {
            }

            @Override
            public void logLater(String line) {
                log.add(line);
            }

            @Override
            public void saveAsleep(String s) {
                saved.put("asleep", s);
            }
        }, () -> clock != 0 ? clock : System.currentTimeMillis());
        fleets = new FleetController(fleet, config, names, nameService, hub, power, standby, crashed, new FleetController.Host() {
            @Override
            public Connection connection() {
                return null;
            }

            @Override
            public int detectionFor(String phone) {
                return 2;
            }

            @Override
            public boolean mutedFor(String phone) {
                return false;
            }

            @Override
            public void saveWatched() {
                watchedSaves++;
            }

            @Override
            public void logLine(String text) {
                log.add(text);
            }

            @Override
            public MapWindow mapWindowIfOpened() {
                return null;
            }
        });
        fleet.setOnTaken(fleets::taken); // as the window does
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.47"), IP, "pixel-8");
    }

    @AfterEach
    void tearDown() {
        nameService.stop();
        hub.close();
        phone.close();
    }

    private static FleetProtocol.Status status(int mode, int token) {
        return new FleetProtocol.Status(70, false, 3, 1, 9, 250, mode, 5, token);
    }

    private static Names.Reply reply(String name, int mode, int token) {
        return new Names.Reply(Names.Kind.PHONE, name, "Pixel 8", "2.3.0-pc.47", null, mode, status(mode, token));
    }

    private static void waitFor(java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out: " + what);
            Thread.sleep(20);
        }
    }

    private static void swing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /** The phone on loopback answers the scan's queries, saying phoneMode. */
    private void answerQueries() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (!phone.isClosed()) {
                try {
                    phone.receive(p);
                    if (p.getData()[0] != Names.NAME_QUERY) continue;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    out.write(Names.reply(config.getKey(), Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.47"));
                    out.writeByte(1);
                    out.writeByte(0); // no fix
                    out.writeInt(0);
                    out.writeInt(0);
                    out.writeShort(0);
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeByte(phoneMode);
                    status(phoneMode, 0).writeTo(out);
                    byte[] r = bytes.toByteArray();
                    phone.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    return;
                }
            }
        }, "fake-phone");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Items 1a, 1b: from the scan to the sleep book. A phone asleep in this PC's book that answers the scan "normal" (woken
     * by another PC, or on the phone) is asleep no more - any phone, chosen or not; one that answers "deep sleep" is
     * in the book. And a standby this PC left it in is over once it says another mode.
     */
    @Test
    void thePhonesOwnAnswerToTheScanReachesTheSleepBook() throws Exception {
        network = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc",
                List.of(new Tailscale.Peer("pixel-8", "android", true, IP, IP + ":1", "")));
        answerQueries();
        power.remember(new PowerController.Asleep("MobileAlpha", IP, System.currentTimeMillis() - 10 * 60_000L, 5));
        standby.put(IP, "MobileAlpha");
        phoneMode = 0;
        nameService.start(); // its first scan at once
        waitFor(() -> power.asleep(IP) == null, "it answers awake: not asleep here any more");
        assertTrue(log.contains("MobileAlpha is awake again."), log.toString());
        assertTrue(standby.containsKey(IP), "its standby: not over at one answer (sent, maybe, just before it took the standby)");
        waitFor(() -> !standby.containsKey(IP), "said again a scan later: the standby this PC left it in is over");

        phoneMode = 2; // put to sleep by another PC: its word alone files it
        nameService.scanSoon();
        waitFor(() -> power.asleep(IP) != null, "it answers asleep: in the book");
        PowerController.Asleep a = power.asleep(IP);
        assertFalse(a.setHere());
        assertEquals(5, a.intervalMin());
        assertTrue(power.checkInOverdue(a, System.currentTimeMillis() + 9 * 60_000L) <= 0, "its next check counts from that answer");
    }

    /** Item 1e: a queued mode sent for a hello - the hello's answer isn't "sleep on" (also on the link's port, which asks the same book). */
    @Test
    void aQueuedModeSentForAHelloLeavesItUnanswered() {
        byte[] hello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0, 0, 0};
        clock = System.currentTimeMillis();
        power.remember(new PowerController.Asleep("MobileAlpha", IP, clock - 5 * 60_000L, 5));
        fleet.queue("MobileAlpha", ControlLink.POWER_STANDBY, 0, 0, 0, 0, System.currentTimeMillis());
        for (int i = 0; i < 5; i++) { // its window's hellos, 1.5 s apart
            assertNotNull(fleets.hello(IP, hello), "the mode goes with each hello");
            assertNull(power.checkIn(IP, hello), "and no 'sleep on' behind it, hello " + i);
            clock += 1500;
        }
        fleet.cancel("MobileAlpha");
        assertNull(fleets.hello(IP, hello), "nothing queued");
        assertEquals(Boolean.FALSE, power.checkIn(IP, hello), "nothing sent: this PC set the sleep - sleep on");
    }

    /** Item 4b: a rename in the name map moves the queued mode, the sleep record and the overwatch token. */
    @Test
    void aRenameMovesWhatIsKeptByName() throws Exception {
        CameraFootprint.Pose pose = new CameraFootprint.Pose(59.9, 10.7, 120, 45, 0, 0, 10, 70, 40);
        hub.watch("MobileAlpha", 0x51EE7, pose);
        power.remember(new PowerController.Asleep("MobileAlpha", IP, System.currentTimeMillis(), 5, true));
        FleetManager.Wanted w = fleet.queue("MobileAlpha", ControlLink.POWER_DEEP_SLEEP, 5, 0, 0, 0, System.currentTimeMillis());
        fleets.lastCell("MobileAlpha", 3.9, 1000);
        assertTrue(names.rename("MobileAlpha", "MobileKilo"));
        swing();
        assertNull(fleet.wanted("MobileAlpha"));
        assertEquals(w.token(), fleet.wanted("MobileKilo").token());
        assertEquals("MobileKilo", power.asleep(IP).name());
        assertEquals("MobileKilo", hub.watched().get(0).drone());
        assertEquals(0x51EE7, hub.watched().get(0).token());
        assertEquals(1, watchedSaves, "the watched list saved under the new name");
        FleetCards.Card card = fleets.cards().stream().filter(c -> c.name().equals("MobileKilo")).findFirst().orElseThrow();
        assertEquals("OVERWATCH", card.badge());
        assertEquals("DEEP SLEEP", card.queued());
        assertEquals(3.9, card.cellVolts(), 1e-9);
        assertTrue(names.rename("MobileKilo", "MobileAlpha")); // put back (the phone never took it): all of it back
        swing();
        assertEquals(w.token(), fleet.wanted("MobileAlpha").token());
        assertEquals("MobileAlpha", power.asleep(IP).name());
        assertEquals("MobileAlpha", hub.watched().get(0).drone());
    }

    /**
     * Item 4e: FLEET's OVERWATCH for a drone that overwatches already. The token in force stays (with its view) until the
     * phone takes the new one; cancelled, the new one is forgotten.
     */
    @Test
    void aQueuedOverwatchTakesOverOnlyWhenItIsTaken() throws Exception {
        CameraFootprint.Pose pose = new CameraFootprint.Pose(59.9, 10.7, 120, 45, 0, 0, 10, 70, 40);
        hub.watch("MobileAlpha", 0x51EE7, pose);
        fleets.request("MobileAlpha", ControlLink.POWER_OVERWATCH, 5, null);
        FleetManager.Wanted w = fleet.wanted("MobileAlpha");
        assertNotNull(w);
        assertNotEquals(0x51EE7, w.owToken());
        assertEquals(1, hub.watched().size());
        assertEquals(0x51EE7, hub.watched().get(0).token(), "the phone still alerts with this one");
        assertTrue(hub.expected(w.owToken()));
        // CHECK EVERY changed before it was taken: queued again, the first queued token forgotten
        fleets.request("MobileAlpha", ControlLink.POWER_OVERWATCH, 1, null);
        FleetManager.Wanted second = fleet.wanted("MobileAlpha");
        assertFalse(hub.expected(w.owToken()));
        assertTrue(hub.expected(second.owToken()));
        assertEquals(0x51EE7, hub.watched().get(0).token());
        // the phone says it took it
        assertNull(fleet.onReply(reply("MobileAlpha", 3, second.token()), System.currentTimeMillis()));
        swing();
        assertEquals(1, hub.watched().size());
        assertEquals(second.owToken(), hub.watched().get(0).token());
        assertNotNull(hub.watched().get(0).pose(), "with the view the drone had");
        assertTrue(watchedSaves >= 1);
        assertTrue(power.asleep(IP).overwatch());
        assertTrue(power.asleep(IP).setHere(), "this PC's sleep");
        // another one queued, then cancelled: forgotten, the one in force stays
        fleets.request("MobileAlpha", ControlLink.POWER_OVERWATCH, 5, null);
        int third = fleet.wanted("MobileAlpha").owToken();
        fleets.cancel("MobileAlpha");
        swing();
        assertFalse(hub.expected(third));
        assertEquals(second.owToken(), hub.watched().get(0).token());
        // queued before a restart (this hub was never told to expect it): expected again before the mode can go out
        FleetManager.Wanted old = fleet.queue("MobileAlpha", ControlLink.POWER_OVERWATCH, 5, 2, 0, 0x0DD, System.currentTimeMillis());
        assertFalse(hub.expected(0x0DD));
        fleets.phoneReply(IP, reply("MobileAlpha", 3, 0), 3);
        assertTrue(hub.expected(0x0DD));
        assertNull(fleet.onReply(reply("MobileAlpha", 3, old.token()), System.currentTimeMillis()));
        swing();
        assertEquals(0x0DD, hub.watched().get(0).token());
        assertEquals(1, hub.watched().size());
        assertNotNull(hub.watched().get(0).pose());
        // ... and taken without ever having been expected, it is in force all the same
        FleetManager.Wanted last = fleet.queue("MobileAlpha", ControlLink.POWER_OVERWATCH, 5, 2, 0, 0x0EE, System.currentTimeMillis());
        assertNull(fleet.onReply(reply("MobileAlpha", 3, last.token()), System.currentTimeMillis()));
        swing();
        assertEquals(0x0EE, hub.watched().get(0).token());
        assertEquals(1, hub.watched().size());
    }

    /**
     * Item 1c: a phone another PC put to standby is asked once a minute; with the badge's 30 s its card read STANDBY and
     * ACTIVE by turns. Its word holds for as long as it is left alone.
     */
    @Test
    void aRestingPhonesBadgeHoldsBetweenItsAnswers() {
        long now = System.currentTimeMillis();
        fleet.onReply(reply("MobileAlpha", 1, 0), now - 45_000); // standby, said 45 s ago; held online meanwhile
        FleetCards.Card card = fleets.cards().get(0);
        assertEquals("STANDBY", card.badge(), "45 s after its answer: still the mode it said");
        fleet.onReply(reply("MobileAlpha", 1, 0), now - NameService.saidFreshMs(1) - 5000);
        assertEquals("ACTIVE", fleets.cards().get(0).badge(), "not heard for longer than it is left alone: its word is old");
        fleet.onReply(reply("MobileAlpha", 0, 0), now - 45_000);
        assertEquals("ACTIVE", fleets.cards().get(0).badge());
    }

    /**
     * Item 9: one question for the lists - is this drone's overwatch blind, or did it drop to deep sleep - from the hub's
     * STATUS and the phone's check-ins, the newer word of the two.
     */
    @Test
    void oneQuestionSaysWhatIsWrongWithADronesOverwatch() throws Exception {
        int token = 0x51EE7;
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("MobileAlpha"), "not asleep here");
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("MobileNobody"));
        hub.watch("MobileAlpha", token, null);
        hub.start();
        long since = System.currentTimeMillis();
        power.remember(new PowerController.Asleep("MobileAlpha", IP, since, 5, true));
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("MobileAlpha"), "nothing said: nothing wrong");
        // its hello says its camera gives no pictures
        byte[] blindHello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0, (byte) (OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES), 0};
        power.checkIn(IP, blindHello);
        assertEquals(OverwatchHub.Trouble.BLIND, fleets.overwatchTrouble("MobileAlpha"));
        // the hub hears "watching again" (its STATUS, at once): the newer word
        Thread.sleep(20);
        offer(OverwatchProtocol.status(token, OverwatchProtocol.STATE_WATCHING, 0, 80, 300), OverwatchHub.Trouble.NONE);
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("mobilealpha"));
        offer(OverwatchProtocol.status(token, OverwatchProtocol.STATE_BLIND, OverwatchProtocol.BLIND_LOST, 80, 300), OverwatchHub.Trouble.BLIND);
        assertEquals(OverwatchHub.Trouble.BLIND, fleets.overwatchTrouble("MobileAlpha"));
        assertEquals(OverwatchProtocol.BLIND_LOST, fleets.overwatchWord("MobileAlpha").reason());
        // it left overwatch for deep sleep by itself
        offer(OverwatchProtocol.status(token, OverwatchProtocol.STATE_DROPPED, de.droiddrone.common.PowerFloor.REASON_BATTERY, 18, 310),
                OverwatchHub.Trouble.DROPPED);
        assertEquals(OverwatchHub.Trouble.DROPPED, fleets.overwatchTrouble("MobileAlpha"));
        power.remember(new PowerController.Asleep("MobileAlpha", IP, since, 5, false)); // as the window files it
        assertEquals(OverwatchHub.Trouble.DROPPED, fleets.overwatchTrouble("MobileAlpha"));
        assertEquals(de.droiddrone.common.PowerFloor.REASON_BATTERY, fleets.overwatchWord("MobileAlpha").reason());
        // woken: nothing to show; a deep sleep set afterwards isn't "dropped"
        power.clear(IP);
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("MobileAlpha"));
        power.remember(new PowerController.Asleep("MobileAlpha", IP, System.currentTimeMillis() + 60_000, 5, false));
        assertEquals(OverwatchHub.Trouble.NONE, fleets.overwatchTrouble("MobileAlpha"), "the hub's word is of the sleep before");
    }

    /** A STATUS to the running hub, and a wait until its word is the one expected. */
    private void offer(byte[] status, OverwatchHub.Trouble expected) throws Exception {
        hub.offer(status, status.length, InetAddress.getLoopbackAddress(), phone.getLocalPort());
        for (int i = 0; i < 100; i++) {
            OverwatchHub.Word w = hub.word("MobileAlpha");
            if (w != null && w.trouble() == expected) return;
            Thread.sleep(20);
        }
        fail("the hub never said " + expected);
    }

    /** Item 4d as far as this side goes: the queue asks the controller whether this PC's pilot link is to the phone. */
    @Test
    void theBooksAreWiredTogether() {
        // the sleep book's interval decides how long a queued mode waits
        power.remember(new PowerController.Asleep("MobileAlpha", IP, System.currentTimeMillis(), 30));
        long t0 = System.currentTimeMillis();
        fleet.queue("MobileAlpha", ControlLink.POWER_STANDBY, 0, 0, 0, 0, t0);
        fleet.expire(t0 + 59 * 60_000L);
        assertNotNull(fleet.wanted("MobileAlpha"), "two of its 30 min check-ins");
        fleet.expire(t0 + 61 * 60_000L);
        assertNull(fleet.wanted("MobileAlpha"));
        // an older phone's hello proves this PC set its sleep: the build comes from the name map, or FLEET's last word
        byte[] hello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0, 0, 0};
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileBravo", "Pixel 7", "2.3.0-pc.47"), "100.64.0.6", "pixel-7");
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileCharlie", "Pixel 9", "2.3.0-pc.51"), "100.64.0.7", "pixel-9");
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileDelta", "Pixel 9", "2.3.0-pc.47"), "100.64.0.8", "pixel-9b");
        fleet.onReply(new Names.Reply(Names.Kind.PHONE, "MobileDelta", "Pixel 9", "2.3.0-pc.51", null, 0, status(0, 0)), t0); // updated since
        for (String ip : List.of("100.64.0.6", "100.64.0.7", "100.64.0.8")) {
            power.nameReply(ip, names.nameAt(ip), 2, status(2, 0));
            assertFalse(power.asleep(ip).setHere());
            power.checkIn(ip, hello);
        }
        assertTrue(power.asleep("100.64.0.6").setHere(), "pc.47 says hello only to the PC that set its sleep");
        assertFalse(power.asleep("100.64.0.7").setHere(), "pc.51 says it to every PC that knows it");
        assertFalse(power.asleep("100.64.0.8").setHere(), "FLEET heard a newer build than the name map has");
        // a drone of the virtual fleet says hello to one PC, whatever its version
        power.clear(IP);
        fleet.onReply(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "virtual", "2.3.0-pc.51", null, 0, status(0, 0)), t0);
        power.checkIn(IP, hello);
        assertTrue(power.asleep(IP).setHere());
    }
}
