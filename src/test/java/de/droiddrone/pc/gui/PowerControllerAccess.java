package de.droiddrone.pc.gui;

/** Tests elsewhere: PowerController's package-private rules. */
public final class PowerControllerAccess {
    private PowerControllerAccess() {
    }

    public static boolean holdConnects(boolean awaitingWake, boolean listening, int intervalMin, long now, long next) {
        return PowerController.holdConnects(awaitingWake, listening, intervalMin, now, next);
    }
}
