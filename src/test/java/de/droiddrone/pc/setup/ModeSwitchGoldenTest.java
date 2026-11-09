package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ModeSwitch.plan over a grid of Betaflight "aux" lists and targets, against the plans as 1.0.53 made them (the review's
 * rewrite of plan into one slot-clearing helper had to write the same CLI lines in the same order). A change to the
 * plans on purpose: run with GOLDEN_DUMP=&lt;file&gt; before and after, diff the two, then put the new hash here.
 */
class ModeSwitchGoldenTest {
    private static final String RECORDED = "0aba0d7e2247edf0e38c4fd46fd93f922366c90253d04a1d1d61d62af3ed2b59";

    /** 20 slots, these set, the rest empty; 6 fields per line for an old Betaflight. */
    private static String aux(int fields, String... used) {
        StringBuilder sb = new StringBuilder("# aux\n");
        for (int i = 0; i < 20; i++) {
            String line = "aux " + i + " 0 0 900 900" + (fields == 7 ? " 0 0" : " 0");
            for (String u : used) if (u.startsWith("aux " + i + " ")) line = u;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> auxLists() {
        List<String> out = new ArrayList<>();
        out.add(aux(7));
        out.add(aux(7, "aux 0 0 0 1700 2100 0 0"));
        out.add(aux(7, "aux 0 0 1 1700 2100 0 0", "aux 1 0 1 900 1300 0 0"));
        out.add(aux(7, "aux 0 0 0 1700 2100 0 0", "aux 1 36 2 1700 2100 0 0", "aux 2 2 2 1300 1700 0 0"));
        out.add(aux(7, "aux 0 0 0 1700 2100 0 0", "aux 3 2 1 1700 2100 0 0", "aux 5 1 1 900 1300 0 0", "aux 6 1 4 900 1300 0 0"));
        out.add(aux(7, "aux 0 0 0 1700 2100 0 0", "aux 4 11 1 1700 2100 0 0", "aux 7 1 1 1300 1700 0 0", "aux 8 46 3 1700 2100 0 0"));
        out.add(aux(7, "aux 0 0 13 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0", "aux 2 13 1 1700 2100 0 0"));
        out.add(aux(7, "aux 0 0 0 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0", "aux 2 28 13 900 1300 0 0"));
        out.add(aux(7, "aux 0 36 0 1700 2100 0 0", "aux 1 0 2 1700 2100 0 0", "aux 2 46 2 1300 1700 0 0"));
        out.add(aux(7, "aux 0 0 2 1700 2100 0 0", "aux 1 36 2 1300 1700 0 0", "aux 2 0 0 1700 2100 0 0"));
        out.add(aux(6, "aux 0 0 0 1700 2100 0", "aux 2 1 1 1300 1700 0"));
        String[] full = new String[20];
        for (int i = 0; i < 20; i++) full[i] = "aux " + i + " 28 " + (i % 8) + " 900 1300 0 0";
        out.add(aux(7, full));
        String[] almost = new String[18];
        for (int i = 0; i < 18; i++) almost[i] = "aux " + i + " " + (i == 0 ? 0 : 26) + " " + (i == 0 ? 0 : 5) + " 900 1300 0 0";
        out.add(aux(7, almost));
        return out;
    }

    /** The plan in a fixed order (blocked sorted): every field. */
    private static String canonical(ModeSwitch.Plan p) {
        return "commands=" + p.commands() + "\nproblem=" + p.problem() + "\nrescueProblem=" + p.rescueProblem()
                + "\nmissing=" + p.missing() + "\nblocked=" + new TreeMap<>(p.blocked()) + "\nelsewhere=" + p.elsewhere()
                + "\nreplaced=" + p.replaced() + "\narmProblem=" + p.armProblem() + "\nprearmProblem=" + p.prearmProblem()
                + "\ndoubled=" + p.doubled() + "\nbeeperProblem=" + p.beeperProblem() + "\n";
    }

    static String grid() {
        List<Set<Integer>> available = new ArrayList<>();
        available.add(Set.of(0, 1, 2, 11, 13, 36, 46));
        available.add(null);
        available.add(Set.of(0, 1, 2, 36));
        List<Map<ModeSwitch.Mode, String>> blocked = List.of(Map.of(),
                Map.of(ModeSwitch.Mode.GPS_RESCUE, "No GPS found."), Map.of(ModeSwitch.Mode.POS_HOLD, "Too old."));
        List<ModeSwitch> modes = new ArrayList<>();
        modes.add(null);
        modes.add(ModeSwitch.DEFAULT);
        modes.add(new ModeSwitch(3, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.POS_HOLD, ModeSwitch.Mode.GPS_RESCUE));
        modes.add(new ModeSwitch(1, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.ANGLE));
        int[] rescues = {0, 4, 2};
        int[] arms = {0, 1, 2, 3};
        int[] prearms = {0, 3, 1, 2};
        int[] beepers = {0, ModeSwitch.BEEPER_AUX};
        StringBuilder sb = new StringBuilder();
        List<String> auxLists = auxLists();
        for (int a = 0; a < auxLists.size(); a++) {
            for (int v = 0; v < available.size(); v++) {
                for (int b = 0; b < blocked.size(); b++) {
                    for (ModeSwitch m : modes) {
                        for (int rescue : rescues) {
                            for (int arm : arms) {
                                for (int prearm : prearms) {
                                    for (int beeper : beepers) {
                                        sb.append("# aux ").append(a).append(" available ").append(v).append(" blocked ").append(b)
                                                .append(" modes ").append(m == null ? "-" : m.format()).append(" rescue ").append(rescue)
                                                .append(" arm ").append(arm).append(" prearm ").append(prearm).append(" beeper ").append(beeper).append('\n');
                                        sb.append(canonical(plan(auxLists.get(a), available.get(v), blocked.get(b), m, rescue, arm, prearm, beeper)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ModeSwitch.Plan plan(String aux, Set<Integer> available, Map<ModeSwitch.Mode, String> blocked, ModeSwitch modes,
                                        int rescue, int arm, int prearm, int beeper) {
        return ModeSwitch.plan(aux, available, blocked, modes, rescue, arm, prearm, beeper);
    }

    @Test
    void everyPlanIsAsRecorded() throws Exception {
        String text = grid();
        String dump = System.getenv("GOLDEN_DUMP");
        if (dump != null) Files.writeString(Path.of(dump), text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RECORDED, hash, "the plans differ from the recorded ones (GOLDEN_DUMP=<file> writes them for a diff)");
    }
}
