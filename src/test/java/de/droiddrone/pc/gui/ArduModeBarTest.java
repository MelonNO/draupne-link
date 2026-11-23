package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommand;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.TelemetryRecords;
import de.droiddrone.pc.ArduModes;
import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AP-3 (why ArduPilot won't arm, in RC CHECK) and AP-6 (the OSD's mode buttons and the FC's answer). The
 * records go through the phone's TelemetryRecords as on the drone.
 */
class ArduModeBarTest {
    static final de.droiddrone.common.FcInfo ARDU = new de.droiddrone.common.FcInfo(de.droiddrone.common.FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);

    private static void feed(FcTelemetry t, TelemetryData... records) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (TelemetryData r : records) TelemetryRecords.write(r, ARDU, out);
        t.parse(new DataReader(bytes.toByteArray(), true), ARDU);
    }

    private static TelemetryData text(String s) {
        de.droiddrone.common.DataWriter w = new de.droiddrone.common.DataWriter(true);
        w.writeByte((byte) 3);
        w.writeUTF(s);
        return new TelemetryData(de.droiddrone.common.FcCommon.DD_MAVLINK_STATUS_TEXT, w.getData());
    }

    private static TelemetryData mode(int m, boolean armed) {
        de.droiddrone.common.DataWriter w = new de.droiddrone.common.DataWriter(true);
        w.writeInt(m);
        w.writeBoolean(armed);
        return new TelemetryData(de.droiddrone.common.FcCommon.DD_MAVLINK_MODE, w.getData());
    }

    @Test
    void preArmTextsAreTheBlockersWhileDisarmed() throws Exception {
        FcTelemetry t = new FcTelemetry();
        feed(t, text("PreArm: Throttle below failsafe"), text("Arm: Compass not calibrated"), text("EKF3 IMU0 is using GPS"),
                text("PreArm: Throttle below failsafe"), mode(ArduModes.LOITER, false));
        List<FcTelemetry.Blocker> b = t.getStatus().blockers();
        assertEquals(2, b.size(), "each reason once, the other texts are not reasons: " + b);
        assertEquals("PREARM", b.get(0).osdName());
        assertTrue(b.stream().anyMatch(x -> x.explanation().equals("Throttle below failsafe")));
        assertTrue(b.stream().anyMatch(x -> x.explanation().equals("Compass not calibrated")));
        feed(t, mode(ArduModes.LOITER, true));
        assertEquals(List.of(), t.getStatus().blockers(), "armed: nothing blocks");
    }

    @Test
    void theFcsAnswerComesBack() throws Exception {
        FcTelemetry t = new FcTelemetry();
        assertNull(t.getLastCommandAck());
        feed(t, new TelemetryData(FcCommand.ACK_RECORD, FcCommand.ack(176, 2)), mode(ArduModes.RTL, false));
        FcTelemetry.CommandAck a = t.getLastCommandAck();
        assertEquals(176, a.command());
        assertFalse(a.accepted());
        assertEquals("DENIED", a.word());
        assertEquals("RTL", FcTelemetry.flightMode(t.getStatus()), "the record after it read too");
        assertEquals(0, t.getUnknownCodes());
    }

    @Test
    void buttonsSendModesAndGoStartsPausesAndGoesOn() {
        List<byte[]> sent = new ArrayList<>();
        ArduModeBar bar = new ArduModeBar(c -> sent.add(c));
        FcTelemetry.Status ardu = new FcTelemetry.Status(true, List.of("ARM", "LOITER"), List.of(), 0, 0, 0, true, "LOITER");
        bar.update(null, -1, null, 0);
        assertFalse(bar.isVisible(), "no ArduPilot drone: hidden");
        bar.update(ardu, ArduModes.LOITER, null, 0);
        assertTrue(bar.isVisible());
        assertTrue(bar.lit(ArduModeBar.Action.LOITER));

        bar.press(ArduModeBar.Action.RTL);
        assertEquals(ArduModes.RTL, FcCommand.read(last(sent), last(sent).length).mode);
        bar.press(ArduModeBar.Action.LAND);
        assertEquals(ArduModes.LAND, FcCommand.read(last(sent), last(sent).length).mode);

        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.MISSION_START, kind(last(sent)), "not in AUTO: START");
        bar.update(ardu, ArduModes.AUTO, null, 1);
        assertTrue(bar.lit(ArduModeBar.Action.START));
        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.PAUSE, kind(last(sent)), "in AUTO: PAUSE");
        bar.update(ardu, ArduModes.LOITER, null, 2);
        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.CONTINUE, kind(last(sent)), "after PAUSE: CONTINUE from where it was");
        bar.update(ardu, ArduModes.AUTO, null, 3);
        bar.press(ArduModeBar.Action.LOITER);
        bar.update(ardu, ArduModes.LOITER, null, 4);
        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.MISSION_START, kind(last(sent)), "LOITER pressed, not PAUSE: START again");
    }

    @Test
    void aRefusalShowsForAMoment() {
        ArduModeBar bar = new ArduModeBar(c -> true);
        FcTelemetry.Status ardu = new FcTelemetry.Status(false, List.of("STABILIZE"), List.of(), 0, 0, 0, true, "STABILIZE");
        bar.update(ardu, ArduModes.STABILIZE, null, 0);
        bar.press(ArduModeBar.Action.LOITER);
        bar.update(ardu, ArduModes.STABILIZE, new FcTelemetry.CommandAck(176, 4, 100), 100);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(ArduModeBar.WIDTH, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bar.setSize(bar.getPreferredSize());
        bar.doLayout();
        java.awt.Graphics2D g = img.createGraphics();
        bar.paint(g); // paints off screen without a window
        g.dispose();
        bar.update(ardu, ArduModes.STABILIZE, new FcTelemetry.CommandAck(176, 4, 100), 100 + ArduModeBar.REFUSED_SHOW_MS + 1);
    }

    private static byte[] last(List<byte[]> l) {
        return l.get(l.size() - 1);
    }

    private static int kind(byte[] c) {
        return FcCommand.read(c, c.length).kind;
    }

    /**
     * GU-4: the radio's mode switch first, these buttons as backup. A route paused with GO and then taken elsewhere by
     * the switch is not "paused" any more: the next GO starts it, and the lit button follows what the switch chose.
     */
    @Test
    void theRadiosModeSwitchAndTheButtonsWorkTogether() {
        List<byte[]> sent = new ArrayList<>();
        ArduModeBar bar = new ArduModeBar(c -> sent.add(c));
        FcTelemetry.Status ardu = new FcTelemetry.Status(true, List.of("ARM"), List.of(), 0, 0, 0, true, "AUTO");
        bar.update(ardu, ArduModes.AUTO, null, 0);
        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.PAUSE, kind(last(sent)));
        bar.update(ardu, ArduModes.STABILIZE, null, 1); // the pilot's switch: STABILIZE
        for (ArduModeBar.Action a : ArduModeBar.Action.values()) assertFalse(bar.lit(a), a + ": the switch's mode has no button");
        bar.update(ardu, ArduModes.LOITER, null, 2); // and back to LOITER on the switch
        assertTrue(bar.lit(ArduModeBar.Action.LOITER));
        bar.press(ArduModeBar.Action.START);
        assertEquals(FcCommand.MISSION_START, kind(last(sent)), "the switch took it out of the pause: GO starts the route");
    }

    /** GU-4: a 3-position mode switch (low, middle, top) gives STABILIZE, ALT HOLD and LOITER on ArduPilot - never LAND. */
    @Test
    void aThreePositionSwitchGivesStabilizeAltHoldAndLoiter() {
        int[] modes = de.droiddrone.pc.setup.ardu.ArduPlan.DEFAULT_MODES;
        // ArduPilot's bands: position 1 up to 1230 us, 4 from 1491 to 1620, 6 from 1750
        assertEquals(ArduModes.STABILIZE, modes[0], "low (1000 us)");
        assertEquals(ArduModes.ALT_HOLD, modes[3], "middle (1500 us)");
        assertEquals(ArduModes.LOITER, modes[5], "top (2000 us)");
        assertEquals(ArduModes.LOITER, modes[2], "position 3 stays LOITER (the SITL gate flies it)");
        for (int m : modes) assertNotEquals(ArduModes.LAND, m, "no LAND on the switch: the LND button");
    }

    /** GU-4: ArduPilot drone setup puts ARM and the mode switch where the radio has them for the Betaflight drones. */
    @Test
    void setupTakesTheRadiosSwitchesFromThisPc() throws Exception {
        de.droiddrone.pc.PcConfig config = de.droiddrone.pc.PcConfig.defaults();
        ArduSetupPages.State s = new ArduSetupPages.State();
        s.radioFrom(config);
        assertEquals(1, s.radioArmAux, "not set: ARM on AUX1");
        assertEquals(2, s.radioModeAux, "the mode switch on AUX2");
        config.set(de.droiddrone.pc.setup.ModeSwitch.ARM_KEY, "4");
        config.set(de.droiddrone.pc.setup.ModeSwitch.KEY, "3:ACRO,ANGLE,POS_HOLD");
        s.radioFrom(config);
        assertEquals(4, s.radioArmAux);
        assertEquals(3, s.radioModeAux);
        config.set(de.droiddrone.pc.setup.ModeSwitch.ARM_KEY, "2");
        config.set(de.droiddrone.pc.setup.ModeSwitch.KEY, "off");
        s.radioFrom(config);
        assertEquals(2, s.radioArmAux);
        assertEquals(3, s.radioModeAux, "never on the ARM switch");
    }
}
