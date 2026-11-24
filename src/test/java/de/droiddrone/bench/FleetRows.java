package de.droiddrone.bench;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The map window's FLEET panel as the scenarios use it: opened, its rows found, a row's menu read - and what a row
 * shows, read off the card it paints (its rows are painted, so there is no label to read: the card is had by
 * reflection, read only, as {@code FleetRowsTest} holds the field and accessor names to the app's classes).
 */
public final class FleetRows {
    private FleetRows() {
    }

    /** The panel's row class and its two fields: the phone's name and the card it paints (FleetManagementPanel.RowView). */
    static final String ROW_CLASS = "RowView", NAME_FIELD = "phone", CARD_FIELD = "card";
    /** The card's accessors read here (FleetCards.Card). */
    static final List<String> CARD_PARTS = List.of("badge", "kind", "queued", "heardAgoMs", "phonePercent", "virtual");

    /**
     * What one row shows.
     * @param badge        ACTIVE / CONNECTED / STANDBY / ASLEEP / OVERWATCH / LOST / UNKNOWN / OUT OF ACTION
     * @param kind         the map's sign for it (MapView.Kind's name)
     * @param queued       a mode waiting for the phone, else null
     * @param heardAgoMs   since it was last heard, -1 never
     * @param phonePercent the phone's battery, -1 not known
     * @param virtual      on this PC's loopback (VIRTUAL: the made-up drones, and a phone reached through the bench's wire)
     */
    public record Row(String name, String badge, String kind, String queued, long heardAgoMs, int phonePercent, boolean virtual) {
    }

    /** The map window popped out and its FLEET panel open, a row pressable. */
    public static void open(Ui ui) {
        if (ui.has("POP OUT MAP")) ui.click("POP OUT MAP");
        ui.until("the map window", 10_000, () -> ui.window("DRAUPNE Link map") != null);
        // the rows are there also while the panel is folded away: open means a row can be pressed
        if (!rowsOpen(ui)) ui.click("FLEET");
        ui.until("the fleet's rows", 8000, () -> rowsOpen(ui));
    }

    private static boolean rowsOpen(Ui ui) {
        List<Component> rows = rows(ui);
        return !rows.isEmpty() && ui.whyNotPressable(rows.get(0), new java.awt.Point(100, 15)) == null;
    }

    /** The rows showing, top first. */
    public static List<Component> rows(Ui ui) {
        List<Component> rows = new ArrayList<>();
        for (Component c : ui.components()) if (c.getClass().getSimpleName().equals(ROW_CLASS) && c.getWidth() > 0) rows.add(c);
        return rows;
    }

    /** The words of a pop-up menu's row that begins like this. */
    public static String menuRow(Ui ui, String start) {
        for (String b : ui.buttons()) if (b.startsWith(start)) return b;
        throw new AssertionError("No menu row begins with \"" + start + "\": " + ui.buttons());
    }

    /** What every row showing says now, top first (a row whose card cannot be read is left out). */
    public static List<Row> read(Ui ui) {
        List<Row> out = new ArrayList<>();
        for (Component c : rows(ui)) {
            Row r = Ui.edt(() -> row(c));
            if (r != null) out.add(r);
        }
        return out;
    }

    /** The row of that phone (any case) among these; null when there is none. */
    public static Row of(List<Row> rows, String phone) {
        for (Row r : rows) if (r.name().equalsIgnoreCase(phone)) return r;
        return null;
    }

    /** One row's card (the Swing thread's); null when it has none yet or cannot be read. */
    static Row row(Component c) {
        try {
            Object card = field(c, CARD_FIELD);
            if (card == null) return null;
            String name = String.valueOf(field(c, NAME_FIELD));
            Object queued = part(card, "queued");
            return new Row(name, String.valueOf(part(card, "badge")), String.valueOf(part(card, "kind")), queued == null ? null : String.valueOf(queued),
                    ((Number) part(card, "heardAgoMs")).longValue(), ((Number) part(card, "phonePercent")).intValue(), (Boolean) part(card, "virtual"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object part(Object card, String accessor) throws ReflectiveOperationException {
        Method m = card.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return m.invoke(card);
    }
}
