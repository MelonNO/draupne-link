package de.droiddrone.pc.setup.ardu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.dfu.DfuDevice;
import de.droiddrone.pc.setup.dfu.HexImage;
import de.droiddrone.pc.setup.dfu.StmDfu;
import de.droiddrone.pc.setup.dfu.WinUsbDfu;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Fleet PRD H1 / H4 (AS-1): ArduPilot onto a board that runs Betaflight today, by the STM32's USB DFU. On the pilot's
 * click only, for the one board the pilot picked, disarmed only:
 * <ol>
 *   <li>the DFU devices already on USB are noted (they are never opened: another board in DFU is left alone);</li>
 *   <li>the picked board is asked over MSP whether it is armed (armed or no answer: stop), then the CLI's "bl" sends
 *       it into its bootloader;</li>
 *   <li>exactly one new DFU device must come up - none: the driver or the board; two or more: stop, nothing opened;</li>
 *   <li>that device is opened and written ({@link StmDfu#flash}: 1 MB flash or more, erase, write, read back), then
 *       restarted into ArduPilot.</li>
 * </ol>
 * The firmware is ArduPilot's "_with_bl.hex" (its bootloader and Copter) for the board: a file, or the newest stable
 * one from firmware.ardupilot.org's manifest for Betaflight's board name (asked before "bl", while the board answers).
 */
public final class ArduDfuFlow {
    public static final long WAIT_DFU_MS = 15_000;

    /** The picked Betaflight board. */
    public interface Board {
        /** Armed, or no answer (then: stop). */
        boolean armed() throws IOException;

        /** The CLI's "bl": into its bootloader. Its serial port goes away. */
        void bootloader() throws IOException;
    }

    private static volatile DfuDevice.Bus standIn;

    private ArduDfuFlow() {
    }

    /** The test bench only: a made-up DFU bus; null puts this PC's USB back. */
    public static void use(DfuDevice.Bus bus) {
        standIn = bus;
    }

    /** The DFU bus to use: the bench's stand-in; none while the bench's made-up serial ports stand in; else WinUSB. */
    public static DfuDevice.Bus bus() {
        DfuDevice.Bus b = standIn;
        if (b != null) return b;
        return BetaflightUsb.standingIn() ? NONE : WinUsbDfu.BUS;
    }

    /** No DFU devices (the bench without a made-up one: the real USB is never touched). */
    static final DfuDevice.Bus NONE = new DfuDevice.Bus() {
        @Override
        public List<String> present() {
            return List.of();
        }

        @Override
        public DfuDevice open(String id) throws IOException {
            throw new IOException("no DFU device");
        }
    };

    /** The real board on a serial port: each call opens the port, asks or sends, and lets go. */
    public static Board betaflight(BetaflightUsb.Port port) {
        return new Board() {
            @Override
            public boolean armed() throws IOException {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    return fc.armedNow();
                }
            }

            @Override
            public void bootloader() throws IOException {
                BetaflightUsb fc = BetaflightUsb.open(port);
                try {
                    fc.rebootToBootloader();
                } finally {
                    fc.close();
                }
            }
        };
    }

    /** Betaflight's board name (the CLI's "board_name"), e.g. "MATEKF405"; null if it gives none. Leaves the CLI. */
    public static String boardName(BetaflightUsb fc) throws IOException {
        fc.enterCli();
        try {
            return parseBoardName(fc.command("board_name"));
        } finally {
            fc.exitCli();
        }
    }

    static String parseBoardName(String text) {
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2 && p[0].equalsIgnoreCase("board_name")) return p[1];
        }
        return null;
    }

    /**
     * Betaflight to ArduPilot on the picked board. Returns the DFU device's id that was written.
     * @param sleeper the waits (the tests' made-up chip answers at once)
     */
    public static String fromBetaflight(Board board, DfuDevice.Bus bus, HexImage img, Consumer<String> progress,
                                        StmDfu.Sleeper sleeper, long waitMs) throws IOException, InterruptedException {
        List<String> before = bus.present();
        if (board.armed()) throw new IOException("the drone is armed: firmware only on the bench, disarmed");
        progress.accept("Restarting into DFU ...");
        board.bootloader();
        String id = newDevice(bus, before, sleeper, waitMs);
        progress.accept("Found it in DFU.");
        write(bus, id, img, progress, sleeper);
        return id;
    }

    /** A board already in DFU (its boot button held), picked by its id - written only if it is still there. */
    public static void inDfu(DfuDevice.Bus bus, String id, HexImage img, Consumer<String> progress, StmDfu.Sleeper sleeper)
            throws IOException, InterruptedException {
        if (!bus.present().contains(id)) throw new IOException("that DFU device has gone");
        write(bus, id, img, progress, sleeper);
    }

    private static void write(DfuDevice.Bus bus, String id, HexImage img, Consumer<String> progress, StmDfu.Sleeper sleeper)
            throws IOException, InterruptedException {
        try (DfuDevice d = bus.open(id)) {
            if (!id.equals(d.id())) throw new IOException("opened another device than picked: stopped");
            new StmDfu(d, sleeper).flash(img, progress);
        }
    }

    /** Waits for exactly one DFU device that wasn't there before. */
    static String newDevice(DfuDevice.Bus bus, List<String> before, StmDfu.Sleeper sleeper, long waitMs) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + waitMs;
        while (true) {
            List<String> now = new ArrayList<>(bus.present());
            now.removeAll(before);
            if (now.size() == 1) return now.get(0);
            if (now.size() > 1) throw new IOException("several boards came up in DFU: unplug the others, nothing written");
            if (System.currentTimeMillis() > end) {
                throw new IOException("the board didn't come up in DFU: its USB driver (Betaflight's driver tool or Zadig), or hold its boot button");
            }
            sleeper.sleep(250);
        }
    }

    // ------------------------------------------------------------------ the firmware for this board

    /** The newest stable Copter "_with_bl.hex" for this Betaflight board name, or null (firmware.ardupilot.org). */
    public static String latestCopterHex(String boardName, HttpClient http) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(ArduFirmwareFlow.MANIFEST)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("firmware list: HTTP " + r.statusCode());
        try (InputStream in = new GZIPInputStream(r.body())) {
            return pickWithBl(new String(in.readAllBytes(), StandardCharsets.UTF_8), boardName);
        }
    }

    /** ArduPilot's platform name for a Betaflight board name: the same letters and digits (MATEKF405 = MatekF405). */
    static String key(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** From a manifest: the Copter OFFICIAL "_with_bl.hex" for this board, a /stable/ one first. */
    static String pickWithBl(String manifestJson, String boardName) {
        String want = key(boardName);
        if (want.isEmpty()) return null;
        JsonArray fws = JsonParser.parseString(manifestJson).getAsJsonObject().getAsJsonArray("firmware");
        String any = null;
        for (JsonElement e : fws) {
            JsonObject f = e.getAsJsonObject();
            if (!want.equals(key(str(f, "platform")))) continue;
            if (!"Copter".equalsIgnoreCase(str(f, "vehicletype")) || !"OFFICIAL".equalsIgnoreCase(str(f, "mav-firmware-version-type"))) continue;
            String url = str(f, "url");
            if (!url.endsWith("_with_bl.hex")) continue;
            if (url.contains("/stable/")) return url;
            if (any == null) any = url;
        }
        return any;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
