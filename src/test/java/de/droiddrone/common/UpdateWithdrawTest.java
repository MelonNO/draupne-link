package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-070b (a reader of the code, 2026-11-22): a remote update that waits for a tap on the phone was never withdrawn.
 * The phone told the PC "tap needed" for five minutes (the PC refuses to arm meanwhile) and then said nothing more,
 * while Android's "update this app?" stayed on its screen: armed and flying, one tap on it installed the update and
 * ended the app - and the sticks with it.
 */
class UpdateWithdrawTest {
    private static final boolean DISARMED = true, NOT_KNOWN_DISARMED = false, REMOTE = true, LOCAL = false, REPORTED = true, SILENT = false;

    @Test
    void anInstallThatWaitsIsWithdrawnTheMomentTheFlightControllerIsNotKnownDisarmed() {
        // armed, or unknown (a stale status, its USB just back): the guards' rule - only a fresh "disarmed" counts
        assertEquals(UpdateWithdraw.NOT_DISARMED, UpdateWithdraw.now(NOT_KNOWN_DISARMED, REMOTE, REPORTED));
        assertEquals(UpdateWithdraw.NOT_DISARMED, UpdateWithdraw.now(NOT_KNOWN_DISARMED, REMOTE, SILENT));
        assertEquals(UpdateWithdraw.NOT_DISARMED, UpdateWithdraw.now(NOT_KNOWN_DISARMED, LOCAL, SILENT), "one the pilot began on the phone as well");
    }

    @Test
    void aRemoteOneWaitsOnlyAsLongAsThePcIsToldThatItWaits() {
        assertEquals(UpdateWithdraw.KEEP, UpdateWithdraw.now(DISARMED, REMOTE, REPORTED), "disarmed, and the PC refuses to arm: the tap may come");
        // the five minutes are over (or the phone's word to the PC changed): the PC may arm from here on
        assertEquals(UpdateWithdraw.NO_LONGER_REPORTED, UpdateWithdraw.now(DISARMED, REMOTE, SILENT));
    }

    @Test
    void oneThePilotBeganOnThePhoneWaitsForTheTapWhileDisarmed() {
        assertEquals(UpdateWithdraw.KEEP, UpdateWithdraw.now(DISARMED, LOCAL, SILENT), "nothing is told to a PC about it: no window");
        assertEquals(UpdateWithdraw.KEEP, UpdateWithdraw.now(DISARMED, LOCAL, REPORTED));
    }

    @Test
    void atTheAppsStartAnInstallLeftWaitingByAnEarlierRunIsWithdrawn() {
        final int none = -1;
        assertTrue(UpdateWithdraw.leftOver(4711, none, none), "kept on the disk, and this run began none");
        assertTrue(UpdateWithdraw.leftOver(4711, 5000, none), "nor is it this run's");
        assertFalse(UpdateWithdraw.leftOver(4711, 4711, none), "this run's own, being copied or waiting: the app's window was only opened again");
        assertFalse(UpdateWithdraw.leftOver(none, none, none));
        assertFalse(UpdateWithdraw.leftOver(none, 4711, none));
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and ReleaseHasNoBenchCodeTest do. */
    @Test
    void thePhoneKeepsItsInstallSessionsIdAndAbandonsExactlyThat() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String updater = text(flight.resolve("AppUpdater.java")), remote = text(flight.resolve("RemoteUpdate.java"));
        String service = text(flight.resolve("DDService.java")), activity = text(flight.resolve("MainActivity.java"));
        String receiver = text(flight.resolve("AppUpdateReceiver.java"));
        // the session's id is kept where it is created, and marked as waiting where it is committed
        String install = updater.substring(updater.indexOf("private static void install("));
        assertTrue(install.indexOf("int id = installer.createSession(params);") < install.indexOf("sessionCreated(c, id, remote);"), "kept at its creation");
        assertTrue(install.indexOf("sessionCreated(c, id, remote);") < install.indexOf("committed = true;")
                && install.indexOf("committed = true;") < install.indexOf("session.commit("), "waiting from its commit");
        // withdrawn by its own id, at the three times
        assertTrue(updater.contains("getPackageInstaller().abandonSession(id);") && updater.contains("getPackageInstaller().abandonSession(kept);"));
        assertTrue(remote.contains("UpdateWithdraw.now(fcKnownDisarmed, remote, reported)"), "RemoteUpdate.watch asks the rule");
        String tick = service.substring(service.indexOf("private void tick() {"), service.indexOf("mainTimer.schedule(tt, 1000, 1000);"));
        assertTrue(tick.contains("RemoteUpdate.watch(getApplicationContext(), armed == FcArmedState.DISARMED);"), "asked by the service's timer, every second");
        String onCreate = activity.substring(activity.indexOf("protected void onCreate(Bundle savedInstanceState){"));
        assertTrue(onCreate.substring(0, onCreate.indexOf("setContentView(")).contains("AppUpdater.withdrawLeftOver("), "and at the app's start");
        // Android's answer to a withdrawal is not "cancelled on the phone"
        assertTrue(receiver.contains("AppUpdater.sessionEnded("));
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
