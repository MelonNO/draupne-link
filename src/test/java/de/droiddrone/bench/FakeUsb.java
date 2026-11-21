package de.droiddrone.bench;

import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The bench's USB: the flight controllers "plugged into this PC" are the ones a scenario puts here - never a real
 * serial port, so a board that really is on the pilot's USB is not seen, opened or rebooted by anything the bench runs.
 */
public final class FakeUsb implements BetaflightUsb.Usb {
    private final Map<String, Supplier<BetaflightUsb.Wire>> boards = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final List<String> opened = new ArrayList<>();

    /** A board on a made-up port; each open gets a wire from the supplier (a board answers anew after a replug). */
    public synchronized void plug(String port, String description, Supplier<BetaflightUsb.Wire> board) {
        boards.put(port, board);
        descriptions.put(port, description);
    }

    public synchronized void unplug(String port) {
        boards.remove(port);
        descriptions.remove(port);
    }

    /** The ports the app opened, in order. */
    public synchronized List<String> opened() {
        return List.copyOf(opened);
    }

    @Override
    public synchronized List<BetaflightUsb.Port> ports() {
        List<BetaflightUsb.Port> out = new ArrayList<>();
        descriptions.forEach((port, d) -> out.add(new BetaflightUsb.Port(port, d)));
        return out;
    }

    @Override
    public synchronized BetaflightUsb.Wire open(BetaflightUsb.Port port) throws IOException {
        Supplier<BetaflightUsb.Wire> board = boards.get(port.systemName());
        if (board == null) throw new IOException(port.systemName() + " is gone - plug the flight controller in again.");
        opened.add(port.systemName());
        return board.get();
    }
}
