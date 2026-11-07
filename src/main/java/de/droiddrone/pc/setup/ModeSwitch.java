package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The flight mode switch Drone setup puts in Betaflight's Modes: one 3-position switch on an AUX channel, a mode for
 * each position. By default AUX2: low acro, middle angle, top GPS position hold. Written with the CLI's "aux"
 * command, by Betaflight's permanent mode ids (msp_box.c): ANGLE 1, POS HOLD 11, GPS RESCUE 46. Acro is no mode of
 * its own - it is what flies when none is on.
 *
 * Anything else on that switch is replaced, ARM on it is refused (pick another switch), and the modes this setup
 * manages (ANGLE, HORIZON, POS HOLD, GPS RESCUE) are taken off every other switch, so each is in one place - a switch
 * left as it is keeps them, reported, and a mode on two switches is reported as doubled.
 */
public record ModeSwitch(int aux, Mode low, Mode middle, Mode high) {
    public static final String KEY = "drone.modeSwitch";
    /** Saved for "leave Betaflight's modes as they are". */
    public static final String OFF = "off";
    public static final ModeSwitch DEFAULT = new ModeSwitch(2, Mode.ACRO, Mode.ANGLE, Mode.POS_HOLD);
    /** The AUX channels offered (the radio's mapping fills AUX1-4 by default). */
    public static final int MAX_AUX = 8;
    /** Switch positions in us: low ~1000, middle ~1500, high ~2000 (Betaflight's steps of 25, end exclusive). */
    static final int[][] RANGES = {{900, 1300}, {1300, 1700}, {1700, 2100}};
    static final String[] POSITIONS = {"low", "middle", "high"};
    private static final int MODE_ARM = 0;
    /** Betaflight's permanent id for PREARM (msp_box.c). */
    private static final int MODE_PREARM = 36;
    /** Betaflight's permanent id for BEEPER (BOXBEEPERON, msp_box.c). */
    public static final int BEEPER_MODE = 13;

    /**
     * The BEEPER mode's channel: AUX14 (1.0.49; AUX12 before), the last channel MSP carries and almost never a radio
     * switch, which the app drives itself - the BEEPER button - not the radio (a radio's AUX14 is overridden). Up = beep.
     * Drone setup puts BEEPER there alone: BEEPER on any other switch, and any other mode on AUX14, is taken off, so
     * nothing but the button can make the drone beep (see Beepers). Never beside ARM or PREARM: then the page asks to
     * move them first, and BEEPER isn't written.
     */
    public static final int BEEPER_AUX = 14;
    static final int[] BEEPER_RANGE = {1700, 2100};

    /**
     * GPS rescue from Betaflight 4.4 (where it was rewritten, with its landing phase and sanity checks; earlier ones
     * aren't offered), GPS position hold from 2025.12 (where it came in, as POS HOLD).
     */
    public enum Mode {
        ACRO("Acro", "ACRO", -1, "no self-levelling", false, 0, 0),
        ANGLE("Angle", "ANGLE", 1, "self-levelling; needs the accelerometer", false, 0, 0),
        POS_HOLD("GPS position hold", "POS HOLD", 11, "holds its place; needs a GPS", true, 2025, 12),
        GPS_RESCUE("GPS rescue", "GPS RESCUE", 46, "flies home; needs a GPS", true, 4, 4);

        public final String title;
        /** The mode's name as Betaflight Configurator's Modes tab shows it. */
        public final String configuratorName;
        /** Betaflight's permanent mode id; -1 for acro (no mode). */
        public final int id;
        public final String detail;
        public final boolean needsGps;
        private final int minMajor, minMinor;

        Mode(String title, String configuratorName, int id, String detail, boolean needsGps, int minMajor, int minMinor) {
            this.title = title;
            this.configuratorName = configuratorName;
            this.id = id;
            this.detail = detail;
            this.needsGps = needsGps;
            this.minMajor = minMajor;
            this.minMinor = minMinor;
        }

        /** Why this Betaflight is too old for the mode, or null (new enough, or the version isn't understood). */
        public String tooOld(String versionLine) {
            int[] v = BetaflightPlan.versionNumber(versionLine);
            if (v == null || minMajor == 0) return null;
            boolean old = v[0] < minMajor || (v[0] == minMajor && v[1] < minMinor);
            return old ? title + " needs Betaflight " + minMajor + "." + minMinor + " or newer - this one is " + v[0] + "." + v[1]
                    + ". Update Betaflight to use it." : null;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public Mode[] positions() {
        return new Mode[]{low, middle, high};
    }

    /** The GPS modes on this switch, each once. */
    public List<Mode> gpsModes() {
        List<Mode> out = new ArrayList<>();
        for (Mode m : positions()) if (m.needsGps && !out.contains(m)) out.add(m);
        return out;
    }

    /** As saved: "2:ACRO,ANGLE,POS_HOLD". */
    public String format() {
        return aux + ":" + low.name() + "," + middle.name() + "," + high.name();
    }

    /** The saved form back: null for "off"; the default when missing or broken. */
    public static ModeSwitch parse(String saved) {
        if (saved == null || saved.isBlank()) return DEFAULT;
        if (saved.trim().equalsIgnoreCase(OFF)) return null;
        try {
            String[] p = saved.trim().split(":");
            int aux = Integer.parseInt(p[0]);
            String[] m = p[1].split(",");
            if (aux < 1 || aux > MAX_AUX || m.length != 3) return DEFAULT;
            return new ModeSwitch(aux, Mode.valueOf(m[0].trim().toUpperCase(Locale.ROOT)),
                    Mode.valueOf(m[1].trim().toUpperCase(Locale.ROOT)), Mode.valueOf(m[2].trim().toUpperCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /** "low Acro, middle Angle, high GPS position hold". */
    public String describe() {
        List<String> parts = new ArrayList<>();
        Mode[] m = positions();
        for (int i = 0; i < 3; i++) parts.add(POSITIONS[i] + " " + m[i].title);
        return String.join(", ", parts);
    }

    /** One line of the CLI's "aux" output: aux &lt;slot&gt; &lt;mode&gt; &lt;aux channel from 0&gt; &lt;start&gt; &lt;end&gt; [logic linked]. */
    record Slot(int index, int mode, int channel, int start, int end, int fields) {
        boolean used() {
            return start < end;
        }
    }

    /** The AUX numbers (1 = AUX1) ARM is on in the CLI's "aux" output: none, one, or - not covered - several. */
    public static Set<Integer> armAuxes(String auxOutput) {
        return auxesOf(auxOutput, MODE_ARM);
    }

    /** The AUX numbers PREARM is on in the CLI's "aux" output. */
    public static Set<Integer> prearmAuxes(String auxOutput) {
        return auxesOf(auxOutput, MODE_PREARM);
    }

    private static Set<Integer> auxesOf(String auxOutput, int mode) {
        Set<Integer> auxes = new java.util.TreeSet<>();
        for (Slot s : slots(auxOutput)) if (s.used() && s.mode() == mode) auxes.add(s.channel() + 1);
        return auxes;
    }

    static List<Slot> slots(String auxOutput) {
        List<Slot> slots = new ArrayList<>();
        for (String line : auxOutput.split("\\R")) {
            String[] p = line.replaceFirst("^#\\s*", "").trim().split("\\s+");
            if (p.length < 6 || !p[0].equals("aux")) continue;
            try {
                slots.add(new Slot(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), p.length - 1));
            } catch (NumberFormatException ignored) {
                // not an aux line after all
            }
        }
        return slots;
    }

    /**
     * What Betaflight does when the signal is lost for good (failsafe stage 2, failsafe_procedure). GPS rescue only
     * with a new enough Betaflight and a GPS that answers - the same rules as GPS rescue on a switch.
     */
    public enum Failsafe {
        KEEP("Leave as it is", null),
        DROP("Drop (motors off)", "DROP"),
        GPS_RESCUE("GPS rescue", "GPS-RESCUE");

        public static final String KEY = "drone.failsafe";
        public final String title;
        /** Betaflight's value (lookupTableFailsafe); null = not written. */
        public final String value;

        Failsafe(String title, String value) {
            this.title = title;
            this.value = value;
        }

        @Override
        public String toString() {
            return title;
        }

        public static Failsafe parse(String saved) {
            for (Failsafe f : values()) if (f.name().equalsIgnoreCase(saved == null ? "" : saved.trim())) return f;
            return KEEP;
        }

        /** The value from `get failsafe_procedure` ("failsafe_procedure = DROP" -> "DROP"), or "" if not understood. */
        public static String current(String getOutput) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("failsafe_procedure\\s*=\\s*(\\S+)").matcher(getOutput);
            return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
        }
    }

    /** The ARM switch Drone setup puts in Betaflight's Modes: its AUX number, default AUX1; "off" = leave ARM as it is. */
    public static final String ARM_KEY = "drone.armAux";
    public static final int DEFAULT_ARM_AUX = 1;
    /** Armed with the switch up (2- or 3-position). An ARM already on the chosen switch keeps its own range. */
    static final int[] ARM_RANGE = {1700, 2100};

    /** The saved ARM switch: its AUX number, 0 for "leave as it is"; the default when missing or broken. */
    public static int parseArmAux(String saved) {
        return parseAux(saved, DEFAULT_ARM_AUX);
    }

    /**
     * The PREARM switch Drone setup puts in Betaflight's Modes (1.0.37): its AUX number, default AUX3, up = prearm; "off" =
     * leave PREARM as it is. With a PREARM mode set, Betaflight arms only while it is on - a second switch before the
     * ARM one, so a knocked ARM switch alone does nothing. One prearm switch: PREARM on any other is taken off.
     */
    public static final String PREARM_KEY = "drone.prearmAux";
    public static final int DEFAULT_PREARM_AUX = 3;
    static final int[] PREARM_RANGE = {1700, 2100};

    /** The saved PREARM switch: its AUX number, 0 for "leave as it is"; the default when missing or broken. */
    public static int parsePrearmAux(String saved) {
        return parseAux(saved, DEFAULT_PREARM_AUX);
    }

    private static int parseAux(String saved, int fallback) {
        if (saved == null || saved.isBlank()) return fallback;
        if (saved.trim().equalsIgnoreCase(OFF)) return 0;
        try {
            int aux = Integer.parseInt(saved.trim());
            return aux >= 1 && aux <= MAX_AUX ? aux : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Set by Drone setup: the rescue switch went on (the failsafe is GPS rescue). */
    public static final String RESCUE_FITTED_KEY = "drone.rescueFitted";

    /** What a radio switch is for, as the calibration asks for it. */
    public record Purpose(String title, String hint) {}

    /**
     * What each AUX switch is for (index 0 = AUX1; null = nothing chosen), from Drone setup's choices: ARM, the flight
     * mode switch, and GPS rescue when it is fitted (the failsafe is, or is to become, GPS rescue). First come first:
     * a switch is only named once.
     */
    public static Purpose[] purposes(de.droiddrone.pc.PcConfig config) {
        Purpose[] p = new Purpose[MAX_AUX];
        int arm = parseArmAux(config.getString(ARM_KEY));
        de.droiddrone.pc.ArmSwitch read = de.droiddrone.pc.ArmSwitch.parse(config.getString(de.droiddrone.pc.ArmSwitch.KEY));
        if (arm == 0 && read != null) arm = read.channel() - 3;
        if (arm >= 1 && arm <= MAX_AUX) {
            // an ARM that was already on that switch keeps its own range (plan): it may arm low, not up
            p[arm - 1] = new Purpose("ARM", "Flip the switch you ARM with to its armed position ("
                    + (read != null && read.channel() - 3 == arm ? armedPosition(read) : "up") + ").");
        }
        ModeSwitch modes = parse(config.getString(KEY));
        if (modes != null && p[modes.aux - 1] == null) {
            p[modes.aux - 1] = new Purpose("flight modes", "Flip the 3-position flight mode switch to the top (" + modes.high.title
                    + "). The middle is " + modes.middle.title + ", the bottom " + modes.low.title + ".");
        }
        int rescue = parseRescueAux(config.getString(RESCUE_KEY));
        boolean fitted = Failsafe.parse(config.getString(Failsafe.KEY)) == Failsafe.GPS_RESCUE
                || "true".equalsIgnoreCase(config.getString(RESCUE_FITTED_KEY));
        if (rescue > 0 && fitted && p[rescue - 1] == null) {
            p[rescue - 1] = new Purpose("GPS rescue", "Flip the GPS rescue switch up (rescue on).");
        }
        int prearm = parsePrearmAux(config.getString(PREARM_KEY));
        if (prearm > 0 && p[prearm - 1] == null) {
            p[prearm - 1] = new Purpose("PREARM", "Flip the PREARM switch up (prearm on). Betaflight arms only while it is on: flip it before ARM.");
        }
        return p;
    }

    /** "up", or where Betaflight really arms when the saved ARM range isn't the top: "down - Betaflight arms at 900-1300". */
    public static String armedPosition(de.droiddrone.pc.ArmSwitch arm) {
        int top = 0, bottom = Integer.MAX_VALUE;
        List<String> ranges = new ArrayList<>();
        for (int[] r : arm.ranges()) {
            top = Math.max(top, r[1]);
            bottom = Math.min(bottom, r[0]);
            ranges.add(r[0] + "-" + r[1]);
        }
        if (top >= ARM_RANGE[1] - 100 && bottom >= 1500) return "up";
        String where = top <= 1500 ? "down" : bottom >= 1200 && top <= 1800 ? "the middle" : "not up";
        return where + " - Betaflight arms at " + String.join(", ", ranges);
    }

    /** GPS rescue's own switch (besides the failsafe): up, on a 2- or 3-position switch. Default AUX4; "off" = none. */
    public static final String RESCUE_KEY = "drone.rescueSwitch";
    public static final int DEFAULT_RESCUE_AUX = 4;
    static final int[] RESCUE_RANGE = {1700, 2100};

    /** The saved rescue switch: its AUX number, 0 for off; the default when missing or broken. */
    public static int parseRescueAux(String saved) {
        return parseAux(saved, DEFAULT_RESCUE_AUX);
    }

    /**
     * What to do about it.
     * @param commands      CLI commands still needed (empty: already set, or refused)
     * @param problem       why the flight mode switch can't be written, or null
     * @param rescueProblem why the GPS rescue switch can't be written, or null
     * @param missing       chosen modes this Betaflight doesn't offer (left out: that position flies acro)
     * @param blocked       chosen modes not written, and why (too old a Betaflight, no GPS found...)
     * @param elsewhere     the same modes on other switches, left as they are
     * @param replaced      what was on the switches before and is taken off
     * @param armProblem    why the ARM switch can't be written, or null
     * @param prearmProblem why the PREARM switch can't be written, or null
     * @param doubled       managed modes Betaflight has on more than one switch ("ANGLE on AUX2, AUX5"), as read
     * @param beeperProblem why BEEPER can't go on its channel (ARM or PREARM is there: move it first), or null
     */
    public record Plan(List<String> commands, String problem, String rescueProblem, List<Mode> missing,
                       java.util.Map<Mode, String> blocked, List<String> elsewhere, List<String> replaced, String armProblem,
                       String prearmProblem, List<String> doubled, String beeperProblem) {
        public boolean isSet() {
            return problem == null && commands.isEmpty();
        }
    }

    /**
     * @param auxOutput the CLI's "aux" output
     * @param available the modes this Betaflight offers (MSP_BOXIDS: permanent ids), or null if unknown
     */
    public Plan plan(String auxOutput, Set<Integer> available) {
        return plan(auxOutput, available, java.util.Map.of());
    }

    /** @param blocked modes not to write now, with the reason shown (they fly acro meanwhile) */
    public Plan plan(String auxOutput, Set<Integer> available, java.util.Map<Mode, String> blocked) {
        return plan(auxOutput, available, blocked, this, 0, 0);
    }

    /** As below, ARM left as it is. */
    public static Plan plan(String auxOutput, Set<Integer> available, java.util.Map<Mode, String> blocked, ModeSwitch modes,
                            int rescueAux) {
        return plan(auxOutput, available, blocked, modes, rescueAux, 0);
    }

    /** As below, PREARM left as it is. */
    public static Plan plan(String auxOutput, Set<Integer> available, java.util.Map<Mode, String> blocked, ModeSwitch modes,
                            int rescueAux, int armAux) {
        return plan(auxOutput, available, blocked, modes, rescueAux, armAux, 0);
    }

    /** As below, BEEPER left as it is. */
    public static Plan plan(String auxOutput, Set<Integer> available, java.util.Map<Mode, String> blocked, ModeSwitch modes,
                            int rescueAux, int armAux, int prearmAux) {
        return plan(auxOutput, available, blocked, modes, rescueAux, armAux, prearmAux, 0);
    }

    /**
     * The switches together (they share Betaflight's mode slots).
     * @param modes     the flight mode switch, or null to leave it
     * @param rescueAux GPS rescue up on this AUX too (0 = no): for when Betaflight's failsafe is GPS rescue
     * @param armAux    ARM on this AUX, up (0 = leave ARM as it is). ARM on any other switch is taken off: one arm switch.
     * @param prearmAux PREARM on this AUX, up (0 = leave PREARM as it is). PREARM on any other switch is taken off.
     * @param beeperAux BEEPER alone on this AUX, up ({@link #BEEPER_AUX}; 0 = leave BEEPER as it is). Never where ARM
     *                  or PREARM is or stays: then beeperProblem asks to move them, and BEEPER is left as it is.
     *
     * Only the modes this plan places are taken off other switches (1.0.49): a pilot's own GPS RESCUE switch stays
     * while GPS rescue isn't chosen here, and PREARM left as it is keeps its switch - a switch chosen here that PREARM
     * is on is refused, like ARM. Every slot written is cleared first, even one that is filled again: Betaflight keeps
     * a slot's old mode when it doesn't take the new one, and the write is checked against its echo (see echoProblem).
     */
    public static Plan plan(String auxOutput, Set<Integer> available, java.util.Map<Mode, String> blocked, ModeSwitch modes,
                            int rescueAux, int armAux, int prearmAux, int beeperAux) {
        blocked = java.util.Map.copyOf(blocked);
        List<Slot> slots = slots(auxOutput);
        String armProblem = null;
        int armChannel = -1;
        if (armAux > 0) {
            if (modes != null && modes.aux == armAux) {
                armProblem = "AUX" + armAux + " is the flight mode switch - pick another switch for ARM.";
            } else if (rescueAux == armAux) {
                armProblem = "AUX" + armAux + " is the GPS rescue switch - pick another switch for ARM.";
            } else {
                armChannel = armAux - 1;
            }
        }
        // where ARM will be: the chosen switch, or (left as it is) wherever it is now
        Set<Integer> armChannels = new HashSet<>();
        if (armChannel >= 0) armChannels.add(armChannel);
        else for (Slot s : slots) if (s.used() && s.mode() == MODE_ARM) armChannels.add(s.channel());
        String prearmProblem = null;
        int prearmChannel = -1;
        if (prearmAux > 0) {
            if (modes != null && modes.aux == prearmAux) {
                prearmProblem = "AUX" + prearmAux + " is the flight mode switch - pick another switch for PREARM.";
            } else if (rescueAux == prearmAux) {
                prearmProblem = "AUX" + prearmAux + " is the GPS rescue switch - pick another switch for PREARM.";
            } else if (armChannels.contains(prearmAux - 1)) {
                prearmProblem = "ARM is on AUX" + prearmAux + " - pick another switch for PREARM.";
            } else {
                prearmChannel = prearmAux - 1;
            }
        }
        // where PREARM will be, the same way: a switch PREARM stays on isn't taken for anything else either
        Set<Integer> prearmChannels = new HashSet<>();
        if (prearmChannel >= 0) prearmChannels.add(prearmChannel);
        else for (Slot s : slots) if (s.used() && s.mode() == MODE_PREARM) prearmChannels.add(s.channel());
        String beeperProblem = null;
        int beeperChannel = -1;
        if (beeperAux > 0) {
            if (armChannels.contains(beeperAux - 1)) {
                beeperProblem = "ARM is on AUX" + beeperAux + ", the channel the app's BEEPER button drives: BEEPER would arm the drone. "
                        + "Choose an ARM switch (AUX1-" + MAX_AUX + ") above - ARM moves there, then BEEPER goes on AUX" + beeperAux + ".";
            } else if (prearmChannels.contains(beeperAux - 1)) {
                beeperProblem = "PREARM is on AUX" + beeperAux + ", the channel the app's BEEPER button drives. Choose a PREARM switch "
                        + "(AUX1-" + MAX_AUX + ") above - PREARM moves there, then BEEPER goes on AUX" + beeperAux + ".";
            } else if ((modes != null && modes.aux == beeperAux) || rescueAux == beeperAux) {
                beeperProblem = "AUX" + beeperAux + " is the app's BEEPER channel - pick another switch.";
            } else {
                beeperChannel = beeperAux - 1;
            }
        }
        List<Mode> missing = new ArrayList<>();
        String problem = null, rescueProblem = null;
        java.util.Map<Integer, List<int[]>> wanted = new java.util.LinkedHashMap<>(); // channel -> {mode, start, end}
        if (modes != null) {
            int channel = modes.aux - 1;
            if (armChannels.contains(channel)) {
                problem = "ARM is on AUX" + modes.aux + " - pick another switch for the flight modes.";
            } else if (prearmChannels.contains(channel)) {
                problem = "PREARM is on AUX" + modes.aux + " - pick another switch for the flight modes, or a PREARM switch.";
            } else {
                List<int[]> list = new ArrayList<>();
                Mode[] m = modes.positions();
                for (int i = 0; i < 3; i++) {
                    if (m[i] == Mode.ACRO || blocked.containsKey(m[i])) continue;
                    if (available != null && !available.contains(m[i].id)) {
                        if (!missing.contains(m[i])) missing.add(m[i]);
                        continue;
                    }
                    list.add(new int[]{m[i].id, RANGES[i][0], RANGES[i][1]});
                }
                wanted.put(channel, list);
            }
        }
        if (rescueAux > 0) {
            int channel = rescueAux - 1;
            if (modes != null && modes.aux == rescueAux) {
                rescueProblem = "AUX" + rescueAux + " is the flight mode switch - pick another switch for GPS rescue.";
            } else if (armChannels.contains(channel)) {
                rescueProblem = "ARM is on AUX" + rescueAux + " - pick another switch for GPS rescue.";
            } else if (prearmChannels.contains(channel)) {
                rescueProblem = "PREARM is on AUX" + rescueAux + " - pick another switch for GPS rescue, or a PREARM switch.";
            } else if (blocked.containsKey(Mode.GPS_RESCUE)) {
                rescueProblem = blocked.get(Mode.GPS_RESCUE); // the switch is left as it is meanwhile
            } else if (available != null && !available.contains(Mode.GPS_RESCUE.id)) {
                if (!missing.contains(Mode.GPS_RESCUE)) missing.add(Mode.GPS_RESCUE);
                rescueProblem = "GPS rescue isn't offered by this Betaflight (" + Mode.GPS_RESCUE.detail + ").";
            } else {
                wanted.put(channel, List.<int[]>of(new int[]{Mode.GPS_RESCUE.id, RESCUE_RANGE[0], RESCUE_RANGE[1]}));
            }
        }
        List<String> doubled = doubled(slots);
        Set<Integer> placing = new HashSet<>(); // the modes this plan puts on a switch: only these are taken off others
        for (List<int[]> l : wanted.values()) for (int[] w : l) placing.add(w[0]);
        List<String> elsewhere = new ArrayList<>();
        List<Slot> onSwitches = new ArrayList<>();
        List<Slot> offElsewhere = new ArrayList<>(); // managed modes on other switches: taken off, each in one place
        for (Slot s : slots) {
            if (!s.used() || s.mode() == MODE_ARM || (s.mode() == MODE_PREARM && prearmChannel >= 0)) continue;
            if (beeperChannel >= 0 && (s.mode() == BEEPER_MODE || s.channel() == beeperChannel)) continue; // below
            if (wanted.containsKey(s.channel())) {
                onSwitches.add(s);
            } else if (MANAGED.contains(s.mode())) {
                boolean ours = placing.contains(s.mode());
                String line = modeName(s.mode()) + " on AUX" + (s.channel() + 1);
                if (ours) offElsewhere.add(s);
                else if (!elsewhere.contains(line)) elsewhere.add(line);
            }
        }
        List<String> toPlace = new ArrayList<>();
        List<int[]> place = new ArrayList<>(); // channel, mode, start, end
        for (var e : wanted.entrySet()) {
            for (int[] w : e.getValue()) {
                toPlace.add(key(e.getKey(), w[0], w[1], w[2]));
                place.add(new int[]{e.getKey(), w[0], w[1], w[2]});
            }
        }
        int fields = slots.isEmpty() ? 7 : slots.get(0).fields();
        List<String> commands = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (Slot s : onSwitches) {
            if (toPlace.remove(key(s.channel(), s.mode(), s.start(), s.end()))) continue; // already right: kept
            commands.add(auxCommand(s.index(), 0, 0, 900, 900, fields));
            free.add(s.index());
            String line = modeName(s.mode()) + " on AUX" + (s.channel() + 1);
            if (!replaced.contains(line)) replaced.add(line);
        }
        for (Slot s : offElsewhere) {
            commands.add(auxCommand(s.index(), 0, 0, 900, 900, fields));
            free.add(s.index());
            String line = modeName(s.mode()) + " on AUX" + (s.channel() + 1);
            if (!replaced.contains(line)) replaced.add(line);
        }
        place.removeIf(w -> !toPlace.contains(key(w[0], w[1], w[2], w[3])));
        if (armChannel >= 0) {
            boolean armThere = false;
            for (Slot s : slots) {
                if (!s.used() || s.mode() != MODE_ARM) continue;
                if (s.channel() == armChannel) {
                    armThere = true; // its own range stays: the pilot's switch may arm low
                    continue;
                }
                commands.add(auxCommand(s.index(), 0, 0, 900, 900, fields));
                free.add(s.index());
                String line = "ARM on AUX" + (s.channel() + 1);
                if (!replaced.contains(line)) replaced.add(line);
            }
            if (!armThere) place.add(0, new int[]{armChannel, MODE_ARM, ARM_RANGE[0], ARM_RANGE[1]});
        }
        if (prearmChannel >= 0) {
            boolean prearmThere = false;
            for (Slot s : slots) {
                if (!s.used() || s.mode() != MODE_PREARM) continue;
                if (s.channel() == prearmChannel) {
                    prearmThere = true; // its own range stays, like ARM's
                    continue;
                }
                commands.add(auxCommand(s.index(), 0, 0, 900, 900, fields));
                free.add(s.index());
                String line = "PREARM on AUX" + (s.channel() + 1);
                if (!replaced.contains(line)) replaced.add(line);
            }
            if (!prearmThere) place.add(new int[]{prearmChannel, MODE_PREARM, PREARM_RANGE[0], PREARM_RANGE[1]});
        }
        if (beeperChannel >= 0) {
            boolean beeperThere = false;
            for (Slot s : slots) {
                if (!s.used() || s.mode() == MODE_ARM || (s.mode() == MODE_PREARM && prearmChannel >= 0)) continue;
                if (s.mode() != BEEPER_MODE && s.channel() != beeperChannel) continue;
                if (!beeperThere && s.mode() == BEEPER_MODE && s.channel() == beeperChannel
                        && s.start() == BEEPER_RANGE[0] && s.end() == BEEPER_RANGE[1]) {
                    beeperThere = true; // the button's range exactly: kept
                    continue;
                }
                commands.add(auxCommand(s.index(), 0, 0, 900, 900, fields));
                free.add(s.index());
                String line = modeName(s.mode()) + " on AUX" + (s.channel() + 1);
                if (!replaced.contains(line)) replaced.add(line);
            }
            if (!beeperThere) place.add(new int[]{beeperChannel, BEEPER_MODE, BEEPER_RANGE[0], BEEPER_RANGE[1]});
        }
        for (Slot s : slots) {
            if (!s.used()) free.add(s.index());
        }
        free.sort(null);
        if (free.size() < place.size()) {
            return new Plan(List.of(), "Betaflight's Modes are full - free a few in Betaflight Configurator.", rescueProblem,
                    missing, blocked, elsewhere, List.of(), armProblem, prearmProblem, doubled, beeperProblem);
        }
        // the clears stay, also for a slot filled again below: they all come before the first fill
        for (int i = 0; i < place.size(); i++) {
            int[] w = place.get(i);
            commands.add(auxCommand(free.get(i), w[1], w[0], w[2], w[3], fields));
        }
        String unsafe = sharedWithArm(after(slots, commands), commands);
        if (unsafe != null) { // never expected: a last check on the result, so no plan can ever put a mode beside ARM
            return new Plan(List.of(), unsafe + " - nothing is written; set the Modes tab by hand.", rescueProblem, missing, blocked,
                    elsewhere, List.of(), armProblem, prearmProblem, doubled, beeperProblem);
        }
        return new Plan(List.copyOf(commands), problem, rescueProblem, missing, blocked, elsewhere, List.copyOf(replaced), armProblem,
                prearmProblem, doubled, beeperProblem);
    }

    /** Betaflight's slots as the plan expects them once these "aux" commands are taken: the slots as read, then each command. */
    static List<Slot> after(List<Slot> before, List<String> commands) {
        java.util.Map<Integer, Slot> bySlot = new java.util.TreeMap<>();
        for (Slot s : before) bySlot.put(s.index(), s);
        for (Slot s : slots(String.join("\n", commands))) bySlot.put(s.index(), s);
        return new ArrayList<>(bySlot.values());
    }

    /**
     * Why these slots are unsafe, or null: a mode the commands place (BEEPER, a flight mode, GPS rescue, PREARM) on a
     * channel ARM is on once they are taken. The button drives BEEPER's channel and the flight mode switch is flicked in
     * flight: neither may ever carry ARM. What the pilot had beside ARM and the plan doesn't touch is theirs.
     */
    static String sharedWithArm(List<Slot> result, List<String> commands) {
        Set<Integer> written = new HashSet<>();
        for (Slot s : slots(String.join("\n", commands))) written.add(s.index());
        for (Slot s : result) {
            if (!s.used() || s.mode() == MODE_ARM || !written.contains(s.index())) continue;
            for (Slot a : result) {
                if (a.used() && a.mode() == MODE_ARM && a.channel() == s.channel()) {
                    return "ARM and " + modeName(s.mode()) + " would share AUX" + (s.channel() + 1);
                }
            }
        }
        return null;
    }

    /**
     * Betaflight's echo of one "aux" command against the command: it prints the slot as it now is ("aux 3 1 1 1300 1700
     * 0 0", the same in 4.4, 4.5 and 2025.12). Null when they agree, or when there is no echo to compare (the read-back
     * after the write checks that slot then); otherwise what differs. A mode id Betaflight doesn't take leaves the
     * slot's old mode in place with the new channel and range, and no ###ERROR - this is how it is seen.
     */
    public static String echoProblem(String command, String reply) {
        List<Slot> sent = slots(command);
        if (sent.size() != 1) return null; // not an aux command
        Slot echo = null;
        for (Slot s : slots(reply)) if (s.index() == sent.get(0).index()) echo = s; // the last: Betaflight's own line
        return echo == null ? null : differs(sent.get(0), echo);
    }

    /** Every slot these "aux" commands wrote against Betaflight's "aux" read back after them: null when all are as sent. */
    public static String readBackProblem(List<String> commands, String auxOutput) {
        java.util.Map<Integer, Slot> now = new java.util.HashMap<>();
        for (Slot s : slots(auxOutput)) now.put(s.index(), s);
        java.util.Map<Integer, Slot> sent = new java.util.LinkedHashMap<>();
        for (Slot s : slots(String.join("\n", commands))) sent.put(s.index(), s); // the last command for a slot counts
        for (Slot s : sent.values()) {
            Slot n = now.get(s.index());
            if (n == null) return "aux " + s.index() + " isn't in Betaflight's list after the write";
            String d = differs(s, n);
            if (d != null) return d;
        }
        return null;
    }

    /** "aux 3: sent GPS RESCUE on AUX2 1700-2100, Betaflight has ARM on AUX2 1700-2100", or null if the same. */
    private static String differs(Slot sent, Slot got) {
        if (sent.mode() == got.mode() && sent.channel() == got.channel() && sent.start() == got.start() && sent.end() == got.end()) return null;
        return "aux " + sent.index() + ": sent " + describeSlot(sent) + ", Betaflight has " + describeSlot(got);
    }

    private static String describeSlot(Slot s) {
        return s.used() ? modeName(s.mode()) + " on AUX" + (s.channel() + 1) + " " + s.start() + "-" + s.end() : "nothing (cleared)";
    }

    /** The flight modes this setup manages: each belongs in one place. */
    static final Set<Integer> MANAGED = Set.of(Mode.ANGLE.id, 2 /* HORIZON */, Mode.POS_HOLD.id, Mode.GPS_RESCUE.id);

    /** The managed modes on more than one switch in the CLI's "aux" output: "ANGLE on AUX2, AUX5". */
    public static List<String> doubled(String auxOutput) {
        return doubled(slots(auxOutput));
    }

    static List<String> doubled(List<Slot> slots) {
        java.util.Map<Integer, java.util.TreeSet<Integer>> channels = new java.util.TreeMap<>();
        for (Slot s : slots) {
            if (s.used() && MANAGED.contains(s.mode())) channels.computeIfAbsent(s.mode(), k -> new java.util.TreeSet<>()).add(s.channel() + 1);
        }
        List<String> out = new ArrayList<>();
        for (var e : channels.entrySet()) {
            if (e.getValue().size() < 2) continue;
            List<String> auxes = new ArrayList<>();
            for (int c : e.getValue()) auxes.add("AUX" + c);
            out.add(modeName(e.getKey()) + " on " + String.join(", ", auxes));
        }
        return out;
    }

    private static String key(int channel, int mode, int start, int end) {
        return channel + ":" + mode + ":" + start + "-" + end;
    }

    /** "aux 3 1 1 1300 1700 0 0", padded to the fields this Betaflight prints (6 before 4.1, 7 since). */
    static String auxCommand(int slot, int mode, int channel, int start, int end, int fields) {
        StringBuilder sb = new StringBuilder("aux ").append(slot).append(' ').append(mode).append(' ').append(channel)
                .append(' ').append(start).append(' ').append(end);
        for (int i = 5; i < fields; i++) sb.append(" 0");
        return sb.toString();
    }

    static String modeName(int id) {
        for (Mode m : Mode.values()) {
            if (m.id == id) return m.title.toUpperCase(Locale.ROOT);
        }
        return switch (id) {
            case 0 -> "ARM";
            case 2 -> "HORIZON";
            case 3 -> "ALT HOLD";
            case 13 -> "BEEPER";
            case 35 -> "TURTLE";
            case 36 -> "PREARM";
            default -> "Betaflight mode " + id;
        };
    }
}
