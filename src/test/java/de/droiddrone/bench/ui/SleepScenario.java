package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.List;

/**
 * A phone put to DEEP SLEEP from the LINK card of the smallest window (2026-11-19 afternoon): STANDBY joins DEEP SLEEP
 * and OVERWATCH and all three keep their words whole (F-020: "STAN...", "OVER..."); and another mode asked for right
 * after is queued for the phone's next check-in, not refused with "is flown by another PC" (F-027).
 */
@Tag("ui")
class SleepScenario {
    @Test
    void aSleepingPhonesThreeButtonsAreWholeAndItTakesAnotherMode() throws Exception {
        AppRig.scenario("sleep-small-window").drones(2).size(1093, 584).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            pilot.connect();
            Ui.pause(4000);
            ui.click("DEEP SLEEP");
            ui.until("the phone asleep: PILOT UAV again, and STANDBY beside the two", 30_000, () -> ui.has("PILOT UAV") && ui.has("STANDBY"));
            Ui.pause(1000);
            ui.shot("the phone asleep: three buttons in the LINK card");
            for (String words : List.of("STANDBY", "DEEP SLEEP", "OVERWATCH")) {
                AbstractButton b = (AbstractButton) ui.find(words);
                int[] fit = Ui.edt(() -> {
                    FontMetrics fm = b.getFontMetrics(b.getFont());
                    Insets in = b.getInsets();
                    return new int[]{fm.stringWidth(b.getText()), b.getWidth() - in.left - in.right};
                });
                rig.check(fit[0] <= fit[1], words + " has room for its words (F-020): needs " + fit[0] + " px, has " + fit[1]);
            }
            ui.click("OVERWATCH");
            Ui.pause(1500);
            ui.shot("OVERWATCH asked for a few seconds after DEEP SLEEP");
            rig.check(rig.logged("OVERWATCH queued for"), "OVERWATCH is queued for the phone's next check-in, not refused as \"flown by another PC\" (F-027)");
        });
    }
}
