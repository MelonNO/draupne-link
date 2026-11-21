package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-070d (a reader of the code, 2026-11-22): the phone paced its stick writes on the wall clock - a clock set back N
 * seconds (the network corrects it, in flight too) meant no sticks to the flight controller for N seconds: MSP below
 * the PC's rate of 255, MAVLink at every rate.
 */
class RcPaceTest {
    @Test
    void aClockSetBackHoldsNoFrameBack() {
        RcPace pace = new RcPace();
        pace.setMinPeriod(10); // 50 frames a second announced: half a frame time
        assertTrue(pace.take(3_600_000));
        assertTrue(pace.take(3_600_010));
        // the clock steps back a minute: the very next frame goes on, and the pace is kept from there
        assertTrue(pace.take(3_540_014), "not held back until the clock has caught up - a minute without sticks");
        assertFalse(pace.take(3_540_018));
        assertTrue(pace.take(3_540_024));
        int taken = 0;
        for (long t = 3_540_030; t < 3_541_030; t += 4) if (pace.take(t)) taken++;
        assertTrue(taken >= 80, "a second of 250 frames a second at a 10 ms pace: " + taken);
    }

    @Test
    void belowThePcsRateAFrameWithinHalfAFrameTimeOfTheLastIsDropped() {
        RcPace pace = new RcPace();
        pace.setMinPeriod(10);
        assertTrue(pace.pacing());
        assertTrue(pace.take(1000));
        assertFalse(pace.take(1004), "upstream's rule, kept");
        assertFalse(pace.take(1009));
        assertTrue(pace.take(1010));
        assertFalse(pace.take(1012));
    }

    @Test
    void atThePcsRateEveryFrameGoesOnAndNoClockIsNeeded() {
        RcPace pace = new RcPace();
        assertFalse(pace.pacing(), "nothing set: no pace");
        pace.setMinPeriod(0); // the PC announces 255: its frames are numbered, the stale ones dropped before
        assertFalse(pace.pacing(), "the caller reads no clock at all");
        for (int i = 0; i < 1000; i++) assertTrue(pace.take(5000));
        pace.setMinPeriod(-3);
        assertTrue(pace.take(5000) && pace.take(5000), "a rate that makes no sense paces nothing");
    }

    @Test
    void theFirstFrameGoesOnWhateverTheClockReads() {
        RcPace pace = new RcPace();
        pace.setMinPeriod(500);
        assertTrue(pace.take(3), "a monotonic clock may read next to nothing");
        assertFalse(pace.take(400));
        assertTrue(pace.take(503));
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and ReleaseHasNoBenchCodeTest do. */
    @Test
    void noWallClockOnTheWayFromAStickFrameToItsWrite() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String flight = "Flight/src/main/java/de/droiddrone/flight/";
        // MSP: the frame is built in Msp.setRawRc and written by Serial's writer
        String msp = text(root.resolve("flight-app/overlay/" + flight + "Msp.java"));
        String setRawRc = msp.substring(msp.indexOf("public void setRawRc("), msp.indexOf("boolean idleMayArm("));
        assertFalse(setRawRc.contains("currentTimeMillis"), "Msp.setRawRc paces on the wall clock");
        assertTrue(setRawRc.contains("rcPace.take(android.os.SystemClock.elapsedRealtime())"), "Msp.setRawRc asks RcPace with the monotonic clock");
        assertFalse(text(root.resolve("flight-app/overlay/" + flight + "Serial.java")).contains("currentTimeMillis"), "Serial reads the wall clock");
        // MAVLink: the phone app is built from upstream's file unless the overlay has its own
        Path mavlinkFile = root.resolve("flight-app/overlay/" + flight + "Mavlink.java");
        if (!Files.exists(mavlinkFile)) mavlinkFile = root.resolve("upstream/" + flight + "Mavlink.java");
        String mavlink = text(mavlinkFile);
        String override = mavlink.substring(mavlink.indexOf("public void setRcChannelsOverride("));
        override = override.substring(0, override.indexOf("\n    }"));
        assertFalse(override.contains("currentTimeMillis"), "Mavlink.setRcChannelsOverride paces on the wall clock (" + root.relativize(mavlinkFile) + ")");
        assertTrue(override.contains("rcPace.take(android.os.SystemClock.elapsedRealtime())"), "Mavlink.setRcChannelsOverride asks RcPace with the monotonic clock");
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
