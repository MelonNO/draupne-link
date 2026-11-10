package de.droiddrone.pc.gui;

import de.droiddrone.pc.Names;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The tour's six drones marked in the observation picker (2026-11-10, the pilot: "the six drones that should be selected is marked"). */
class ObservePickerTest {
    private static Names.Entry phone(String name) {
        return new Names.Entry(name, Names.Kind.PHONE, "100.64.0." + (name.length() + 1), "", "", "2.3.0-pc.45", 0, true);
    }

    @Test
    void theToursDronesComeFirstMarkedTour() {
        List<Names.Entry> phones = List.of(phone("Mine"), phone("Alpha"), phone("Other"), phone("Bravo"));
        List<Names.Entry> ordered = ObservePicker.order(phones, Set.of("alpha", "BRAVO"));
        assertEquals(List.of("Alpha", "Bravo", "Mine", "Other"), ordered.stream().map(Names.Entry::name).toList());
        assertTrue(ObservePicker.label(phones.get(1), "  -  online", true).startsWith("Alpha  TOUR"));
        assertFalse(ObservePicker.label(phones.get(0), "  -  online", false).contains("TOUR"));
        assertTrue(ObservePicker.isMarked("Bravo", Set.of("bravo")));
    }

    @Test
    void outsideTheTourNothingIsMarkedOrMoved() {
        List<Names.Entry> phones = List.of(phone("Mine"), phone("Alpha"));
        assertSame(phones, ObservePicker.order(phones, Set.of()));
        assertFalse(ObservePicker.isMarked("Alpha", Set.of()));
    }
}
