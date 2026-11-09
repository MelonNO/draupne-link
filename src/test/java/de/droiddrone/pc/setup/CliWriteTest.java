package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CliWriteTest {
    /**
     * Betaflight's CLI as far as Drone setup's write goes: "aux" as cliAux does it in 4.4, 4.5.2 and 2025.12.5 (an
     * unknown mode id keeps the slot's old mode, no error; the slot printed back), "aux" alone lists the slots, "set"
     * takes the names it knows and answers ###ERROR for the rest, anything else is taken silently.
     */
    static final class FakeBetaflight implements CliWrite.Cli {
        final int[][] slots = new int[20][6]; // mode, channel, start, end, logic, linked (all 0: unused, ARM 900-900)
        final Set<Integer> boxes; // the permanent mode ids this build knows
        final Set<String> settings;
        final List<String> received = new ArrayList<>();
        /** 1.0.48's plan never asked, but a board might not print the slot back: then only the read-back sees it. */
        boolean echo = true;

        FakeBetaflight(Set<Integer> boxes, Set<String> settings) {
            this.boxes = boxes;
            this.settings = settings;
            for (int[] s : slots) {
                s[2] = 900;
                s[3] = 900;
            }
        }

        FakeBetaflight with(int slot, int mode, int channel, int start, int end) {
            slots[slot] = new int[]{mode, channel, start, end, 0, 0};
            return this;
        }

        @Override
        public String command(String command) throws IOException {
            received.add(command);
            String[] p = command.trim().split("\\s+");
            if (p[0].equals("aux") && p.length == 1) {
                StringBuilder sb = new StringBuilder("# aux\r\n");
                for (int i = 0; i < slots.length; i++) sb.append(line(i)).append("\r\n");
                return sb.toString().trim();
            }
            if (p[0].equals("aux")) {
                int i = Integer.parseInt(p[1]);
                int[] mac = slots[i];
                int valid = 0;
                if (p.length > 2 && boxes.contains(Integer.parseInt(p[2]))) {
                    mac[0] = Integer.parseInt(p[2]);
                    valid++;
                }
                if (p.length > 3) {
                    mac[1] = Integer.parseInt(p[3]);
                    valid++;
                }
                if (p.length > 5) {
                    mac[2] = Integer.parseInt(p[4]);
                    mac[3] = Integer.parseInt(p[5]);
                    valid += 2;
                }
                if (p.length > 6) valid++;
                if (p.length > 7) valid++;
                if (valid == 4 || valid == 5) {
                    mac[4] = 0;
                    mac[5] = 0;
                } else if (valid != 6) {
                    slots[i] = new int[]{0, 0, 900, 900, 0, 0};
                }
                return echo ? line(i) : "";
            }
            if (p[0].equals("set")) {
                return settings.contains(p[1]) ? p[1] + " set to " + p[p.length - 1] : "###ERROR IN set: INVALID NAME: " + p[1] + "###";
            }
            return "";
        }

        private String line(int i) {
            int[] s = slots[i];
            return "aux " + i + " " + s[0] + " " + s[1] + " " + s[2] + " " + s[3] + " " + s[4] + " " + s[5];
        }

        boolean got(String command) {
            return received.contains(command);
        }
    }

    private static final Set<Integer> BOXES = Set.of(0, 1, 2, 13, 36, 46); // no POS HOLD (11): a 4.5 build
    private static final Set<String> SETTINGS = Set.of("serial_update_rate_hz", "failsafe_procedure");

    @Test
    void aWriteTakenAsSentIsComplete() throws IOException {
        FakeBetaflight fc = new FakeBetaflight(BOXES, SETTINGS).with(0, 0, 0, 1700, 2100);
        List<String> log = new ArrayList<>();
        CliWrite.Result r = CliWrite.run(fc, List.of("set serial_update_rate_hz = 2000", "aux 1 1 1 1300 1700 0 0",
                "aux 2 13 13 1700 2100 0 0"), log::add);
        assertTrue(r.complete(), r.stopped());
        assertTrue(fc.got("aux"), "the modes read back after the write");
        assertTrue(r.auxAfter().contains("aux 2 13 13 1700 2100 0 0"));
        assertEquals("Betaflight CLI: aux 1 1 1 1300 1700 0 0 -> aux 1 1 1 1300 1700 0 0", log.get(1));
    }

    @Test
    void aRefusalStopsTheWriteAndNothingAfterItIsSent() throws IOException {
        // 1.0.48 sent the rest and saved: here ARM is cleared, then the new ARM would come after a refused setting
        FakeBetaflight fc = new FakeBetaflight(BOXES, SETTINGS).with(0, 0, 2, 1700, 2100);
        CliWrite.Result r = CliWrite.run(fc, List.of("aux 0 0 0 900 900 0 0", "set gps_rescue_allow_arming_without_fix = ON",
                "aux 0 0 0 1700 2100 0 0"), l -> {});
        assertFalse(r.complete());
        assertTrue(r.stopped().contains("refused \"set gps_rescue_allow_arming_without_fix = ON\""), r.stopped());
        assertTrue(r.stopped().contains("INVALID NAME"), r.stopped());
        assertFalse(fc.got("aux 0 0 0 1700 2100 0 0"), "nothing after the refusal");
        assertFalse(fc.got("aux"), "not read back either: the caller leaves without saving");
    }

    @Test
    void aSlotThatKeptArmStopsTheWrite() throws IOException {
        // ARM up on AUX2 in slot 0, and the plan puts POS HOLD there - which this build doesn't know. Betaflight keeps
        // the mode it had: with 1.0.48's plan (no clear) ARM sits on the flight mode switch's top; with the clear first
        // it is ARM 0 again. Either way the echo shows ARM, and the write stops
        for (List<String> commands : List.of(List.of("aux 0 11 1 1700 2100 0 0"),
                List.of("aux 0 0 0 900 900 0 0", "aux 0 11 1 1700 2100 0 0"))) {
            FakeBetaflight fc = new FakeBetaflight(BOXES, SETTINGS).with(0, 0, 1, 1700, 2100);
            List<String> sent = new ArrayList<>(commands);
            sent.add("aux 1 1 1 1300 1700 0 0");
            CliWrite.Result r = CliWrite.run(fc, sent, l -> {});
            assertFalse(r.complete());
            assertTrue(r.stopped().contains("Betaflight has ARM on AUX2 1700-2100"), r.stopped());
            assertFalse(fc.got("aux 1 1 1 1300 1700 0 0"));
        }
    }

    @Test
    void withoutAnEchoTheReadBackFindsIt() throws IOException {
        FakeBetaflight fc = new FakeBetaflight(BOXES, SETTINGS).with(0, 0, 1, 1700, 2100);
        fc.echo = false;
        CliWrite.Result r = CliWrite.run(fc, List.of("aux 0 0 0 900 900 0 0", "aux 0 11 1 1700 2100 0 0"), l -> {});
        assertFalse(r.complete());
        assertTrue(r.stopped().startsWith("Betaflight's modes after the write aren't as sent"), r.stopped());
    }

    @Test
    void aWholePlanGoesThroughOnABoardThatKnowsItsModes() throws IOException {
        // the plan as Drone setup makes it (ARM moved off AUX14, BEEPER there), against the fake board: all of it taken
        String read = "aux 0 0 13 1700 2100 0 0\naux 1 13 3 1700 2100 0 0\n";
        ModeSwitch.Plan p = ModeSwitch.plan(read, BOXES, Map.of(), ModeSwitch.Switches.of(null).arm(1).beeper(ModeSwitch.BEEPER_AUX));
        FakeBetaflight fc = new FakeBetaflight(BOXES, SETTINGS).with(0, 0, 13, 1700, 2100).with(1, 13, 3, 1700, 2100);
        CliWrite.Result r = CliWrite.run(fc, p.commands(), l -> {});
        assertTrue(r.complete(), r.stopped());
        de.droiddrone.pc.ArmSwitch arm = de.droiddrone.pc.ArmSwitch.fromCli(r.auxAfter());
        assertEquals(4, arm.channel(), "ARM on AUX1 (channel 4) as written - what the sticks are told, not AUX14 as read before");
        assertEquals(java.util.Set.of(1), ModeSwitch.armAuxes(r.auxAfter()));
    }

    @Test
    void noBackupMeansNoWrite() {
        assertTrue(CliWrite.usableBackup("# version\n# Betaflight / STM32F405 (S405) 4.5.2 ...\n\n# start the command batch\nbatch start\n"));
        assertFalse(CliWrite.usableBackup(""));
        assertFalse(CliWrite.usableBackup(null));
        assertFalse(CliWrite.usableBackup("###ERROR IN diff: ...###"));
    }
}
