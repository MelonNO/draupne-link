package de.droiddrone.pc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18, the pilot: "always store a last known grid for a phone so that if a drone is lost and
 * runs out of battery it is still possible to find it". Places and names are made up.
 */
class LastKnownTest {
    private static final long T = 1_800_000_000_000L;

    @Test
    void whatIsKnownOfAPhoneSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("last-known.txt");
        LastKnown a = new LastKnown(file);
        a.place("MobileAlpha", 60.1234567, 10.7654321, T);
        a.battery("MobileAlpha", 81, 3.74, T + 1000);
        a.mode("MobileAlpha", "OVERWATCH", T + 2000);
        a.place("MobileBravo", 60.2, 10.8, T + 5000);
        a.saveIfDue(T + 2500);
        assertFalse(Files.exists(file), "not at every change: a few seconds after it");
        a.flush();
        assertTrue(Files.isRegularFile(file));

        LastKnown b = new LastKnown(file); // the app started again: the drone's battery has died meanwhile
        LastKnown.Entry k = b.get("mobilealpha");
        assertNotNull(k, "by name, capitals or not");
        assertTrue(k.hasPlace());
        assertEquals(60.1234567, k.lat(), 1e-7);
        assertEquals(10.7654321, k.lon(), 1e-7);
        assertEquals(T, k.placeAt());
        assertEquals(81, k.phonePercent());
        assertEquals(3.74, k.cellVolts(), 1e-9);
        assertEquals("OVERWATCH", k.mode());
        assertEquals(T + 2000, k.modeAt());
        assertEquals(List.of("MobileBravo", "MobileAlpha"), b.all().stream().map(LastKnown.Entry::name).toList(), "heard of last, first");
    }

    @Test
    void aPlaceWithoutAFixOrOlderThanTheOneHeldIsLeftOut() {
        LastKnown k = new LastKnown(null);
        k.place("MobileAlpha", 0, 0, T);
        assertNull(k.get("MobileAlpha"), "0/0 is no place");
        k.place("MobileAlpha", 60.1, 10.7, T);
        k.place("MobileAlpha", 0, 0, T + 1000);
        k.place("MobileAlpha", 95, 10.7, T + 2000);
        k.place("MobileAlpha", 61.0, 11.0, T - 5000); // an old word arriving late
        assertEquals(60.1, k.get("MobileAlpha").lat(), 1e-9);
        k.place("MobileAlpha", 60.2, 10.7, T + 3000);
        assertEquals(60.2, k.get("MobileAlpha").lat(), 1e-9);
        assertEquals(T + 3000, k.get("MobileAlpha").placeAt());
    }

    @Test
    void theBatteriesAndTheModeKeepWhatIsKnownWhenNothingNewIsSaid() {
        LastKnown k = new LastKnown(null);
        k.battery("MobileAlpha", -1, Double.NaN, T);
        assertNull(k.get("MobileAlpha"), "nothing known: nothing kept");
        k.battery("MobileAlpha", 64, Double.NaN, T);
        k.battery("MobileAlpha", -1, 3.52, T + 1000);
        assertEquals(64, k.get("MobileAlpha").phonePercent(), "the phone's battery stays while only the pack is read");
        assertEquals(3.52, k.get("MobileAlpha").cellVolts(), 1e-9);
        assertFalse(k.get("MobileAlpha").hasPlace());
        k.mode("MobileAlpha", "STANDBY", T + 2000);
        k.mode("MobileAlpha", "", T + 3000);
        k.mode("MobileAlpha", null, T + 3000);
        assertEquals("STANDBY", k.get("MobileAlpha").mode());
        k.mode("MobileAlpha", "DEEP SLEEP", T + 4000);
        assertEquals("DEEP SLEEP", k.get("MobileAlpha").mode());
        assertEquals(T + 4000, k.get("MobileAlpha").modeAt());
    }

    @Test
    void aRenameTakesWhatWasKnownAlongAndAForgottenPhoneIsGone(@TempDir Path dir) {
        Path file = dir.resolve("last-known.txt");
        LastKnown k = new LastKnown(file);
        k.place("MobileAlpha", 60.1, 10.7, T);
        k.rename("MobileAlpha", "MobileZulu", T + 1000);
        assertNull(k.get("MobileAlpha"));
        assertEquals(60.1, k.get("MobileZulu").lat(), 1e-9);
        k.forget("MobileZulu", T + 2000);
        assertNull(k.get("MobileZulu"));
        k.flush();
        assertTrue(new LastKnown(file).all().isEmpty());
    }

    @Test
    void aBrokenLineIsLeftOutAndTheRestRead(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("last-known.txt");
        LastKnown.Entry good = new LastKnown.Entry("MobileAlpha", 60.1, 10.7, T, 55, 3.6, T, "IDLE", T);
        Files.write(file, List.of("nonsense", LastKnown.format(good), "MobileBravo\tx\ty\tz", ""));
        LastKnown k = new LastKnown(file);
        assertEquals(1, k.all().size());
        assertEquals(good, k.get("MobileAlpha"));
    }

    @Test
    void agesAreShort() {
        assertEquals("NOW", LastKnown.age(59_000));
        assertEquals("1 MIN", LastKnown.age(60_000));
        assertEquals("59 MIN", LastKnown.age(59 * 60_000L + 59_000));
        assertEquals("1 H", LastKnown.age(3_600_000L));
        assertEquals("47 H", LastKnown.age(47 * 3_600_000L));
        assertEquals("2 D", LastKnown.age(48 * 3_600_000L));
        assertEquals("NOW", LastKnown.age(-5));
    }
}
