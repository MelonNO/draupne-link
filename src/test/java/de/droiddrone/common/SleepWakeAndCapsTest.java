package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A packet that is not keyed no longer keeps a sleeping phone awake (SleepWake: the key first), and the phone's queues
 * and books have caps - the answers' queue, the PCs that asked its name, the unnumbered packets waiting for the buffer
 * thread, the config updates - each dropping its oldest with one log line (DropOldest). Before, the packet's first
 * byte alone held the phone awake 3 s, and every one of them grew without a bound.
 */
class SleepWakeAndCapsTest {
    private static final String KEY = "DD";
    private final SessionGate gate = new SessionGate(KEY);

    /** [type][key UTF] (the key at 1), or a Connect [8][num i16][client type][key UTF] (the key at 4). */
    private static byte[] keyed(byte type, String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int at = type == UdpCommon.Connect ? 4 : 1;
        byte[] d = new byte[at + 2 + k.length + 8];
        d[0] = type;
        d[at] = (byte) (k.length >> 8);
        d[at + 1] = (byte) k.length;
        System.arraycopy(k, 0, d, at + 2, k.length);
        return d;
    }

    @Test
    void onlyAKeyedPacketOfAKindThatConcernsASleepingPhoneKeepsItAwake() {
        for (byte type : new byte[]{UdpCommon.Connect, FleetProtocol.MODE, Observe.OBSERVE, Observe.STOP, PacketIds.SPECTATE_PILOT_QUERY}) {
            assertTrue(SleepWake.kind(type));
            byte[] good = keyed(type, KEY), other = keyed(type, "XX");
            assertTrue(SleepWake.holdsAwake(good, good.length, gate, false), "type " + type + " with the key");
            assertFalse(SleepWake.holdsAwake(other, other.length, gate, true), "type " + type + " with another key");
            assertFalse(SleepWake.holdsAwake(new byte[]{type}, 1, gate, true), "type " + type + " alone");
            assertFalse(SleepWake.holdsAwake(good, 3, gate, true), "cut short");
        }
        byte[] answer = {PacketIds.SLEEP_ANSWER, 1};
        assertTrue(SleepWake.holdsAwake(answer, 2, gate, true), "the check-in's answer from the PC it checks in with");
        assertFalse(SleepWake.holdsAwake(answer, 2, gate, false), "from anywhere else");
        assertFalse(SleepWake.holdsAwake(answer, 1, gate, true));
        // the stick frame and everything else is no kind of it: the receive thread's compares, as before
        for (byte type : new byte[]{UdpCommon.RcFrame, UdpCommon.Ping, PacketIds.IDLE_STICKS, PacketIds.NAME_QUERY, 0, -1}) {
            assertFalse(SleepWake.kind(type));
            byte[] d = keyed(type, KEY);
            assertFalse(SleepWake.holdsAwake(d, d.length, gate, true));
        }
        assertFalse(SleepWake.holdsAwake(null, 0, gate, true));
    }

    @Test
    void anOverflowIsSaidOnceAMinuteWithItsCount() {
        DropOldest.Said said = new DropOldest.Said("Answers", 256);
        assertEquals("Answers: over 256 waiting - the oldest dropped (1)", said.dropped(1000));
        for (int i = 0; i < 99; i++) assertNull(said.dropped(1001 + i));
        assertEquals("Answers: over 256 waiting - the oldest dropped (100 since the last word)", said.dropped(1000 + DropOldest.SAY_EVERY_MS));
        assertEquals(101, said.count());
    }

    @Test
    void aFullExecutorDropsItsOldestTaskAndRunsTheRest() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        ThreadPoolExecutor e = DropOldest.executor("testAnswers", 4, log::add);
        try {
            CountDownLatch hold = new CountDownLatch(1), started = new CountDownLatch(1);
            e.execute(() -> {
                started.countDown();
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            AtomicLong ran = new AtomicLong();
            for (int i = 0; i < 10; i++) {
                final int bit = i;
                e.execute(() -> ran.getAndUpdate(v -> v | (1L << bit)));
            }
            assertEquals(4, e.getQueue().size(), "never more than its cap waiting");
            hold.countDown();
            e.shutdown();
            assertTrue(e.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0b1111000000L, ran.get(), "the four newest ran, the six oldest were dropped");
            assertEquals(1, log.size(), "one line: " + log);
            assertTrue(log.get(0).startsWith("testAnswers: over 4 waiting - the oldest dropped"));
        } finally {
            e.shutdownNow();
        }
    }

    @Test
    void theBookOfPcsThatAskedHoldsItsCapTheOldestOut() {
        KnownPcs<Integer> book = KnownPcs.forAlerts();
        int dropped = 0;
        for (int pc = 0; pc < KnownPcs.BOOK_MAX + 1000; pc++) if (book.asked(pc, 1000 + pc)) dropped++;
        assertEquals(KnownPcs.BOOK_MAX, book.size(), "unique askers by the thousand: the book stays at its cap");
        assertEquals(1000, dropped);
        assertFalse(book.askedWithin(0, 5000), "the first ones made room");
        assertTrue(book.askedWithin(KnownPcs.BOOK_MAX + 999, 5000));
        assertFalse(book.asked(KnownPcs.BOOK_MAX + 999, 6000), "one it has asks again: nothing dropped");
        // who is told is as before: the newest, at most MAX
        assertEquals(KnownPcs.MAX, book.toTell(null, 0, 6286, 6000).size());
    }

    @Test
    void theUnnumberedPacketsWaitingHoldTheirCapTheOldestOut() throws Exception {
        long[] clock = {0};
        UdpSender sender = new UdpSender(new java.net.DatagramSocket(0, InetAddress.getLoopbackAddress()));
        try {
            // the client side (the PC's), where a Connect answered starts the session over: both sides share the cap
            ReceiverBuffer b = new ReceiverBuffer(sender, false, KEY, null, () -> clock[0]);
            byte[] c = keyed(UdpCommon.Connect, KEY);
            c[1] = -1;
            c[2] = -1;
            c[3] = 1;
            b.addPacket(new DatagramPacket(c, c.length, InetAddress.getLoopbackAddress(), 9));
            assertTrue(b.isConnected());
            while (b.getNextPacket() != null) {
                // the Connect itself
            }
            for (int i = 0; i < ReceiverBuffer.MAX_UNNUMBERED + 500; i++) addTelemetry(b, i);
            int waiting = 0, first = -1;
            SavedPacket p;
            while ((p = b.getNextPacket()) != null) {
                if (first < 0) first = ((p.data[1] & 0xff) << 8) | (p.data[2] & 0xff);
                waiting++;
            }
            assertEquals(ReceiverBuffer.MAX_UNNUMBERED, waiting, "at most its cap");
            assertEquals(500, first, "the oldest went");
            // a new session starts the count over: three waiting are three delivered
            for (int i = 0; i < ReceiverBuffer.MAX_UNNUMBERED + 10; i++) addTelemetry(b, i);
            b.addPacket(new DatagramPacket(c, c.length, InetAddress.getLoopbackAddress(), 9)); // its Connect answered: the buffer starts over
            while (b.getNextPacket() != null) {
                // the new session's Connect
            }
            for (int i = 0; i < 3; i++) addTelemetry(b, i);
            AtomicInteger again = new AtomicInteger();
            while (b.getNextPacket() != null) again.incrementAndGet();
            assertEquals(3, again.get());
        } finally {
            sender.close();
        }
    }

    /** [TelemetryData][its number, u16]: an unnumbered packet the buffer hands on. */
    private static void addTelemetry(ReceiverBuffer b, int i) {
        byte[] d = {UdpCommon.TelemetryData, (byte) (i >> 8), (byte) i};
        b.addPacket(new DatagramPacket(d, d.length, InetAddress.getLoopbackAddress(), 9));
    }

    /** The phone app cannot be run here: its sources are read, as FcPortLatchTest and UpdateWithdrawTest do. */
    @Test
    void thePhoneChecksTheKeyBeforeItStaysAwakeAndUsesTheCaps() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String udp = new String(Files.readAllBytes(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java")), StandardCharsets.UTF_8);
        assertEquals(1, udp.split("PowerMode\\.packetWhileAsleep\\(\\)", -1).length - 1);
        assertTrue(udp.replaceAll("\\s+", " ").contains("if (SleepWake.kind(d[0]) && PowerMode.listeningAsleep() && SleepWake.holdsAwake(d, len, gate, "
                + "receiverPacket.getAddress().equals(lastPcIp))) PowerMode.packetWhileAsleep();"), "the kind, asleep, then the key: only then awake");
        assertTrue(udp.contains("spectatorAnswers = DropOldest.executor(\"spectatorAnswers\", ANSWERS_MAX, Logcat::log)"));
        assertTrue(udp.contains("configUpdates = DropOldest.executor(\"configUpdates\", CONFIG_UPDATES_MAX, Logcat::log)"));
        String config = udp.substring(udp.indexOf("case UdpCommon.Config:"));
        config = config.substring(0, config.indexOf("case UdpCommon.Disconnect:"));
        assertFalse(config.contains("new Thread("), "no thread of its own per Config");
        assertTrue(config.contains("configUpdates.execute("));
        String answer = udp.substring(udp.indexOf("private void answerName(DatagramPacket packet, byte[] d, int len) {"));
        answer = answer.substring(0, answer.indexOf("\n    }"));
        assertTrue(answer.contains("askersDropped.dropped(now)"), "a book that made room says so");
    }
}
