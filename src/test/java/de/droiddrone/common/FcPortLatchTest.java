package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-070a (a reader of the code, 2026-11-22): a Config packet that changed "use the native serial port" in flight was
 * half applied - the port's restart waited for the disarm, but the stick writer asked the settings at every write and
 * wrote to a port that was not open: no sticks from that moment.
 */
class FcPortLatchTest {
    private static final FcPortLatch.Settings USB = new FcPortLatch.Settings(false, "/dev/ttyS0", 115200, 0, 0);
    private static final FcPortLatch.Settings NATIVE = new FcPortLatch.Settings(true, "/dev/ttyS3", 57600, 1, 2);

    @Test
    void aSettingChangedInFlightChangesNothingUntilTheRestartThatWaitsForTheDisarm() {
        FcPortLatch port = new FcPortLatch(USB);
        FcPortLatch.Settings[] config = {USB};
        AtomicInteger asked = new AtomicInteger();
        // the PC's Config packet, armed: Config has the new settings from here on
        config[0] = NATIVE;
        assertSame(USB, port.inUse(), "the writer goes on writing to the port that is open");
        // the USB link drops in the air and the port is looked for again: as it was, whatever Config says now
        for (int i = 0; i < 20; i++) {
            assertSame(USB, port.forOpen(() -> {
                asked.incrementAndGet();
                return config[0];
            }));
        }
        assertEquals(0, asked.get(), "Config is not even asked");
        assertFalse(port.inUse().nativePort);
        assertEquals("/dev/ttyS0", port.inUse().nativePath);
        assertEquals(115200, port.inUse().baudRate);
        assertEquals(0, port.inUse().usbPortIndex);
        assertEquals(0, port.inUse().fcProtocol);
    }

    @Test
    void theRestartBringsTheNewSettingsOnceThePortIsOpenedAgain() {
        FcPortLatch port = new FcPortLatch(USB);
        port.restartAsked(); // disarmed: the port is to be closed and opened with the new settings
        assertSame(USB, port.inUse(), "until then the old port is the one that is open");
        assertSame(NATIVE, port.forOpen(() -> NATIVE));
        assertSame(NATIVE, port.inUse());
        assertTrue(port.inUse().nativePort);
        // the restart is used up: a later look for the port is with what is in use
        assertSame(NATIVE, port.forOpen(() -> USB));
    }

    @Test
    void aRestartAskedWhileTheSettingsAreReadIsKeptForTheNextTry() {
        FcPortLatch port = new FcPortLatch(USB);
        FcPortLatch.Settings other = new FcPortLatch.Settings(true, "/dev/ttyS1", 115200, 0, 0);
        port.restartAsked();
        assertSame(NATIVE, port.forOpen(() -> {
            port.restartAsked(); // a second change's restart, in this very moment
            return NATIVE;
        }));
        assertSame(other, port.forOpen(() -> other), "its settings are not lost");
        assertSame(other, port.forOpen(() -> USB));
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and ReleaseHasNoBenchCodeTest do. */
    @Test
    void serialAsksConfigForThePortsSettingsInOnePlaceOnly() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String serial = new String(Files.readAllBytes(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Serial.java")), StandardCharsets.UTF_8);
        int feeds = serial.indexOf("private FcPortLatch.Settings settingsNow()");
        assertTrue(feeds >= 0, "Serial keeps the port's settings in an FcPortLatch, fed by settingsNow()");
        String settingsNow = serial.substring(feeds);
        settingsNow = settingsNow.substring(0, settingsNow.indexOf("\n    }"));
        for (String getter : new String[]{"config.isUseNativeSerialPort()", "config.getNativeSerialPort()", "config.getSerialBaudRate()",
                "config.getUsbSerialPortIndex()", "config.getFcProtocol()"}) {
            assertEquals(1, serial.split(java.util.regex.Pattern.quote(getter), -1).length - 1, getter + " in Serial.java: the writer and the opening ask the latch");
            assertTrue(settingsNow.contains(getter), getter + " is read where the latch is fed");
        }
        // fed where the port is about to be opened, told of the restart where it is asked for
        assertEquals(1, serial.split("fcPort\\.forOpen\\(", -1).length - 1);
        String restart = serial.substring(serial.indexOf("public void restart(){"));
        assertTrue(restart.substring(0, restart.indexOf("\n    }")).contains("fcPort.restartAsked();"), "Serial.restart()");
    }
}
