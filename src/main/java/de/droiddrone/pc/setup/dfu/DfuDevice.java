package de.droiddrone.pc.setup.dfu;

import java.io.IOException;
import java.util.List;

/**
 * Fleet PRD H1 / H4: one USB DFU device opened by the pilot's pick - the STM32's own bootloader on a flight controller
 * that ran Betaflight a moment ago. Only DFU's class requests on the control pipe (interface 0, alternate setting 0:
 * the internal flash). {@link WinUsbDfu} is the real one (Windows' WinUSB driver); the tests have a made-up one.
 */
public interface DfuDevice extends AutoCloseable {
    int DNLOAD = 1, UPLOAD = 2, GETSTATUS = 3, CLRSTATUS = 4, GETSTATE = 5, ABORT = 6;

    /** A class request to the interface, host to device (bmRequestType 0x21). */
    void out(int request, int value, byte[] data) throws IOException;

    /** A class request to the interface, device to host (bmRequestType 0xA1): what came back. */
    byte[] in(int request, int value, int length) throws IOException;

    /** The device's id (its USB instance id: vendor, product and the chip's serial). */
    String id();

    /** The flash's layout string, e.g. "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg". */
    String layout() throws IOException;

    /** The most a DNLOAD / UPLOAD carries (the DFU functional descriptor's wTransferSize; ST's ROM: 2048). */
    int transferSize();

    @Override
    void close();

    /** The DFU devices on this PC's USB: listed without opening any; one opened by its id only. */
    interface Bus {
        /** The ids of the STM32 DFU devices present now (none opened). */
        List<String> present() throws IOException;

        /** Opens this one (and no other). */
        DfuDevice open(String id) throws IOException;
    }
}
