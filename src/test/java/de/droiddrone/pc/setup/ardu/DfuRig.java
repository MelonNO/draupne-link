package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.setup.dfu.DfuDevice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Made-up STM32 boards in DFU on a made-up USB, for tests outside this package (the setup guide's FIND page). */
public final class DfuRig {
    private final FakeDfu.Bus bus = new FakeDfu.Bus();

    public DfuDevice.Bus bus() {
        return bus;
    }

    /** An F405 in its DFU bootloader appears on the bus. */
    public void add(String id) throws IOException {
        bus.add(new FakeDfu(id, FakeDfu.F405));
    }

    /** The devices opened so far. */
    public List<String> opened() {
        synchronized (bus) {
            return List.copyOf(bus.opened);
        }
    }

    /** Written and restarted into its new firmware. */
    public boolean running(String id) {
        synchronized (bus) {
            FakeDfu d = bus.devices.get(id);
            return d != null && d.running;
        }
    }

    /** ArduPilot's "_with_bl.hex" in miniature. */
    public static byte[] withBlHex() {
        byte[] bl = ArduDfuTest.random(15_000, 1), copter = ArduDfuTest.random(150_000, 2);
        return ArduDfuTest.hex(new long[]{0x08000000L, 0x08004000L}, new byte[][]{bl, copter}).getBytes(StandardCharsets.US_ASCII);
    }
}
