package de.droiddrone.pc.observe;

import de.droiddrone.pc.Names;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The phones chosen for OBSERVATION (1.0.48), remembered as a set in the settings ({@link #KEY}: names, comma
 * separated), up to {@link #MAX} - and the rules for which may join: idle phones only (one another PC flies can't be
 * added; one that gets a pilot while in the grid leaves it, the pilot's takeover rules untouched), phone apps of
 * pc.37 or newer. Which tile is selected or enlarged, and so asks its phone for the sharp picture.
 */
public final class ObserveSet {
    public static final String KEY = "observe.phones";
    public static final int MAX = 6;
    /** The first phone app that streams to observers. */
    public static final int MIN_PC_BUILD = 37;

    private final List<String> phones = new ArrayList<>();
    private int selected = -1, enlarged = -1;

    public static ObserveSet parse(String saved) {
        ObserveSet s = new ObserveSet();
        if (saved != null) for (String n : saved.split(",")) s.add(n.trim());
        return s;
    }

    public String encode() {
        return String.join(",", phones);
    }

    public List<String> phones() {
        return List.copyOf(phones);
    }

    public int size() {
        return phones.size();
    }

    public boolean contains(String phone) {
        return indexOf(phone) >= 0;
    }

    public int indexOf(String phone) {
        for (int i = 0; i < phones.size(); i++) if (phones.get(i).equalsIgnoreCase(phone)) return i;
        return -1;
    }

    /** @return added (not there yet, room left, a name) */
    public boolean add(String phone) {
        if (phone == null || phone.isBlank() || contains(phone) || phones.size() >= MAX) return false;
        phones.add(phone);
        return true;
    }

    public boolean remove(String phone) {
        int i = indexOf(phone);
        if (i < 0) return false;
        phones.remove(i);
        if (selected == i) selected = -1;
        else if (selected > i) selected--;
        if (enlarged == i) enlarged = -1;
        else if (enlarged > i) enlarged--;
        return true;
    }

    /** Why this phone can't join the grid now, or null when it can: another PC flies it, or its app is too old. */
    public static String refusal(Names.Position where, String appVersion) {
        if (where != null && where.flown()) return "another PC flies it";
        int build = de.droiddrone.pc.FcTelemetry.pcBuild(appVersion);
        if (appVersion != null && !appVersion.isEmpty() && build < MIN_PC_BUILD) {
            return "its app is older than 2.3.0-pc." + MIN_PC_BUILD;
        }
        return null;
    }

    // ------------------------------------------------------------------ selection

    public int selected() {
        return selected;
    }

    public int enlarged() {
        return enlarged;
    }

    /** A click on a tile (or its cone on the map): that one is selected; again: none. */
    public void select(int i) {
        selected = i == selected || i < 0 || i >= phones.size() ? -1 : i;
    }

    /** A double-click: enlarged, or back to the grid; the enlarged one is the selected one too. */
    public void toggleEnlarged(int i) {
        if (i < 0 || i >= phones.size()) return;
        if (enlarged == i) {
            enlarged = -1;
        } else {
            enlarged = i;
            selected = i;
        }
    }

    /** The selected or enlarged tile asks its phone for the sharp picture (720p); the rest for the grid's. */
    public boolean wantsSharp(int i) {
        return i >= 0 && (i == selected || i == enlarged);
    }

    @Override
    public String toString() {
        return phones.toString().toLowerCase(Locale.ROOT);
    }
}
