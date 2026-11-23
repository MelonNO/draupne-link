package de.droiddrone.bench.hw;

import de.droiddrone.bench.BadLink;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bad-network box on the real phone (R8, {@link HwLinkMatrixScenario}) held before it meets the phone and the
 * board: its cells and their time, its rows, the app's link log as it reads it, the board's word as it samples it,
 * when its armed part is skipped, and the rules the armed part holds the board to - the last also on the made-up
 * board behind a real keeper, set as the rig's real board is.
 */
class HwLinkMatrixTest {
    @AfterEach
    void end() {
        BetaflightUsb.use(null);
    }

    // ------------------------------------------------------------------ the cells

    @Test
    void theTableHasItsCellsAndFitsItsHourQuickItsFewMinutes() {
        List<HwLinkMatrix.Steady> steady = HwLinkMatrix.steady(1, false);
        assertEquals(List.of("clean", "4G good", "one bar", "loss 20 %", "loss 50 %", "bursts", "jitter 300 ms", "slow", "narrow band 300 kbit/s"),
                steady.stream().map(s -> s.net().name.replaceAll(" \\(.*", "")).toList(), "the LinkMatrix's networks a real phone makes sense of, doubled packets left out");
        assertTrue(steady.stream().allMatch(s -> s.measureMs() == HwLinkMatrix.STEADY_MS));
        assertEquals("steady clean (30 ms each way)", steady.get(0).name());
        List<HwLinkMatrix.Cut> cuts = HwLinkMatrix.cuts(false);
        assertEquals(12, cuts.size());
        assertEquals(List.of("cut 1 s both ways", "cut 1 s PC to phone only", "cut 1 s phone to PC only", "cut 3 s both ways"),
                cuts.subList(0, 4).stream().map(HwLinkMatrix.Cut::name).toList());
        assertEquals("cut 60 s phone to PC only", cuts.get(11).name());
        for (long ms : HwLinkMatrix.CUT_MS) {
            for (BadLink.Way way : BadLink.Way.values()) assertTrue(cuts.contains(new HwLinkMatrix.Cut(ms, way)), ms + " " + way);
        }
        long full = HwLinkMatrix.estimateMs(steady, cuts, true), linkOnly = HwLinkMatrix.estimateMs(steady, cuts, false);
        assertTrue(full <= 60 * 60_000L && full > linkOnly && linkOnly >= 20 * 60_000L, "the whole table in about an hour at most: " + full / 60_000 + " min");

        List<HwLinkMatrix.Steady> quick = HwLinkMatrix.steady(1, true);
        List<HwLinkMatrix.Cut> quickCuts = HwLinkMatrix.cuts(true);
        assertEquals(List.of("clean", "one bar"), quick.stream().map(s -> s.net().name.replaceAll(" \\(.*", "")).toList());
        assertEquals(List.of(new HwLinkMatrix.Cut(1000, BadLink.Way.BOTH), new HwLinkMatrix.Cut(10_000, BadLink.Way.BOTH)), quickCuts, "a short cut and a long one");
        assertTrue(cuts.containsAll(quickCuts));
        long quickMs = HwLinkMatrix.estimateMs(quick, quickCuts, true);
        assertTrue(quickMs <= 6 * 60_000L && HwLinkMatrix.estimateMs(quick, quickCuts, false) <= 5 * 60_000L, "quick: about five minutes, " + quickMs / 1000 + " s");
        assertEquals(12_000, HwLinkMatrix.armedCutMs(new HwLinkMatrix.Cut(60_000, BadLink.Way.BOTH)), "the armed part's cut: the window is 10 s");
        assertEquals(3000, HwLinkMatrix.armedCutMs(new HwLinkMatrix.Cut(3000, BadLink.Way.TO_PC)));
    }

    @Test
    void thePilotsSticksAreToldFromThePhonesOwnIdleOnesByTheirRate() {
        assertTrue(HwLinkMatrix.pilotsSticks(19, 100), "188 a second, as measured at the board");
        assertTrue(HwLinkMatrix.pilotsSticks(8, 100));
        assertFalse(HwLinkMatrix.pilotsSticks(2, 100), "the phone's idle sticks: 20 a second");
        assertFalse(HwLinkMatrix.pilotsSticks(0, 100));
        assertFalse(HwLinkMatrix.pilotsSticks(5, 0));
    }

    @Test
    void aCutMayBeginTwoSessionsAndOneMoreEveryFourSecondsThePhoneHearsThePcUnheard() {
        assertEquals(2, HwLinkMatrix.sessionsAtMost(new HwLinkMatrix.Cut(60_000, BadLink.Way.BOTH)));
        assertEquals(2, HwLinkMatrix.sessionsAtMost(new HwLinkMatrix.Cut(60_000, BadLink.Way.TO_PHONE)));
        assertEquals(2, HwLinkMatrix.sessionsAtMost(new HwLinkMatrix.Cut(3000, BadLink.Way.TO_PC)));
        assertEquals(4, HwLinkMatrix.sessionsAtMost(new HwLinkMatrix.Cut(10_000, BadLink.Way.TO_PC)));
        assertEquals(17, HwLinkMatrix.sessionsAtMost(new HwLinkMatrix.Cut(60_000, BadLink.Way.TO_PC)));
    }

    // ------------------------------------------------------------------ the rows

    @Test
    void aRowHasEveryColumnFromTheStartAndItsTableOneHeader(@TempDir Path dir) throws Exception {
        HwLinkMatrix.Row stopped = new HwLinkMatrix.Row(HwLinkMatrix.CUT_COLUMNS).put("cell", "cut 1 s both ways");
        assertEquals(HwLinkMatrix.CUT_COLUMNS.size(), stopped.line().split("\t", -1).length, "a cell that stopped half way writes a whole row");
        assertEquals("-", stopped.get("ready again ms"));
        assertThrows(IllegalArgumentException.class, () -> stopped.put("no such column", 1));
        HwLinkMatrix.Row full = new HwLinkMatrix.Row(HwLinkMatrix.CUT_COLUMNS).put("cell", "cut 3 s both ways").put("what", "one\ttwo\r\nthree").put("ready ms", "");
        assertEquals("one two  three", full.get("what"), "no tab or line break gets into a cell");
        assertEquals("-", full.get("ready ms"));

        Path file = HwLinkMatrix.append(dir, "hw-cuts", stopped);
        HwLinkMatrix.append(dir, "hw-cuts", full);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(String.join("\t", HwLinkMatrix.CUT_COLUMNS), lines.get(0));
        assertTrue(lines.get(2).startsWith("cut 3 s both ways\t"));
        HwLinkMatrix.Row steady = new HwLinkMatrix.Row(HwLinkMatrix.STEADY_COLUMNS).put("cell", "steady clean");
        assertThrows(IllegalStateException.class, () -> HwLinkMatrix.append(dir, "hw-cuts", steady), "never two tables in one file");
        assertEquals(3, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        HwLinkMatrix.append(dir, "hw-steady", steady);
        assertTrue(Files.exists(dir.resolve("hw-steady.tsv")));
        assertEquals("none", HwLinkMatrix.ms(-1));
        assertEquals("120", HwLinkMatrix.ms(120));
    }

    // ------------------------------------------------------------------ the app's link log

    private static final String HEADER = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,fc_data_age_ms,rxloss,failsafe,armed,"
            + "blockers,radio,rc_output,video_fps,throttle,channels,ping_max_ms,video_rx_fps,event";

    @Test
    void theAppsLinkLogIsReadByItsRowsOwnTimes(@TempDir Path logs) throws Exception {
        String rows = HEADER + "\r\n"
                + "10:00:00.000,CONNECTING,-1,0,0,0,0,-1,,,,,1,0,0,,,0,0,\r\n"
                + "10:00:00.100,READY,40,5,22,9,30,20,0,0,0,MSP,1,1,30,1000,1500 1500 1000 1500 1000,45,30,\r\n"
                + "10:00:00.200,READY,60,5,21,9,35,20,0,0,0,MSP,1,1,30,1000,1500 1500 1000 1500 1000,60,29.5,\r\n"
                + "10:00:00.250,,,,,,,,,,,,,,,,,,,\"Link: stalled, \"\"300 ms\"\"\"\r\n"
                + "10:00:00.300,READY,-1,0,400,0,400,400,1,0,0,RXLOSS MSP,1,1,0,1000,1500 1500 1000 1500 1000,60,0,\r\n"
                + "10:00:00.400,CONNECTING,-1,0,0,0,0,-1,,,,,1,0,0,,,0,,\r\n"
                + "10:00:00.500,READY,50,5,20,9,30,20,0,0,1,,1,1,30,1000,1500 1500 1000 1500 2000,50,30,\r\n"
                + "10:00:00.6"; // the row being written
        Files.writeString(logs.resolve("link-20261123-100000.csv"), rows, StandardCharsets.UTF_8);
        Files.writeString(logs.resolve("app-2026-11-23.log"), "not a link log", StandardCharsets.UTF_8);
        List<AppLinkLog.Row> all = AppLinkLog.read(logs, System.currentTimeMillis() - 60_000);
        assertEquals(6, all.size(), "the sample rows; the event's row and the half-written one left out: " + all);
        assertEquals(List.of(), AppLinkLog.read(logs, System.currentTimeMillis() + 60_000), "a log not written since the cell began is not read");
        assertEquals(29.5, all.get(2).videoFps(), "the frames received, where the row has them");

        AppLinkLog.Figures f = AppLinkLog.figures(AppLinkLog.between(all, LocalTime.parse("10:00:00.100"), LocalTime.parse("10:00:00.500")));
        assertEquals(5, f.rows());
        assertEquals(80, f.readyPercent());
        assertEquals(1, f.leftReady());
        assertEquals(50, f.pingMedian());
        assertEquals(60, f.pingMax());
        assertEquals(400, f.telemetryGapMax());
        assertEquals(3, f.pictureRows());
        assertEquals(1, f.appRxloss());
        assertEquals(1, f.appArmed());
        assertTrue(f.words().contains("LINK READY in 80 % of 5 rows"), f.words());

        assertEquals(200, AppLinkLog.first(all, LocalTime.parse("10:00:00.300"), AppLinkLog.Row::picture), "the picture back 200 ms after the moment");
        assertEquals(-1, AppLinkLog.first(all, LocalTime.parse("10:00:00.600"), AppLinkLog.Row::picture), "none yet");
        assertEquals(400, AppLinkLog.msBetween(LocalTime.parse("23:59:59.800"), LocalTime.parse("00:00:00.200")), "across midnight, the short way");
        assertEquals(-400, AppLinkLog.msBetween(LocalTime.parse("00:00:00.200"), LocalTime.parse("23:59:59.800")));
        assertEquals(List.of("a", "b, \"c\"", "", "d"), AppLinkLog.cells("a,\"b, \"\"c\"\"\",,d"));
        assertEquals(0, AppLinkLog.figures(List.of()).rows());
    }

    // ------------------------------------------------------------------ the board's word

    private static KeeperClient.State said(String line) {
        return KeeperClient.State.of("OK " + line);
    }

    @Test
    void theBoardsWordIsReadLookByLook() {
        BoardTrace t = new BoardTrace();
        t.add(1000, said("armed=0 blocks=MSP window=0 lastEnd=-"));
        t.add(1100, said("armed=1 blocks=none window=9000 lastEnd=-"));
        t.add(1200, said("armed=1 blocks=RXLOSS window=8900 lastEnd=-"));
        t.add(1300, said("armed=0 blocks=MSP+RXLOSS window=0 lastEnd=RECEIVER_LOST:_the_board_says_RXLOSS"));
        t.add(1400, said("armed=0 blocks=MSP+BOXFAILSAFE window=0 lastEnd=RECEIVER_LOST:_the_board_says_RXLOSS"));
        t.add(1500, said("armed=1 blocks=MSP window=0 lastEnd=RECEIVER_LOST:_the_board_says_RXLOSS"));
        assertEquals(200, t.first("RXLOSS", 1000));
        assertEquals(300, t.last("RXLOSS", 1000));
        assertFalse(t.said("FAILSAFE", 1000), "BOXFAILSAFE is another block than FAILSAFE");
        assertEquals(16, t.percent("BOXFAILSAFE", 1000), "one look in six");
        assertEquals(33, t.percent("RXLOSS", 1000));
        assertTrue(t.everArmed(1000));
        assertEquals(300, t.disarmed(1000, 2000), "disarmed at the first look that says so after an armed one");
        assertEquals(-1, t.disarmed(1000, 1300), "not before that moment");
        assertTrue(t.armedAgain(1300, 2000), "armed again after it");
        assertFalse(t.armedAgain(1300, 1500));
        assertEquals("RECEIVER LOST: the board says RXLOSS", t.keeperEnded(1000));
        assertNull(t.keeperEnded(1600));
        assertTrue(t.words(1000).startsWith("RXLOSS at 200 ms"), t.words(1000));
        assertEquals("neither RXLOSS nor FAILSAFE", new BoardTrace().words(0));
    }

    // ------------------------------------------------------------------ the armed part

    @Test
    void withoutTheHardwareFilesWordTheArmedPartIsSkippedAndSaysWhy() {
        HwLinkMatrix.Cut longCut = new HwLinkMatrix.Cut(60_000, BadLink.Way.BOTH), shortCut = new HwLinkMatrix.Cut(1000, BadLink.Way.BOTH);
        String no = HwLinkMatrix.armedSkip(false, null, 2000, shortCut);
        assertTrue(no != null && no.contains("board.mayArm=true"), no);
        assertTrue(HwLinkMatrix.armedSkip(false, "the keeper is silent", 0, longCut).contains("board.mayArm=true"), "the hardware file's word first");
        assertNull(HwLinkMatrix.armedSkip(true, null, 2000, longCut), "with the word, and the keeper ending a window on a cut: every cut");
        assertNull(HwLinkMatrix.armedSkip(true, null, -1, longCut), "a keeper that knows no failsafe_delay refuses the window itself, and says why");
        assertTrue(HwLinkMatrix.armedSkip(true, "the keeper is silent", 2000, shortCut).contains("the keeper is silent"));
        String letRun = HwLinkMatrix.armedSkip(true, null, 0, new HwLinkMatrix.Cut(10_000, BadLink.Way.TO_PC));
        assertTrue(letRun != null && letRun.contains("board.letFailsafeRun"), "the board's own failsafe let run: a cut that outlasts the window is not armed for: " + letRun);
        assertNull(HwLinkMatrix.armedSkip(true, null, 0, new HwLinkMatrix.Cut(3000, BadLink.Way.BOTH)));
    }

    private static HwLinkMatrix.ArmedVerdict judge(double gap, long failsafeAt, long limit, boolean failsafe, boolean rxloss, String keeper, long disarmed,
                                                   boolean rearmed, boolean switchOn) {
        return HwLinkMatrix.judge(new HwLinkMatrix.ArmedFacts(gap, failsafeAt, limit, failsafe, rxloss, keeper, disarmed, rearmed, switchOn));
    }

    @Test
    void theArmedPartHoldsTheBoardToTheRulesOfAShortAndALongCut() {
        // sticks kept (a cut from the phone to the PC): armed to the end, nothing said
        HwLinkMatrix.ArmedVerdict kept = judge(30, 20_000, 2000, false, false, null, -1, false, true);
        assertEquals(List.of(), kept.wrong());
        assertTrue(kept.endedBy().startsWith("nothing"), kept.endedBy());
        assertEquals(1, judge(30, 20_000, 2000, false, false, null, 400, false, true).wrong().size(), "sticks kept and disarmed: wrong");
        assertEquals(1, judge(30, 20_000, 2000, true, false, null, -1, false, true).wrong().size(), "sticks kept and FAILSAFE: wrong");

        // the real board (failsafe_delay 20 s), the keeper's rule: a 1 s cut ended by the keeper on RXLOSS, no FAILSAFE - right
        HwLinkMatrix.ArmedVerdict keeper = judge(1100, 20_000, 2000, false, true, "RECEIVER LOST: the board says RXLOSS", 300, false, false);
        assertEquals(List.of(), keeper.wrong());
        assertTrue(keeper.endedBy().startsWith("the keeper: RECEIVER LOST"), keeper.endedBy());
        assertTrue(String.join(" ", keeper.notes()).contains("no re-arm"), keeper.notes().toString());
        // ... but FAILSAFE after a second: no failsafe for a short cut
        assertTrue(judge(1100, 20_000, 2000, true, true, "RECEIVER LOST", 300, false, false).wrong().get(0).startsWith("no failsafe for a short cut"));
        // a long cut (past the keeper's 2 s) and the board armed all through: neither a failsafe nor the keeper's end
        List<String> stillArmed = judge(3200, 20_000, 2000, false, true, null, -1, false, true).wrong();
        assertEquals(1, stillArmed.size(), stillArmed.toString());
        assertTrue(stillArmed.get(0).contains("still armed"), stillArmed.get(0));
        // the board's own failsafe let run (failsafe_delay 1.5 s here): a short cut leaves it armed, a long one has its FAILSAFE
        assertEquals(List.of(), judge(500, 1500, 0, false, true, null, -1, false, true).wrong(), "half a second: armed through it, as stage 1 holds");
        assertTrue(judge(500 + 0.0, 20_000, 0, false, true, null, 200, false, true).wrong().get(0).startsWith("a short cut"), "with the failsafe let run, a short cut must not disarm");
        assertEquals(List.of(), judge(3000, 1500, 0, true, true, null, 1600, false, true).wrong(), "a long one: its FAILSAFE, disarmed");
        assertEquals(List.of(), judge(3000, 1500, 0, true, true, null, -1, false, true).wrong(), "or armed still in its stage 2 (a rescue): a failsafe all the same");
        // and never a re-arm
        List<String> rearmed = judge(3000, 1500, 0, true, true, null, 1600, true, true).wrong();
        assertEquals(1, rearmed.size());
        assertTrue(rearmed.get(0).startsWith("RE-ARMED by itself") && rearmed.get(0).contains("the ARM switch never off"), rearmed.get(0));
    }

    /**
     * The armed part's pieces on the made-up board set as the rig's real board is (GPS rescue after 20 s, the throttle
     * and the ARM switch held meanwhile) behind a real keeper that ends a window on a cut: armed in a window, the
     * phone's frames kept from the board for 1.2 s - the board's word sampled as the scenario samples it, and judged.
     */
    @Test
    void onTheMadeUpBoardACutWhileArmedIsEndedByTheKeeperAndJudgedRight() throws Exception {
        FakeFlightBoard board = KeeperBench.armable();
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 200", "set failsafe_throttle_low_delay = 200", "rxfail 3 h", "rxfail 4 h");
        board.modelsRxLoss = true;
        try (KeeperBench bench = new KeeperBench(board)) {
            bench.config.failsafeEndsWindow = true;
            bench.start().beat();
            try (StandInPhone phone = bench.phone().sticks(1000, 1000).startSticks()) {
                long before = bench.sticksAtBoard();
                Waits.waitFor(() -> bench.sticksAtBoard() > before + 30, 5000, "stick frames at the board", bench.said);
                assertEquals("OK window=10000", bench.ask("OPEN 10"));
                BoardTrace trace = new BoardTrace();
                long cutAt, until;
                try (BoardTrace.Sampler look = trace.sample(bench.keeper.controlPort(), 20)) {
                    bench.arm(phone);
                    assertTrue(bench.ask("MARK").startsWith("OK"));
                    Thread.sleep(200);
                    cutAt = System.currentTimeMillis();
                    assertEquals("OK", bench.ask("FAULT starve")); // the phone's frames no longer reach the board: a cut
                    Thread.sleep(1200);
                    assertEquals("OK", bench.ask("FAULT clear"));
                    until = System.currentTimeMillis();
                    Thread.sleep(500);
                    assertNull(look.failure(), "the keeper answered every look");
                }
                KeeperClient.State fig = KeeperClient.State.of(bench.ask("FIGURES")), st = bench.state();
                long disarmed = trace.disarmed(cutAt, until);
                HwLinkMatrix.ArmedFacts f = new HwLinkMatrix.ArmedFacts(fig.real("maxGapMs"), st.number("failsafeDelay") * 100L, st.number("stickLimitMs"),
                        trace.said("FAILSAFE", cutAt), trace.said("RXLOSS", cutAt), trace.keeperEnded(cutAt), disarmed,
                        disarmed >= 0 && trace.armedAgain(cutAt + disarmed, until), false);
                HwLinkMatrix.ArmedVerdict v = HwLinkMatrix.judge(f);
                assertEquals(List.of(), v.wrong(), f + " " + trace.samples());
                assertTrue(f.stickGapMs() >= 1000, "the cut is the largest stick gap at the board: " + f.stickGapMs());
                assertEquals(20_000, f.failsafeAtMs());
                assertEquals(2000, f.stickLimitMs());
                assertTrue(f.rxlossSeen() && !f.failsafeSeen(), trace.words(cutAt));
                assertTrue(disarmed >= 0 && disarmed < 1500, "disarmed by the keeper's lock on the board's RXLOSS: " + disarmed + " ms into the cut");
                assertTrue(f.keeperEnded().startsWith("RECEIVER LOST"), f.keeperEnded());
                assertTrue(v.endedBy().startsWith("the keeper: RECEIVER LOST"), v.endedBy());
                assertFalse(f.rearmed());
                assertEquals(0, board.stage2(), "the board's own failsafe never ran");
                assertTrue(bench.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));
            }
        }
    }
}
