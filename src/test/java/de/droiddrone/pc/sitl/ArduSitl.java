package de.droiddrone.pc.sitl;

import de.droiddrone.pc.setup.ardu.MavSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fleet PRD AP-8: one ArduPilot Copter as a program on this PC (SITL) - ArduPilot's own Windows build (the one Mission
 * Planner's simulation downloads: ArduCopter.elf with its Cygwin libraries) in %LOCALAPPDATA%\DroidDroneBuild\sitl\copter,
 * or where DRAUPNE_ARDU_SITL points. Each instance runs in a folder of its own (its parameters, its logs), takes a
 * quad frame from a small defaults file, and is reached over TCP on 5760 + 10 x instance - the same MAVLink the phone
 * speaks over USB. Only the fleet's SITL tests use it (tag ardusitl, gradlew fleetSitl).
 */
public final class ArduSitl implements AutoCloseable {
    public static final String ENV = "DRAUPNE_ARDU_SITL";

    private final Process process;
    private final Socket socket;
    public final MavSession fc;
    public final int instance;
    public final double homeLat, homeLon, homeAlt;

    /** The SITL build's folder, or null when it isn't installed. */
    public static Path home() {
        String env = System.getenv(ENV);
        Path p = env != null ? Path.of(env) : Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "DroidDroneBuild", "sitl", "copter");
        return Files.isRegularFile(p.resolve("ArduCopter.elf")) ? p : null;
    }

    public static boolean installed() {
        return home() != null;
    }

    /** Starts instance n at home (lat, lon, height above sea level), heading north; waits for its heartbeat. */
    public static ArduSitl start(int n, double lat, double lon, double alt, double speedup) throws IOException, InterruptedException {
        try {
            return startOnce(n, lat, lon, alt, speedup);
        } catch (IOException e) {
            // once (a full fleetSitl run): no heartbeat within 5 s of the start, right after the 10-drone test - again, once
            Thread.sleep(2000);
            return startOnce(n, lat, lon, alt, speedup);
        }
    }

    private static ArduSitl startOnce(int n, double lat, double lon, double alt, double speedup) throws IOException, InterruptedException {
        Path bin = home();
        if (bin == null) throw new IOException("ArduPilot SITL not installed (" + ENV + ")");
        Path dir = bin.resolve("run" + n);
        Files.createDirectories(dir);
        Path defaults = dir.resolve("draupne.parm");
        Files.writeString(defaults, String.join("\n",
                "FRAME_CLASS 1", "FRAME_TYPE 1", // quad X
                "SYSID_THISMAV " + (n + 1),
                "SIM_SPEEDUP " + speedup, "") + "\n");
        List<String> cmd = new ArrayList<>(List.of(bin.resolve("ArduCopter.elf").toString(), "--model", "quad",
                "--home", lat + "," + lon + "," + alt + ",0", "-I" + n, "--speedup", String.valueOf(speedup),
                "--defaults", defaults.toString()));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("sitl.log").toFile()).start();
        Socket s = null;
        long end = System.currentTimeMillis() + 20_000;
        while (s == null && System.currentTimeMillis() < end) {
            try {
                s = new Socket("127.0.0.1", 5760 + 10 * n);
            } catch (IOException e) {
                if (!p.isAlive()) throw new IOException("SITL stopped at the start: see " + dir.resolve("sitl.log"));
                Thread.sleep(250);
            }
        }
        if (s == null) {
            p.destroyForcibly();
            throw new IOException("SITL's TCP port never opened");
        }
        s.setTcpNoDelay(true);
        MavSession fc = new MavSession(wire(s));
        if (!fc.awaitHeartbeat(20_000)) {
            fc.close();
            p.destroyForcibly();
            throw new IOException("no heartbeat from SITL");
        }
        return new ArduSitl(p, s, fc, n, lat, lon, alt);
    }

    private ArduSitl(Process p, Socket s, MavSession fc, int n, double lat, double lon, double alt) {
        process = p;
        socket = s;
        this.fc = fc;
        instance = n;
        homeLat = lat;
        homeLon = lon;
        homeAlt = alt;
    }

    static MavSession.Wire wire(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        return new MavSession.Wire() {
            @Override
            public void write(byte[] data) throws IOException {
                out.write(data);
                out.flush();
            }

            @Override
            public int read(byte[] buf, int timeoutMs) throws IOException {
                s.setSoTimeout(Math.max(1, timeoutMs));
                try {
                    return in.read(buf);
                } catch (java.net.SocketTimeoutException e) {
                    return 0;
                }
            }

            @Override
            public void close() {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // gone
                }
            }
        };
    }

    /** Waits until ArduPilot would arm: a 3D fix and its position known (the EKF has settled). */
    public void awaitReady(long timeoutMs) throws IOException, InterruptedException {
        fc.stream(24, 5); // GPS_RAW_INT
        fc.stream(33, 10); // GLOBAL_POSITION_INT
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            double[] pos = fc.position();
            if (fc.gpsFix() >= 3 && pos != null && Math.abs(pos[0]) > 1) return;
            Thread.sleep(250);
        }
        throw new IOException("SITL not ready (fix " + fc.gpsFix() + ")");
    }

    /** Waits until ArduPilot's own pre-arm checks pass (it would arm now). */
    public void awaitPreArm(long timeoutMs) throws IOException, InterruptedException {
        fc.stream(1, 2); // SYS_STATUS
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (fc.preArmOk()) return;
            Thread.sleep(250);
        }
        throw new IOException("ArduPilot's pre-arm checks never passed");
    }

    @Override
    public void close() {
        fc.close();
        try {
            socket.close();
        } catch (IOException ignored) {
            // gone
        }
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
    }
}
