package de.droiddrone.pc;

import de.droiddrone.pc.setup.ModeSwitch;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Feeds joystick input into a ControlLink with the safety gate shared by the CLI and the GUI:
 * RC output starts only once the throttle has been seen low, and stops when input disappears.
 * A radio that comes back within {@link #RESUME_WINDOW_MS} (a USB blip) resumes at once, whatever the throttle, so a
 * glitch in flight doesn't cut the sticks until the pilot pulls the throttle down. After a longer gap Betaflight has
 * failed safe and disarmed (1.5 s by default), and the throttle-low rule applies again. The same when the link to the
 * phone first comes up: nothing is sent until it is up and the throttle has been seen low then. With Betaflight's arm
 * switch known (Drone setup reads it), it must be off too.
 * <p>
 * A link that goes down in flight no longer stops the sticks (1.0.33, {@link ControlLink#sticksWanted}): they keep
 * going out as they are, and reach the drone the moment its uplink works again - on 4G that is often while the
 * phone's own packets still don't come through. Betaflight's own rules keep that safe: a drone its failsafe landed
 * and disarmed won't arm again until the arm switch has been seen off, and never with the throttle up. The sticks
 * stop only when the pilot asks (STANDBY, DEEP SLEEP), the radio goes, or the link is given up.
 * <p>
 * When they did stop and the flight controller says it is still armed (fresh telemetry), the drone is in the air
 * (the failsafe is GPS rescue and it is flying home, or the loss was shorter than the failsafe's stage 2), and
 * waiting for throttle low and arm off would make the first frame disarm it in flight. The sticks go live at once,
 * as they are.
 */
public class RcSession {
    /** Throttle must be at or below this before RC output starts, so a link-up never spins motors unexpectedly. */
    public static final int THROTTLE_SAFE_MAX = 1050;
    /** A radio back within this long after it vanished resumes without the throttle-low rule. */
    public static final long RESUME_WINDOW_MS = 2000;
    /** Telemetry older than this says nothing about whether the FC is armed now. */
    public static final long FC_ARMED_FRESH_MS = 2000;
    /** What the OSD shows for a moment when the sticks went live on an armed drone (see {@link #lastResumeBanner}). */
    public static final String ARMED_RESUME_BANNER = "LINK BACK - ARMED - STICKS LIVE";

    /** A line for the OSD and when it was set (this session's clock: MonoClock.ms in the app). */
    public record Banner(String text, long at) {}

    private final ControlLink link;
    private final RcMapper mapper;
    private final Consumer<String> events;
    private final LongSupplier clock;
    private final BooleanSupplier linkUp;
    /** The flight controller says it is armed, in telemetry no older than FC_ARMED_FRESH_MS. */
    private final BooleanSupplier fcArmedNow;
    /** Where Betaflight's arm switch is, or null (not read yet): then only the throttle rule. */
    private final ArmSwitch armSwitch;
    private volatile boolean rcEnabled;
    private volatile short[] lastChannels;
    /** A joystick state came with the last tick (its mapping may still be incomplete: lastChannels null then). */
    private volatile boolean radioPresent;
    /** The radio's raw axes and buttons last tick, and when they last changed: a live radio always jitters a little. */
    private float[] lastAxes;
    private boolean[] lastButtons;
    private volatile long inputChangedAt;
    /** Armed and the radio's input bit for bit the same this long: stop sending (0 = never, the default). */
    private volatile long frozenStopMs;
    /** The sticks are held back because the radio looks frozen (see {@link #setFrozenStopMs}). */
    private volatile boolean frozenStopped;
    /** When the radio vanished while RC was live; 0 when it didn't (or the window has passed). */
    private long lostAt;
    private volatile Banner banner;
    /** The BEEPER button is held: AUX12 up in every frame (see {@link #withBeeper}). */
    private volatile boolean beeperHeld;
    /** When the idle sticks last went to the phone (see {@link #idleSticks}); 0 = not yet this session. */
    private long idleSentAt;
    /** The idle sticks go to the phone this often: a lost one is replaced soon, and they change only with the radio's AUX. */
    static final long IDLE_STICKS_EVERY_MS = 1000;

    public RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, ArmSwitch armSwitch) {
        this(link, mapper, events, MonoClock::ms, link::sticksWanted,
                () -> link.getTelemetry().isArmedNow(MonoClock.ms(), FC_ARMED_FRESH_MS), armSwitch);
        armBlocked = () -> link.getTelemetry().isPhoneInstalling(MonoClock.ms());
    }

    /**
     * 1.0.49 (H9): while this says so - the phone installs an update, and its app restarts any moment - no frame that
     * could arm the drone goes out: on a disarmed drone the sticks stop while the arm switch is on (or unknown), and
     * start again only with the usual throttle-low, arm-off rule. An armed drone keeps its sticks.
     */
    private volatile BooleanSupplier armBlocked = () -> false;

    void setArmBlocked(BooleanSupplier blocked) {
        armBlocked = blocked;
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, LongSupplier clock, BooleanSupplier linkUp) {
        this(link, mapper, events, clock, linkUp, () -> false, null);
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, LongSupplier clock, BooleanSupplier linkUp,
              ArmSwitch armSwitch) {
        this(link, mapper, events, clock, linkUp, () -> false, armSwitch);
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, LongSupplier clock, BooleanSupplier linkUp,
              BooleanSupplier fcArmedNow, ArmSwitch armSwitch) {
        this.link = link;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
        this.linkUp = linkUp;
        this.fcArmedNow = fcArmedNow;
        this.armSwitch = armSwitch;
    }

    /**
     * A radio that hangs while it still reports itself sends the same sticks forever, and Betaflight never sees a
     * loss. With a limit set, an armed drone whose radio input hasn't changed by a single bit for that long gets no
     * more frames: Betaflight's failsafe (GPS rescue, landing) takes over, and the sticks go out again the moment the
     * input moves. Off by default: a pilot holding position with the sticks untouched, on a radio without gimbal
     * jitter, would be cut off.
     */
    public void setFrozenStopMs(long ms) {
        frozenStopMs = Math.max(0, ms);
    }

    /** The sticks are being held back because the radio looks frozen. */
    public boolean isFrozenStopped() {
        return frozenStopped;
    }

    /**
     * STANDBY or DEEP SLEEP: no sticks, as asked. Not a lost radio - a WAKE afterwards starts with the throttle-low
     * (and arm-off) rule like a new link, never with the quick resume a USB blip gets.
     */
    public void onPaused(Joystick.State state) {
        short[] channels = state == null ? null : mapper.map(state.axes(), state.buttons());
        lastChannels = channels;
        radioPresent = state != null;
        noteInput(state, clock.getAsLong());
        if (rcEnabled) events.accept("Sticks paused (the phone rests) - RC output stops until WAKE and the throttle is low.");
        rcEnabled = false;
        frozenStopped = false;
        lostAt = 0;
    }

    private void noteInput(Joystick.State state, long now) {
        if (state == null || lastAxes == null || !java.util.Arrays.equals(state.axes(), lastAxes)
                || !java.util.Arrays.equals(state.buttons(), lastButtons)) inputChangedAt = now;
        lastAxes = state == null ? null : state.axes();
        lastButtons = state == null ? null : state.buttons();
    }

    /**
     * The BEEPER button (1.0.44): while held, AUX12 ({@link ModeSwitch#BEEPER_AUX}) goes up in the frames, Betaflight's
     * BEEPER mode there (Drone setup puts it) beeps the buzzer and - disarmed - the motors. Only while sticks go out.
     */
    public void setBeeper(boolean held) {
        beeperHeld = held;
    }

    public boolean isBeeperHeld() {
        return beeperHeld;
    }

    /**
     * The frame with the BEEPER channel set: padded to it as upstream pads unused channels (roll, pitch, yaw centred,
     * the rest low), AUX12 up while held and low otherwise. The app owns AUX12 - a radio's AUX12 is overridden.
     */
    static short[] withBeeper(short[] channels, boolean held) {
        int at = 3 + ModeSwitch.BEEPER_AUX; // roll, pitch, yaw, throttle, then AUX1 at index 4
        short[] out = channels.length > at ? channels.clone() : java.util.Arrays.copyOf(channels, at + 1);
        for (int i = channels.length; i < out.length; i++) out[i] = (short) (i < 3 ? RcMapper.MID_CHANNEL_LEVEL : RcMapper.MIN_CHANNEL_LEVEL);
        out[at] = (short) (held ? RcMapper.MAX_CHANNEL_LEVEL : RcMapper.MIN_CHANNEL_LEVEL);
        return out;
    }

    /**
     * The sticks the phone feeds Betaflight while the drone is disarmed and no frame from this PC arrives (phone app
     * pc.33 on): Betaflight 2025.12 beeps "receiver lost" whenever no sticks come, disarmed too, and the pilot's rule is
     * a silent drone while the phone is in. The radio's AUX as they are, but nothing that can arm or spin: throttle
     * low, roll / pitch / yaw centred, the ARM switch at a value outside every arm range, BEEPER low. Null - nothing
     * sent - while where ARM sits isn't known, or no value is outside its ranges. The phone takes them only on a fresh
     * DISARMED from Betaflight; they can't arm it, and Betaflight wants the arm switch cycled after a receiver loss.
     */
    static short[] idleSticks(short[] channels, ArmSwitch arm) {
        short[] out = withBeeper(channels, false);
        for (int i = 0; i < 3; i++) out[i] = (short) RcMapper.MID_CHANNEL_LEVEL;
        out[PcConfig.THROTTLE_INDEX] = (short) RcMapper.MIN_CHANNEL_LEVEL;
        if (arm == null) return null; // where ARM is isn't known yet: nothing sent rather than a guess
        if (arm.channel() < out.length) {
            for (int v : new int[]{RcMapper.MIN_CHANNEL_LEVEL, RcMapper.MAX_CHANNEL_LEVEL, RcMapper.MID_CHANNEL_LEVEL}) {
                out[arm.channel()] = (short) v;
                if (!arm.on(out)) break;
            }
        }
        return arm.on(out) ? null : out; // an arm range over the whole stick: no idle that is surely disarmed
    }

    /** Called on every input tick (the RC rate); sends immediately. state is null when the joystick is gone. */
    public void onInput(Joystick.State state) {
        // judged as the flight controller will see it: channels the radio doesn't carry are padded (see ArmSwitch.on)
        short[] channels = state == null ? null : mapper.map(state.axes(), state.buttons());
        if (channels != null) channels = withBeeper(channels, beeperHeld);
        lastChannels = channels;
        radioPresent = state != null;
        long now = clock.getAsLong();
        noteInput(state, now);
        if (!linkUp.getAsBoolean()) {
            if (rcEnabled) events.accept("Link given up - RC output stopped until it is back and the throttle is low.");
            rcEnabled = false;
            lostAt = 0; // a link that was down long enough to notice: Betaflight has failed safe, no quick resume
            return;
        }
        // H9: the phone installs an update - nothing that could arm a disarmed drone goes out
        boolean noArm = channels != null && armBlocked.getAsBoolean() && !fcArmedNow.getAsBoolean()
                && (armSwitch == null || armSwitch.on(channels));
        if (noArm && rcEnabled) {
            events.accept("The phone installs an update - RC output stopped: no arming until it is back (throttle low, arm switch off).");
            rcEnabled = false;
            lostAt = 0;
        }
        if (channels == null) {
            if (rcEnabled) {
                events.accept("Joystick lost or mapping incomplete - RC output stopped.");
                lostAt = now;
            }
            rcEnabled = false;
        } else if (!rcEnabled && noArm) {
            // waits: see waitingFor()
        } else if (!rcEnabled) {
            boolean blip = lostAt != 0 && now - lostAt <= RESUME_WINDOW_MS;
            if (blip) {
                rcEnabled = true;
                events.accept("Joystick back after " + (now - lostAt) + " ms - RC output resumed.");
            } else if (fcArmedNow.getAsBoolean()) {
                // armed and flying without sticks: every frame withheld is a frame of failsafe, and a frame with the
                // arm switch off would disarm it in the air - the pilot's sticks as they are, now
                rcEnabled = true;
                banner = new Banner(ARMED_RESUME_BANNER, now);
                events.accept("Link back - FC armed - sticks live.");
            } else if (channels[PcConfig.THROTTLE_INDEX] <= THROTTLE_SAFE_MAX && (armSwitch == null || !armSwitch.on(channels))) {
                rcEnabled = true;
                events.accept(armSwitch == null ? "Throttle low - RC output enabled." : "Throttle low, arm switch off - RC output enabled.");
            }
            if (rcEnabled || !blip) lostAt = 0;
        }
        if (channels != null && (idleSentAt == 0 || now - idleSentAt >= IDLE_STICKS_EVERY_MS)) {
            short[] idle = idleSticks(channels, armSwitch);
            if (idle != null && link.sendIdleSticks(idle)) idleSentAt = now;
        }
        if (rcEnabled && frozenHolds(now)) return;
        if (rcEnabled) link.sendRc(channels);
    }

    /** Whether the frozen-radio limit holds this frame back; says so once each way. */
    private boolean frozenHolds(long now) {
        long limit = frozenStopMs;
        boolean frozen = limit > 0 && inputChangedAt != 0 && now - inputChangedAt > limit && fcArmedNow.getAsBoolean();
        if (frozen && !frozenStopped) {
            events.accept("RADIO FROZEN for " + limit / 1000 + " s while armed - sticks stopped, Betaflight's failsafe takes over until the radio moves.");
        } else if (!frozen && frozenStopped) {
            events.accept("The radio moves again - sticks sent again.");
        }
        frozenStopped = frozen;
        return frozen;
    }

    /**
     * How long the radio's raw axes have been bit for bit the same while it is there, in ms. A live radio's gimbals
     * always jitter; several seconds of none suggest its USB has hung while still reporting itself (RADIO FROZEN?).
     */
    public long msSinceInputChanged() {
        long at = inputChangedAt;
        return lastAxes == null || at == 0 ? 0 : clock.getAsLong() - at;
    }

    /**
     * What the sticks wait for before they start: "NO RADIO" (no joystick), "MAPPING INCOMPLETE" (the radio lacks an
     * axis or button the mapping needs), "THROTTLE DOWN", "ARM SWITCH OFF", or both; null when live.
     */
    public String waitingFor() {
        if (rcEnabled) return null;
        short[] ch = lastChannels;
        if (ch == null) return radioPresent ? "MAPPING INCOMPLETE" : "NO RADIO";
        if (armBlocked.getAsBoolean()) return "PHONE UPDATING";
        boolean throttle = ch[PcConfig.THROTTLE_INDEX] > THROTTLE_SAFE_MAX;
        boolean arm = armSwitch != null && armSwitch.on(ch);
        return throttle && arm ? "THROTTLE DOWN, ARM OFF" : arm ? "ARM SWITCH OFF" : "THROTTLE DOWN";
    }

    /** No arm switch is known (Drone setup hasn't read Betaflight's modes): the sticks only wait for the throttle. */
    public boolean isArmSwitchUnknown() {
        return armSwitch == null;
    }

    /**
     * The last line meant for the OSD ({@link #ARMED_RESUME_BANNER}) and when it was set; null if none yet. The
     * window decides how long to show it (about 3 s).
     */
    public Banner lastResumeBanner() {
        return banner;
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
