package de.droiddrone.pc.setup;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.bench.hw.FakeFlightBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-077: the motor test looked at "armed" only when it opened. Betaflight 4.4.1 uses props in / out
 * (yaw_motors_reversed) live, from the mixer write on (mixer.c), and refuses the EEPROM write while armed
 * (msp.c) - so PROPS OUT pressed on a board armed meanwhile reversed its yaw in memory, unsaved and with no
 * restart. Here each write the motor test makes reads the board's status first and leaves an armed board as it
 * was; a props write whose save fails is put back. Made-up board (FakeFlightBoard), no real port.
 */
@Timeout(30)
class MotorTestArmedTest {
    private static final String ARMED = "The drone is armed. Disarm it first.";
    private static final int MSP_SET_MIXER_CONFIG = 43, MSP_EEPROM_WRITE = 250;
    private final FakeUsb usb = new FakeUsb();
    private final FakeFlightBoard board = new FakeFlightBoard("MADEUP1");
    /** The board's next MSP_EEPROM_WRITE finds it armed (it is armed just before the request reaches it). */
    private volatile boolean armAtSave;
    private MotorTest test;

    private MotorTest open() throws IOException {
        board.battery(16.0, 4);
        usb.plug("MADEUP1", "Made-up Betaflight board", () -> new ArmsAtSave(board.connect()));
        BetaflightUsb.use(usb);
        test = MotorTest.open(usb.ports().get(0));
        return test;
    }

    @AfterEach
    void close() {
        if (test != null) test.close();
        BetaflightUsb.use(null);
    }

    private boolean propsOutInMemory() {
        return board.inMemory().contains("set yaw_motors_reversed = ON");
    }

    private long requests(int code) {
        return board.mspRequests().stream().filter(c -> c == code).count();
    }

    @Test
    void propsOutOnABoardArmedSinceTheTestOpenedIsNotWritten() throws Exception {
        MotorTest t = open();
        board.arm();
        int restarts = board.restarts;
        IOException e = assertThrows(IOException.class, () -> t.setPropsOutAndRestart(true));
        assertEquals(ARMED, e.getMessage());
        assertFalse(propsOutInMemory(), "yaw not reversed in the armed board's memory");
        assertEquals(0, requests(MSP_SET_MIXER_CONFIG), "no mixer write reaches an armed board");
        assertEquals(0, requests(MSP_EEPROM_WRITE));
        assertEquals(restarts, board.restarts, "no restart");
        assertFalse(t.isClosed(), "the motor test goes on");
        board.disarm();
        t.setPropsOutAndRestart(true); // disarmed: written and saved
        assertTrue(board.saved().contains("set yaw_motors_reversed = ON"), "saved once disarmed");
    }

    @Test
    void aPropsWriteWhoseSaveFailsIsPutBackAndSaidSo() throws Exception {
        MotorTest t = open();
        armAtSave = true; // armed between the status read and the save: Betaflight refuses the EEPROM write
        IOException e = assertThrows(IOException.class, () -> t.setPropsOutAndRestart(true));
        assertTrue(e.getMessage().contains("Not saved") && e.getMessage().contains("put back"), e.getMessage());
        assertFalse(propsOutInMemory(), "the old value (props in) is back in the board's memory");
        assertEquals(2, requests(MSP_SET_MIXER_CONFIG), "the new value, then the old one");
        assertFalse(board.saved().contains("set yaw_motors_reversed = ON"), "nothing saved");
    }

    @Test
    void motorOrderOnAnArmedBoardIsNotWritten() throws Exception {
        MotorTest t = open();
        board.arm();
        IOException e = assertThrows(IOException.class, () -> t.setOutputOrderAndRestart(new int[]{1, 0, 2, 3}));
        assertEquals(ARMED, e.getMessage());
        assertTrue(board.inMemory().contains("set motor_output_reordering = 0,1,2,3,4,5,6,7"), "order unchanged");
        assertEquals(0, requests(MSP_EEPROM_WRITE));
    }

    @Test
    void aMotorOrderWhoseSaveFailsIsPutBack() throws Exception {
        MotorTest t = open();
        armAtSave = true;
        IOException e = assertThrows(IOException.class, () -> t.setOutputOrderAndRestart(new int[]{1, 0, 2, 3}));
        assertTrue(e.getMessage().contains("Not saved") && e.getMessage().contains("put back"), e.getMessage());
        assertTrue(board.inMemory().contains("set motor_output_reordering = 0,1,2,3,4,5,6,7"), "the old order is back in memory");
    }

    @Test
    void spinDirectionOnAnArmedBoardIsNotSent() throws Exception {
        MotorTest t = open();
        board.arm();
        IOException e = assertThrows(IOException.class, () -> t.setDirection(2, true));
        assertEquals(ARMED, e.getMessage());
        assertEquals(List.of(), board.dshotCommands(), "no DShot command reaches an armed board (Betaflight ignores it and says OK)");
        board.disarm();
        t.setDirection(2, true);
        assertEquals(List.of("motor 2: 8 12"), board.dshotCommands());
    }

    /** The board's wire, arming the board just before an MSP_EEPROM_WRITE reaches it when the test says so. */
    private final class ArmsAtSave implements BetaflightUsb.Wire {
        private final BetaflightUsb.Wire wire;

        ArmsAtSave(BetaflightUsb.Wire wire) {
            this.wire = wire;
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return wire.read(buf);
        }

        @Override
        public int available() throws IOException {
            return wire.available();
        }

        @Override
        public void write(byte[] data) throws IOException {
            boolean save = data.length >= 5 && data[0] == '$' && data[1] == 'M' && data[2] == '<' && (data[4] & 0xff) == MSP_EEPROM_WRITE;
            if (save && armAtSave) {
                armAtSave = false;
                board.arm(); // and stays armed: the put-back must reach an armed board too (Betaflight takes a mixer write armed)
            }
            wire.write(data);
        }

        @Override
        public boolean isOpen() {
            return wire.isOpen();
        }

        @Override
        public void close() {
            wire.close();
        }
    }
}
