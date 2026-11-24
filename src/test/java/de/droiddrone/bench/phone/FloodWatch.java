package de.droiddrone.bench.phone;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BS6's figures - a stick age, a memory, a thread count - each a series by phase (before the flood, during it, after
 * it), and the verdicts on them: the sticks' age during the flood as before it, the memory flat, the threads capped.
 * Pure: {@code FloodWatchTest}.
 */
final class FloodWatch {
    private FloodWatch() {
    }

    enum Phase {
        BEFORE, DURING, AFTER
    }

    /** One figure, sampled by phase. Negative values are "not known" and not kept. */
    static final class Series {
        final String name;
        private final EnumMap<Phase, List<Long>> by = new EnumMap<>(Phase.class);

        Series(String name) {
            this.name = name;
            for (Phase p : Phase.values()) by.put(p, new ArrayList<>());
        }

        synchronized void add(Phase p, long v) {
            if (v >= 0) by.get(p).add(v);
        }

        synchronized List<Long> of(Phase p) {
            return List.copyOf(by.get(p));
        }

        /** "before p50 4, p95 9, max 12 (300); during ..." for the film. */
        synchronized String said(String unit) {
            StringBuilder s = new StringBuilder(name + ":");
            for (Phase p : Phase.values()) {
                List<Long> v = by.get(p);
                s.append(' ').append(p.name().toLowerCase()).append(' ');
                s.append(v.isEmpty() ? "-" : "p50 " + percentile(v, 50) + ", p95 " + percentile(v, 95) + ", max " + percentile(v, 100) + " " + unit + " (" + v.size() + ")");
                if (p != Phase.AFTER) s.append(';');
            }
            return s.toString();
        }
    }

    /** The nearest-rank percentile (100: the largest); -1 of nothing. */
    static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return -1;
        List<Long> v = new ArrayList<>(values);
        v.sort(null);
        int rank = (int) Math.ceil(p / 100.0 * v.size());
        return v.get(Math.max(0, Math.min(v.size() - 1, rank - 1)));
    }

    /**
     * The sticks' age (or the gap between stick packets) during the flood as before it: its 95th percentile within half
     * again of before's and slackMs - the network's own ups and downs are let pass, a flood that holds up the sticks is not.
     */
    static boolean unchanged(List<Long> before, List<Long> during, long slackMs) {
        if (before.isEmpty() || during.isEmpty()) return false;
        return percentile(during, 95) <= percentile(before, 95) * 3 / 2 + slackMs;
    }

    /**
     * How much a memory figure grew over the flood: the median of the last third of its samples less that of the first
     * third (one sample a third at the least; 0 with fewer than two). A figure that saws up and down with collections
     * does not count as growth; one that climbs does.
     */
    static long growth(List<Long> during) {
        int n = during.size();
        if (n < 2) return 0;
        int third = Math.max(1, n / 3);
        return median(during.subList(n - third, n)) - median(during.subList(0, third));
    }

    static long median(List<Long> v) {
        return percentile(v, 50);
    }

    /** The most of a count during the flood, against the most before it. */
    static boolean capped(List<Long> before, List<Long> during, long more) {
        if (before.isEmpty() || during.isEmpty()) return false;
        return percentile(during, 100) <= percentile(before, 100) + more;
    }

    // ------------------------------------------------------------------ the phone's figures

    private static final Pattern PSS = Pattern.compile("TOTAL(?: PSS)?:?\\s+([\\d,]+)"), JAVA_HEAP = Pattern.compile("Java Heap:\\s+([\\d,]+)");

    /** The app's memory as Android counts it ({@code dumpsys meminfo <app>}: TOTAL PSS), kB; -1 when it does not say. */
    static long pssKb(String meminfo) {
        return number(PSS, meminfo);
    }

    /** Its Java heap of that ("Java Heap:" in the App Summary), kB; -1 when it does not say. */
    static long javaHeapKb(String meminfo) {
        return number(JAVA_HEAP, meminfo);
    }

    private static long number(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : -1;
    }

    /** Bytes as the film says them: kB below a megabyte, else MB with one decimal. */
    static String bytes(long b) {
        return b < 1_000_000 ? (b + 999) / 1000 + " kB" : String.format(java.util.Locale.ROOT, "%.1f MB", b / 1e6);
    }
}
