package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OSD drawn over whatever numbers a flight controller may send (the bench, 2026-11-19): every record of the
 * phone's telemetry filled with random and with extreme values - a board with a broken sensor, a half-set-up one, a
 * firmware that fills a field differently. The OSD is painted over every picture on the window's own thread: an
 * error there is a video without its figures, or worse. Nothing is checked but that it draws.
 */
class OsdAnyNumbersTest {
    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0);
    private static final VideoPipeline.Stats VIDEO = new VideoPipeline.Stats(true, 1280, 720, 60, 60, 4.2, 0.9, 0.6, 0, 0, null, false, 4, 0, 0, 0, 38, 24,
            185, 62, 48, 211);
    private static final byte[] EXTREMES = {0, 1, -1, 127, -128, 2, 100};

    /** A record's payload: random bytes, or one extreme value throughout, or mostly zero. */
    private static byte[] payload(Random random, int length) {
        byte[] b = new byte[length];
        switch (random.nextInt(4)) {
            case 0 -> random.nextBytes(b);
            case 1 -> java.util.Arrays.fill(b, EXTREMES[random.nextInt(EXTREMES.length)]);
            case 2 -> {
                for (int i = 0; i < length; i++) b[i] = EXTREMES[random.nextInt(EXTREMES.length)];
            }
            default -> {
                if (length > 0) b[random.nextInt(length)] = (byte) random.nextInt(256);
            }
        }
        return b;
    }

    private static void record(DataOutputStream o, Random random, int code, int length) throws Exception {
        if (random.nextInt(6) == 0) return; // a record the phone did not send this time
        o.writeShort(code);
        o.write(payload(random, length));
    }

    /** The records as the phone app writes them (the lengths are {@code OsdPreview}'s), each filled with anything. */
    private static byte[] packet(Random random) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        if (random.nextBoolean()) {
            byte[] names = (random.nextBoolean() ? "ARM;ANGLE;HORIZON;BEEPER;AIR MODE;" : "ARM;;;;;;;GPS RESCUE;FAILSAFE;X").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            o.writeShort(FcCommon.MSP_BOXNAMES);
            o.writeShort(names.length);
            o.write(names);
        }
        record(o, random, FcCommon.MSP_BATTERY_CONFIG, 6);
        record(o, random, FcCommon.MSP_ATTITUDE, 6);
        record(o, random, FcCommon.MSP_ALTITUDE, 6);
        record(o, random, FcCommon.MSP_ANALOG, 8);
        record(o, random, FcCommon.MSP_BATTERY_STATE, 10);
        if (random.nextInt(6) != 0) { // the status record says how many flag bytes follow: kept true to its own length
            o.writeShort(FcCommon.MSP_STATUS);
            o.write(payload(random, 9));
            o.writeByte(4);
            o.write(payload(random, 4 + 7));
        }
        record(o, random, FcCommon.MSP_RAW_GPS, 22);
        record(o, random, FcCommon.MSP_COMP_GPS, 5);
        record(o, random, FcCommon.DD_TIMERS, 12);
        record(o, random, FcCommon.DD_PHONE_BATTERY_STATE, 2);
        record(o, random, FcCommon.DD_NETWORK_STATE, 2);
        record(o, random, FcCommon.DD_CAMERA_FPS, 2);
        return bytes.toByteArray();
    }

    @Test
    void whateverTheFlightControllerSendsTheOsdStillDraws() throws Exception {
        Random random = new Random(20261119L);
        OsdRenderer renderer = new OsdRenderer();
        OsdLayout every = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) every = every.with(e, new OsdLayout.Placement(true, every.get(e).x(), every.get(e).y()));
        int[][] sizes = {{1280, 720}, {640, 360}, {160, 80}, {1920, 1080}};
        List<String> wrong = new ArrayList<>();
        int strayCodes = 0, withNetwork = 0, withGps = 0;
        for (int i = 0; i < 4000 && wrong.size() < 5; i++) {
            byte[] packet = packet(random);
            FcTelemetry telemetry = new FcTelemetry();
            try {
                telemetry.parse(new DataReader(packet, true), BETAFLIGHT);
                if (random.nextBoolean()) telemetry.parse(new DataReader(packet(random), true), BETAFLIGHT); // a second, different word
                short[] channels = {(short) random.nextInt(), (short) random.nextInt(), (short) (1000 + random.nextInt(1000)), (short) random.nextInt(3000)};
                OsdRenderer.Data d = new OsdRenderer.Data(true, random.nextBoolean(), random.nextInt(), random.nextBoolean(), random.nextBoolean(),
                        telemetry, VIDEO, random.nextBoolean(), null, channels, false, false, false, false, null, random.nextBoolean(), null, false,
                        random.nextInt(5000), random.nextBoolean(), null, false);
                int[] size = sizes[i % sizes.length];
                BufferedImage img = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                renderer.paint(g, 0, 0, size[0], size[1], d, every);
                g.dispose();
                if (telemetry.getUnknownCodes() > 0) strayCodes++;
                if (telemetry.getNetwork() != null) withNetwork++;
                if (telemetry.getGps() != null) withGps++;
            } catch (Throwable t) {
                StringBuilder hex = new StringBuilder();
                for (byte b : packet) hex.append(String.format("%02x", b));
                StackTraceElement[] at = t.getStackTrace();
                wrong.add("picture " + i + ": " + t + (at.length > 0 ? " at " + at[0] + (at.length > 1 ? " < " + at[1] : "") : "") + "\n  packet " + hex);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        // the test itself: its packets are read to their end (a wrong length here and the parser stops at the first record)
        assertTrue(strayCodes == 0 && withNetwork > 2500 && withGps > 2500,
                strayCodes + " packets with a stray code, " + withNetwork + " with the network, " + withGps + " with GPS");
    }
}
