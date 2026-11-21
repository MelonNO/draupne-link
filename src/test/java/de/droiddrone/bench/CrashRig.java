package de.droiddrone.bench;

import de.droiddrone.pc.fleet.VirtualDrone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The app killed in the middle of something - as by Task Manager, a Windows update's restart, a power cut: the first
 * half of such a scenario runs in a JVM of its own (this class's {@code main}), which brings the app to the moment
 * and then stops dead, with no shutdown code run at all. The scenario's second half starts the app again as the same
 * user and looks at what is left.
 */
public final class CrashRig {
    private CrashRig() {
    }

    /**
     * Runs the first half in a JVM of its own and waits for it to die.
     *
     * @param moment how far the app is brought before it is killed: "armed" (flying, armed), "connected" (linked, never armed)
     * @return the user (sandbox) the killed app ran as
     */
    public static Sandbox killAt(String scenario, String moment) throws IOException, InterruptedException {
        Path note = Files.createDirectories(Sandbox.runRoot().resolve("crash")).resolve(scenario + "-" + System.nanoTime() + ".txt");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("bench.") || key.startsWith("javax.sound.") || key.equals("java.security.manager") || key.equals("java.io.tmpdir")
                    || key.equals("org.bytedeco.javacpp.cachedir") || key.equals("sun.java2d.uiScale") || key.equals("user.home")) {
                cmd.add("-D" + key + "=" + System.getProperty(key));
            }
        }
        cmd.add("-Xmx1g");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(CrashRig.class.getName());
        cmd.add(scenario);
        cmd.add(moment);
        cmd.add(note.toString());
        Path out = Sandbox.runRoot().resolve("crash").resolve(note.getFileName().toString().replace(".txt", ".out"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        if (!p.waitFor(4, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new AssertionError("The first half (" + moment + ") did not reach its moment within 4 minutes: see " + out);
        }
        if (!Files.exists(note)) throw new AssertionError("The first half (" + moment + ") ended before its moment (exit " + p.exitValue() + "): see " + out);
        List<String> lines = Files.readAllLines(note, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !lines.get(1).equals("killed")) throw new AssertionError("The first half failed: " + lines + " - see " + out);
        return Sandbox.reopen(Path.of(lines.get(0)), scenario);
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0], moment = args[1];
        Path note = Path.of(args[2]);
        AppRig rig = AppRig.scenario(scenario + "-before-the-kill").drones(2).start();
        String state;
        try {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
            VirtualDrone drone = rig.drone(name);
            pilot.connect();
            pilot.waitForVideo();
            ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
            if (moment.equals("armed")) {
                pilot.arm(drone);
                pilot.throttle(0.15);
                pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 2);
                Ui.pause(6000); // some seconds of flight on disk
            } else {
                Ui.pause(4000);
            }
            ui.shot("the moment of the kill: " + moment);
            rig.film.close(true, List.of("killed here on purpose: " + moment));
            state = "killed";
        } catch (Throwable t) {
            state = "failed: " + t;
            t.printStackTrace();
        }
        Files.writeString(note, rig.sandbox.root() + "\n" + state + "\n", StandardCharsets.UTF_8);
        Fence.down();
        Runtime.getRuntime().halt(137); // no shutdown hook, no closing of files: dead
    }
}
