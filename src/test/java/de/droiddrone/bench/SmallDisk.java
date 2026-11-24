package de.droiddrone.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A disk that really runs full, for the bench (ui/DiskFullScenario): a few MB of WSL's memory (tmpfs) mounted in WSL's
 * Ubuntu, which Windows - and the app under test - sees as {@code \\wsl.localhost\Ubuntu\mnt\...}. A write there that
 * does not fit fails as on a full disk: "There is not enough space on the disk" (an IOException), measured 2026-11-24.
 * No Windows admin rights are needed ({@code wsl -u root} is WSL's own root), and no Windows setting is changed.
 * <p>
 * What it does not do: Windows asks such a disk for its free room and gets WSL's own big disk (about 1 TB on this PC),
 * so the app's LOW DISK warning - which asks the same - never shows on it. The writes failing is the point.
 * <p>
 * WSL stops a distribution that has nothing running a few seconds after its last program ended - and a tmpfs goes with
 * it - so a program of its own (a long sleep) keeps it up while the disk is mounted.
 */
public final class SmallDisk implements AutoCloseable {
    private final String distro;
    private final String mountPoint;
    private final Path windows;
    private final Process keepAlive;
    private final String keepAlivePid;
    private boolean closed;

    private SmallDisk(String distro, String mountPoint, Process keepAlive, String keepAlivePid) {
        this.distro = distro;
        this.mountPoint = mountPoint;
        this.windows = Path.of("\\\\wsl.localhost\\" + distro + mountPoint.replace('/', '\\'));
        this.keepAlive = keepAlive;
        this.keepAlivePid = keepAlivePid;
    }

    /**
     * Mounts a disk of this many MB; null with the reason in why[0] when WSL is not there or will not mount it (the
     * caller then says why it cannot run). Mount before the app's fence is up: the fence lets the bench start wsl.exe,
     * but the mount is the scenario's business, not the app's.
     */
    public static SmallDisk mount(int mb, String[] why) {
        try {
            Result name = wsl(null, "echo $WSL_DISTRO_NAME");
            String distro = name.out.strip();
            if (name.code != 0 || distro.isEmpty() || !distro.matches("[A-Za-z0-9._-]+")) {
                why[0] = "WSL gave no distribution name (exit " + name.code + "): " + name.out.strip();
                return null;
            }
            String point = "/mnt/draupne-bench-disk-" + ProcessHandle.current().pid();
            Result mounted = wsl(distro, "mkdir -p " + point + " && mount -t tmpfs -o size=" + mb + "m,mode=1777 tmpfs " + point
                    + " && df -k " + point + " | tail -1");
            if (mounted.code != 0) {
                why[0] = "WSL would not mount a " + mb + " MB tmpfs at " + point + " (exit " + mounted.code + "): " + mounted.out.strip();
                return null;
            }
            // a program of its own keeps the distribution up (its PID said first, for the end)
            Process keep = new ProcessBuilder("wsl.exe", "-d", distro, "-u", "root", "--", "sh", "-c", "echo $$; exec sleep 10800")
                    .redirectErrorStream(true).start();
            String first = new java.io.BufferedReader(new java.io.InputStreamReader(keep.getInputStream(), StandardCharsets.UTF_8)).readLine();
            String pid = first == null ? "" : first.strip();
            SmallDisk disk = new SmallDisk(distro, point, keep, pid.matches("\\d+") ? pid : null);
            if (!Files.isDirectory(disk.windows)) {
                disk.close();
                why[0] = "Windows does not see the WSL disk at " + disk.windows;
                return null;
            }
            return disk;
        } catch (IOException | RuntimeException e) {
            why[0] = "WSL is not usable here: " + e;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            why[0] = "interrupted";
            return null;
        }
    }

    /** The disk as Windows and the app see it. */
    public Path path() {
        return windows;
    }

    /** WSL's own word on the disk: "size used free" in KB, as df says it. */
    public String df() {
        try {
            Result r = wsl(distro, "df -k " + mountPoint + " | tail -1");
            String[] p = r.out.strip().split("\\s+");
            return p.length >= 4 ? p[1] + " KB, " + p[2] + " KB used, " + p[3] + " KB free" : r.out.strip();
        } catch (IOException | InterruptedException e) {
            return "(df: " + e + ")";
        }
    }

    /** WSL's free KB on the disk; -1 when it does not say. */
    public long freeKb() {
        try {
            Result r = wsl(distro, "df -k " + mountPoint + " | tail -1");
            String[] p = r.out.strip().split("\\s+");
            return p.length >= 4 ? Long.parseLong(p[3]) : -1;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Writes into this file until the disk refuses, then gives back {@code leave} bytes of it (0: the disk stays
     * full). The file must be in a folder the fence lets the bench write in (the user's own folder on this disk).
     * @return the words of the write that was refused (the disk's own)
     */
    public static String fill(Path filler, long leave) throws IOException {
        byte[] block = new byte[256 * 1024];
        String refused = null;
        try (OutputStream o = Files.newOutputStream(filler, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            for (int i = 0; i < 100_000; i++) o.write(block);
        } catch (IOException e) {
            refused = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // what a refused block left behind, a smaller block at a time: the last bytes too
        byte[] small = new byte[4096];
        for (int tries = 0; tries < 2000; tries++) {
            try (OutputStream o = Files.newOutputStream(filler, java.nio.file.StandardOpenOption.APPEND)) {
                o.write(small);
            } catch (IOException e) {
                break;
            }
        }
        if (leave > 0) {
            try (RandomAccessFile f = new RandomAccessFile(filler.toFile(), "rw")) {
                f.setLength(Math.max(0, f.length() - leave));
            }
        }
        return refused == null ? "(the disk took 25 GB?)" : refused;
    }

    /**
     * Copies what the app left on this disk (all but the filler) into a folder of the run's: the disk's memory is
     * gone when it is unmounted, and the run's report keeps what was there.
     */
    public static List<String> keep(Path from, Path to, String filler) {
        List<String> said = new ArrayList<>();
        if (!Files.isDirectory(from)) return said;
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : all.toList()) {
                if (p.getFileName().toString().equals(filler)) continue;
                Path target = to.resolve(from.relativize(p).toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        said.add(from.relativize(p) + " (" + Files.size(p) + " bytes)");
                    }
                } catch (IOException e) {
                    said.add(from.relativize(p) + ": NOT COPIED (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            said.add("the disk could not be listed: " + e.getMessage());
        }
        return said;
    }

    /** Unmounts the disk (everything on it goes) and lets WSL's distribution stop when it likes. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            // a disk still in use (an app that did not close) is let go of lazily: gone from sight at once, freed when
            // its last file is closed; its folder then goes a moment later
            wsl(distro, "umount " + mountPoint + " || umount -l " + mountPoint + "; rmdir " + mountPoint + " 2>/dev/null || (sleep 2; rmdir " + mountPoint + ")"
                    + (keepAlivePid == null ? "" : "; kill " + keepAlivePid + " 2>/dev/null") + "; true");
        } catch (IOException | InterruptedException ignored) {
        }
        keepAlive.destroy();
    }

    @Override
    public String toString() {
        return windows + " (WSL tmpfs " + mountPoint + ")";
    }

    private record Result(int code, String out) {}

    private static Result wsl(String distro, String command) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("wsl.exe"));
        if (distro != null) cmd.addAll(List.of("-d", distro));
        cmd.addAll(List.of("-u", "root", "--", "sh", "-c", command));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(-1, "timed out");
        }
        return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }
}
