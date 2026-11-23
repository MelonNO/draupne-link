package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.ArduShow;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualFleet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet TO-2 / TO-3: the tour's and Fleet demo's fleet steps in their order on the show's virtual ArduPilot drones, each
 * by the same presses the steps make (SAFE TO AUTOLAUNCH, DISPATCH, FOLLOW GROUP, DISCARD LEAD, CALL RESERVE) - and the
 * tour cancelled in the middle: nothing of the show's fleet logic runs on after it.
 */
class TourArduWalkTest {
    private Fleet fleet;
    private ArduShow show;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (show != null) show.close();
        if (fleet != null) fleet.close();
    }

    private static void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !ok.getAsBoolean()) Thread.sleep(100);
        assertTrue(ok.getAsBoolean(), what);
    }

    private static long groupLinks() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().equals("groupLinkReceive")).count();
    }

    @Test
    void theStepsStartToEnd(@TempDir Path dir) throws Exception {
        fleet = new Fleet(PcConfig.defaults(), dir.resolve(VirtualFleet.FILE), log::add);
        show = TourArdu.start(fleet, 59.9, 10.75, log::add);
        assertNotNull(show, log.toString());
        // DISPATCH: the scouts marked, then sent where the step sends them
        SwingUtilities.invokeAndWait(() -> TourArdu.markScouts(show));
        until(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> show.ops().dispatch(show.dispatchTarget()[0], show.dispatchTarget()[1]));
            } catch (Exception e) {
                return false;
            }
            return ArduShow.SCOUTS.stream().allMatch(s -> show.phone(s).armed());
        }, 20_000, "the scouts away: " + log);
        // FOLLOW GROUP, DISCARD LEAD
        SwingUtilities.invokeAndWait(() -> assertTrue(TourArdu.formGroup(show)));
        until(() -> show.ops().picture() != null, 10_000, "the group: " + log);
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> show.ops().discardLead());
        until(() -> !ArduShow.LEAD.equals(show.lead()), 8000, "a new lead: " + log);
        // SAFE TO AUTOLAUNCH, CALL RESERVE
        SwingUtilities.invokeAndWait(() -> TourArdu.markReserve(show));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> show.ops().callReserve());
        until(() -> show.phone(ArduShow.RESERVE).armed(), 20_000, "the reserve away: " + log);
    }

    @Test
    void cancelledMidwayNothingRunsOn(@TempDir Path dir) throws Exception {
        fleet = new Fleet(PcConfig.defaults(), dir.resolve(VirtualFleet.FILE), log::add);
        show = TourArdu.start(fleet, 59.9, 10.75, log::add);
        assertNotNull(show);
        SwingUtilities.invokeAndWait(() -> TourArdu.markScouts(show));
        long before = groupLinks();
        ArduShow.Ops ops = show.ops();
        show.close(); // EXIT TOUR within the DISPATCH step's 2 s
        Thread.sleep(300);
        long afterClose = groupLinks();
        assertTrue(afterClose < before || before == 0, "the show's group link closed: " + before + " -> " + afterClose);
        // the step's timer fires after the close; a reserve tick comes late too
        SwingUtilities.invokeAndWait(() -> {
            ops.dispatch(59.9, 10.76);
            ops.callReserve();
            ops.tickReserves();
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(afterClose, groupLinks(), "no new group link after the close (it was never closed)");
        show = null;
    }
}
