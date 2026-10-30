package de.droiddrone.pc;

import java.util.List;
import java.util.function.Consumer;

import static de.droiddrone.common.Log.log;

/**
 * Owns GLFW joystick polling on the main thread (GLFW requires it) and publishes snapshots for the GUI thread.
 * Polls at the RC rate and calls the input listener (the RC session) in the same tick, so a stick movement is sent
 * at most one tick after the radio reports it.
 */
public class JoystickService {
    public record Snapshot(List<Joystick.Info> joysticks, Joystick.Info selected, Joystick.State state) {}

    private volatile Snapshot snapshot = new Snapshot(List.of(), null, null);
    private volatile int requestedId = -1;
    /**
     * The radio in use, once one is: only this device is ever read again. If it disappears there is no input (RC
     * stops) until it comes back - never another joystick or gamepad, whose axes and switches would go to the drone.
     * Another device only by choosing it (select).
     */
    private volatile Joystick.Info locked;
    private final String initialFilter;
    private final int pollHz;
    private volatile Consumer<Joystick.State> inputListener;
    private volatile boolean running = true;

    public JoystickService(String initialFilter, int pollHz) {
        this.initialFilter = initialFilter == null ? "" : initialFilter.toLowerCase();
        this.pollHz = pollHz;
    }

    /** Polls until stop() is called. Call from the main thread after Joystick.init(). */
    public void run() {
        List<Joystick.Info> joysticks = List.of();
        long nextListRefresh = 0;
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY); // this thread sends the RC frames
        Pacer pacer = new Pacer(pollHz);
        while (running) {
            Joystick.poll();
            long now = System.currentTimeMillis();
            if (now >= nextListRefresh) {
                joysticks = Joystick.list();
                nextListRefresh = now + 1000;
            }
            Joystick.Info selected = resolve(joysticks);
            Joystick.State state = selected == null ? null : Joystick.read(selected.id());
            snapshot = new Snapshot(joysticks, selected, state);
            Consumer<Joystick.State> listener = inputListener;
            if (listener != null) {
                try {
                    listener.accept(state);
                } catch (RuntimeException e) {
                    log("Input listener error: " + e);
                }
            }
            try {
                pacer.await();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    Joystick.Info resolve(List<Joystick.Info> joysticks) {
        Joystick.Info lock = locked;
        if (lock != null) {
            for (Joystick.Info info : joysticks) if (info.sameDevice(lock)) return info; // its id may have changed
            return null;
        }
        int id = requestedId;
        Joystick.Info found = null;
        for (Joystick.Info info : joysticks) {
            if (id >= 0 ? info.id() == id : info.name() != null && info.name().toLowerCase().contains(initialFilter)) {
                found = info;
                break;
            }
        }
        if (found == null && id < 0 && !joysticks.isEmpty()) found = joysticks.get(0); // first start: whatever is there
        if (found != null) locked = found;
        return found;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /** The pilot chose this device: it becomes the one in use. */
    public void select(int joystickId) {
        requestedId = joystickId;
        locked = null;
    }

    public void setInputListener(Consumer<Joystick.State> listener) {
        inputListener = listener;
    }

    public void stop() {
        running = false;
    }
}
