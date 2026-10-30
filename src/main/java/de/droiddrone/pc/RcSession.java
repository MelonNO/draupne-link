package de.droiddrone.pc;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Feeds joystick input into a ControlLink with the safety gate shared by the CLI and the GUI:
 * RC output starts only once the throttle has been seen low, and stops when input disappears.
 * A radio that comes back within {@link #RESUME_WINDOW_MS} (a USB blip) resumes at once, whatever the throttle, so a
 * glitch in flight doesn't cut the sticks until the pilot pulls the throttle down. After a longer gap Betaflight has
 * failed safe and disarmed (1.5 s by default), and the throttle-low rule applies again. The same when the link to the
 * phone comes up: nothing is sent until it is up and the throttle has been seen low then, and a link that goes down
 * stops the sticks until both again. With Betaflight's arm switch known (Drone setup reads it), it must be off too.
 */
public class RcSession {
    /** Throttle must be at or below this before RC output starts, so a link-up never spins motors unexpectedly. */
    public static final int THROTTLE_SAFE_MAX = 1050;
    /** A radio back within this long after it vanished resumes without the throttle-low rule. */
    public static final long RESUME_WINDOW_MS = 2000;

    private final ControlLink link;
    private final RcMapper mapper;
    private final Consumer<String> events;
    private final LongSupplier clock;
    private final java.util.function.BooleanSupplier linkUp;
    /** Where Betaflight's arm switch is, or null (not read yet): then only the throttle rule. */
    private final ArmSwitch armSwitch;
    private volatile boolean rcEnabled;
    private volatile short[] lastChannels;
    /** The radio's raw axes last tick, and when they last changed: a live radio always jitters a little. */
    private float[] lastAxes;
    private volatile long inputChangedAt;
    /** When the radio vanished while RC was live; 0 when it didn't (or the window has passed). */
    private long lostAt;

    public RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, ArmSwitch armSwitch) {
        this(link, mapper, events, System::currentTimeMillis, () -> link.getState() == ControlLink.State.READY, armSwitch);
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, LongSupplier clock, java.util.function.BooleanSupplier linkUp) {
        this(link, mapper, events, clock, linkUp, null);
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, LongSupplier clock, java.util.function.BooleanSupplier linkUp,
              ArmSwitch armSwitch) {
        this.link = link;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
        this.linkUp = linkUp;
        this.armSwitch = armSwitch;
    }

    /** Called on every input tick (the RC rate); sends immediately. state is null when the joystick is gone. */
    public void onInput(Joystick.State state) {
        short[] channels = state == null ? null : mapper.map(state.axes(), state.buttons());
        lastChannels = channels;
        long now = clock.getAsLong();
        if (state == null || lastAxes == null || !java.util.Arrays.equals(state.axes(), lastAxes)) inputChangedAt = now;
        lastAxes = state == null ? null : state.axes();
        if (!linkUp.getAsBoolean()) {
            if (rcEnabled) events.accept("Link down - RC output stopped until it is back and the throttle is low.");
            rcEnabled = false;
            lostAt = 0; // a link that was down long enough to notice: Betaflight has failed safe, no quick resume
            return;
        }
        if (channels == null) {
            if (rcEnabled) {
                events.accept("Joystick lost or mapping incomplete - RC output stopped.");
                lostAt = now;
            }
            rcEnabled = false;
        } else if (!rcEnabled) {
            boolean blip = lostAt != 0 && now - lostAt <= RESUME_WINDOW_MS;
            if (blip) {
                rcEnabled = true;
                events.accept("Joystick back after " + (now - lostAt) + " ms - RC output resumed.");
            } else if (channels[PcConfig.THROTTLE_INDEX] <= THROTTLE_SAFE_MAX && (armSwitch == null || !armSwitch.on(channels))) {
                rcEnabled = true;
                events.accept(armSwitch == null ? "Throttle low - RC output enabled." : "Throttle low, arm switch off - RC output enabled.");
            }
            if (rcEnabled || !blip) lostAt = 0;
        }
        if (rcEnabled) link.sendRc(channels);
    }

    /**
     * How long the radio's raw axes have been bit for bit the same while it is there, in ms. A live radio's gimbals
     * always jitter; several seconds of none suggest its USB has hung while still reporting itself (RADIO FROZEN?).
     */
    public long msSinceInputChanged() {
        long at = inputChangedAt;
        return lastAxes == null || at == 0 ? 0 : clock.getAsLong() - at;
    }

    /** What the sticks wait for before they start ("THROTTLE DOWN", "ARM SWITCH OFF", both), or null when live. */
    public String waitingFor() {
        if (rcEnabled) return null;
        short[] ch = lastChannels;
        if (ch == null) return "THROTTLE DOWN";
        boolean throttle = ch[PcConfig.THROTTLE_INDEX] > THROTTLE_SAFE_MAX;
        boolean arm = armSwitch != null && armSwitch.on(ch);
        return throttle && arm ? "THROTTLE DOWN, ARM OFF" : arm ? "ARM SWITCH OFF" : "THROTTLE DOWN";
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
