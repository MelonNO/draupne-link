package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BeepersTest {
    // Betaflight 2025.12.5's beeper table as `beeper list` prints it
    static final String LIST = "Available: GYRO_CALIBRATED RX_LOST RX_LOST_LANDING DISARMING ARMING ARMING_GPS_FIX BAT_CRIT_LOW"
            + " BAT_LOW GPS_STATUS RX_SET ACC_CALIBRATION ACC_CALIBRATION_FAIL READY_BEEP MULTI_BEEPS DISARM_REPEAT ARMED"
            + " SYSTEM_INIT ON_USB BLACKBOX_ERASE CRASHFLIP CAM_CONNECTION_OPEN CAM_CONNECTION_CLOSE ARMING_GPS_NO_FIX ALL\r\n";

    @Test
    void aDefaultBetaflightGetsEverySoundButTheFourOffAndTheBeaconOnTheButton() {
        BetaflightPlan.Change c = Beepers.plan(LIST, "Disabled:  none\r\n", "Disabled:  RX_LOST  RX_SET\r\n");
        assertNotNull(c);
        List<String> commands = c.commands();
        assertTrue(commands.contains("beeper -GYRO_CALIBRATED"));
        assertTrue(commands.contains("beeper -READY_BEEP"));
        assertTrue(commands.contains("beeper -BAT_LOW"));
        assertTrue(commands.contains("beeper -ARMING_GPS_NO_FIX"));
        for (String keep : Beepers.KEEP) {
            assertFalse(commands.contains("beeper -" + keep), keep + " stays on");
        }
        assertFalse(commands.contains("beeper -ALL"));
        // 1.0.71 (the pilot, 2026-11-18: it beeps when the link is lost - the buzzer and the motors): both beacons on
        assertEquals(List.of("beacon RX_SET", "beacon RX_LOST"), commands.subList(commands.size() - 2, commands.size()));
        assertEquals(19 + 2, commands.size(), "23 conditions, 4 kept, plus the two beacons");
        assertTrue(c.description().contains("a lost link"), c.description());
    }

    @Test
    void aKeptSoundThatWasOffGoesOnAndAllSetMeansNothing() {
        String disabled = "Disabled:  GYRO_CALIBRATED  RX_LOST  DISARMING  ARMING  ARMING_GPS_FIX  BAT_CRIT_LOW  BAT_LOW"
                + "  GPS_STATUS  ACC_CALIBRATION  ACC_CALIBRATION_FAIL  READY_BEEP  MULTI_BEEPS  DISARM_REPEAT  ARMED  SYSTEM_INIT"
                + "  BLACKBOX_ERASE  CRASHFLIP  CAM_CONNECTION_OPEN  CAM_CONNECTION_CLOSE  ARMING_GPS_NO_FIX\r\n";
        BetaflightPlan.Change c = Beepers.plan(LIST, disabled, "Disabled:  none\r\n");
        assertNotNull(c);
        assertEquals(List.of("beeper RX_LOST"), c.commands());
        assertNull(Beepers.plan(LIST, disabled.replace("  RX_LOST", ""), "Disabled:  none\r\n"));
        // a drone set up before 1.0.71: only the lost receiver's beacon is still to switch on
        BetaflightPlan.Change beacon = Beepers.plan(LIST, disabled.replace("  RX_LOST", ""), "Disabled:  RX_LOST\r\n");
        assertNotNull(beacon);
        assertEquals(List.of("beacon RX_LOST"), beacon.commands());
    }

    @Test
    void repliesNotUnderstoodChangeNothing() {
        assertNull(Beepers.plan("###ERROR IN beeper: INVALID NAME\r\n", "", "Unknown command, try 'help'\r\n"));
        // no DShot: no beacon command, the beepers all the same
        BetaflightPlan.Change c = Beepers.plan(LIST, "Disabled:  none", "Unknown command, try 'help'");
        assertNotNull(c);
        assertFalse(c.commands().stream().anyMatch(x -> x.startsWith("beacon")));
    }
}
