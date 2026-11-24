package de.droiddrone.bench.night;

import de.droiddrone.bench.PhoneWords;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.pc.DiskSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which sleep each round of {@link PhoneNightScenario} takes, its row in soak/phone-night.tsv, and what an OVERWATCH
 * round needs that a DEEP SLEEP round does not. The run says which sleep with BENCH_SLEEP (bench.ps1 {@code -Sleep};
 * the system property {@value #PROPERTY}): {@code deep} - left out too - DEEP SLEEP every round, the night as it always
 * was; {@code overwatch} every round; {@code both} by turns, OVERWATCH first (a short try has the new one twice).
 * <p>
 * What OVERWATCH needs besides, from the apps' code: the camera (granted with the others, PhoneRig.grantPermissions);
 * a phone app of pc.36 or newer whose camera gives the motion detection a picture - else the PC takes no OVERWATCH and
 * says so beside the button ({@link #PC_REFUSALS}): the round takes DEEP SLEEP, and so do the rounds after; and a
 * battery above the app's own floor - under {@value PowerFloor#BATTERY_MIN} % and not charging (the rig pretends the
 * phone off its cable) the phone leaves OVERWATCH for DEEP SLEEP by itself within a minute: under
 * {@link #OVERWATCH_BATTERY_MIN} % the round takes DEEP SLEEP. Its heat floor stays above the bench's own (the rig waits
 * between rounds above 42 C): met all the same, the round goes on - it is counted, not judged. The disk: under the PC's
 * 2 GB floor (DiskSpace.LOW_BYTES, OverwatchHub's) the app keeps no picture or clip of an alert; nothing of the round
 * depends on it, and the film says so at the start. The phone keeps its clips within 2 GB and 1 GB free by itself.
 */
final class PhoneNightSleep {
    private PhoneNightSleep() {
    }

    /** The system property that carries BENCH_SLEEP into the scenario's JVM (build.gradle). */
    static final String PROPERTY = "bench.sleep";

    enum Mode {
        DEEP("DEEP SLEEP every round"), OVERWATCH("OVERWATCH every round"), BOTH("OVERWATCH and DEEP SLEEP by turns, OVERWATCH first");

        final String words;

        Mode(String words) {
            this.words = words;
        }

        /** As bench.ps1's -Sleep and BENCH_SLEEP write it. */
        String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** deep, overwatch or both (any case); nothing at all is deep. */
        static Mode parse(String value) {
            if (value == null || value.isBlank()) return DEEP;
            for (Mode m : values()) if (m.word().equals(value.trim().toLowerCase(Locale.ROOT))) return m;
            throw new IllegalArgumentException("BENCH_SLEEP (" + PROPERTY + ") is \"" + value + "\": deep, overwatch or both");
        }

        static Mode ofRun() {
            return parse(System.getProperty(PROPERTY));
        }
    }

    /** Whether round {@code round} (from 0) is an OVERWATCH round in this mode. */
    static boolean overwatch(Mode mode, int round) {
        return switch (mode) {
            case DEEP -> false;
            case OVERWATCH -> true;
            case BOTH -> round % 2 == 0;
        };
    }

    /** The PC's button for the sleep, and the start of the line its log has once it is asked ("DEEP SLEEP asked: ..."). */
    static String button(boolean overwatch) {
        return overwatch ? "OVERWATCH" : "DEEP SLEEP";
    }

    static String asked(boolean overwatch) {
        return button(overwatch) + " asked";
    }

    /** The table's mode column. */
    static String word(boolean overwatch) {
        return overwatch ? "overwatch" : "deep";
    }

    // ------------------------------------------------------------------ what OVERWATCH needs

    /** Under this the phone would leave OVERWATCH for DEEP SLEEP by itself (PowerFloor, a few % to spare for the round). */
    static final int OVERWATCH_BATTERY_MIN = PowerFloor.BATTERY_MIN + 5;

    /** Why a round whose turn is OVERWATCH takes DEEP SLEEP for its battery; null when it may watch (-1: not known). */
    static String lowForOverwatch(int batteryPercent) {
        if (batteryPercent < 0 || batteryPercent >= OVERWATCH_BATTERY_MIN) return null;
        return "the phone's battery is at " + batteryPercent + " % - under " + OVERWATCH_BATTERY_MIN + " % the phone would leave OVERWATCH by itself (its floor: "
                + PowerFloor.BATTERY_MIN + " % off the cable)";
    }

    /** The PC's own notices when it will not put a phone to OVERWATCH (MainWindow.prepareOverwatch). */
    static final List<String> PC_REFUSALS = List.of("NO PICTURE TO WATCH", "UPDATE THE PHONE APP");

    /** The PC's refusal among the notices showing after OVERWATCH was pressed; null when none of them is one. */
    static String refusal(List<String> notices) {
        for (String n : notices) {
            for (String r : PC_REFUSALS) if (n != null && n.toUpperCase(Locale.ROOT).contains(r)) return r;
        }
        return null;
    }

    /** What the film says at the start when the PC's disk is under the overwatch floor; null when there is room or it is not known. */
    static String diskNote(long freeBytes) {
        if (freeBytes < 0 || freeBytes >= DiskSpace.LOW_BYTES) return null;
        return String.format(Locale.ROOT, "LOW DISK on this PC (%.1f GB free, under %.0f GB): the app keeps no OVERWATCH picture or clip this night - "
                + "the alerts and clips in the table are the phone's own count", freeBytes / 1e9, DiskSpace.LOW_BYTES / 1e9);
    }

    // ------------------------------------------------------------------ the table

    static final List<String> COLUMNS = List.of("minutes", "round", "mode", "phone app MB", "PC heap MB", "PC threads", "woke after s",
            "phone's own checks", "new sessions on the phone", "socket opened anew",
            // an OVERWATCH round's own, recorded and not judged (blank in a DEEP SLEEP round)
            "alerts", "clips", "to deep sleep by itself", "battery C", "phone app MB watching");

    /** Of the columns, the ones every round has, before the OVERWATCH ones. */
    static final int EVERY_ROUND = COLUMNS.indexOf("alerts");

    /**
     * What an OVERWATCH round adds to its row: the motion alerts and clips the phone's log tells of, the times its
     * floor sent it to DEEP SLEEP, its battery's temperature at the end of the watch (NaN: not read), the app's
     * memory while it watched (-1: not read).
     */
    record Overwatched(long alerts, long clips, long toDeepSleep, double batteryC, long watchingMb) {
        static Overwatched of(String phoneLog, double batteryC, long watchingMb) {
            return new Overwatched(count(phoneLog, PhoneWords.OVERWATCH_MOTION), count(phoneLog, PhoneWords.OVERWATCH_CLIP_WAITS),
                    count(phoneLog, PhoneWords.OVERWATCH_TO_DEEP_SLEEP), batteryC, watchingMb);
        }

        List<Object> cells() {
            return List.of(alerts, clips, toDeepSleep, Double.isNaN(batteryC) ? "" : (Object) batteryC, watchingMb < 0 ? "" : (Object) watchingMb);
        }
    }

    /** A round's row: the figures every round has (minutes, round, mode, ...), then an OVERWATCH round's own or blanks. */
    static Object[] row(Object[] everyRound, Overwatched watched) {
        if (everyRound.length != EVERY_ROUND) throw new IllegalArgumentException(everyRound.length + " figures for " + EVERY_ROUND + " columns");
        List<Object> cells = new ArrayList<>(Arrays.asList(everyRound));
        if (watched != null) cells.addAll(watched.cells());
        else while (cells.size() < COLUMNS.size()) cells.add("");
        return cells.toArray();
    }

    static long count(String text, String part) {
        long n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }
}
