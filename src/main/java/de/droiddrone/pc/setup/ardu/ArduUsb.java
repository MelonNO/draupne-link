package de.droiddrone.pc.setup.ardu;

import com.fazecast.jSerialComm.SerialPort;
import de.droiddrone.common.MavlinkSniff;
import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fleet PRD AP-7 / AS-1: an ArduPilot flight controller on this PC's USB, for Drone setup on the bench. A port is
 * taken only when the board on it speaks first - ArduPilot sends its heartbeat once a second unasked - so a Betaflight
 * board on the bench is never sent a byte from here (it would be listened to for {@link #LISTEN_MS} and let go).
 * The test bench can stand in for the ports ({@link #use}).
 */
public final class ArduUsb {
    static final int LISTEN_MS = 2500;

    private static volatile Function<BetaflightUsb.Port, MavSession.Wire> standIn;

    private ArduUsb() {
    }

    /** The test bench only: the wire a port opens as; null puts this PC's serial ports back. */
    public static void use(Function<BetaflightUsb.Port, MavSession.Wire> wires) {
        standIn = wires;
    }

    /** What was found: the port and its session (heartbeat heard), or nothing. */
    public record Found(BetaflightUsb.Port port, MavSession session) {
    }

    /**
     * Every flight-controller-looking port, listened to in turn; the first whose board sends a MAVLink heartbeat from an
     * autopilot is opened as a session. Several ArduPilot boards on USB: refused (which one would be written is not
     * the pilot's to guess) - the found ones are closed again and an IOException names them.
     */
    public static Found find() throws IOException, InterruptedException {
        List<BetaflightUsb.Port> candidates = BetaflightUsb.findFlightControllers();
        List<Found> found = new ArrayList<>();
        for (BetaflightUsb.Port p : candidates) {
            MavSession.Wire wire;
            try {
                wire = open(p);
            } catch (IOException e) {
                continue; // in use (Mission Planner, Betaflight Configurator): not this tool's
            }
            if (!heartbeatOn(wire)) {
                wire.close();
                continue;
            }
            found.add(new Found(p, new MavSession(wire)));
        }
        if (found.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Found f : found) {
                names.append(names.length() == 0 ? "" : ", ").append(f.port().systemName());
                f.session().close();
            }
            throw new IOException("Several ArduPilot boards on USB (" + names + "): plug in one.");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /** Listens (writes nothing) until a heartbeat from an autopilot comes, or LISTEN_MS. */
    static boolean heartbeatOn(MavSession.Wire wire) throws InterruptedException {
        MavlinkSniff sniff = new MavlinkSniff();
        byte[] buf = new byte[512];
        long end = System.currentTimeMillis() + LISTEN_MS;
        while (System.currentTimeMillis() < end) {
            if (Thread.interrupted()) throw new InterruptedException();
            int n;
            try {
                n = wire.read(buf, 200);
            } catch (IOException e) {
                return false;
            }
            if (n < 0) return false;
            if (n > 0 && sniff.feed(buf, n)) return true;
        }
        return false;
    }

    static MavSession.Wire open(BetaflightUsb.Port p) throws IOException {
        Function<BetaflightUsb.Port, MavSession.Wire> s = standIn;
        if (s != null) return s.apply(p);
        SerialPort port = SerialPort.getCommPort(p.systemName());
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 1000);
        if (!port.openPort()) throw new IOException(p.systemName() + " is in use - close Mission Planner and retry.");
        port.flushIOBuffers();
        return new MavSession.Wire() {
            @Override
            public void write(byte[] data) throws IOException {
                if (port.writeBytes(data, data.length) != data.length) throw new IOException("write failed");
            }

            @Override
            public int read(byte[] buf, int timeoutMs) {
                if (!port.isOpen()) return -1;
                int n = port.readBytes(buf, buf.length);
                return n < 0 ? -1 : n;
            }

            @Override
            public void close() {
                port.closePort();
            }
        };
    }
}
