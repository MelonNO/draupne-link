package de.droiddrone.bench;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FLEET's rows are painted, so {@link FleetRows} reads what a row shows off the card it paints, by reflection: the
 * names it reads by are held here to the app's own classes, and the reading itself to a stand-in row.
 */
class FleetRowsTest {
    @Test
    void theRowAndCardItReadsAreStillTheApps() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        Class<?> row = Class.forName("de.droiddrone.pc.gui.FleetManagementPanel$" + FleetRows.ROW_CLASS, false, loader);
        Class<?> card = Class.forName("de.droiddrone.pc.gui.FleetCards$Card", false, loader);
        assertEquals(String.class, row.getDeclaredField(FleetRows.NAME_FIELD).getType(), "the row's phone name");
        Field c = row.getDeclaredField(FleetRows.CARD_FIELD);
        assertEquals(card, c.getType(), "the row's card");
        Map<String, Class<?>> types = Map.of("badge", String.class, "queued", String.class, "heardAgoMs", long.class, "phonePercent", int.class,
                "virtual", boolean.class);
        for (String part : FleetRows.CARD_PARTS) {
            Class<?> got = card.getDeclaredMethod(part).getReturnType();
            if (part.equals("kind")) assertEquals("Kind", got.getSimpleName(), "the card's kind: the map's sign");
            else assertEquals(types.get(part), got, "the card's " + part);
        }
    }

    // ------------------------------------------------------------------ a stand-in row, as the panel has it

    enum Kind { ASLEEP }

    record Card(String badge, Kind kind, String queued, long heardAgoMs, int phonePercent, boolean virtual) {
    }

    static final class RowView extends Component {
        private final String phone;
        private final Card card;

        RowView(String phone, Card card) {
            this.phone = phone;
            this.card = card;
        }
    }

    @Test
    void aRowIsReadOffItsCardAndFoundByItsNameInAnyCase() {
        FleetRows.Row r = FleetRows.row(new RowView("MobileAlpha", new Card("ASLEEP", Kind.ASLEEP, null, 12_000, 87, true)));
        assertEquals(new FleetRows.Row("MobileAlpha", "ASLEEP", "ASLEEP", null, 12_000, 87, true), r);
        FleetRows.Row q = FleetRows.row(new RowView("MobileZulu", new Card("ACTIVE", Kind.ASLEEP, "DEEP SLEEP", -1, -1, true)));
        assertEquals("DEEP SLEEP", q.queued());
        assertNull(FleetRows.row(new RowView("MobileBravo", null)), "a row with no card yet is left out");
        assertEquals(r, FleetRows.of(List.of(q, r), "mobilealpha"));
        assertNull(FleetRows.of(List.of(q, r), "MobileCharlie"));
    }
}
