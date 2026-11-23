package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Fleet PRD AS-1: getting ArduPilot firmware onto a board on USB that runs ArduPilot - its board id (AUTOPILOT_VERSION),
 * the newest stable Copter .apj for it from firmware.ardupilot.org's manifest (only on the pilot's click), the restart
 * into its bootloader (MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN 3), the bootloader's new serial port found by asking each
 * port for a sync, then {@link ArduFirmware#upload}. Disarmed only: the session says armed or not known otherwise.
 */
public final class ArduFirmwareFlow {
    public static final String MANIFEST = "https://firmware.ardupilot.org/manifest.json.gz";
    static final int MSG_AUTOPILOT_VERSION = 148, MAV_CMD_REQUEST_MESSAGE = 512, MAV_CMD_REBOOT = 246;

    private ArduFirmwareFlow() {
    }

    /** The board's id (AUTOPILOT_VERSION's board_version, upper 16 bits), -1 when it doesn't say. */
    public static int boardId(MavSession fc) throws IOException, InterruptedException {
        int[] id = {-1};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != MSG_AUTOPILOT_VERSION) return;
            com.MAVLink.common.msg_autopilot_version v = new com.MAVLink.common.msg_autopilot_version(p);
            synchronized (got) {
                id[0] = (int) ((v.board_version >>> 16) & 0xffff);
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            fc.command(MAV_CMD_REQUEST_MESSAGE, MSG_AUTOPILOT_VERSION, 0, 0, 0, 0, 0, 0, 2000);
            synchronized (got) {
                if (id[0] < 0) got.wait(2000);
                return id[0];
            }
        } finally {
            fc.removePacketListener(l);
        }
    }

    /** The newest official stable Copter .apj's address for this board id, or null (firmware.ardupilot.org's manifest). */
    public static String latestCopter(int boardId, HttpClient http) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(MANIFEST)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("firmware list: HTTP " + r.statusCode());
        try (InputStream in = new GZIPInputStream(r.body())) {
            return pick(new String(in.readAllBytes(), StandardCharsets.UTF_8), boardId);
        }
    }

    /** From a manifest: the Copter OFFICIAL apj for the board, a /stable/ one first. */
    static String pick(String manifestJson, int boardId) {
        JsonObject m = JsonParser.parseString(manifestJson).getAsJsonObject();
        JsonArray fws = m.getAsJsonArray("firmware");
        String any = null;
        for (JsonElement e : fws) {
            JsonObject f = e.getAsJsonObject();
            if (!f.has("board_id") || f.get("board_id").getAsInt() != boardId) continue;
            if (!"Copter".equalsIgnoreCase(str(f, "vehicletype")) || !"apj".equalsIgnoreCase(str(f, "format"))) continue;
            if (!"OFFICIAL".equalsIgnoreCase(str(f, "mav-firmware-version-type"))) continue;
            String url = str(f, "url");
            if (url.contains("/stable/")) return url;
            if (any == null) any = url;
        }
        return any;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    public static byte[] download(String url, HttpClient http) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("firmware download: HTTP " + r.statusCode());
        return r.body();
    }

    /** The flight-controller-looking ports now (to tell the bootloader's new one). */
    public static java.util.Set<String> portsNow() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (BetaflightUsb.Port p : BetaflightUsb.findFlightControllers()) s.add(p.systemName());
        return s;
    }

    /** Into its bootloader: refused while armed or not known; the session ends (the port goes away). */
    public static void rebootToBootloader(MavSession fc) throws IOException, InterruptedException {
        if (fc.armed()) throw new IOException("armed, or not known: only on the bench, disarmed");
        fc.command(MAV_CMD_REBOOT, 3, 0, 0, 0, 0, 0, 0, 1500);
        fc.close();
    }

    /**
     * The bootloader's port: asked for a sync, for up to timeoutMs - only the board's own port or one that came up
     * after the restart (others present before, another drone's board among them, are left alone).
     */
    public static SerialWire findBootloader(long timeoutMs, String ownPort, java.util.Set<String> before) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (BetaflightUsb.Port p : BetaflightUsb.findFlightControllers()) {
                if (!p.systemName().equals(ownPort) && before.contains(p.systemName())) continue;
                SerialWire w;
                try {
                    w = SerialWire.open(p.systemName());
                } catch (IOException e) {
                    continue;
                }
                try {
                    ArduFirmware.sync(w);
                    return w;
                } catch (IOException e) {
                    w.close();
                }
            }
            Thread.sleep(500);
        }
        throw new IOException("no ArduPilot bootloader came up on USB");
    }

    /** The bootloader's serial port (jSerialComm). */
    public static final class SerialWire implements ArduFirmware.Wire, AutoCloseable {
        private final SerialPort port;

        private SerialWire(SerialPort port) {
            this.port = port;
        }

        static SerialWire open(String name) throws IOException {
            SerialPort p = SerialPort.getCommPort(name);
            p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            if (!p.openPort()) throw new IOException(name + " is in use");
            return new SerialWire(p);
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (port.writeBytes(data, data.length) != data.length) throw new IOException("write failed");
        }

        @Override
        public int read(int timeoutMs) {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, Math.max(1, timeoutMs), 0);
            byte[] b = new byte[1];
            return port.readBytes(b, 1) == 1 ? b[0] & 0xff : -1;
        }

        @Override
        public void close() {
            port.closePort();
        }
    }
}
