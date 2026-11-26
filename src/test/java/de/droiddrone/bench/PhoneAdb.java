package de.droiddrone.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * adb as the phone rig calls it - the one door through which anything reaches a phone, real or emulated. It is an
 * interface so that the rig's rules for a real phone (what is refused, what is always put back) are tested with a
 * recorder standing in the door: those tests need no phone, no emulator and no adb.
 */
public interface PhoneAdb {
    /** {@code adb -s <serial> args...} run to its end; what it printed, both streams. */
    byte[] run(long timeoutS, List<String> args);

    /** A command that goes on until it is closed (the log tap): what it prints is added to the end of that file. */
    Running follow(List<String> args, Path into) throws IOException;

    /**
     * The emulator started with these arguments, its own output added to the end of that file (the SDK's other program;
     * a real phone has none). Closing what comes back ends that emulator and its own program under it.
     */
    Running startEmulator(List<String> args, Path log) throws IOException;

    /** A command that is still going. */
    interface Running extends AutoCloseable {
        boolean alive();

        @Override
        void close();
    }

    /** The real one: adb.exe and emulator.exe of an Android SDK on this PC, one phone by its serial. */
    final class Sdk implements PhoneAdb {
        private final Path sdk, avdHome;
        private final String serial;

        /** @param avdHome where the emulator's devices are kept; null for a real phone */
        public Sdk(Path sdk, Path avdHome, String serial) {
            this.sdk = sdk;
            this.avdHome = avdHome;
            this.serial = serial;
        }

        private List<String> adbCommand(List<String> args) {
            List<String> cmd = new ArrayList<>(List.of(sdk.resolve("platform-tools").resolve("adb.exe").toString(), "-s", serial));
            cmd.addAll(args);
            return cmd;
        }

        @Override
        public byte[] run(long timeoutS, List<String> args) {
            try {
                ProcessBuilder b = new ProcessBuilder(adbCommand(args)).redirectErrorStream(true);
                realEnvironment(b);
                Process p = b.start();
                p.getOutputStream().close();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Thread reader = new Thread(() -> {
                    try (InputStream in = p.getInputStream()) {
                        in.transferTo(out);
                    } catch (IOException ignored) {
                    }
                }, "benchAdb");
                reader.setDaemon(true);
                reader.start();
                if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("adb " + String.join(" ", args) + " did not end within " + timeoutS + " s");
                }
                reader.join(5000);
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException("adb could not be started: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Running follow(List<String> args, Path into) throws IOException {
            ProcessBuilder b = new ProcessBuilder(adbCommand(args)).redirectErrorStream(true);
            realEnvironment(b);
            Process p = b.start();
            p.getOutputStream().close();
            // Through this program and not straight into the file: when the bench is killed its end of the pipe goes,
            // and adb ends at its next line. Written straight into the file it would go on for good - Windows ends no
            // child with its parent - with nobody left to end it.
            Thread pump = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = p.getInputStream();
                     OutputStream out = Files.newOutputStream(into, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (int n; (n = in.read(buf)) >= 0; ) {
                        out.write(buf, 0, n);
                        out.flush(); // on the disk at once: a run that is killed keeps what was said until then
                    }
                } catch (IOException ignored) {
                    // the phone gone, or the file: the rig's tap sees that this one ended and starts another
                } finally {
                    p.destroy();
                }
            }, "benchLogTapPump");
            pump.setDaemon(true);
            pump.start();
            return new Running() {
                @Override
                public boolean alive() {
                    return p.isAlive();
                }

                @Override
                public void close() {
                    p.destroy();
                    try {
                        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        p.destroyForcibly();
                    }
                }
            };
        }

        @Override
        public Running startEmulator(List<String> args, Path log) throws IOException {
            List<String> cmd = new ArrayList<>(List.of(sdk.resolve("emulator").resolve("emulator.exe").toString()));
            cmd.addAll(args);
            ProcessBuilder b = new ProcessBuilder(cmd);
            realEnvironment(b);
            // added to, not written over: an emulator bench.ps1 started (-Avd) writes there too (2026-11-26)
            Files.writeString(log, "---- " + java.time.LocalDateTime.now().withNano(0) + "  emulator " + String.join(" ", args) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            b.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process p = b.start();
            return new Running() {
                @Override
                public boolean alive() {
                    return p.isAlive();
                }

                @Override
                public void close() {
                    // its own program (qemu) runs under it: ended with it - Windows ends no child with its parent
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            };
        }

        /** The programs the bench starts live outside the test JVM's sealed folders: they get this user's real ones. */
        private void realEnvironment(ProcessBuilder b) {
            String local = System.getProperty("bench.realLocalAppData");
            String roaming = System.getProperty("bench.realAppData");
            if (local != null && !local.isBlank()) {
                b.environment().put("LOCALAPPDATA", local);
                b.environment().put("TEMP", local + "\\Temp");
                b.environment().put("TMP", local + "\\Temp");
            }
            if (roaming != null && !roaming.isBlank()) b.environment().put("APPDATA", roaming);
            b.environment().put("ADB_MDNS", "0"); // no network discovery: nothing for Windows' firewall to ask about
            b.environment().put("ADB_MDNS_OPENSCREEN", "0");
            b.environment().put("ANDROID_SDK_ROOT", sdk.toString());
            b.environment().put("ANDROID_HOME", sdk.toString());
            if (avdHome != null) b.environment().put("ANDROID_AVD_HOME", avdHome.toString());
        }
    }
}
