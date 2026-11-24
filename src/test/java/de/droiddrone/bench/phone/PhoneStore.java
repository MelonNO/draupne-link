package de.droiddrone.bench.phone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BS9, the phone's storage and backup, read: what Android keeps of the installed app ({@code dumpsys package}: its
 * flags, ALLOW_BACKUP among them or not), what Android's backup holds or queues of it ({@code dumpsys backup}) and what
 * the media store's index - the gallery's - lists of it ({@code content query}). Nothing here starts a backup: that
 * would send the app's data off the phone. Pure: {@code PhoneStoreTest}.
 */
final class PhoneStore {
    private PhoneStore() {
    }

    private static final Pattern FLAGS = Pattern.compile("(?m)^\\s*(?:pkgFlags|flags)=\\[([^\\]]*)]");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*versionName=(\\S+)");
    /** A line that ends a package's block: another package's head, or a section that starts in the first column. */
    private static final Pattern BLOCK_END = Pattern.compile("(?m)^(?:\\S|\\s*Package \\[)");

    /** The lines of {@code dumpsys package}'s block of that app ("Package [app] (...):" and what follows it); "" when there is none. */
    static String block(String dumpsysPackage, String app) {
        String text = dumpsysPackage == null ? "" : dumpsysPackage;
        String head = "Package [" + app + "]";
        int at = text.indexOf(head);
        if (at < 0) return "";
        int from = text.indexOf('\n', at);
        if (from < 0) return text.substring(at);
        Matcher end = BLOCK_END.matcher(text);
        return end.find(from + 1) ? text.substring(at, end.start()) : text.substring(at);
    }

    /** The flags Android keeps of the app ({@code flags=[ ... ]}, and {@code pkgFlags=[ ... ]} where it prints those too); empty when its block is not there. */
    static Set<String> flags(String dumpsysPackage, String app) {
        Set<String> out = new TreeSet<>();
        Matcher m = FLAGS.matcher(block(dumpsysPackage, app));
        while (m.find()) for (String w : m.group(1).trim().split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** The app's version as its block says it; "" when it does not. */
    static String versionName(String dumpsysPackage, String app) {
        Matcher m = VERSION.matcher(block(dumpsysPackage, app));
        return m.find() ? m.group(1) : "";
    }

    /** Android lets a backup of the app be made: its flags say ALLOW_BACKUP. */
    static boolean allowsBackup(Set<String> flags) {
        return flags.contains("ALLOW_BACKUP");
    }

    /**
     * What {@code dumpsys backup} says of these apps.
     *
     * @param enabled whether Android's backup is on for the phone's user; null when it does not say
     * @param lines   every line that names one of the apps (a participant, one backed up, one queued)
     */
    record Backup(Boolean enabled, List<String> lines) {
    }

    private static final Pattern ENABLED = Pattern.compile("Backup Manager is (enabled|disabled)");

    /** Only the lines that name the apps are kept: the rest of what Android says there (other apps, accounts) is not the bench's. */
    static Backup backup(String dumpsysBackup, Collection<String> apps) {
        String text = dumpsysBackup == null ? "" : dumpsysBackup;
        Matcher e = ENABLED.matcher(text);
        Boolean enabled = e.find() ? Boolean.valueOf(e.group(1).equals("enabled")) : null;
        List<Pattern> names = new ArrayList<>();
        // a whole name: de.droiddrone.flight.pc is not found inside de.droiddrone.flight.pc.bench, nor the other way round
        for (String app : apps) names.add(Pattern.compile("(?<![\\w.])" + Pattern.quote(app) + "(?![\\w.])"));
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            for (Pattern n : names) {
                if (n.matcher(line).find()) {
                    lines.add(line.trim());
                    break;
                }
            }
        }
        return new Backup(enabled, lines);
    }

    /** The number {@code grep -c} printed; -1 when it printed none. */
    static int count(String grepCount) {
        String s = grepCount == null ? "" : grepCount.trim();
        return s.matches("\\d{1,9}") ? Integer.parseInt(s) : -1;
    }

    /** The rows {@code content query} printed ("Row: 3 _data=/storage/..."), each as its path where it has one. */
    static List<String> rows(String text) {
        List<String> out = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\\R")) {
            String l = line.trim();
            if (!l.startsWith("Row:")) continue;
            int data = l.indexOf("_data=");
            out.add(data >= 0 ? l.substring(data + "_data=".length()) : l);
        }
        return out;
    }

    /** Whether the index answered at all: rows counted, or its own word that it has none. */
    static boolean answered(int rows, String besidesRows) {
        return rows > 0 || rows == 0 && besidesRows != null && besidesRows.contains("No result found");
    }
}
