package de.droiddrone.bench.link;

import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.LinkBench;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.pc.fleet.FlightModel;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.droiddrone.bench.LinkBench.timeTo;
import static de.droiddrone.bench.LinkBench.within;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The bad-link table: the PC's real link and a made-up drone with each kind of bad network between them, and what
 * must hold on every one of them. Two halves:
 * <ul>
 * <li><b>steady</b> - the network is bad in one way from before the connect: the link comes up, the drone arms on the
 * switch, stays armed for 12 s (no failsafe of the network's making), the picture flows, the switch disarms it.</li>
 * <li><b>cuts</b> - a good network that goes away for 1 s to a minute while the drone is armed, both ways or one way:
 * no failsafe for a short one, a failsafe for a long one, the link back by itself, <i>no re-arm with the switch still
 * on</i>, the picture back, and the pilot in control again after cycling the switch.</li>
 * </ul>
 * Every cell writes a row to matrix/steady.tsv or matrix/cuts.tsv in the run's folder and its two logs beside them;
 * the safety rules of {@link LinkBench} are watched throughout. {@code -Dbench.quick=true} runs a few cells only.
 */
@Tag("badlink")
class LinkMatrixScenario {
    private static final boolean QUICK = Boolean.getBoolean("bench.quick");
    private static final long SEED = Long.getLong("bench.seed", 20261119L);
    /** Cells whose failure is a listed finding (docs/BENCH-FINDINGS.md): reported as such, not as a new failure. */
    private static final Map<String, String> KNOWN = Map.of();

    @TestFactory
    List<DynamicTest> steady() {
        List<DynamicTest> tests = new ArrayList<>();
        for (BadLink net : BadLink.steady(SEED, QUICK)) tests.add(DynamicTest.dynamicTest("steady: " + net.name, () -> steadyCell(net)));
        return tests;
    }

    @TestFactory
    List<DynamicTest> cuts() {
        record Cut(long ms, BadLink.Way way) {
        }
        List<Cut> all = QUICK ? List.of(new Cut(1000, BadLink.Way.BOTH), new Cut(3000, BadLink.Way.BOTH))
                : List.of(new Cut(1000, BadLink.Way.BOTH), new Cut(3000, BadLink.Way.BOTH), new Cut(6000, BadLink.Way.BOTH),
                new Cut(15_000, BadLink.Way.BOTH), new Cut(60_000, BadLink.Way.BOTH), new Cut(6000, BadLink.Way.TO_PHONE), new Cut(6000, BadLink.Way.TO_PC),
                // the phone's way back gone for less than the 4 s the PC's own numbering lives: the PC is "connected"
                // again at the first packet, on its old numbering, while the phone began a new session at the
                // PC's Connect - a review of 2026-11-20 reads LINK READY 1 to 2 s late out of the code; no row had it
                new Cut(3000, BadLink.Way.TO_PC));
        List<DynamicTest> tests = new ArrayList<>();
        for (Cut c : all) {
            String name = "cut " + c.ms() / 1000 + " s " + switch (c.way()) {
                case BOTH -> "both ways";
                case TO_PHONE -> "PC to phone only";
                case TO_PC -> "phone to PC only";
            };
            tests.add(DynamicTest.dynamicTest(name, () -> cutCell(name, c.ms(), c.way())));
        }
        return tests;
    }

    /** Ready, the sticks through, Betaflight's start-up grace over. */
    private static boolean bringUp(LinkBench b, Map<String, String> row, List<String> wrong, long readyMaxMs) throws InterruptedException {
        long ready = timeTo(readyMaxMs, b::ready);
        row.put("ready ms", Long.toString(ready));
        if (ready < 0) {
            wrong.add("the link did not come up within " + readyMaxMs / 1000 + " s");
            return false;
        }
        if (!within(10_000, () -> de.droiddrone.pc.fleet.BenchFleetAccess.hadSticks(b.drone))) wrong.add("no sticks reached the flight controller within 10 s");
        Thread.sleep(FlightModel.BOOT_GRACE_MS + 800);
        return true;
    }

    private void steadyCell(BadLink net) throws Exception {
        Map<String, String> row = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        row.put("network", net.name);
        try (LinkBench b = new LinkBench(net).start(0)) {
            try {
                if (bringUp(b, row, wrong, 40_000)) {
                    // as a pilot arms: switch on; not armed after 5 s - off for a moment and on again, four times at most
                    long armStart = System.currentTimeMillis(), arm = -1;
                    int tries = 0;
                    while (arm < 0 && tries < 4) {
                        tries++;
                        b.armSwitch(true);
                        if (within(5000, b::armed)) arm = System.currentTimeMillis() - armStart;
                        else b.cycleSwitchOff();
                    }
                    row.put("arm ms", Long.toString(arm));
                    row.put("switch tries", Integer.toString(tries));
                    if (arm < 0) wrong.add("the drone did not arm: the switch was put on four times, 5 s each");
                    long framesBefore = b.frames.get(), lossesBefore = b.linkLosses.get();
                    Thread.sleep(12_000);
                    long disarms = b.failsafeDisarms.get() + b.otherDisarms.get();
                    row.put("disarmed by itself", Long.toString(disarms));
                    if (arm >= 0 && disarms > 0) wrong.add("armed and held for 12 s, the drone was disarmed " + disarms + " time(s) by the network alone");
                    long frames = b.frames.get() - framesBefore;
                    row.put("frames in 12 s", Long.toString(frames));
                    if (frames == 0) wrong.add("no picture at all in 12 s");
                    row.put("link lost", Long.toString(b.linkLosses.get() - lossesBefore));
                    row.put("ping ms", Integer.toString(b.link.getPing()));
                    row.put("tier", String.valueOf(b.link.getTier()));
                    boolean stillArmed = b.armed();
                    b.armSwitch(false);
                    long disarm = stillArmed ? timeTo(8000, () -> !b.armed()) : 0;
                    row.put("disarm ms", Long.toString(disarm));
                    if (disarm < 0) wrong.add("the switch off did not disarm the drone within 8 s");
                    net.heal();
                    if (!within(20_000, b::ready)) wrong.add("the link is not READY within 20 s of the network being good again");
                }
            } finally {
                finishRow(b, row, wrong, "steady", net.name);
            }
        }
        verdict("steady: " + net.name, wrong);
    }

    private void cutCell(String name, long cutMs, BadLink.Way way) throws Exception {
        Map<String, String> row = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        row.put("cut", name);
        BadLink net = BadLink.clean(SEED);
        try (LinkBench b = new LinkBench(net).start(0)) {
            try {
                if (bringUp(b, row, wrong, 20_000)) {
                    b.armSwitch(true);
                    if (!within(10_000, b::armed)) wrong.add("the drone did not arm before the cut");
                    Thread.sleep(2500);
                    long sessionsBefore = b.sessions(), lossesBefore = b.linkLosses.get();
                    b.note("CUT " + cutMs + " ms, " + way);
                    net.cut(cutMs, way);
                    Thread.sleep(cutMs + 300);
                    b.note("the network is back");
                    boolean armedAfterCut = b.armed();
                    long failsafes = b.failsafeDisarms.get();
                    row.put("armed after the cut", armedAfterCut ? "yes" : "no");
                    row.put("failsafe", Long.toString(failsafes));
                    boolean sticksCut = way != BadLink.Way.TO_PC;
                    // Betaflight as modelled: sticks held 300 ms, stage 1 for 1.5 s, then the motors go
                    long failsafeAt = FlightModel.HOLD_MS + FlightModel.FAILSAFE_DELAY_MS;
                    if (sticksCut && cutMs >= failsafeAt + 700 && armedAfterCut) wrong.add("no sticks for " + cutMs + " ms and the drone is still armed: no failsafe");
                    if (sticksCut && cutMs <= failsafeAt - 700 && !armedAfterCut) wrong.add("a " + cutMs + " ms cut disarmed the drone (failsafe is due only after " + failsafeAt + " ms)");
                    long back = timeTo(20_000, b::ready);
                    row.put("ready again ms", Long.toString(back));
                    if (back < 0) wrong.add("the link did not come back within 20 s of the network");
                    if (way == BadLink.Way.TO_PC && cutMs < 4000 && back > 1000) {
                        wrong.add("LINK READY came " + back + " ms after the phone was heard again (the cut was shorter than the PC's own 4 s: nothing had to be begun anew)");
                    }
                    // the switch is still ON: three seconds in which a drone that failed safe must stay disarmed
                    Thread.sleep(3000);
                    if (!armedAfterCut && b.armed()) wrong.add("RE-ARMED by itself after the failsafe, the switch never off");
                    row.put("link lost", Long.toString(b.linkLosses.get() - lossesBefore));
                    long sessions = b.sessions() - sessionsBefore;
                    row.put("new sessions", Long.toString(sessions));
                    // F-001, the pilot's rule: one when the link goes, one when it is back (a new id every 4 s while
                    // the phone is not heard at all, every second when it is)
                    if (sessions > 2) wrong.add(sessions + " new sessions for one cut (two at most)");
                    long framesBefore = b.frames.get();
                    long picture = timeTo(20_000, () -> b.frames.get() > framesBefore + 5);
                    row.put("picture again ms", Long.toString(picture));
                    if (picture < 0) wrong.add("no picture within 20 s of the link being back");
                    // the pilot takes it again: switch off, on - it arms; off - it disarms
                    b.cycleSwitchOff();
                    if (b.armed() && !within(5000, () -> !b.armed())) wrong.add("the switch off did not disarm the drone after the cut");
                    b.armSwitch(true);
                    long rearm = timeTo(10_000, b::armed);
                    row.put("arms again ms", Long.toString(rearm));
                    if (rearm < 0) wrong.add("after the switch went off and on the drone did not arm again within 10 s: " + de.droiddrone.pc.fleet.BenchFleetAccess.failsafe(b.drone));
                    b.armSwitch(false);
                    if (!within(8000, () -> !b.armed())) wrong.add("the switch off did not disarm the drone at the end");
                }
            } finally {
                finishRow(b, row, wrong, "cuts", name);
            }
        }
        verdict(name, wrong);
    }

    private static void finishRow(LinkBench b, Map<String, String> row, List<String> wrong, String table, String cell) {
        for (String v : b.violations) wrong.add("SAFETY: " + v);
        List<String> errors = b.errors();
        if (!errors.isEmpty()) wrong.add(errors.size() + " error line(s) in the logs, the first: " + errors.get(0));
        row.put("network counts", b.net.counts());
        row.put("verdict", wrong.isEmpty() ? "ok" : KNOWN.containsKey(cell) ? "KNOWN " + KNOWN.get(cell) : "WRONG");
        row.put("what", String.join("; ", wrong));
        try {
            Path dir = Files.createDirectories(Sandbox.runRoot().resolve("matrix"));
            Path file = dir.resolve(table + ".tsv");
            if (!Files.exists(file)) Files.writeString(file, String.join("\t", row.keySet()) + "\n", StandardCharsets.UTF_8);
            Files.writeString(file, String.join("\t", row.values()) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Path logs = Files.createDirectories(dir.resolve("logs"));
            Files.writeString(logs.resolve((table + "-" + cell).replaceAll("[^A-Za-z0-9._-]+", "_") + ".log"), String.join("\n", b.log) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void verdict(String cell, List<String> wrong) {
        if (wrong.isEmpty()) return;
        String known = KNOWN.get(cell.replace("steady: ", ""));
        if (known != null) org.junit.jupiter.api.Assumptions.abort("Known finding " + known + ": " + String.join("; ", wrong));
        fail(cell + ": " + String.join("; ", wrong));
    }
}
