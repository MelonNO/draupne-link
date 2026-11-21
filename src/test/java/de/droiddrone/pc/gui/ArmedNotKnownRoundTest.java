package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-062 in the window: what the pilot is shown and asked while a link hasn't read yet whether its drone is armed
 * (the rule itself: ArmedCheckTest; end to end: ArmedNotKnownEndToEndTest).
 */
class ArmedNotKnownRoundTest {
    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0);

    /** MSP_STATUS as the phone passes it on: the first mode box (ARM) and the second (ANGLE) on, nothing against arming. */
    private static byte[] armedStatus() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcCommon.MSP_STATUS);
        o.writeShort(125); o.writeShort(0); o.writeShort(0x23);
        o.writeByte(0); o.writeShort(14);
        o.writeByte(4); o.writeByte(0b11); o.writeByte(0); o.writeByte(0); o.writeByte(0);
        o.writeInt(0); o.writeByte(0); o.writeShort(48);
        return bytes.toByteArray();
    }

    private static byte[] names() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        byte[] n = "ARM;ANGLE;".getBytes(StandardCharsets.US_ASCII);
        o.writeShort(FcCommon.MSP_BOXNAMES);
        o.writeShort(n.length);
        o.write(n);
        return bytes.toByteArray();
    }

    private static boolean drawsArmState(FcTelemetry t) {
        OsdRenderer.Data sample = new OsdPreview().data();
        OsdRenderer.Data d = new OsdRenderer.Data(true, true, 42, true, false, t, sample.video(), false, null,
                sample.channels(), false, false, false, false, null, false, null, false);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, OsdLayout.defaults(), bounds);
        g.dispose();
        return bounds.containsKey(OsdElement.ARMED);
    }

    /** The OSD said DISARMED of a drone in the air for a link's first seconds: nothing now, until the flight controller has said. */
    @Test
    void theOsdSaysNothingOfArmedBeforeItIsKnown() throws Exception {
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(armedStatus(), true), BETAFLIGHT);
        assertFalse(t.getStatus().armed(), "read without the mode names: not armed, of an armed drone");
        assertFalse(drawsArmState(t), "no DISARMED on the picture from a status that can't say");
        t.parse(new DataReader(names(), true), BETAFLIGHT);
        t.parse(new DataReader(armedStatus(), true), BETAFLIGHT);
        assertTrue(t.getStatus().armed());
        assertTrue(drawsArmState(t), "ARMED once the names are here");
    }

    /**
     * LOST DRONE ends the link at one click unless the link is back (LinkReviewRoundTest: linkBack, a fresh status).
     * Right after a reconnect the link is up and the first status since isn't here: asked then too.
     */
    @Test
    void lostDroneAsksTooWhileTheLinkIsUpAndItsFirstStatusIsNotHere() {
        ControlLink.State ready = ControlLink.State.READY;
        assertTrue(MainWindow.linkUpUnread(ready, false, false, 2500), "the status of before the loss: old");
        assertTrue(MainWindow.linkUpUnread(ready, false, false, -1), "no status at all");
        assertFalse(MainWindow.linkUpUnread(ready, false, false, 300), "a fresh status: linkBack's own case");
        assertFalse(MainWindow.linkUpUnread(ControlLink.State.CONNECTING, false, false, 2500), "lost: ends at once, as before");
        assertFalse(MainWindow.linkUpUnread(ready, true, false, 2500), "stalled: the phone isn't heard");
        assertFalse(MainWindow.linkUpUnread(ready, false, true, 2500), "the FC gone");
        for (long age : new long[]{-1, 0, 300, 1999, 2000, 60_000}) {
            assertTrue(MainWindow.linkBack(ready, false, false, age) || MainWindow.linkUpUnread(ready, false, false, age),
                    "up, heard, its flight controller there: asked, whatever the status's age (" + age + ")");
        }
    }
}
