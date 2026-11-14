package de.droiddrone.pc.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * FIND GPS in Drone setup (1.0.68, the pilot: "a button in the betaflight setup that searches for the GPS uart"): the
 * GPS put on each free UART in turn - written, saved, the board restarted - until Betaflight hears it. Found: it
 * stays there. None: every port, the GPS feature and gps_auto_baud as they were.
 * <p>
 * Only free UARTs are tried (and the one the GPS is on now, last - it was looked at already): a port carrying the
 * receiver, MSP, telemetry or anything else is never taken over. Betaflight's "diff all" is saved before the first
 * write, as Drone setup's own writes do.
 */
public final class GpsSearch {
    /** Betaflight tries its baud rates first: as long as Drone setup's own GPS check. */
    public static final long GPS_WAIT_MS = 12_000;
    /** A restarted board's USB port is back within this. */
    static final long BACK_WAIT_MS = 15_000;

    /** What the search does with the flight controller (BetaflightUsb; a made-up one in the tests). */
    public interface Board extends AutoCloseable {
        int featureMask() throws IOException;

        BetaflightUsb.BatteryState batteryState() throws IOException;

        boolean waitForGps(long timeoutMs) throws IOException;

        void enterCli() throws IOException;

        String command(String command) throws IOException;

        String command(String command, long timeoutMs) throws IOException;

        /** Leaves the CLI unsaved: Betaflight restarts. */
        void exitCli();

        /** "save": Betaflight restarts. */
        void saveAndReboot() throws IOException;

        @Override
        void close();
    }

    /** Betaflight over USB as the search uses it. */
    public static Board of(BetaflightUsb fc) {
        return new Board() {
            public int featureMask() throws IOException { return fc.featureMask(); }
            public BetaflightUsb.BatteryState batteryState() throws IOException { return fc.batteryState(); }
            public boolean waitForGps(long timeoutMs) throws IOException { return fc.waitForGps(timeoutMs); }
            public void enterCli() throws IOException { fc.enterCli(); }
            public String command(String command) throws IOException { return fc.command(command); }
            public String command(String command, long timeoutMs) throws IOException { return fc.command(command, timeoutMs); }
            public void exitCli() { fc.exitCli(); }
            public void saveAndReboot() throws IOException { fc.saveAndReboot(); }
            public void close() { fc.close(); }
        };
    }

    /** The same flight controller again (after its restart: its USB port comes back). */
    public interface Opener {
        Board open() throws IOException;
    }

    /**
     * @param foundUart the UART the GPS answered on (1 = UART1), 0 for none
     * @param tried the UARTs tried, in order
     * @param skipped the ports left alone, with what they carry ("UART1 (serial receiver)")
     * @param battery a battery was in at the start (null: not known) - some boards power the GPS from it alone
     * @param problem why it stopped early, or null
     */
    public record Result(int foundUart, List<Integer> tried, List<String> skipped, Boolean battery, String problem) {
        public boolean found() {
            return foundUart > 0;
        }
    }

    private final Opener opener;
    private final BiFunction<String, String, Path> backup;
    private final Consumer<String> progress, log;
    private final BooleanSupplier stop;
    /** Tests: no wait for the restart. */
    long restartMs = 1500;

    /**
     * @param backup saves Betaflight's "diff all" (with its version line) and says where; null when it couldn't
     * @param progress the line under the GPS row while it runs (any thread)
     * @param stop asked between tries: the page was left - the ports go back as they were
     */
    public GpsSearch(Opener opener, BiFunction<String, String, Path> backup, Consumer<String> progress, Consumer<String> log, BooleanSupplier stop) {
        this.opener = opener;
        this.backup = backup;
        this.progress = progress;
        this.log = log;
        this.stop = stop;
    }

    /** The UARTs to try: the free ones in order, then the one the GPS is on now. */
    public static List<Integer> candidates(List<GpsSetup.Port> ports) {
        List<Integer> out = new ArrayList<>();
        int gpsNow = 0;
        for (GpsSetup.Port p : ports) {
            if (p.uart() == 0) continue;
            if (p.hasGps()) gpsNow = p.uart();
            else if (p.functions() == 0 && !out.contains(p.uart())) out.add(p.uart());
        }
        out.sort(null);
        if (gpsNow > 0) out.add(gpsNow);
        return out;
    }

    /** The UARTs left alone, with what they carry. */
    public static List<String> skipped(List<GpsSetup.Port> ports) {
        List<String> out = new ArrayList<>();
        for (GpsSetup.Port p : ports) {
            if (p.uart() == 0 || p.hasGps() || p.functions() == 0) continue;
            out.add(p.name() + " (" + GpsSetup.functionNames(p.functions()) + ")");
        }
        return out;
    }

    /**
     * The commands that put things back as they were: every port that changed, the GPS feature, gps_auto_baud.
     * Ports are named as the CLI wrote them before.
     */
    static List<String> restore(String serialBefore, String serialNow, int maskBefore, int maskNow, String autoBaudBefore, String autoBaudNow) {
        List<String> commands = new ArrayList<>();
        List<GpsSetup.Port> now = GpsSetup.ports(serialNow);
        for (GpsSetup.Port b : GpsSetup.ports(serialBefore)) {
            GpsSetup.Port n = null;
            for (GpsSetup.Port p : now) if (p.id() == b.id()) n = p;
            if (n == null || n.functions() != b.functions() || !n.rest().equals(b.rest())) {
                commands.add("serial " + b.token() + " " + b.functions() + " " + b.rest());
            }
        }
        if (!BetaflightPlan.hasGps(maskBefore) && BetaflightPlan.hasGps(maskNow)) commands.add("feature -GPS");
        if (GpsSetup.autoBaudKnown(autoBaudBefore) && !GpsSetup.autoBaudOn(autoBaudBefore) && GpsSetup.autoBaudOn(autoBaudNow)) {
            commands.add("set gps_auto_baud = OFF");
        }
        return commands;
    }

    public Result run() {
        List<Integer> tried = new ArrayList<>();
        List<String> skipped = List.of();
        Boolean battery = null;
        Board b = null;
        try {
            b = opener.open();
            try {
                battery = b.batteryState().present();
            } catch (IOException e) {
                // an old Betaflight without it
            }
            int maskBefore = b.featureMask();
            b.enterCli();
            String version = b.command("version");
            String serialBefore = b.command("serial");
            String autoBaudBefore = b.command("get gps_auto_baud");
            List<GpsSetup.Port> ports = GpsSetup.ports(serialBefore);
            skipped = skipped(ports);
            List<Integer> uarts = candidates(ports);
            if (!version.contains("Betaflight")) return stopped(b, tried, skipped, battery, "Not Betaflight: nothing written.");
            if (ports.isEmpty()) return stopped(b, tried, skipped, battery, "Betaflight's ports not read: nothing written.");
            if (uarts.isEmpty()) return stopped(b, tried, skipped, battery, "No free UART to try.");
            String diff = b.command("diff all", 20_000);
            Path saved = CliWrite.usableBackup(diff) ? backup.apply(diff, version.trim()) : null;
            if (saved == null) return stopped(b, tried, skipped, battery, "Backup failed (see log): nothing written.");
            log.accept("GPS search: trying " + names(uarts) + (skipped.isEmpty() ? "" : "; left alone: " + String.join(", ", skipped))
                    + ". Settings before: " + saved);
            int mask = maskBefore;
            String serialNow = serialBefore, autoBaudNow = autoBaudBefore;
            for (int i = 0; i < uarts.size(); i++) {
                if (stop.getAsBoolean()) break;
                int uart = uarts.get(i);
                progress.accept("Trying UART" + uart + " (" + (i + 1) + " of " + uarts.size() + ")...");
                List<String> commands = new ArrayList<>();
                for (BetaflightPlan.Change c : GpsSetup.gpsPlan(mask, serialNow, autoBaudNow, uart)) commands.addAll(c.commands());
                write(b, commands, "GPS on UART" + uart);
                b = back();
                tried.add(uart);
                if (b.waitForGps(GPS_WAIT_MS)) {
                    log.accept("GPS search: the GPS answers on UART" + uart + ". Left there, saved.");
                    return new Result(uart, tried, skipped, battery, null);
                }
                log.accept("GPS search: nothing on UART" + uart + ".");
                mask = b.featureMask();
                b.enterCli();
                serialNow = b.command("serial");
                autoBaudNow = b.command("get gps_auto_baud");
            }
            // none: as it was
            List<String> commands = restore(serialBefore, serialNow, maskBefore, mask, autoBaudBefore, autoBaudNow);
            write(b, commands, "ports as before");
            b = null; // restarting: nothing more to say to it
            log.accept("GPS search: no GPS on " + names(tried) + ". Ports as before.");
            return new Result(0, tried, skipped, battery, stop.getAsBoolean() ? "Stopped: the page was left." : null);
        } catch (IOException e) {
            log.accept("GPS search stopped: " + e.getMessage());
            return new Result(0, tried, skipped, battery, "Stopped: " + e.getMessage()
                    + (tried.isEmpty() ? "" : " GPS left on UART" + tried.get(tried.size() - 1) + "; Retry reads it."));
        } finally {
            if (b != null) b.close();
        }
    }

    private Result stopped(Board b, List<Integer> tried, List<String> skipped, Boolean battery, String why) {
        b.exitCli();
        log.accept("GPS search: " + why);
        return new Result(0, tried, skipped, battery, why);
    }

    /** In the CLI: the commands, then save (Betaflight restarts); none: the CLI left (it restarts all the same). */
    private void write(Board b, List<String> commands, String what) throws IOException {
        if (commands.isEmpty()) {
            b.exitCli();
            return;
        }
        CliWrite.Result written = CliWrite.run(b::command, commands, log);
        if (!written.complete()) {
            b.exitCli();
            throw new IOException(what + ": " + written.stopped() + ". Not saved.");
        }
        b.saveAndReboot();
    }

    /** The board after its restart: opened again as soon as its port is back. */
    private Board back() throws IOException {
        sleep(restartMs);
        long until = System.currentTimeMillis() + BACK_WAIT_MS;
        IOException last = null;
        while (System.currentTimeMillis() < until) {
            try {
                return opener.open();
            } catch (IOException e) {
                last = e;
                sleep(500);
            }
        }
        throw new IOException("the flight controller didn't come back after its restart" + (last == null ? "" : " (" + last.getMessage() + ")"));
    }

    private static void sleep(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    static String names(List<Integer> uarts) {
        List<String> n = new ArrayList<>();
        for (int u : uarts) n.add("UART" + u);
        return String.join(", ", n);
    }
}
