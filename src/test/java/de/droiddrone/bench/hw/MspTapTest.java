package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper's tap ({@link MspTap}): its figures against a cadence that is known exactly (frames handed in with
 * their times), its row a second and its frame log, its cutting of a byte stream into frames held to the phone's own
 * reader (common.MspFrames), and what it reads out of Betaflight's answers.
 */
class MspTapTest {
    private static final long MS = 1_000_000;
    @TempDir
    Path dir;

    private static MspTap.Frame stick(int throttle, int arm) {
        byte[] raw = StandInPhone.stickFrame(throttle, arm);
        return new MspTap.Splitter(64).feed(raw, raw.length).get(0);
    }

    /** A stick frame that was whole at inNs and written to the board at outNs. */
    private static MspTap.Seen passed(long inNs, long outNs, int throttle) {
        return new MspTap.Seen(inNs, outNs, MspTap.Way.PHONE_TO_BOARD, stick(throttle, 1000), throttle, 1000, null);
    }

    @Test
    void theFiguresOfAKnownCadence() throws Exception {
        MspTap tap = new MspTap(dir);
        // 250 stick frames, one every 4 ms at the board - but one came 37 ms after the one before it; each held 0.1 ms, one 2.5 ms
        long out = 10 * MS;
        for (int i = 0; i < 250; i++) {
            out += i == 100 ? 37 * MS : 4 * MS;
            long held = i == 7 ? 2_500_000 : 100_000;
            tap.seen(passed(out - held, out, 1000 + i % 50));
        }
        // and what does not count as a stick at the board: one starved, an answer, a question of the keeper's own
        tap.seen(new MspTap.Seen(out + MS, 0, MspTap.Way.PHONE_TO_BOARD, stick(1010, 1000), 1010, 1000, "starved"));
        MspTap.Frame status = new MspTap.Splitter(4096).feed(MspTap.bytes(MspFrames.V2, MspFrames.RESPONSE, Msp.STATUS, new byte[22]), 31).get(0);
        tap.seen(new MspTap.Seen(out + 2 * MS, out + 2 * MS + 50_000, MspTap.Way.BOARD_TO_PHONE, status, -1, -1, null));
        byte[] own = MspFrames.v1Request(Msp.STATUS, null);
        tap.seen(new MspTap.Seen(out + 3 * MS, out + 3 * MS + 20_000, MspTap.Way.KEEPER_TO_BOARD, new MspTap.Splitter(64).feed(own, own.length).get(0), -1, -1, null));

        MspTap.Figures f = tap.figures();
        assertEquals(250, f.frames());
        assertEquals((248 * 4 + 37) / 1000.0, f.seconds(), 1e-9, "from the first frame to the last");
        assertEquals(249 / ((248 * 4 + 37) / 1000.0), f.perSecond(), 1e-6);
        assertEquals(37.0, f.maxGapMs(), 1e-9, "the largest gap, exactly");
        assertTrue(f.p99GapMs() >= 4.0 && f.p99GapMs() <= 4.05, "99 of 100 gaps are 4 ms (counted in steps of 0.05): " + f.p99GapMs());
        assertEquals(2.5, f.addedMaxMs(), 1e-9, "the longest the keeper held one");
        assertEquals((249 * 0.1 + 2.5) / 250, f.addedMeanMs(), 1e-9);
        assertTrue(f.toString().contains("250 stick frames in 1.0 s at the board: 242.0 a second, largest gap 37.0 ms, 99th percentile 4."), f.toString());
        assertTrue(f.line().startsWith("frames=250 seconds=1.03 perSecond=242.0 maxGapMs=37.00 p99GapMs=4.0"), f.line());

        String row = tap.row(new MspTap.Row("up", "phone", "1", "0", 4200, 23.9, 3.9833, "delay 20 ms", "first, chain"));
        String[] cell = row.split(",", -1);
        List<String> names = List.of(MspTap.HEADER.split(","));
        assertEquals(names.size(), cell.length);
        assertTrue(cell[0].matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3}"), "the clock, as the app's link log writes it: " + cell[0]);
        assertEquals("up,phone,1,0,4200,23.90,3.983", String.join(",", Arrays.copyOfRange(cell, 1, 8)));
        assertEquals("250", cell[names.indexOf("stick_frames")]);
        assertEquals("37.000", cell[names.indexOf("stick_gap_max_ms")]);
        assertEquals("4.000", cell[names.indexOf("stick_gap_p99_ms")], "by rank: of 249 gaps sorted, the 247th");
        assertEquals("2.500", cell[names.indexOf("keeper_added_max_ms")]);
        assertEquals("0.1096", cell[names.indexOf("keeper_added_mean_ms")]);
        assertEquals("1010", cell[names.indexOf("throttle")], "the last stick frame seen, passed on or not");
        assertEquals("1000", cell[names.indexOf("arm_switch")]);
        assertEquals("251", cell[names.indexOf("to_board")], "250 sticks and the keeper's own question");
        assertEquals("1", cell[names.indexOf("from_board")]);
        assertEquals("1", cell[names.indexOf("dropped")]);
        assertEquals("delay 20 ms", cell[names.indexOf("faults")]);
        assertEquals("first; chain", cell[names.indexOf("scenario")], "no comma inside a cell");

        // the next second begins empty; a gap across the two seconds belongs to the second its later frame is in
        tap.seen(passed(out + 9 * MS, out + 10 * MS, 1000));
        String[] next = tap.row(new MspTap.Row("up", "phone", "", "", 0, Double.NaN, Double.NaN, "", "")).split(",", -1);
        assertEquals("1", next[names.indexOf("stick_frames")]);
        assertEquals("10.000", next[names.indexOf("stick_gap_max_ms")]);
        assertEquals("", next[names.indexOf("armed")]);
        assertEquals("", next[names.indexOf("volts")]);
        String[] empty = tap.row(new MspTap.Row("away", "none", "", "", 0, Double.NaN, Double.NaN, "", "")).split(",", -1);
        assertEquals("0", empty[names.indexOf("stick_frames")]);
        assertEquals("", empty[names.indexOf("stick_gap_max_ms")]);
        assertEquals("", empty[names.indexOf("throttle")]);

        // a mark begins the figures anew; the gap from the frame before the mark is not one of them
        tap.mark();
        assertEquals(0, tap.figures().frames());
        tap.seen(passed(out + 20 * MS, out + 20 * MS + 1, 1000));
        tap.seen(passed(out + 25 * MS, out + 25 * MS + 1, 1000));
        tap.seen(passed(out + 31 * MS, out + 31 * MS + 1, 1000));
        MspTap.Figures since = tap.figures();
        assertEquals(3, since.frames());
        assertEquals(6.0, since.maxGapMs(), 1e-9);
        assertEquals(0.011, since.seconds(), 1e-9);

        tap.event("TRIP: THROTTLE CAP, and so on");
        tap.close();
        List<String> lines = Files.readAllLines(tap.csvFile() == null ? onlyCsv() : tap.csvFile());
        assertEquals(MspTap.HEADER, lines.get(0));
        assertEquals(5, lines.size(), "the header, three rows, one event");
        assertEquals(row, lines.get(1));
        String[] event = lines.get(4).split(",", -1);
        assertEquals(names.size(), event.length);
        assertEquals("TRIP: THROTTLE CAP; and so on", event[names.indexOf("event")]);
        assertEquals("", event[1]);
    }

    private Path onlyCsv() throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("board-")).findFirst().orElseThrow();
        }
    }

    @Test
    void theNinetyNinthPercentileIsByRank() {
        assertEquals(0, MspTap.p99(new long[0], 0));
        assertEquals(5, MspTap.p99(new long[]{5}, 1));
        long[] hundred = new long[100];
        for (int i = 0; i < 100; i++) hundred[i] = 100 - i; // 100 .. 1
        assertEquals(99, MspTap.p99(hundred, 100), "of 100 values the 99th");
        long[] many = new long[250];
        Arrays.fill(many, 4);
        many[17] = 90;
        many[18] = 80;
        many[19] = 70;
        assertEquals(70, MspTap.p99(many, 250), "of 250 values the 248th: two may be larger");
    }

    @Test
    void theFrameLogHasALineForEveryFrameAndWhyOneWasNotPassedOn() throws Exception {
        MspTap tap = new MspTap(dir);
        assertNull(tap.takeProblem());
        Path log = tap.openLog("first chain / sticks at rest");
        assertEquals("frames-first_chain___sticks_at_rest.log", log.getFileName().toString());
        tap.seen(passed(5_000_000, 5_250_000, 1000));
        tap.seen(new MspTap.Seen(9_000_000, 0, MspTap.Way.PHONE_TO_BOARD, stick(1200, 2000), 1200, 2000, "dropped: throttle above the ceiling of 1100"));
        tap.closeLog();
        tap.seen(passed(12_000_000, 12_250_000, 1000)); // after the scenario: counted, not logged
        tap.drain();
        List<String> lines = Files.readAllLines(log);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("# first chain / sticks at rest - every MSP frame at the keeper."), lines.get(0));
        assertTrue(lines.get(1).matches("# clock \\d\\d:\\d\\d:\\d\\d\\.\\d{3} = \\d+ us"), lines.get(1));
        assertEquals("5000 phone>board v2 < 200 len=16 held=250 thr=1000 arm=1000", lines.get(2));
        assertEquals("9000 phone>board v2 < 200 len=16 held=- thr=1200 arm=2000  dropped: throttle above the ceiling of 1100", lines.get(3));
        assertEquals(2, tap.figures().frames());
        // without a folder nothing is written, and the figures are kept all the same
        MspTap dry = new MspTap(null);
        assertNull(dry.openLog("x"));
        dry.seen(passed(MS, 2 * MS, 1000));
        dry.row(new MspTap.Row("up", "none", "", "", 0, Double.NaN, Double.NaN, "", ""));
        assertEquals(1, dry.figures().frames());
        assertNull(dry.csvFile());
        assertTrue(dry.now() > 0);
        tap.close();
    }

    @Test
    void aStreamIsCutIntoFramesAsThePhonesOwnReaderCutsIt() {
        Random r = new Random(5);
        for (int round = 0; round < 200; round++) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            List<byte[]> made = new ArrayList<>();
            int junk = 0;
            for (int i = 0; i < 12; i++) {
                if (r.nextInt(3) == 0) { // bytes that are no frame: text, noise - never a '$'
                    int n = 1 + r.nextInt(9);
                    for (int k = 0; k < n; k++) stream.write("#Rexit\r\n\0abc".charAt(r.nextInt(12)));
                    junk += n;
                }
                byte[] payload = new byte[r.nextInt(40)];
                r.nextBytes(payload);
                boolean v2 = r.nextBoolean();
                byte type = new byte[]{MspFrames.REQUEST, MspFrames.RESPONSE, MspFrames.ERROR}[r.nextInt(3)];
                byte[] frame = MspTap.bytes(v2 ? MspFrames.V2 : MspFrames.V1, type, v2 ? r.nextInt(0x4000) : r.nextInt(256), payload.length == 0 ? null : payload);
                made.add(frame);
                stream.write(frame, 0, frame.length);
            }
            byte[] all = stream.toByteArray();
            MspTap.Splitter splitter = new MspTap.Splitter(MspFrames.MAX_CARRY);
            MspFrames theirs = new MspFrames();
            List<MspTap.Frame> mine = new ArrayList<>();
            List<MspFrames.Frame> phones = new ArrayList<>();
            for (int at = 0; at < all.length; ) { // handed over in reads that end anywhere
                int n = Math.min(all.length - at, 1 + r.nextInt(23));
                byte[] read = Arrays.copyOfRange(all, at, at + n);
                mine.addAll(splitter.feed(read, n));
                phones.addAll(theirs.feed(read, n));
                at += n;
            }
            assertEquals(made.size(), mine.size(), "round " + round);
            assertEquals(phones.size(), mine.size());
            for (int i = 0; i < mine.size(); i++) {
                MspTap.Frame a = mine.get(i);
                MspFrames.Frame b = phones.get(i);
                assertArrayEquals(made.get(i), a.raw(), "each frame's own bytes, as they came");
                assertEquals(b.version, a.version());
                assertEquals(b.type, a.type());
                assertEquals(b.code, a.code());
                assertArrayEquals(b.payload == null ? new byte[0] : b.payload, a.payload());
            }
            assertEquals(junk, splitter.skipped(), "and every byte that was part of none is counted");
            assertEquals(0, splitter.pending());
        }
    }

    @Test
    void aFrameWithAWrongChecksumOrAnOverlongPayloadIsNoFrame() {
        byte[] good = StandInPhone.stickFrame(1000, 1000), bad = good.clone();
        bad[bad.length - 1] ^= 0x40;
        MspTap.Splitter s = new MspTap.Splitter(64);
        assertEquals(0, s.feed(bad, bad.length).size());
        assertEquals(bad.length, s.skipped(), "stepped over byte by byte, as text is");
        assertEquals(1, s.feed(good, good.length).size());
        // a header that says more than this side ever sends is no frame's start: nothing waits for the rest of it
        byte[] big = MspFrames.v2Request(Msp.SET_RAW_RC, new byte[65]);
        assertEquals(0, s.feed(big, 20).size());
        assertEquals(0, s.pending(), "no frame is begun");
        assertEquals(1, new MspTap.Splitter(65).feed(big, big.length).size());
        // half a frame waits for its other half
        assertEquals(0, s.feed(good, 10).size());
        assertEquals(10, s.pending());
        byte[] rest = Arrays.copyOfRange(good, 10, good.length);
        List<MspTap.Frame> whole = s.feed(rest, rest.length);
        assertEquals(1, whole.size());
        assertEquals(Msp.SET_RAW_RC, whole.get(0).code());
        assertTrue(whole.get(0).v2() && whole.get(0).request() && !whole.get(0).answer());
        assertArrayEquals(new int[]{1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000}, Msp.channels(whole.get(0).payload()));
    }

    @Test
    void whatBetaflightsAnswersSay() {
        // MSP_STATUS as Betaflight 4.4 sends it: armed is the first mode flag; the arming-disable flags follow the count of extra mode-flag bytes
        byte[] status = new byte[22];
        Msp.putU32(status, 6, 1);                 // mode flags: ARM
        status[15] = 0;                           // no further mode-flag bytes
        status[16] = 26;                          // 26 arming-disable flags
        Msp.putU32(status, 17, 1 << 16 | 1 << 2); // MSP, RXLOSS
        assertTrue(Msp.armed(status));
        assertEquals(1 << 16 | 1 << 2, Msp.armingDisableFlags(status));
        byte[] more = new byte[25];               // three more mode-flag bytes before them
        more[15] = 3;
        more[19] = 26;
        Msp.putU32(more, 20, 1 << Msp.ARMING_DISABLED_MSP_BIT);
        assertEquals(1 << 16, Msp.armingDisableFlags(more));
        assertEquals(-1, Msp.armingDisableFlags(new byte[11]), "an old firmware's short status: not known");
        assertEquals(-1, Msp.armingDisableFlags(null));
        // volts: hundredths at the end since Betaflight 4.0, tenths before
        byte[] analog = new byte[9];
        analog[0] = (byte) 239;
        Msp.putU16(analog, 7, 2387);
        assertEquals(23.87, Msp.volts(Msp.ANALOG, analog), 1e-9);
        assertEquals(23.9, Msp.volts(Msp.ANALOG, new byte[]{(byte) 239, 0, 0, 0, 0, 0, 0}), 1e-9);
        byte[] battery = new byte[11];
        battery[0] = 6;
        battery[3] = (byte) 239;
        Msp.putU16(battery, 9, 2391);
        assertEquals(23.91, Msp.volts(Msp.BATTERY_STATE, battery), 1e-9);
        assertTrue(Double.isNaN(Msp.volts(Msp.STATUS, status)));
        assertTrue(Double.isNaN(Msp.volts(Msp.ANALOG, new byte[0])));
    }
}
