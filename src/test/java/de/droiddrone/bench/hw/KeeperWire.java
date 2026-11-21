package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Drone setup's lease on the board, as the app's own door to a flight controller ({@link BetaflightUsb.Usb} /
 * {@link BetaflightUsb.Wire}): what the PC app's setup pages, motor test and compass pages open while the flight
 * controller keeper has the board's port ({@link FcKeeper}). In the bench:
 * <pre>
 * rig.usb.plug(KeeperWire.PORT_NAME, KeeperWire.DESCRIPTION, () -> KeeperWire.open(setupPort));
 * </pre>
 * The keeper gives the lease only while the board is disarmed and no arm window is open, and cuts the phone's side for
 * as long as it is held. A lease that is not given is a wire that is dead from the start - the port "gone", as a board
 * that restarts - with the keeper's reason in {@link #refusal()}; {@link #usb} says it in the IOException instead. The
 * keeper ends the lease when the board restarts (every CLI visit ends so): then the wire reads "gone", as USB does.
 */
public final class KeeperWire implements BetaflightUsb.Wire {
    public static final String PORT_NAME = "KEEPER", DESCRIPTION = "The bench's flight controller (through its keeper)";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String refusal;
    private volatile boolean gone;

    private KeeperWire(Socket socket, InputStream in, OutputStream out, String refusal) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.refusal = refusal;
        gone = refusal != null;
    }

    /** Asks the keeper for the lease. Never throws: without it the wire is dead already and {@link #refusal()} says why. */
    public static KeeperWire open(int setupPort) {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), setupPort), 2000);
            socket.setSoTimeout(5000); // the keeper asks the board whether it is armed before it answers
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b != '\n'; b = in.read()) {
                if (b < 0) throw new IOException("the keeper closed the connection");
                line.write(b);
            }
            String answer = line.toString(StandardCharsets.UTF_8).trim();
            if (!answer.equals("OK")) throw new IOException(answer.startsWith("NO ") ? answer.substring(3) : answer);
            socket.setSoTimeout(100); // from here on a port's read: a tenth of a second for a first byte
            return new KeeperWire(socket, in, socket.getOutputStream(), null);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return new KeeperWire(socket, null, null, "the keeper does not give Drone setup the board: " + e.getMessage());
        }
    }

    /**
     * This PC's USB with the keeper's board on it and nothing else, for {@code BetaflightUsb.use}: one port, always
     * listed; opening it asks for the lease, and a refusal is the IOException.
     */
    public static BetaflightUsb.Usb usb(int setupPort) {
        BetaflightUsb.Port port = new BetaflightUsb.Port(PORT_NAME, DESCRIPTION);
        return new BetaflightUsb.Usb() {
            @Override
            public List<BetaflightUsb.Port> ports() {
                return List.of(port);
            }

            @Override
            public BetaflightUsb.Wire open(BetaflightUsb.Port p) throws IOException {
                if (!p.systemName().equals(PORT_NAME)) throw new IOException(p.systemName() + " is gone - plug the flight controller in again.");
                KeeperWire wire = KeeperWire.open(setupPort);
                if (wire.refusal() != null) throw new IOException(PORT_NAME + " is in use - " + wire.refusal());
                return wire;
            }
        };
    }

    /** Why the keeper gave no lease; null: it did. */
    public String refusal() {
        return refusal;
    }

    @Override
    public int read(byte[] buf) throws IOException {
        if (gone) return -1;
        try {
            int n = in.read(buf);
            if (n < 0) gone = true;
            return n;
        } catch (SocketTimeoutException nothingYet) {
            return 0;
        } catch (IOException e) {
            gone = true;
            return -1;
        }
    }

    @Override
    public int available() throws IOException {
        if (gone) return 0;
        try {
            return in.available();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (gone) throw new IOException("Could not write to the flight controller" + (refusal == null ? "" : " (" + refusal + ")"));
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            gone = true;
            throw new IOException("Could not write to the flight controller");
        }
    }

    @Override
    public boolean isOpen() {
        return !gone && !socket.isClosed();
    }

    @Override
    public void close() {
        gone = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
