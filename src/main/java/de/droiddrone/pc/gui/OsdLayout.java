package de.droiddrone.pc.gui;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Which OSD elements are on and where they are (SETTINGS > OSD). Immutable: the editor makes a new one for every change
 * and hands it to the video view, which paints with whatever layout it has. Stored in the settings file as
 * {@code osd.<key> = on|off,x,y}; a missing or broken entry means the element's default.
 */
final class OsdLayout {
    enum Align { LEFT, CENTER, RIGHT }

    /** x, y: fractions of the picture, see {@link OsdElement}. */
    record Placement(boolean on, double x, double y) {
        /** Left third: left-aligned at x; right third: right-aligned at x; between: centred on x. */
        Align align() {
            return x < 1 / 3.0 ? Align.LEFT : x > 2 / 3.0 ? Align.RIGHT : Align.CENTER;
        }
    }

    static final String KEY_PREFIX = "osd.";
    private static final OsdLayout DEFAULTS = new OsdLayout(defaultPlacements());

    private final Map<OsdElement, Placement> placements;

    private OsdLayout(Map<OsdElement, Placement> placements) {
        this.placements = placements;
    }

    static OsdLayout defaults() {
        return DEFAULTS;
    }

    static Placement defaultPlacement(OsdElement e) {
        return new Placement(e.defaultOn, e.defaultX, e.defaultY);
    }

    private static Map<OsdElement, Placement> defaultPlacements() {
        Map<OsdElement, Placement> map = new EnumMap<>(OsdElement.class);
        for (OsdElement e : OsdElement.values()) map.put(e, defaultPlacement(e));
        return map;
    }

    /** @param settings the settings file's value for a key, "" or null when it has none */
    static OsdLayout load(Function<String, String> settings) {
        Map<OsdElement, Placement> map = defaultPlacements();
        for (OsdElement e : OsdElement.values()) {
            Placement p = parse(settings.apply(KEY_PREFIX + e.key), e);
            if (p != null) map.put(e, p);
        }
        return new OsdLayout(map);
    }

    private static Placement parse(String value, OsdElement e) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.trim().split(",");
        if (parts.length != 3) return null;
        boolean on;
        if (parts[0].trim().equalsIgnoreCase("on")) on = true;
        else if (parts[0].trim().equalsIgnoreCase("off")) on = false;
        else return null;
        try {
            double x = Double.parseDouble(parts[1].trim()), y = Double.parseDouble(parts[2].trim());
            if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) return null;
            if (!e.movable) {
                x = e.defaultX;
                y = e.defaultY;
            }
            return new Placement(on || !e.hideable, x, y);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    Placement get(OsdElement e) {
        return placements.get(e);
    }

    boolean isOn(OsdElement e) {
        return placements.get(e).on();
    }

    /** This layout with one element changed (kept on if it can't be turned off, kept in place if it can't move). */
    OsdLayout with(OsdElement e, Placement p) {
        Map<OsdElement, Placement> map = new EnumMap<>(placements);
        map.put(e, new Placement(p.on() || !e.hideable, e.movable ? clamp(p.x()) : e.defaultX, e.movable ? clamp(p.y()) : e.defaultY));
        return new OsdLayout(map);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Every element as the settings file stores it. */
    Map<String, String> toSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        for (OsdElement e : OsdElement.values()) out.put(KEY_PREFIX + e.key, format(placements.get(e)));
        return out;
    }

    private static String format(Placement p) {
        return String.format(Locale.ROOT, "%s,%.5f,%.5f", p.on() ? "on" : "off", p.x(), p.y());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OsdLayout l && l.placements.equals(placements);
    }

    @Override
    public int hashCode() {
        return placements.hashCode();
    }
}
