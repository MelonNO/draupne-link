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

    private Joystick.Info resolve(List<Joystick.Info> joysticks) {
        int id = requestedId;
        for (Joystick.Info info : joysticks) {
            if (id >= 0 ? info.id() == id : info.name() != null && info.name().toLowerCase().contains(initialFilter)) {
                return info;
            }
        }
        return id >= 0 || joysticks.isEmpty() ? null : joysticks.get(0);
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public void select(int joystickId) {
        requestedId = joystickId;
    }

    public void setInputListener(Consumer<Joystick.State> listener) {
        inputListener = listener;
    }

    public void stop() {
        running = false;
    }
}
