package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * The hardware rig's way to a flight controller on USB: which port, and the port opened so that MSP answers - also
 * right after a restart (leaving the CLI, a save and MSP's reboot all restart Betaflight: its port goes away for some
 * seconds and comes back), and on a board a tool that died left in the CLI.
 */
public final class BoardPort {
    /**
     * How often to look for a board that restarts, and for how long.
     * @param stepMs between two looks
     * @param backMs after this long without an answer it is given up
     */
    public record Timing(long stepMs, long backMs) {
        /** A real board is back within a few seconds; DRAUPNE Link's own pages give it six. */
        public static final Timing REAL = new Timing(500, 25_000);
    }

    private BoardPort() {
    }

    /** A board that restarts is looked for this long at most before "none found" is said. */
    private static final long FIND_MS = 6000;

    /**
     * The flight controller on USB: the one there is, or the one on the named port ("" = whichever is the only one).
     * Never a guess between two. One that is not there yet - Betaflight restarting - is waited for a few seconds.
     */
    public static BetaflightUsb.Port find(String wanted, Timing timing) throws IOException {
        String name = wanted == null ? "" : wanted.strip();
        long end = System.currentTimeMillis() + Math.min(FIND_MS, timing.backMs());
        while (true) {
            List<BetaflightUsb.Port> ports = found(name);
            if (ports.size() == 1) return ports.get(0);
            if (ports.size() > 1) throw new IOException("Several flight controllers (" + ports + "): say which with -Pport=COMx");
            if (System.currentTimeMillis() >= end) {
                throw new IOException(name.isEmpty() ? "No flight controller found: USB in?"
                        : "No flight controller on " + name + ". Found: " + BetaflightUsb.findFlightControllers());
            }
            pause(timing.stepMs());
        }
    }

    /** Every flight controller on USB, or the one on the named port; waited for as {@link #find}. Empty: none. */
    public static List<BetaflightUsb.Port> findAll(String wanted, Timing timing) throws IOException {
        String name = wanted == null ? "" : wanted.strip();
        long end = System.currentTimeMillis() + Math.min(FIND_MS, timing.backMs());
        while (true) {
            List<BetaflightUsb.Port> ports = found(name);
            if (!ports.isEmpty() || System.currentTimeMillis() >= end) return ports;
            pause(timing.stepMs());
        }
    }

    private static List<BetaflightUsb.Port> found(String name) {
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        return name.isEmpty() ? ports : ports.stream().filter(p -> p.systemName().equalsIgnoreCase(name)).toList();
    }

    /**
     * Opens the port and returns once Betaflight answers MSP on it; looks again every {@link Timing#stepMs} until
     * {@link Timing#backMs} are over (a board that restarts is not there at first). A board that answers no MSP but
     * has its CLI open - a tool died in there - is told "exit": it restarts, what was changed and not saved is
     * dropped, and it is opened again. Another firmware than Betaflight is refused at once.
     */
    public static BetaflightUsb open(BetaflightUsb.Port port, Timing timing, Consumer<String> log) throws IOException {
        long end = System.currentTimeMillis() + timing.backMs();
        IOException last;
        while (true) {
            last = null;
            if (listed(port)) {
                BetaflightUsb fc = null;
                String variant = null;
                try {
                    fc = BetaflightUsb.open(port);
                    variant = fc.firmware();
                } catch (IOException e) {
                    last = e;
                    // the port is there and takes what is written, but nothing answers MSP: a tool may have left the CLI open
                    if (fc != null && said(e, "No answer") && leftTheCli(fc)) {
                        log.accept(port.systemName() + " was in the CLI (a tool left it there): exit sent, Betaflight restarts, unsaved changes are dropped.");
                    }
                }
                if (variant != null && variant.startsWith("BTFL")) return fc;
                if (fc != null) fc.close();
                if (variant != null) throw new IOException(port.systemName() + " runs " + variant + ", not Betaflight.");
            } else {
                last = new IOException(port.systemName() + " is not there");
            }
            if (System.currentTimeMillis() >= end) {
                throw new IOException(port.systemName() + " does not answer (" + last.getMessage() + "). If it stays so: unplug its USB and battery, then plug in again.");
            }
            pause(timing.stepMs());
        }
    }

    /**
     * For the end of a tool that visited the CLI (the visit restarts Betaflight): waits for it and says so - "back,
     * disarmed" - or that it is not back. False when it is not back.
     */
    static boolean isBack(BetaflightUsb.Port port, Timing timing, java.io.PrintStream out) {
        try (BetaflightUsb fc = open(port, timing, s -> out.println("  " + s))) {
            out.println("Betaflight is back after its restart: " + (Msp.armed(fc.msp(Msp.STATUS, null)) ? "ARMED" : "disarmed") + ".");
            return true;
        } catch (IOException e) {
            out.println("Betaflight is NOT BACK after its restart: " + e.getMessage());
            return false;
        }
    }

    private static boolean listed(BetaflightUsb.Port port) {
        for (BetaflightUsb.Port p : BetaflightUsb.findFlightControllers()) if (p.systemName().equals(port.systemName())) return true;
        return false;
    }

    /**
     * A board that answers no MSP: if its CLI is open, it is left. Enter first - it runs (as an unknown command) what
     * the MSP request left in the CLI's line, so the next line is clean - then "exit". Outside the CLI neither does
     * anything: Betaflight looks only for '#' and 'R' there (CLI, bootloader), and there is none in these.
     * @return true when it was in the CLI: a prompt came back, or "exit" was answered by words or by the port going away
     */
    private static boolean leftTheCli(BetaflightUsb fc) {
        boolean prompt = false;
        try {
            fc.command("", 800);
            prompt = true;
        } catch (IOException nothing) {
            // an empty line there (Betaflight says nothing to that), or no CLI
        }
        try {
            fc.command("exit", 800);
        } catch (IOException e) { // no prompt after exit: Betaflight restarts
            return prompt || said(e, "disconnected") || said(e, "cut off");
        }
        return prompt;
    }

    /** BetaflightUsb's own words for how a request failed: "No answer ...", "... disconnected", "... was cut off". */
    private static boolean said(IOException e, String words) {
        return String.valueOf(e.getMessage()).contains(words);
    }

    static void pause(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        }
    }
}
