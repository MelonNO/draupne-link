package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GU-8 at the click: a right-click menu made on the pilot's PC and clicked after the PC became a watcher does nothing -
 * no SAFE TO AUTOLAUNCH, no FOLLOW GROUP, no TAKE CONTROL, no DISPATCH.
 */
class FleetWatchOnlyClickTest {
    private static final class Host implements FleetOps.Host {
        volatile boolean watchOnly;
        final List<String> log = new CopyOnWriteArrayList<>(), flown = new CopyOnWriteArrayList<>();

        public String flownPhone() {
            return "Lead";
        }

        public InetSocketAddress addressOf(String phone) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 9);
        }

        public String key() {
            return "DD";
        }

        public void log(String line) {
            log.add(line);
        }

        public void alert(String text) {
            log.add("! " + text);
        }

        public void flyNow(String phone) {
            flown.add(phone);
        }

        public boolean confirm(String question) {
            return true;
        }

        public boolean watchOnly() {
            return watchOnly;
        }
    }

    @Test
    void aMenuMadeForThePilotDoesNothingOnceWatching() throws Exception {
        Host host = new Host();
        FleetOps ops = new FleetOps(host);
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<JMenuItem> items = ops.items("MobileB");
                JMenuItem mark = items.stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark")).findFirst().orElseThrow();
                JMenuItem follow = items.stream().filter(i -> i.getText().startsWith("FOLLOW GROUP: add")).findFirst().orElseThrow();
                host.watchOnly = true; // the menu still open: the PC is a watcher now
                mark.doClick();
                follow.doClick();
                for (JMenuItem i : items) i.doClick();
                assertFalse(ops.isMarked("MobileB"), "not marked from a watching PC");
                host.watchOnly = false;
                assertTrue(ops.items("MobileB").stream().noneMatch(i -> i.getText().startsWith("FOLLOW GROUP: start")), "nobody chosen");
                assertTrue(host.flown.isEmpty(), "no TAKE CONTROL");
                // the pilot's PC again: the same menu item works
                ops.items("MobileB").stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark")).findFirst().orElseThrow().doClick();
                assertTrue(ops.isMarked("MobileB"));
                List<JMenuItem> place = ops.placeItems(59.9, 10.7);
                host.watchOnly = true;
                for (JMenuItem i : place) i.doClick();
            });
            assertTrue(host.log.stream().anyMatch(l -> l.startsWith("Watching only: DISPATCH")), host.log.toString());
            assertTrue(host.log.stream().noneMatch(l -> l.startsWith("! DISPATCH")), "DISPATCH never ran: " + host.log);
        } finally {
            ops.close();
        }
    }
}
