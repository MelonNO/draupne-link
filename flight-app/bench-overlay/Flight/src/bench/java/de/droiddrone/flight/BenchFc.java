/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE TEST RIG'S BUILD ONLY - NEVER RELEASED (flight-app/bench-overlay/README.md).
 * <p>
 * On the rig the phone and the flight controller are both plugged into the PC, so the phone cannot have the board on
 * its own USB as it has in flight. This is the other way to it: a TCP connection to this phone's own 127.0.0.1, which
 * {@code adb reverse tcp:<port> tcp:<port>} carries down the cable to a program on the PC that talks to the board (or
 * to Betaflight running as a program). In the Android emulator the same.
 * <p>
 * Chosen by adb, never by the app's settings (the PC writes those at every connect) and never on a screen:
 * {@code adb shell setprop debug.draupne.fc tcp:<port>}. Not set (or set to usb) - and it is gone after every restart
 * of the phone - this build looks for its flight controller on USB exactly as the released app does. It is read each time
 * {@link Serial} looks for a flight controller, so it takes effect when the app next opens its port: at once while it
 * has none, else after the app (or the link) is started again.
 * <p>
 * What Serial sees is a USB port by another name: bytes that come go to {@link Serial#benchData} (the USB reader's
 * way on), the end of the stream or an error is {@link Serial#benchLost} (a board pulled from USB: the port is opened
 * again at once, then looked for every 150 ms for 5 s, then every 2 s). Differences from USB, on purpose none but
 * these: a write has no time limit (TCP has none to give; how long writes took is in the statistics), and a
 * connection that ended without one byte or within a second - nobody behind adb's forward, which itself always
 * answers - makes the next try wait as long as Serial waits between two looks for a USB device (150 ms in the 5 s
 * after a flight controller was lost, else 2 s), so that a port nobody is behind cannot be opened over and over
 * without a pause.
 * <p>
 * The address is 127.0.0.1 and nothing else, in the code: this class cannot be pointed at a network.
 */
final class BenchFc {
    /** True in this build alone: Serial's other way is compiled in. */
    static final boolean BUILT_IN = true;
    /** In the code of every app that has this class; an APK is scanned for it (flight-app/scan-apk.ps1). */
    static final String MARKER = "DRAUPNE-BENCH-FC";
    /** The system property adb sets: tcp:&lt;port&gt;. */
    static final String PROPERTY = "debug.draupne.fc";

    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int READ_BUFFER = 2048; // as Serial's
    /** The property is read again this long after the last time, not at every step of Serial's loop. */
    private static final long REREAD_MS = 1000, REREAD_BY_GETPROP_MS = 5000;
    /** A connection this old that brought bytes was a flight controller: after it, the next try is at once. */
    private static final long HEALTHY_MS = 1000;
    /**
     * A connection that was none (no byte, or gone within a second) makes the next try wait as long as Serial waits
     * between two looks for a USB device: 150 ms in the 5 s after a flight controller was lost, else 2 s.
     */
    private static final long FAST_GAP_MS = 150, FAST_FOR_MS = 5000, SLOW_GAP_MS = 2000;
    /** A write that took longer counts as slow (Serial's own measure of a busy USB port). */
    private static final long SLOW_WRITE_MS = 15;

    private static final Object LOCK = new Object();

    /** One connection: the reader and the writes of a generation of Serial hold on to theirs. */
    private static final class Link {
        final Serial serial;
        final int id, port;
        final Socket socket;
        final OutputStream out;
        final long openedAt = SystemClock.elapsedRealtime();
        volatile boolean closedOnPurpose, dead;
        volatile long bytesIn, bytesOut, lastInAt;
        volatile String writeError;

        Link(Serial serial, int id, int port, Socket socket, OutputStream out) {
            this.serial = serial;
            this.id = id;
            this.port = port;
            this.socket = socket;
            this.out = out;
        }
    }

    // ---- what adb chose
    private static volatile int port; // 0: not chosen - USB, as the released app
    private static volatile long readAt; // 0: never read
    private static volatile String readBy = "nothing yet";
    private static String chosen; // the property's value at the last read (under LOCK)
    private static boolean noReflection, saidUnreadable;

    // ---- the connection
    private static volatile Link current;
    private static volatile long notBefore, fastUntil;
    private static boolean saidNoConnect; // Serial opens under its own lock, one at a time

    // ---- for the statistics (BenchControlReceiver)
    private static final AtomicLong connects = new AtomicLong(), connectsFailed = new AtomicLong(), heldBack = new AtomicLong(),
            linksLost = new AtomicLong(), bytesIn = new AtomicLong(), bytesOut = new AtomicLong(), writes = new AtomicLong(),
            writeErrors = new AtomicLong(), slowWrites = new AtomicLong(), maxWriteMs = new AtomicLong();
    private static volatile String lastError = "";

    private BenchFc() {
    }

    /** Did adb choose TCP? Asked where Serial looks for a flight controller; the property is read here. */
    static boolean wanted() {
        long now = SystemClock.elapsedRealtime();
        long at = readAt;
        if (at == 0 || now - at >= (noReflection ? REREAD_BY_GETPROP_MS : REREAD_MS)) reread();
        return port != 0;
    }

    private static void reread() {
        synchronized (LOCK) {
            String value = property();
            readAt = Math.max(1, SystemClock.elapsedRealtime());
            if (value.equals(chosen)) return;
            chosen = value;
            port = portOf(value);
            if (port != 0) {
                log(MARKER + ": the flight controller over TCP 127.0.0.1:" + port + " (" + PROPERTY + " = " + value + ", read by " + readBy + ")");
            } else if (value.isEmpty() || value.equalsIgnoreCase("usb")) {
                log(MARKER + ": " + PROPERTY + (value.isEmpty() ? " is not set" : " = " + value)
                        + ": the flight controller over USB, as the released app (read by " + readBy + ")");
            } else {
                log(MARKER + ": " + PROPERTY + " = " + value + " is not tcp:<port>: the flight controller over USB, as the released app");
            }
        }
    }

    /** tcp:5761 gives 5761; anything else 0. */
    static int portOf(String value) {
        if (value == null) return 0;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!v.startsWith("tcp:")) return 0;
        try {
            int p = Integer.parseInt(v.substring(4).trim());
            return p >= 1 && p <= 65535 ? p : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The property's value, "" when not set. Android's own reader first (android.os.SystemProperties.get: not in
     * the public SDK, but on Android's list of interfaces an app may call, and every app may read debug.*); should
     * a phone refuse that, the getprop program instead.
     */
    private static String property() {
        if (!noReflection) {
            try {
                Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, PROPERTY);
                readBy = "SystemProperties";
                return v == null ? "" : v.toString().trim();
            } catch (Throwable e) {
                noReflection = true;
                log(MARKER + ": SystemProperties is not to be had here (" + e + "): getprop instead");
            }
        }
        Process getprop = null;
        try {
            getprop = new ProcessBuilder("/system/bin/getprop", PROPERTY).redirectErrorStream(true).start();
            getprop.getOutputStream().close();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getprop.getInputStream()))) {
                line = r.readLine();
            }
            getprop.waitFor(2, TimeUnit.SECONDS);
            readBy = "getprop";
            return line == null ? "" : line.trim();
        } catch (Throwable e) {
            readBy = "nothing";
            if (!saidUnreadable) {
                saidUnreadable = true;
                log(MARKER + ": " + PROPERTY + " cannot be read on this phone (" + e + "): the flight controller over USB");
            }
            return "";
        } finally {
            if (getprop != null) getprop.destroy();
        }
    }

    /** Is the rig's connection the port Serial has open (alive or just lost)? Writes then go to it. */
    static boolean active() {
        return current != null;
    }

    /** Is it still there: connected, no end of stream, no error. */
    static boolean attached() {
        Link link = current;
        return link != null && !link.dead;
    }

    /**
     * Connects and starts the reader; false when nothing is there (Serial then comes again, as for a USB device
     * that is not plugged in). Called under Serial's port lock, as the USB port's open.
     */
    static boolean open(Serial serial, int id) {
        final int p = port;
        if (p == 0) return false;
        close(); // an earlier connection, should one be left
        if (SystemClock.elapsedRealtime() < notBefore) {
            heldBack.incrementAndGet();
            return false;
        }
        Socket socket = new Socket();
        OutputStream out;
        boolean noDelay;
        try {
            socket.setTcpNoDelay(true); // each stick packet goes out as it is written, as on USB
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), p), CONNECT_TIMEOUT_MS);
            noDelay = socket.getTcpNoDelay();
            out = socket.getOutputStream();
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            connectsFailed.incrementAndGet();
            lastError = "connect: " + e;
            if (!saidNoConnect) {
                saidNoConnect = true;
                log(MARKER + ": nothing at 127.0.0.1:" + p + " (" + e + ") - is `adb reverse tcp:" + p + " tcp:" + p
                        + "` set, and the PC's bridge running? Tried again as a USB port is, without more lines here");
            }
            return false;
        }
        saidNoConnect = false;
        final Link link = new Link(serial, id, p, socket, out);
        current = link;
        connects.incrementAndGet();
        log(MARKER + ": connected to 127.0.0.1:" + p + " (TCP_NODELAY " + (noDelay ? "on" : "OFF") + ")");
        Thread reader = new Thread(() -> readLoop(link));
        reader.setName("benchFcReader");
        reader.setPriority(Thread.MAX_PRIORITY); // as the USB reader
        reader.start();
        return true;
    }

    /** The reader's thread: until the stream ends, fails or is closed. */
    private static void readLoop(Link link) {
        byte[] buf = new byte[READ_BUFFER];
        String why;
        try {
            InputStream in = link.socket.getInputStream();
            for (;;) {
                int n = in.read(buf);
                if (n < 0) {
                    why = "end of stream";
                    break;
                }
                if (n == 0) continue;
                link.bytesIn += n; // this thread alone writes it
                link.lastInAt = SystemClock.elapsedRealtime();
                bytesIn.addAndGet(n);
                try {
                    link.serial.benchData(link.id, buf, n);
                } catch (RuntimeException e) {
                    log(MARKER + ": reader: " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            why = e.toString();
        }
        link.dead = true;
        closeQuietly(link.socket);
        if (link.closedOnPurpose) return;
        if (link.writeError != null) why = "write failed: " + link.writeError;
        long now = SystemClock.elapsedRealtime();
        if (link.bytesIn > 0 && now - link.openedAt >= HEALTHY_MS) {
            // a flight controller that was there and went: the next try at once, as after a USB glitch, and fast
            // tries for a while
            fastUntil = now + FAST_FOR_MS;
            notBefore = now;
        } else {
            // never a byte, or gone within a second - nobody behind the port: the next try when a USB probe would
            // come again
            notBefore = now + (now < fastUntil ? FAST_GAP_MS : SLOW_GAP_MS);
        }
        linksLost.incrementAndGet();
        lastError = why;
        link.serial.benchLost(link.id, "bench TCP 127.0.0.1:" + link.port + ": " + why
                + (link.bytesIn == 0 ? ", and not one byte had come" : ""));
    }

    /**
     * One MSP or MAVLink packet out. False: not written (no connection, or it is lost and Serial has not opened the
     * next yet). An error closes the connection, which ends the reader - and the reader says the link is lost.
     */
    static boolean write(byte[] data) throws IOException {
        Link link = current;
        if (link == null || link.dead || data == null) return false;
        long start = System.nanoTime();
        try {
            synchronized (link) { // MAVLink is written from more than one thread
                link.out.write(data);
                link.bytesOut += data.length;
            }
        } catch (IOException e) {
            boolean first = !link.dead;
            if (first) link.writeError = e.toString();
            link.dead = true;
            closeQuietly(link.socket);
            if (first && !link.closedOnPurpose) {
                writeErrors.incrementAndGet();
                lastError = "write: " + e;
                throw e;
            }
            return false;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        bytesOut.addAndGet(data.length);
        writes.incrementAndGet();
        if (ms > SLOW_WRITE_MS) slowWrites.incrementAndGet();
        if (ms > maxWriteMs.get()) maxWriteMs.set(ms);
        return true;
    }

    /** Closed on purpose (Serial closes its port): the reader ends without a word. Under Serial's port lock. */
    static void close() {
        Link link = current;
        current = null;
        if (link == null) return;
        link.closedOnPurpose = true;
        closeQuietly(link.socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** One line of name=value, for the rig to read out of logcat. */
    static String stats() {
        boolean tcp = wanted(); // the property as it is now
        Link link = current;
        long now = SystemClock.elapsedRealtime();
        StringBuilder s = new StringBuilder(MARKER).append(" stats:");
        s.append(" chosen=").append(tcp ? "tcp:" + port : "usb");
        s.append(" readBy=").append(readBy);
        s.append(" link=").append(link == null ? "none" : link.dead ? "lost" : "up");
        if (link != null) {
            s.append(" linkPort=").append(link.port);
            s.append(" linkMs=").append(now - link.openedAt);
            s.append(" linkBytesIn=").append(link.bytesIn);
            s.append(" linkBytesOut=").append(link.bytesOut);
            s.append(" lastInAgoMs=").append(link.lastInAt == 0 ? -1 : now - link.lastInAt);
        }
        s.append(" connects=").append(connects.get());
        s.append(" connectsFailed=").append(connectsFailed.get());
        s.append(" heldBack=").append(heldBack.get());
        s.append(" linksLost=").append(linksLost.get());
        s.append(" bytesIn=").append(bytesIn.get());
        s.append(" bytesOut=").append(bytesOut.get());
        s.append(" writes=").append(writes.get());
        s.append(" writeErrors=").append(writeErrors.get());
        s.append(" slowWrites=").append(slowWrites.get());
        s.append(" maxWriteMs=").append(maxWriteMs.get());
        s.append(" nextTryInMs=").append(Math.max(0, notBefore - now));
        s.append(" lastError=\"").append(lastError).append('"');
        return s.toString();
    }
}
