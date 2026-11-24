package de.droiddrone.bench.phone;

import de.droiddrone.bench.BenchControl;
import de.droiddrone.bench.PhoneRig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BS9's reading of what Android says of the app's backup and of the gallery's index ({@link PhoneStore}), on text as Android prints it. */
class PhoneStoreTest {
    private static final String RELEASED = PhoneRig.APP, BENCH = BenchControl.PACKAGE;

    /** dumpsys package of both DRAUPNE Flights, the bench build's block first; another app's beside them allows backup. */
    private static final String PACKAGES = """
            Activity Resolver Table:
              Non-Data Actions:
                  android.intent.action.MAIN:
                    a1b2c3d de.droiddrone.flight.pc/de.droiddrone.flight.MainActivity filter 1f2e3d4

            Packages:
              Package [de.droiddrone.flight.pc.bench] (5e6f7a8):
                userId=10181
                pkg=Package{9b0c1d2 de.droiddrone.flight.pc.bench}
                versionCode=2030059 minSdk=26 targetSdk=34
                versionName=2.3.0-pc.59-beta-bench
                flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY LARGE_HEAP ]
                privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE ALLOW_AUDIO_PLAYBACK_CAPTURE ]
                pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY LARGE_HEAP ]
              Package [de.droiddrone.flight.pc] (3c4d5e6):
                userId=10180
                versionCode=2030055 minSdk=26 targetSdk=34
                versionName=2.3.0-pc.55
                flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]
                pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]
              Package [com.example.notes] (7f8a9b0):
                versionName=1.0
                flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]

            Queries:
              system apps queryable: false
            """;

    @Test
    void eachAppsFlagsAreReadFromItsOwnBlock() {
        Set<String> bench = PhoneStore.flags(PACKAGES, BENCH), released = PhoneStore.flags(PACKAGES, RELEASED);
        assertEquals(Set.of("HAS_CODE", "ALLOW_CLEAR_USER_DATA", "TEST_ONLY", "LARGE_HEAP"), bench, "privateFlags are no flags of backup's");
        assertFalse(PhoneStore.allowsBackup(bench));
        assertEquals(Set.of("HAS_CODE", "ALLOW_CLEAR_USER_DATA", "LARGE_HEAP"), released, "not the next app's ALLOW_BACKUP");
        assertFalse(PhoneStore.allowsBackup(released));
        assertTrue(PhoneStore.allowsBackup(PhoneStore.flags(PACKAGES, "com.example.notes")));
        assertEquals("2.3.0-pc.59-beta-bench", PhoneStore.versionName(PACKAGES, BENCH));
        assertEquals("2.3.0-pc.55", PhoneStore.versionName(PACKAGES, RELEASED));
        assertEquals(Set.of(), PhoneStore.flags(PACKAGES, "com.example.absent"));
        assertEquals(Set.of(), PhoneStore.flags("Unable to find package: x", RELEASED));
        assertEquals("", PhoneStore.versionName(null, RELEASED));
        // an app that allows backup is seen as one, whether its flags are on one line or two
        String allowed = "Packages:\n  Package [" + RELEASED + "] (1a2b3c4):\n    flags=[ HAS_CODE ALLOW_BACKUP ]\n";
        assertTrue(PhoneStore.allowsBackup(PhoneStore.flags(allowed, RELEASED)));
    }

    @Test
    void onlyTheLinesThatNameTheAppAreKeptOfAndroidsBackup() {
        String none = """
                Backup Manager is enabled / provisioned / not pending init
                Auto-restore is enabled
                Last backup pass started: 1732400000000 (now = 1732410000000)
                Transport whitelist:
                    com.android.localtransport/.LocalTransportService
                Available transports:
                  * com.google.android.gms/.backup.BackupTransportService
                       destination: someone (an account)
                Pending init: 0
                Ever backed up: 2
                    com.example.notes
                    de.droiddrone.flight.pc.benchmark
                Pending key/value backup: 0
                Full backup queue:1
                    1732400000000 : com.example.notes
                """;
        PhoneStore.Backup b = PhoneStore.backup(none, List.of(RELEASED, BENCH));
        assertEquals(Boolean.TRUE, b.enabled());
        assertEquals(List.of(), b.lines(), "another package whose name only begins as the app's is not the app");
        String queued = none.replace("1732400000000 : com.example.notes", "1732400000000 : " + RELEASED);
        assertEquals(List.of("1732400000000 : " + RELEASED), PhoneStore.backup(queued, List.of(RELEASED, BENCH)).lines());
        assertEquals(List.of(), PhoneStore.backup(queued, List.of(BENCH)).lines(), "the bench build's name holds the released app's, not the other way round");
        PhoneStore.Backup off = PhoneStore.backup("Backup Manager is disabled / not provisioned / not pending init\n", List.of(RELEASED));
        assertEquals(Boolean.FALSE, off.enabled());
        assertNull(PhoneStore.backup("", List.of(RELEASED)).enabled());
    }

    @Test
    void theGallerysAnswerIsCountedAndOnlyDraupnesEntriesKept() {
        assertEquals(42, PhoneStore.count("42\n"));
        assertEquals(0, PhoneStore.count("0"));
        assertEquals(-1, PhoneStore.count("Error while accessing provider:media"));
        assertTrue(PhoneStore.answered(42, ""));
        assertTrue(PhoneStore.answered(0, "No result found."));
        assertFalse(PhoneStore.answered(0, "Error while accessing provider:media\njava.lang.SecurityException: ..."));
        assertFalse(PhoneStore.answered(-1, ""));
        assertEquals(List.of(), PhoneStore.rows(""));
        assertEquals(List.of("/storage/emulated/0/Android/media/" + RELEASED + "/Video/flight-1.mp4", "Row: 9 _id=31"),
                PhoneStore.rows("Row: 3 _data=/storage/emulated/0/Android/media/" + RELEASED + "/Video/flight-1.mp4\nRow: 9 _id=31\nNo result found.\n"));
    }
}
