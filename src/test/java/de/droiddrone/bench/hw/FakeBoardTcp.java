package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The made-up board on a TCP port of this PC's loopback - as Betaflight running as a program listens on one - for a
 * keeper that runs in a process of its own ({@code FcKeeper board=tcp:127.0.0.1:port}): the board itself stays in the
 * test's JVM, where the test can look at it after the keeper's process is gone. One connection at a time, as a COM
 * port: while one is open the board's port is "in use" to everybody else, and when it ends - the keeper ended, or was
 * killed - the port is free again and the board is as it was left.
 */
final class FakeBoardTcp implements AutoCloseable {
    private final FakeFlightBoard board;
    private final ServerSocket server;
    private volatile boolean closed;
    private volatile Socket current;

    FakeBoardTcp(FakeFlightBoard board) throws IOException {
        this.board = board;
        server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::accept, "fakeBoardTcp");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** A keeper has the board just now; false only once its connection has ended and the board's port is free again. */
    boolean connected() {
        return current != null;
    }

    private void accept() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            BetaflightUsb.Wire wire;
            try {
                wire = board.usb().open(board.port()); // away, or in use: no connection, as a port that cannot be opened
                socket.setTcpNoDelay(true);
            } catch (IOException e) {
                quietly(socket);
                continue;
            }
            current = socket;
            Thread up = new Thread(() -> toBoard(socket, wire), "fakeBoardTcpIn");
            up.setDaemon(true);
            up.start();
            fromBoard(socket, wire); // one at a time: the next is taken when this one has ended
            wire.close();
            quietly(socket);
            current = null;
        }
    }

    private void toBoard(Socket socket, BetaflightUsb.Wire wire) {
        byte[] buf = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) wire.write(java.util.Arrays.copyOf(buf, n));
        } catch (IOException e) {
            // the keeper went (ended, or killed), or the board did
        }
        quietly(socket);
    }

    private void fromBoard(Socket socket, BetaflightUsb.Wire wire) {
        byte[] buf = new byte[4096];
        try {
            OutputStream out = socket.getOutputStream();
            while (!socket.isClosed()) {
                int n = wire.read(buf);
                if (n < 0) break; // the board restarts: the connection ends, as a USB port goes away
                if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (IOException e) {
            // the keeper went
        }
    }

    private static void quietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Socket s = current;
        if (s != null) quietly(s);
    }
}
