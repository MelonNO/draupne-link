package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.68 FIND GPS: the GPS put on each free UART in turn until Betaflight hears it, against a made-up Betaflight that
 * keeps its settings, restarts on save and hears the GPS only on the UART it is wired to.
 */
class GpsSearchTest {
    /** A board: UART1 the receiver, UART2 free, UART3 the GPS (as set), UART4 free, UART5 free; USB is MSP. */
    static final class Fc {
        Map<String, String[]> ports = new LinkedHashMap<>(); // token -> {functions, rest}
        int mask = BetaflightPlan.FEATURE_GPS;
        boolean autoBaud = false;
        int wiredTo; // the UART the GPS really is on, 0 none
        boolean batteryIn = true;
        int opens, saves, unsaved;
        boolean refuseSerial;
        final List<String> sent = new ArrayList<>();

        Fc(int wiredTo) {
            this.wiredTo = wiredTo;
            ports.put("0", new String[]{"64", "115200 57600 0 115200"});
            ports.put("1", new String[]{"0", "115200 57600 0 115200"});
            ports.put("2", new String[]{"2", "115200 57600 0 115200"});
            ports.put("3", new String[]{"0", "115200 57600 0 115200"});
            ports.put("4", new String[]{"0", "115200 57600 0 115200"});
            ports.put("20", new String[]{"1", "115200 57600 0 115200"});
        }

        String serial() {
            StringBuilder b = new StringBuilder();
            ports.forEach((t, p) -> b.append("serial ").append(t).append(' ').append(p[0]).append(' ').append(p[1]).append('\n'));
            return b.toString();
        }

        /** Betaflight hears the GPS: the feature on, and GPS on the port it is wired to. */
        boolean hears() {
            if (!BetaflightPlan.hasGps(mask) || wiredTo == 0) return false;
            String[] p = ports.get(Integer.toString(wiredTo - 1));
            return p != null && (Integer.parseInt(p[0]) & GpsSetup.FUNCTION_GPS) != 0;
        }

        GpsSearch.Opener opener() {
            return () -> {
                opens++;
                return new Session();
            };
        }

        /** One USB connection: CLI changes in RAM until save. */
        final class Session implements GpsSearch.Board {
            Map<String, String[]> pendingPorts;
            int pendingMask;
            boolean pendingAuto, closed;

            public int featureMask() {
                return mask;
            }

            public BetaflightUsb.BatteryState batteryState() {
                return new BetaflightUsb.BatteryState(batteryIn ? 6 : 0, batteryIn ? 24.8 : 0.4);
            }

            public boolean waitForGps(long timeoutMs) {
                return hears();
            }

            public void enterCli() {
                pendingPorts = new LinkedHashMap<>();
                ports.forEach((t, p) -> pendingPorts.put(t, p.clone()));
                pendingMask = mask;
                pendingAuto = autoBaud;
            }

            public String command(String c) throws IOException {
                return command(c, 1000);
            }

            public String command(String c, long timeoutMs) throws IOException {
                if (closed || pendingPorts == null) throw new IOException("not in the CLI");
                switch (c) {
                    case "version" -> {
                        return "# Betaflight / STM32F405 (S405) 4.4.1 Nov 22 2023 / 12:00:00 (abc) MSP API: 1.45\n";
                    }
                    case "serial" -> {
                        StringBuilder b = new StringBuilder();
                        pendingPorts.forEach((t, p) -> b.append("serial ").append(t).append(' ').append(p[0]).append(' ').append(p[1]).append('\n'));
                        return b.toString();
                    }
                    case "get gps_auto_baud" -> {
                        return "gps_auto_baud = " + (pendingAuto ? "ON" : "OFF") + "\nAllowed values: OFF, ON\n";
                    }
                    case "diff all" -> {
                        return "# version\n# Betaflight / STM32F405 (S405) 4.4.1\n\n# feature\nfeature GPS\n";
                    }
                    default -> {
                    }
                }
                sent.add(c);
                String[] w = c.split("\\s+", 4);
                if (w[0].equals("serial")) {
                    if (refuseSerial) return "###ERROR IN serial: INVALID ARGUMENT###";
                    pendingPorts.put(w[1], new String[]{w[2], w[3]});
                } else if (c.equals("feature GPS")) {
                    pendingMask |= BetaflightPlan.FEATURE_GPS;
                } else if (c.equals("feature -GPS")) {
                    pendingMask &= ~BetaflightPlan.FEATURE_GPS;
                } else if (c.startsWith("set gps_auto_baud = ")) {
                    pendingAuto = c.endsWith("ON");
                    return "gps_auto_baud set to " + (pendingAuto ? "ON" : "OFF") + "\n";
                }
                return "";
            }

            public void exitCli() {
                unsaved++;
                closed = true;
            }

            public void saveAndReboot() {
                ports = pendingPorts;
                mask = pendingMask;
                autoBaud = pendingAuto;
                saves++;
                closed = true;
            }

            public void close() {
                closed = true;
            }
        }
    }

    private static GpsSearch search(Fc fc, List<String> log, List<String> progress) {
        GpsSearch s = new GpsSearch(fc.opener(), (diff, version) -> Path.of("backup.txt"), progress::add, log::add, () -> false);
        s.restartMs = 0;
        return s;
    }

    @Test
    void theFreeUartsAreTriedInTurnAndTheGpsStaysWhereItAnswers() {
        Fc fc = new Fc(4);
        String before = fc.serial();
        List<String> log = new ArrayList<>(), progress = new ArrayList<>();
        GpsSearch.Result r = search(fc, log, progress).run();
        assertTrue(r.found(), "found: " + log);
        assertEquals(4, r.foundUart());
        assertEquals(List.of(2, 4), r.tried(), "UART2 first, then UART4 - the GPS's old UART3 comes last, UART1 never");
        assertEquals(List.of("UART1 (serial receiver)"), r.skipped());
        assertEquals("2 115200 57600 0 115200", String.join(" ", fc.ports.get("3")), "GPS on UART4, saved");
        assertEquals("0", fc.ports.get("2")[0], "taken off UART3 - one GPS port");
        assertEquals("64", fc.ports.get("0")[0], "the receiver's UART untouched");
        assertEquals("1", fc.ports.get("20")[0], "USB's MSP untouched");
        assertTrue(fc.autoBaud, "found at any baud rate");
        assertNotEquals(before, fc.serial());
        assertEquals(List.of("Trying UART2 (1 of 4)...", "Trying UART4 (2 of 4)..."), progress);
        assertTrue(log.stream().anyMatch(l -> l.contains("Settings before: backup.txt")), "the backup is said first: " + log);
    }

    @Test
    void noGpsAnywhereLeavesEveryPortAndSettingAsItWas() {
        Fc fc = new Fc(0);
        fc.mask = 0; // GPS off too
        String before = fc.serial();
        List<String> log = new ArrayList<>();
        GpsSearch.Result r = search(fc, log, new ArrayList<>()).run();
        assertFalse(r.found());
        assertNull(r.problem());
        assertEquals(List.of(2, 4, 5, 3), r.tried(), "every free UART, then the one it was set on");
        assertEquals(before, fc.serial(), "the ports as before");
        assertEquals(0, fc.mask, "the GPS feature off again");
        assertFalse(fc.autoBaud, "gps_auto_baud as it was");
        assertEquals(Boolean.TRUE, r.battery());
    }

    @Test
    void theGpsOnTheUartItWasSetOnIsFoundLastAndNothingElseChanges() {
        Fc fc = new Fc(3);
        fc.autoBaud = true;
        GpsSearch.Result r = search(fc, new ArrayList<>(), new ArrayList<>()).run();
        assertEquals(3, r.foundUart());
        assertEquals("2", fc.ports.get("2")[0]);
        assertEquals("0", fc.ports.get("1")[0], "UART2 free again after its try");
    }

    @Test
    void nothingIsWrittenWithoutABackupNorOnAnythingButBetaflight() {
        Fc fc = new Fc(4);
        String before = fc.serial();
        List<String> log = new ArrayList<>();
        GpsSearch s = new GpsSearch(fc.opener(), (diff, version) -> null, x -> {}, log::add, () -> false);
        s.restartMs = 0;
        GpsSearch.Result r = s.run();
        assertFalse(r.found());
        assertEquals("Backup failed (see log): nothing written.", r.problem());
        assertEquals(0, fc.saves);
        assertEquals(before, fc.serial());
        assertTrue(fc.sent.isEmpty(), "not a command written: " + fc.sent);
    }

    @Test
    void aRefusedCommandSavesNothingAndStops() {
        Fc fc = new Fc(4);
        fc.refuseSerial = true;
        String before = fc.serial();
        GpsSearch.Result r = search(fc, new ArrayList<>(), new ArrayList<>()).run();
        assertFalse(r.found());
        assertNotNull(r.problem());
        assertTrue(r.problem().startsWith("Stopped: GPS on UART2: Betaflight refused"), r.problem());
        assertEquals(0, fc.saves);
        assertEquals(before, fc.serial(), "the CLI left unsaved: as it was");
    }

    @Test
    void leavingThePageStopsAfterTheTryRunningAndPutsThePortsBack() {
        Fc fc = new Fc(0);
        String before = fc.serial();
        int[] asked = {0};
        GpsSearch s = new GpsSearch(fc.opener(), (diff, version) -> Path.of("b.txt"), x -> {}, x -> {}, () -> ++asked[0] > 1);
        s.restartMs = 0;
        GpsSearch.Result r = s.run();
        assertEquals(List.of(2), r.tried(), "the first try only");
        assertEquals("Stopped: the page was left.", r.problem());
        assertEquals(before, fc.serial());
    }

    @Test
    void portsInUseAreNeverTried() {
        List<GpsSetup.Port> ports = GpsSetup.ports("""
                serial 20 1 115200 57600 0 115200
                serial 0 64 115200 57600 0 115200
                serial 1 2048 115200 57600 0 115200
                serial 2 0 115200 57600 0 115200
                serial 30 0 115200 57600 0 115200
                serial UART5 0 115200 57600 0 115200
                """);
        assertEquals(List.of(3, 5), GpsSearch.candidates(ports), "free hardware UARTs only: not USB, a soft serial, the receiver nor SmartAudio");
        assertEquals(List.of("UART1 (serial receiver)", "UART2 (SmartAudio)"), GpsSearch.skipped(ports));
    }
}
