package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet AS-11: From Betaflight reads only the board the pilot picks - two made-up boards on made-up USB; the one not
 * picked is never opened; one board alone is preselected but still asked; a cancel opens nothing.
 */
class FromBetaflightPickTest {
    static final BetaflightUsb.Port A = new BetaflightUsb.Port("COMA", "Board A"), B = new BetaflightUsb.Port("COMB", "Board B");
    final List<String> opened = new CopyOnWriteArrayList<>();

    @AfterEach
    void back() {
        BetaflightUsb.use(null);
    }

    /** A made-up Betaflight board: no MSP answer (no pack read), the CLI's banner, a "diff all" with its pack. */
    static final class Board implements BetaflightUsb.Wire {
        final int mah;
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        boolean open = true;

        Board(int mah) {
            this.mah = mah;
        }

        @Override
        public synchronized int read(byte[] buf) {
            byte[] b = out.poll();
            if (b == null) {
                try {
                    wait(50);
                } catch (InterruptedException ignored) {
                }
                return 0;
            }
            System.arraycopy(b, 0, buf, 0, b.length);
            return b.length;
        }

        @Override
        public synchronized int available() {
            byte[] b = out.peek();
            return b == null ? 0 : b.length;
        }

        @Override
        public synchronized void write(byte[] data) {
            String s = new String(data, StandardCharsets.US_ASCII);
            if (s.equals("#")) out.add("\r\nEntering CLI Mode\r\n# ".getBytes(StandardCharsets.US_ASCII));
            else if (s.startsWith("diff all")) out.add(("diff all\r\nset bat_capacity = " + mah + "\r\n\r\n# ").getBytes(StandardCharsets.US_ASCII));
            notifyAll();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private void twoBoards() {
        BetaflightUsb.use(new BetaflightUsb.Usb() {
            public List<BetaflightUsb.Port> ports() {
                return List.of(A, B);
            }

            public BetaflightUsb.Wire open(BetaflightUsb.Port port) {
                opened.add(port.systemName());
                return new Board(port == A ? 1300 : 2200);
            }
        });
    }

    @Test
    void withTwoBoardsThePilotPicksAndTheOtherIsNeverOpened() throws Exception {
        twoBoards();
        List<Object> asked = new ArrayList<>();
        FromBetaflight.Carried c = FromBetaflight.readPicked(BetaflightUsb.findFlightControllers(), null, (boards, pre) -> {
            asked.add(boards);
            asked.add(pre);
            return B;
        });
        assertEquals(List.of(List.of(A, B), "none"), List.of(asked.get(0), asked.get(1) == null ? "none" : asked.get(1)),
                "two boards: none preselected");
        assertEquals(2200, c.capacityMah(), "board B's pack");
        assertEquals(List.of("COMB"), opened, "board A is never opened");
    }

    @Test
    void cancelledNothingIsOpened() throws Exception {
        twoBoards();
        assertNull(FromBetaflight.readPicked(BetaflightUsb.findFlightControllers(), null, (boards, pre) -> null));
        assertTrue(opened.isEmpty());
    }

    @Test
    void oneBoardIsPreselectedButStillAskedAndAFoundArduPilotBoardIsNotOffered() throws Exception {
        twoBoards();
        List<BetaflightUsb.Port> offered = new ArrayList<>();
        BetaflightUsb.Port[] pre = {null};
        FromBetaflight.Carried c = FromBetaflight.readPicked(BetaflightUsb.findFlightControllers(), "COMB", (boards, p) -> {
            offered.addAll(boards);
            pre[0] = p;
            return p;
        });
        assertEquals(List.of(A), offered, "COMB is the ArduPilot board found: not offered");
        assertEquals(A, pre[0], "the only board preselected, still confirmed by the chooser");
        assertEquals(1300, c.capacityMah());
        assertEquals(List.of("COMA"), opened);
        assertNull(FromBetaflight.preselect(List.of(A, B)));
    }

    @Test
    void aBoardNotOnTheListIsRefused() {
        twoBoards();
        BetaflightUsb.Port other = new BetaflightUsb.Port("COMX", "Somebody's");
        assertThrows(IOException.class, () -> FromBetaflight.readPicked(List.of(A, B), null, (boards, p) -> other));
        assertTrue(opened.isEmpty());
    }
}
