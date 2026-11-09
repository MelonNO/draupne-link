package de.droiddrone.pc.observe;

import de.droiddrone.common.Observe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The grid's own state off the window: a muted tile's sound, the saved volumes, the line under each tile. */
class ObservationSessionTest {
    private static ObservationSession session() {
        ObserveSet set = new ObserveSet();
        set.add("MobileAlpha");
        return new ObservationSession(set, List.of(), null);
    }

    @Test
    void aMutedTileStopsAskingForSoundAfterAWhile() {
        ObservationSession o = session();
        assertTrue(o.audioWanted("MobileAlpha", 1000));
        assertFalse(o.isMuted("MobileAlpha"));
        o.setMuted("MobileAlpha", true, 1000);
        assertTrue(o.isMuted("MobileAlpha"));
        assertTrue(o.audioWanted("MobileAlpha", 1000 + ObservationSession.MUTED_STOP_MS - 1), "as the pilot's MUTE: a while yet");
        assertFalse(o.audioWanted("MobileAlpha", 1000 + ObservationSession.MUTED_STOP_MS));
        o.setMuted("MobileAlpha", false, 20_000);
        assertTrue(o.audioWanted("MobileAlpha", 20_000));

        o.setMuted("MobileBravo", true, 5000 - ObservationSession.MUTED_STOP_MS); // muted from the start
        assertFalse(o.audioWanted("MobileBravo", 5000), "no sound asked from the first look");
    }

    @Test
    void theSavedVolumeIsKeptInRange() {
        assertEquals(100, ObservationSession.volume(""));
        assertEquals(40, ObservationSession.volume("40"));
        assertEquals(0, ObservationSession.volume("-5"));
        assertEquals(100, ObservationSession.volume("250"));
        assertEquals(100, ObservationSession.volume("loud"));
    }

    @Test
    void theLineUnderATile() {
        long now = 100_000;
        int[] asked = {0};
        java.util.function.Supplier<int[]> picture = () -> {
            asked[0]++;
            return new int[]{1280, 720, 30};
        };
        Observe.State refused = new Observe.State(Observe.ENDED_PILOT, 0, 0, 0, 0);
        assertEquals("NOT STREAMING: " + Observe.stateName(Observe.ENDED_PILOT),
                ObservationSession.tileStatus(refused, now - 1000, now, false, picture, false, now));
        assertEquals("Asking the phone...", ObservationSession.tileStatus(refused, now - 3000, 0, false, picture, false, now),
                "an old state no longer shows");
        assertEquals("DEEP SLEEP - joins at next check-in", ObservationSession.tileStatus(null, 0, 0, true, picture, false, now));
        assertEquals("NO SIGNAL for 4 s", ObservationSession.tileStatus(null, 0, now - 4000, false, picture, false, now));
        assertEquals(0, asked[0], "the picture's figures only while it streams");
        assertEquals("1280x720  30 FPS  SHARP", ObservationSession.tileStatus(null, 0, now - 100, false, picture, true, now));
        assertEquals("Waiting for the picture...", ObservationSession.tileStatus(null, 0, now - 100, false, () -> new int[]{0, 0, 0}, false, now));
    }
}
