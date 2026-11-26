package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-LINK-2: while this PC has no Tailscale address the link sends nothing and waits for it - the header and the OSD
 * say so in short words, ahead of the usual ones (CONNECTING, WAKING, LINK LOST).
 */
class NoTailscaleWordsTest {
    @Test
    void theHeaderSaysNoTailscaleFirst() {
        assertEquals("NO TAILSCALE", MainWindow.waitingPill(ControlLink.State.CONNECTING, false, false, true));
        assertEquals("NO TAILSCALE", MainWindow.waitingPill(ControlLink.State.CONNECTING, true, false, true), "not WAKING: no wake can go out");
        assertEquals("NO TAILSCALE", MainWindow.waitingPill(ControlLink.State.CONNECTING, false, true, true), "not LINK LOST: the reason (red)");
        assertEquals("CONNECTING", MainWindow.waitingPill(ControlLink.State.CONNECTING, false, false, false), "with Tailscale: as before");
        assertEquals("LINK LOST", MainWindow.waitingPill(ControlLink.State.CONNECTING, false, true, false));
    }

    @Test
    void theOsdSaysItOnThisPc() {
        assertEquals("NO TAILSCALE ON THIS PC", OsdDataBuilder.NO_TAILSCALE);
    }
}
