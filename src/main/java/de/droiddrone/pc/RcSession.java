package de.droiddrone.pc;

import java.util.function.Consumer;

/**
 * Feeds joystick input into a ControlLink with the safety gate shared by the CLI and the GUI:
 * RC output starts only once the throttle has been seen low, and stops when input disappears.
 */
public class RcSession {
    /** Throttle must be at or below this before RC output starts, so a link-up never spins motors unexpectedly. */
    public static final int THROTTLE_SAFE_MAX = 1050;

    private final ControlLink link;
    private final RcMapper mapper;
    private final Consumer<String> events;
    private volatile boolean rcEnabled;
    private volatile short[] lastChannels;

    public RcSession(ControlLink link, RcMapper mapper, Consumer<String> events) {
        this.link = link;
        this.mapper = mapper;
        this.events = events;
    }

    /** Called on every input tick (the RC rate); sends immediately. state is null when the joystick is gone. */
    public void onInput(Joystick.State state) {
        short[] channels = state == null ? null : mapper.map(state.axes(), state.buttons());
        lastChannels = channels;
        if (channels == null) {
            if (rcEnabled) events.accept("Joystick lost or mapping incomplete - RC output stopped.");
            rcEnabled = false;
        } else if (!rcEnabled && channels[PcConfig.THROTTLE_INDEX] <= THROTTLE_SAFE_MAX) {
            rcEnabled = true;
            events.accept("Throttle low - RC output enabled.");
        }
        if (rcEnabled) link.sendRc(channels);
    }

    public boolean isRcEnabled() {
        return rcEnabled;
    }

    public short[] getLastChannels() {
        return lastChannels;
    }

    public ControlLink getLink() {
        return link;
    }
}
