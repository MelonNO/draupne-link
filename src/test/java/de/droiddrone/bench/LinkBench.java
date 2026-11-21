package de.droiddrone.bench;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.testing.Wire;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The PC's real link flying a made-up drone through a bad network, without a window: the link ({@code ControlLink},
 * the same object the app's window uses), a {@link Wire} with a {@link BadLink} on it, the drone, and the sticks sent
 * 50 times a second as the radio thread does. Beside whatever a scenario checks, the safety rules are watched all the
 * time, 100 times a second, from the drone's own flight controller:
 * <ul>
 * <li>it never arms while the ARM switch is off;</li>
 * <li>after a failsafe took the motors, it does not arm again until the switch has been off.</li>
 * </ul>
 */
public final class LinkBench implements AutoCloseable {
    public final List<String> log = new CopyOnWriteArrayList<>();
    public final List<String> violations = new CopyOnWriteArrayList<>();
    public final BadLink net;
    public final PcConfig config;
    public VirtualDrone drone;
    public Wire wire;
    public ControlLink link;

    private volatile boolean running = true;
    private volatile boolean switchOn;
    private volatile long switchOffSince = System.currentTimeMillis();
    private volatile boolean needsSwitchOff;
    private volatile int throttle = 1000;
    private Thread sticks, monitor;
    public final AtomicLong frames = new AtomicLong(), keyFrames = new AtomicLong(), fragments = new AtomicLong();
    public final AtomicLong arms = new AtomicLong(), failsafeDisarms = new AtomicLong(), otherDisarms = new AtomicLong(), linkLosses = new AtomicLong();
    public volatile long longestNotReadyMs;
    private final long startedMs = System.currentTimeMillis();

    public LinkBench(BadLink net) throws Exception {
        this.net = net;
        config = PcConfig.defaults();
        config.set("cameraWidth", "640"); // a small picture: the table is about the link, and many cells run
        config.set("cameraHeight", "360");
    }

    public LinkBench start(int slot) throws Exception {
        drone = BenchFleetAccess.start(slot, "MobileBench", config.getKey(), line -> log.add(stamp() + " drone: " + line));
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        wire.rule(net);
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.setEvents(line -> log.add(stamp() + " pc: " + line));
        link.setVideoListener(new ControlLink.VideoListener() {
            @Override
            public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
            }

            @Override
            public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                fragments.incrementAndGet();
                if (offset == 0) {
                    frames.incrementAndGet();
                    if (keyFrame) keyFrames.incrementAndGet();
                }
            }
        }, false);
        link.start();
        sticks = new Thread(this::sendSticks, "benchSticks");
        sticks.setDaemon(true);
        sticks.start();
        monitor = new Thread(this::watch, "benchSafety");
        monitor.setDaemon(true);
        monitor.start();
        return this;
    }

    private String stamp() {
        return String.format("%7.2f", (System.currentTimeMillis() - startedMs) / 1000.0);
    }

    public void note(String line) {
        log.add(stamp() + " bench: " + line);
    }

    /**
     * The sticks at the app's own rate (JoystickService: 250 a second, paced as it paces them). Until 2026-11-20 this
     * slept 20 ms a frame - on Windows some 30 to 50 frames a second - and the table's rows that lose or overtake
     * frames measured the bench's thin stream of sticks, not the app's: on "jitter 300 ms" two frames in three were
     * overtaken and dropped, the rest left gaps over 100 ms several times a second, and the flight controller's
     * half second of clean signal before arming hardly ever came (F-002).
     */
    private void sendSticks() {
        de.droiddrone.pc.Pacer pacer = new de.droiddrone.pc.Pacer(de.droiddrone.pc.ControlLink.DEFAULT_RC_RATE);
        while (running) {
            link.sendRc(new short[]{1500, 1500, 1500, (short) throttle, (short) (switchOn ? 1900 : 1000), 1500, 1000, 1000});
            try {
                pacer.await();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void watch() {
        boolean wasArmed = false, wasReady = false;
        long notReadySince = 0;
        while (running) {
            long now = System.currentTimeMillis();
            boolean armed = BenchFleetAccess.armed(drone);
            if (armed && !wasArmed) {
                arms.incrementAndGet();
                // the slowest network of the table takes well under 1.5 s to carry a switch that was on
                if (!switchOn && now - switchOffSince > 1500) violate("ARMED with the ARM switch off for " + (now - switchOffSince) + " ms");
                if (needsSwitchOff) violate("ARMED again after a failsafe without the ARM switch having been off");
            }
            if (!armed && wasArmed) {
                String why = BenchFleetAccess.lastDisarm(drone);
                if (why != null && why.contains("failsafe")) {
                    failsafeDisarms.incrementAndGet();
                    if (switchOn) needsSwitchOff = true;
                } else {
                    otherDisarms.incrementAndGet();
                }
                note("disarmed: " + why);
            }
            wasArmed = armed;
            boolean ready = link.getState() == ControlLink.State.READY;
            if (wasReady && !ready) {
                linkLosses.incrementAndGet();
                notReadySince = now;
                note("the PC's link is no longer READY (" + link.getState() + ")");
            }
            if (!wasReady && ready && notReadySince > 0) {
                longestNotReadyMs = Math.max(longestNotReadyMs, now - notReadySince);
                note("the PC's link is READY again after " + (now - notReadySince) + " ms");
            }
            wasReady = ready;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void violate(String what) {
        violations.add(stamp() + " " + what);
        note("SAFETY RULE BROKEN: " + what);
    }

    /** The ARM switch (AUX1). Off also clears "must go off first" once the drone has had time to see it. */
    public void armSwitch(boolean on) {
        if (!on && switchOn) switchOffSince = System.currentTimeMillis();
        switchOn = on;
        note("ARM switch " + (on ? "ON" : "off"));
    }

    /**
     * The switch off long enough for the drone to have seen it (a second over the worst network of the table), so
     * the next ON is a pilot's own decision to arm again.
     */
    public void cycleSwitchOff() throws InterruptedException {
        armSwitch(false);
        Thread.sleep(1500);
        needsSwitchOff = false;
    }

    public void throttle(int value) {
        throttle = value;
    }

    public boolean armed() {
        return BenchFleetAccess.armed(drone);
    }

    public boolean ready() {
        return link.getState() == ControlLink.State.READY;
    }

    /** True when it became so within ms. */
    public static boolean within(long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(20);
        }
        return true;
    }

    /** How long it took, ms; -1 when it did not happen within max. */
    public static long timeTo(long maxMs, BooleanSupplier ok) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        return within(maxMs, ok) ? System.currentTimeMillis() - t0 : -1;
    }

    /** New sessions the phone started (its own log's words). */
    public long sessions() {
        return log.stream().filter(l -> l.contains(" drone: ") && l.contains("new session")).count();
    }

    /** Lines of either side's log that are errors. */
    public List<String> errors() {
        List<String> out = new ArrayList<>();
        for (String l : log) {
            String lower = l.toLowerCase();
            if (lower.contains(" error") || lower.contains("exception")) out.add(l);
        }
        return out;
    }

    @Override
    public void close() {
        running = false;
        if (sticks != null) sticks.interrupt();
        if (monitor != null) monitor.interrupt();
        if (link != null) link.close();
        if (wire != null) wire.close();
        if (drone != null) BenchFleetAccess.stop(drone);
    }
}
