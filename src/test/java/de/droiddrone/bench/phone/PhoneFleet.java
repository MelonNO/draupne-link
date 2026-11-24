package de.droiddrone.bench.phone;

import de.droiddrone.bench.FleetRows;
import de.droiddrone.bench.hw.AppLinkLog;
import de.droiddrone.pc.ArmedCheck;
import de.droiddrone.pc.Names;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link PhoneFleetScenario} works out without the phone: the made-up drones' names beside the real one, the
 * real phone's name from the PC's PHONE list, the FLEET words it waits for, what changed in the made-up drones' rows,
 * how often the PC heard the sleeping phone, the rows of the app's link log that say armed.
 */
final class PhoneFleet {
    private PhoneFleet() {
    }

    /** A FLEET row's words (FleetCards.badge), as the scenario waits for them. */
    static final String ACTIVE = "ACTIVE", CONNECTED = "CONNECTED", ASLEEP = "ASLEEP", STANDBY = "STANDBY", UNKNOWN = "UNKNOWN";
    /**
     * While the phone sleeps its row says ASLEEP - or UNKNOWN once a check-in is late that this PC did not hear (the
     * bench's wire hands the phone's packets to whichever socket of the PC spoke through it last: see the scenario).
     */
    static final Set<String> WHILE_ASLEEP = Set.of(ASLEEP, UNKNOWN);

    /** The name at the start of a PHONE list entry ("MobileAlpha  online" -> MobileAlpha); "" for none. */
    static String nameOf(String entry) {
        if (entry == null || entry.isBlank()) return "";
        return entry.trim().split("\\s+")[0];
    }

    /**
     * Names for the made-up drones: the fleet's own way, from Zulu down, but never the real phone's (it keeps its own:
     * two devices with one name and the PC refuses to connect to either).
     */
    static List<String> madeUpNames(String realPhone, int count) {
        List<String> out = new ArrayList<>();
        for (int i = Names.LETTERS.length - 1; i >= 0 && out.size() < count; i--) {
            String n = Names.Kind.PHONE.name(i);
            if (!n.equalsIgnoreCase(realPhone)) out.add(n);
        }
        return out;
    }

    /** Each made-up drone's row against its first badge: what is missing or says something else now ("MobileZulu: ACTIVE -> UNKNOWN"). */
    static List<String> changed(Map<String, String> firstBadges, List<FleetRows.Row> now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : firstBadges.entrySet()) {
            FleetRows.Row r = FleetRows.of(now, e.getKey());
            if (r == null) out.add(e.getKey() + ": no row");
            else if (!r.badge().equals(e.getValue())) out.add(e.getKey() + ": " + e.getValue() + " -> " + r.badge());
        }
        return out;
    }

    /**
     * How often the PC heard the phone again, from the row's "heard" (ms since) read every few seconds: it only grows
     * between two hearings, so each time it falls - by more than a second, the card's own rounding - is one (-1, never
     * heard, is skipped).
     */
    static int heardAgain(List<Long> heardAgoMs) {
        int n = 0;
        long last = -1;
        for (long h : heardAgoMs) {
            if (h < 0) continue;
            if (last >= 0 && h + 1000 < last) n++;
            last = h;
        }
        return n;
    }

    /** The question the app asks before an action that stops the sticks (F-062, ArmedCheck), by its window's title. */
    enum Question {
        /** No such question. */
        NONE,
        /** "DEEP SLEEP - armed?": not known yet - the link's first seconds; answered with the action, as a pilot on the ground does. */
        NOT_KNOWN,
        /** "DEEP SLEEP while armed?": armed, or armed moments ago - never answered by the bench. */
        ARMED
    }

    static Question question(String windowTitle, String action) {
        if (windowTitle == null || action == null) return Question.NONE;
        if (windowTitle.equals(ArmedCheck.NOT_KNOWN.title(action))) return Question.NOT_KNOWN;
        if (windowTitle.equals(ArmedCheck.ARMED.title(action)) || windowTitle.equals(ArmedCheck.MAY_FLY.title(action))) return Question.ARMED;
        return Question.NONE;
    }

    static long count(String text, String part) {
        long n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    /** The app's link log rows that say the flight controller was armed (blank: not known, 0: disarmed). */
    static long armed(List<AppLinkLog.Row> rows) {
        return rows.stream().filter(r -> {
            String a = r.armed() == null ? "" : r.armed().trim();
            return !a.isEmpty() && !a.equals("0") && !a.equalsIgnoreCase("false");
        }).count();
    }
}
