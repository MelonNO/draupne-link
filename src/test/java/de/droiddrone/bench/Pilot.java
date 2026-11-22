package de.droiddrone.bench;

import de.droiddrone.pc.fleet.VirtualDrone;

import java.util.function.BooleanSupplier;

/**
 * What a pilot does, in the bench's words: the window's buttons through {@link Ui}, the sticks and switches through
 * the {@link FakeRadio}. Each move waits for what a pilot would wait for - the button that comes next, the drone's
 * own word that it is armed - so a scenario reads as the flight it is.
 */
public final class Pilot {
    private final AppRig rig;
    private final Ui ui;

    public Pilot(AppRig rig) {
        this.rig = rig;
        this.ui = rig.ui;
    }

    /** PILOT UAV on the phone that is chosen, and the link up. */
    public void connect() {
        ui.click("PILOT UAV");
        connecting(20_000);
        ui.until("LINK READY in the header", 20_000, this::linkUp);
        ui.waitForControl("STANDBY MODE", 5_000);
    }

    /** The LINK card's top button before the link is up (I-010; STANDBY MODE from the first LINK READY on). */
    public static final String STOP = "STOP";

    /** PILOT UAV taken: the LINK card's top button says STOP, or STANDBY MODE when the link is up already. */
    public void connecting(long ms) {
        ui.until("STOP (or STANDBY MODE) on the LINK card", ms, () -> showsStop(ui) || ui.has("STANDBY MODE"));
    }

    /** A button that says exactly STOP shows (Ui.has would also take "STOP WATCHING" when there is none). */
    public static boolean showsStop(Ui ui) {
        return ui.all(STOP).stream().anyMatch(c -> STOP.equalsIgnoreCase(Ui.edt(() -> Ui.textOf(c))));
    }

    /**
     * Brings the app back towards "nothing connected" as a pilot would: STANDBY MODE, or STOP for a link that never
     * came up (I-010); the question that may follow answered Standby mode.
     */
    public static void endLink(Ui ui) {
        if (ui.has("STANDBY MODE")) ui.click("STANDBY MODE");
        else if (showsStop(ui)) ui.click(STOP);
        if (ui.has("Keep flying")) ui.click("Standby mode");
    }

    /**
     * The link is up as the header says it: LINK READY, or FC LOST - a phone with no flight controller on its USB (the
     * emulator's) has a link all the same. CONNECTING, WAITING FOR PHONE and LINK STALLED are not.
     */
    public boolean linkUp() {
        return ui.has("LINK READY") || ui.has("FC LOST");
    }

    /** The picture has come (the app's log says so; the picture itself is the drone's made-up camera). */
    public void waitForVideo() {
        rig.waitForLog("Video: H.264", 30_000);
    }

    /** Sticks centred, throttle down, the ARM switch (AUX1) up; waits for the drone itself to say armed. */
    public void arm(VirtualDrone drone) {
        rig.radio.sticksAtRest();
        Ui.pause(1300); // Betaflight wants half a second of clean sticks before the switch counts
        // as a pilot does it: switch on; if the drone does not arm (Betaflight wants the switch off first when the
        // sticks were not clean the moment it went on - a weak link does that), off for a second and on again
        for (int attempt = 1; attempt <= 4; attempt++) {
            rig.radio.aux(1, 1);
            long end = System.currentTimeMillis() + (attempt == 4 ? 10_000 : 4000);
            while (System.currentTimeMillis() < end && !drone.readout().armed()) Ui.pause(50);
            if (drone.readout().armed()) {
                if (attempt > 1) rig.film.remark("Armed at the ARM switch's " + attempt + ". try (off and on again, as the OSD's ARMING BLOCKED asks).");
                return;
            }
            rig.radio.aux(1, -1);
            Ui.pause(1200);
        }
        rig.radio.aux(1, 1);
        until("the drone to arm (the switch was cycled four times)", 5000, () -> drone.readout().armed());
    }

    public void disarm(VirtualDrone drone) {
        rig.radio.sticksAtRest();
        Ui.pause(300);
        rig.radio.aux(1, -1);
        until("the drone to disarm", 10_000, () -> !drone.readout().armed());
    }

    /** Throttle from -1 (down) to +1 (full), the other sticks centred. */
    public void throttle(double value) {
        rig.radio.sticks(0, 0, 0, value);
    }

    /** STANDBY MODE: the link ended on purpose, the phone resting. */
    public void standby() {
        ui.waitForControl("STANDBY MODE", 5_000); // the card's words follow LINK READY at its next round (STOP before)
        ui.click("STANDBY MODE");
        ui.waitForControl("PILOT UAV", 20_000);
    }

    /** Waits for something that is not on a window (a drone's state, a file); fails with a picture of the app at that moment. */
    public void until(String what, long ms, BooleanSupplier ok) {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                ui.shot("TIMED OUT after " + ms + " ms waiting for " + what);
                throw new AssertionError("Timed out after " + ms + " ms waiting for " + what);
            }
            Ui.pause(50);
        }
    }
}
