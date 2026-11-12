package de.droiddrone.pc;

import de.droiddrone.common.FcCommon;
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
 * phone's own packets still don't come through. Betaflight's own rule - a drone its failsafe landed and disarmed
 * won't arm again until the arm switch has been seen off, and never with the throttle up - doesn't keep that safe
 * by itself: the phone feeds the idle sticks (arm switch off) to a DISARMED flight controller that gets no frames,
 * which is the arm switch seen off, and the next live frame, arm switch still on, arms it on the ground. So (1.0.64)
 * once nothing says the phone hears this PC ({@link ControlLink#sticksUnheard}), the flight controller's word is
 * watched: a fresh DISARMED while the frame could arm stops the sticks, and they start again as at connect - arm
 * switch off and throttle low. ARMED, or no word at all, changes nothing: a drone in the air must not be starved.
 * Otherwise the sticks stop only when the pilot asks (STANDBY, DEEP SLEEP), the radio goes, or the link is given up.
 * <p>
 * When they did stop and the flight controller says it is still armed (fresh telemetry), the drone is in the air
 * (the failsafe is GPS rescue and it is flying home, or the loss was shorter than the failsafe's stage 2), and
 * waiting for throttle low and arm off would make the first frame disarm it in flight. The sticks go live at once,
 * as they are. And while the flight controller says nothing yet (1.0.64: this PC's app started with the drone in the
 * air - its state takes seconds to arrive on a weak link) the start isn't taken at all: READING FC, until it is
 * known or the link has been heard for {@link #FC_READ_WAIT_MS} without it.
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
    /**
     * 1.0.64: with the flight controller's armed state unknown, the sticks don't start before the link has been heard
     * this long without a word of it (see {@link #waitingFor}: READING FC). Then as before: a drone that never says.
     */
    public static final long FC_READ_WAIT_MS = 5000;
    /** One input tick counts this much heard time at most (a stalled input loop is not five seconds of link). */
    private static final long MAX_TICK_MS = 250;
    /**
     * 1.0.64: after a loss, ARMED read this long after the link is back closes the watch: by then the status is from
     * after the loss, and a late burst of old ones is through.
     */
    static final long LOSS_SETTLE_MS = FC_ARMED_FRESH_MS + 1000;
    /**
     * 1.0.64: DISARMED stops the sticks after a loss only once it has been read this long without a break - two
     * statuses in a row (they come every 0.25-0.5 s), as ARMED needs two: one stray status must not cut the sticks of a
     * drone in the air.
     */
    static final long DISARMED_CONFIRM_MS = 600;

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
    /** The BEEPER button is held (see {@link #withBeeper}); when it was pressed (this session's clock). */
    private volatile boolean beeperHeld;
    private volatile long beeperHeldAt;
    /** The BEEPER's channel in the frame (3 + BEEPER_AUX), or -1: not set up on the drone, or the channel is taken. */
    private volatile int beeperChannel = -1;
    /** Why the BEEPER does nothing on this drone (null: it works): shown on the button and logged at a press. */
    private volatile String beeperOff = BEEPER_NOT_SET_UP;
    /** The BEEPER sounded in the last frame; and it was let go by its time limit (said once). */
    private volatile boolean beeping;
    private boolean beeperTimedOut;
    /** The flight controller said DISARMED, in telemetry no older than FC_ARMED_FRESH_MS (the BEEPER waits for it). */
    private final BooleanSupplier fcDisarmedNow;
    /** A held BEEPER lets go by itself after this long: a button stuck down (or a dialog over it) can't beep forever. */
    public static final long BEEPER_MAX_MS = 10_000;
    /** What Drone setup found: "true" when Betaflight's BEEPER mode sits on AUX14 alone, with the button's range. */
    public static final String BEEPER_KEY = "drone.beeperSet";
    public static final String BEEPER_NOT_SET_UP = "BEEPER off: not set up (Drone setup puts it on AUX"
            + ModeSwitch.BEEPER_AUX + ")";
    /** When the idle sticks last went to the phone (see {@link #idleSticks}); 0 = not yet this session. */
    private long idleSentAt;
    /** The idle sticks go to the phone this often: a lost one is replaced soon, and they change only with the radio's AUX. */
    static final long IDLE_STICKS_EVERY_MS = 1000;

    /** Nothing says the phone hears this PC now ({@link ControlLink#sticksUnheard}): a loss, or one way only. */
    private final BooleanSupplier sticksUnheard;
    /** See {@link #FC_READ_WAIT_MS}; 0 = the sticks never wait for the flight controller's state. */
    private final long fcReadWaitMs;
    /** The link was up at the last tick; when that tick was (0: none yet). */
    private boolean linkWasUp;
    private long lastTickAt;
    /** How long the link has been heard with the flight controller's armed state unknown, ms (0 while it is known). */
    private long fcUnknownHeardMs;
    /** The sticks wait for the flight controller's armed state (see {@link #waitingFor}). */
    private volatile boolean readingFc;
    /**
     * The link was unheard and what the flight controller says of it isn't settled yet (see {@link #watchLoss}); since
     * when, whether it was unheard at the last tick, and when it was heard again (0: not yet).
     */
    private boolean lossWatch;
    private long lossWatchSince;
    private boolean wasUnheard;
    private long heardAgainAt;
    /** The sticks were stopped in this watch already (the throttle rule, without an arm switch, stops them once). */
    private boolean lossStopped;
    /** Since when the flight controller has read DISARMED without a break (0: it doesn't). */
    private long disarmedSince;

    public RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, ArmSwitch armSwitch) {
        this(link, mapper, events, new Inputs(MonoClock::ms, link::sticksWanted,
                () -> link.getTelemetry().isArmedNow(MonoClock.ms(), FC_ARMED_FRESH_MS),
                () -> link.getTelemetry().isDisarmedNow(MonoClock.ms(), FC_ARMED_FRESH_MS), armSwitch,
                link::sticksUnheard, FC_READ_WAIT_MS));
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

    /**
     * What a session reads besides the link: its clock (ms), whether the link is up for sticks, the flight
     * controller's armed and disarmed state as fresh telemetry says (both false: unknown), the arm switch (null:
     * none set), whether nothing says the phone hears this PC now (1.0.64), and how long the sticks wait for the
     * flight controller's state (FC_READ_WAIT_MS in the app; 0 = they don't).
     */
    record Inputs(LongSupplier clock, BooleanSupplier linkUp, BooleanSupplier fcArmedNow, BooleanSupplier fcDisarmedNow,
                  ArmSwitch armSwitch, BooleanSupplier sticksUnheard, long fcReadWaitMs) {
        /** As before 1.0.64: the phone always heard, no wait for the flight controller's state. */
        Inputs(LongSupplier clock, BooleanSupplier linkUp, BooleanSupplier fcArmedNow, BooleanSupplier fcDisarmedNow,
               ArmSwitch armSwitch) {
            this(clock, linkUp, fcArmedNow, fcDisarmedNow, armSwitch, () -> false, 0);
        }

        /** Tests: a clock and a link, the FC's state unknown, no arm switch. */
        static Inputs of(LongSupplier clock, BooleanSupplier linkUp) {
            return new Inputs(clock, linkUp, () -> false, () -> false, null);
        }
    }

    RcSession(ControlLink link, RcMapper mapper, Consumer<String> events, Inputs in) {
        this.fcDisarmedNow = in.fcDisarmedNow();
        this.link = link;
        this.mapper = mapper;
        this.events = events;
        this.clock = in.clock();
        this.linkUp = in.linkUp();
        this.fcArmedNow = in.fcArmedNow();
        this.armSwitch = in.armSwitch();
        this.sticksUnheard = in.sticksUnheard();
        this.fcReadWaitMs = Math.max(0, in.fcReadWaitMs());
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
        if (rcEnabled) events.accept("Sticks paused (phone resting) - RC output off until it wakes and the throttle is low.");
        rcEnabled = false;
        frozenStopped = false;
        lostAt = 0;
        linkDown(); // a WAKE starts like a new link
    }

    /** No sticks on this link now (given up, or paused): what was watched on it is over, the next start is a new one. */
    private void linkDown() {
        linkWasUp = false;
        readingFc = false;
        lossWatch = false;
        wasUnheard = false;
        disarmedSince = 0;
    }

    private void noteInput(Joystick.State state, long now) {
        if (state == null || lastAxes == null || !java.util.Arrays.equals(state.axes(), lastAxes)
                || !java.util.Arrays.equals(state.buttons(), lastButtons)) inputChangedAt = now;
        lastAxes = state == null ? null : state.axes();
        lastButtons = state == null ? null : state.buttons();
    }

    /**
     * The BEEPER button (1.0.44; AUX14 from 1.0.49): while held, AUX14 ({@link ModeSwitch#BEEPER_AUX}) goes up in the
     * frames, and Betaflight's BEEPER mode there (Drone setup puts it) beeps the buzzer and - disarmed - the motors.
     * Only while sticks go out, only on a fresh DISARMED from the flight controller, and for {@link #BEEPER_MAX_MS} at
     * most: then it lets go by itself until the next press.
     */
    public void setBeeper(boolean held) {
        if (held && !beeperHeld) {
            beeperHeldAt = clock.getAsLong();
            beeperTimedOut = false;
        }
        beeperHeld = held;
    }

    public boolean isBeeperHeld() {
        return beeperHeld;
    }

    /** The drone beeps now: the last frame had BEEPER up. */
    public boolean isBeeping() {
        return beeping;
    }

    /**
     * Whether Drone setup put BEEPER on AUX14 ({@link #BEEPER_KEY}). Only then do the frames carry AUX14, and only while
     * nothing else uses that channel: never ARM's (with ARM there, BEEPER up arms the drone - 1.0.48's C1), never one
     * the radio's mapping drives.
     */
    public void setBeeperSetUp(boolean setUp) {
        int at = 3 + ModeSwitch.BEEPER_AUX; // roll, pitch, yaw, throttle, then AUX1 at index 4
        String aux = "AUX" + ModeSwitch.BEEPER_AUX;
        String off = !setUp ? BEEPER_NOT_SET_UP
                : at >= FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT ? "BEEPER off: " + aux + " is beyond what MSP carries"
                : armSwitch != null && armSwitch.channel() == at ? "BEEPER off: " + aux + " in use by ARM - move ARM in Drone setup"
                : mapper.isMapped(at) ? "BEEPER off: " + aux + " in use by the radio mapping" : null;
        beeperOff = off;
        beeperChannel = off == null ? at : -1;
    }

    /** Why the BEEPER does nothing on this drone (not set up, its channel taken), or null when it works. */
    public String beeperOff() {
        return beeperOff;
    }

    /**
     * From Drone setup's read of the CLI's "aux": true when Betaflight's BEEPER mode sits on AUX14 only, with 2000 inside
     * its range and 1000 outside it - the button's. Anything else (BEEPER on a radio switch too, or not there): false.
     */
    public static boolean beeperSetUp(String auxOutput) {
        boolean high = false, low = false;
        for (String line : auxOutput.split("\\R")) {
            String[] p = line.replaceFirst("^#\\s*", "").trim().split("\\s+");
            if (p.length < 6 || !p[0].equals("aux")) continue;
            try {
                int mode = Integer.parseInt(p[2]), channel = Integer.parseInt(p[3]);
                int start = Integer.parseInt(p[4]), end = Integer.parseInt(p[5]);
                if (mode != ModeSwitch.BEEPER_MODE || start >= end) continue;
                if (channel != ModeSwitch.BEEPER_AUX - 1) return false;
                high |= RcMapper.MAX_CHANNEL_LEVEL >= start && RcMapper.MAX_CHANNEL_LEVEL < end;
                low |= RcMapper.MIN_CHANNEL_LEVEL >= start && RcMapper.MIN_CHANNEL_LEVEL < end;
            } catch (NumberFormatException ignored) {
                // not an aux line after all
            }
        }
        return high && !low;
    }

    /**
     * The frame as it goes out (1.0.49): the radio's channels, padded to 8 as before 1.0.44 ({@link ControlLink#padForRxMap}).
     * With a BEEPER channel (&gt;= 0) the frame reaches it - up while beeping, low otherwise - and the channels in between
     * go out as 0: not sent, as Betaflight's rx_msp.c makes every channel a frame doesn't carry, and holds (rx.c).
     * 1.0.44 to 1.0.48 padded every frame to 16 with 1000, and a mode with a low range on a switch the radio never
     * sent came on.
     */
    static short[] withBeeper(short[] channels, int beeperChannel, boolean beep) {
        short[] out = ControlLink.padForRxMap(channels);
        if (beeperChannel < 0) return out;
        if (out.length <= beeperChannel) out = java.util.Arrays.copyOf(out, beeperChannel + 1); // zeros in between
        else if (out == channels) out = channels.clone();
        out[beeperChannel] = (short) (beep ? RcMapper.MAX_CHANNEL_LEVEL : RcMapper.MIN_CHANNEL_LEVEL);
        return out;
    }

    /**
     * The sticks the phone feeds Betaflight while the drone is disarmed and no frame from this PC arrives (phone app
     * pc.33 on): Betaflight 2025.12 beeps "receiver lost" whenever no sticks come, disarmed too, and the pilot's rule is
     * a silent drone while the phone is in. The frame's AUX as they are (BEEPER low), but nothing that can arm or spin:
     * throttle low, roll / pitch / yaw centred, the ARM switch at a value outside every arm range (the frame reaching
     * it, 0 in between). Null - nothing sent - while where ARM sits isn't known, or no value is outside its ranges.
     * The phone takes them only on a fresh DISARMED, and (pc.38) only when Betaflight's own arm ranges agree they can't
     * arm - ARM may have moved since Drone setup read it.
     */
    static short[] idleSticks(short[] frame, ArmSwitch arm) {
        if (arm == null) return null; // where ARM is isn't known yet: nothing sent rather than a guess
        short[] out = frame.length > arm.channel() ? frame.clone() : java.util.Arrays.copyOf(frame, arm.channel() + 1);
        for (int i = 0; i < 3; i++) out[i] = (short) RcMapper.MID_CHANNEL_LEVEL;
        out[PcConfig.THROTTLE_INDEX] = (short) RcMapper.MIN_CHANNEL_LEVEL;
        for (int v : new int[]{RcMapper.MIN_CHANNEL_LEVEL, RcMapper.MAX_CHANNEL_LEVEL, RcMapper.MID_CHANNEL_LEVEL}) {
            out[arm.channel()] = (short) v;
            if (!arm.on(out)) break;
        }
        return arm.on(out) ? null : out; // an arm range over the whole stick: no idle that is surely disarmed
    }

    /** The radio's frame passes the arm gate: throttle low, the arm switch known and off (idle sticks need it, H1). */
    private boolean passesArmGate(short[] frame) {
        return frame[PcConfig.THROTTLE_INDEX] <= THROTTLE_SAFE_MAX && armSwitch != null && !armSwitch.on(frame);
    }

    /** This frame beeps: BEEPER held (for under BEEPER_MAX_MS), set up and free, and the FC freshly DISARMED. */
    private boolean beepNow(long now) {
        if (!beeperHeld || beeperChannel < 0) return false;
        if (now - beeperHeldAt >= BEEPER_MAX_MS) {
            if (!beeperTimedOut) events.accept("BEEPER let go after " + BEEPER_MAX_MS / 1000 + " s - press again for more.");
            beeperTimedOut = true;
            return false;
        }
        return fcDisarmedNow.getAsBoolean();
    }

    /** Called on every input tick (the RC rate); sends immediately. state is null when the joystick is gone. */
    public void onInput(Joystick.State state) {
        // judged as the flight controller will see it: channels the radio doesn't carry are padded or unknown (ArmSwitch.on)
        short[] channels = state == null ? null : mapper.map(state.axes(), state.buttons());
        long now = clock.getAsLong();
        boolean beep = channels != null && beepNow(now);
        beeping = beep;
        if (channels != null) channels = withBeeper(channels, beeperChannel, beep);
        lastChannels = channels;
        radioPresent = state != null;
        noteInput(state, now);
        if (!linkUp.getAsBoolean()) {
            if (rcEnabled) events.accept("Link given up - RC output off until link back and throttle low.");
            rcEnabled = false;
            lostAt = 0; // a link that was down long enough to notice: Betaflight has failed safe, no quick resume
            linkDown();
            lastTickAt = now;
            return;
        }
        // 1.0.64: what the link and the flight controller say this tick (a few volatile reads: nothing waits)
        boolean unheard = sticksUnheard.getAsBoolean();
        boolean fcArmed = fcArmedNow.getAsBoolean(), fcDisarmed = fcDisarmedNow.getAsBoolean();
        if (!linkWasUp || fcArmed || fcDisarmed) fcUnknownHeardMs = 0;
        else if (!unheard) fcUnknownHeardMs += Math.min(MAX_TICK_MS, Math.max(0, now - lastTickAt));
        linkWasUp = true;
        lastTickAt = now;
        boolean reading = false;
        // H9: the phone installs an update - nothing that could arm a disarmed drone goes out
        boolean noArm = channels != null && armBlocked.getAsBoolean() && !fcArmed
                && (armSwitch == null || armSwitch.isUnknown(channels) || armSwitch.on(channels));
        if (noArm && rcEnabled) {
            events.accept("Phone installs an update - RC output stopped. No arming until back (throttle low, arm off).");
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
            } else if (fcArmed) {
                // armed and flying without sticks: every frame withheld is a frame of failsafe, and a frame with the
                // arm switch off would disarm it in the air - the pilot's sticks as they are, now
                rcEnabled = true;
                banner = new Banner(ARMED_RESUME_BANNER, now);
                events.accept("Link back - FC armed - sticks live.");
            } else if (fcReadWaitMs > 0 && !fcDisarmed && (unheard || fcUnknownHeardMs < fcReadWaitMs)) {
                // 1.0.64: the flight controller hasn't said yet (the app just started, or the link is out). Armed in
                // the air, "throttle down, arm off" obeyed would be an arm-off frame: a disarm in flight. Not taken
                // until its state is known, or the link has been heard for fcReadWaitMs without it
                reading = true;
            } else if (channels[PcConfig.THROTTLE_INDEX] <= THROTTLE_SAFE_MAX && (armSwitch == null || !armSwitch.on(channels))) {
                rcEnabled = true;
                events.accept(armSwitch == null ? "Throttle low - RC output enabled." : "Throttle low, arm switch off - RC output enabled.");
            }
            if (rcEnabled || !blip) lostAt = 0;
        }
        readingFc = reading;
        watchLoss(channels, now, unheard, fcArmed, fcDisarmed);
        // only while the radio itself is at rest (throttle low, ARM off): an idle frame can't carry a live switch then
        if (channels != null && passesArmGate(channels) && (idleSentAt == 0 || now - idleSentAt >= IDLE_STICKS_EVERY_MS)) {
            short[] idle = idleSticks(withBeeper(channels, beeperChannel, false), armSwitch);
            if (idle != null && link.sendIdleSticks(idle)) idleSentAt = now;
        }
        if (rcEnabled && frozenHolds(now)) return;
        if (rcEnabled) link.sendRc(channels);
    }

    /**
     * 1.0.64: the link unheard, then what the flight controller says of it (see the class comment). From the first
     * unheard tick the watch is open. A fresh DISARMED while the sticks are live and the frame could arm - the arm
     * switch on, or not known and the throttle up - stops them, for the start rule to begin again; not before the
     * watch is FC_ARMED_FRESH_MS old, so the status is from after the loss began and not the last one before it, nor
     * before DISARMED has been read for DISARMED_CONFIRM_MS.
     * The watch closes with the link heard again and DISARMED read with nothing that could arm, or ARMED still read
     * LOSS_SETTLE_MS after (flying on: the sticks never stopped). No word from the flight controller: it stays open,
     * and the sticks go on - a drone in the air must not be starved.
     */
    private void watchLoss(short[] channels, long now, boolean unheard, boolean fcArmed, boolean fcDisarmed) {
        if (unheard && !lossWatch) {
            lossWatch = true;
            lossWatchSince = now;
            lossStopped = false;
        }
        if (unheard) heardAgainAt = 0;
        else if (wasUnheard) heardAgainAt = now;
        wasUnheard = unheard;
        if (!fcDisarmed) disarmedSince = 0;
        else if (disarmedSince == 0) disarmedSince = now;
        if (!lossWatch || channels == null) return;
        // no arm switch known: the throttle alone, as at the start - and once a watch (a throttle resting at the limit
        // would stop and start them tick by tick)
        boolean couldArm = armSwitch != null ? armSwitch.on(channels)
                : !lossStopped && channels[PcConfig.THROTTLE_INDEX] > THROTTLE_SAFE_MAX;
        if (fcDisarmed && rcEnabled && couldArm) {
            if (now - lossWatchSince < FC_ARMED_FRESH_MS) return; // may be the last status before the loss: wait
            if (now - disarmedSince < DISARMED_CONFIRM_MS) return; // one status alone stops nothing: the next one too
            // the watch stays open: still unheard, the arm switch off and on again is stopped again at once
            rcEnabled = false;
            lostAt = 0;
            lossStopped = true;
            events.accept((unheard ? "Link lost" : "Link back") + " - FC disarmed - "
                    + (armSwitch != null ? "ARM off" : "throttle down") + " to fly again.");
        } else if (!unheard && lostAt == 0 && (fcDisarmed || fcArmed && now - heardAgainAt >= LOSS_SETTLE_MS)) {
            lossWatch = false;
        }
    }

    /** Whether the frozen-radio limit holds this frame back; says so once each way. */
    private boolean frozenHolds(long now) {
        long limit = frozenStopMs;
        boolean frozen = limit > 0 && inputChangedAt != 0 && now - inputChangedAt > limit && fcArmedNow.getAsBoolean();
        if (frozen && !frozenStopped) {
            events.accept("RADIO FROZEN " + limit / 1000 + " s while armed - sticks stopped, FC failsafe until the radio moves.");
        } else if (!frozen && frozenStopped) {
            events.accept("Radio moves again - sticks resumed.");
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
     * axis or button the mapping needs), "READING FC" (1.0.64: the flight controller's armed state isn't known yet -
     * nothing to do but wait; never "throttle down, arm off" to a drone that may be in the air), "ARM SWITCH NOT SENT"
     * (the radio's mapping doesn't carry Betaflight's ARM channel, so its value at the drone is unknown),
     * "THROTTLE DOWN", "ARM SWITCH OFF", or both; null when live.
     */
    public String waitingFor() {
        if (rcEnabled) return null;
        short[] ch = lastChannels;
        if (ch == null) return radioPresent ? "MAPPING INCOMPLETE" : "NO RADIO";
        if (armBlocked.getAsBoolean()) return "PHONE UPDATING";
        if (readingFc) return "READING FC";
        if (armSwitch != null && armSwitch.isUnknown(ch)) return "ARM SWITCH NOT SENT";
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
