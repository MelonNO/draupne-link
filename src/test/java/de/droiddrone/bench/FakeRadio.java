package de.droiddrone.bench;

import de.droiddrone.pc.Joystick;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bench's radio: what the app sees instead of GLFW's joysticks - one device shaped like a TX16S in USB joystick
 * mode (8 axes: roll, pitch, throttle, yaw, four switches; 16 buttons), with the hands of a test on it. The axes are
 * as GLFW gives them: -1 .. +1. A real radio's sticks are never perfectly still, so each reading carries a little
 * noise unless the radio is frozen (the app's RADIO FROZEN rule looks for exactly that).
 * <p>
 * Two hard limits for a run that flies a real flight controller (off unless asked for, so a made-up drone's scenario
 * is as it was): a throttle {@link #ceiling} the radio never gives more than, whatever a scenario asks, and
 * {@link #handsOff()} - ARM off and the throttle down in one move, from any thread, held until {@link #handsOn()}.
 * Both are kept where the app reads the radio, not only where a scenario moves it.
 */
public final class FakeRadio implements Joystick.Source {
    public static final String NAME = "Bench TX16S Joystick";
    /** The default channel map of the app's settings: roll=axis0, pitch=axis1, throttle=axis2, yaw=axis3, aux1..4=axis4..7. */
    public static final int ROLL = 0, PITCH = 1, THROTTLE = 2, YAW = 3, AUX1 = 4, AUX2 = 5, AUX3 = 6, AUX4 = 7;

    private final Joystick.Info info;
    private final Random noise = new Random(7);
    /** A move reads the sticks as they are and puts them back changed: one move at a time, or one of two is lost. */
    private final Object hands = new Object();
    private volatile float[] axes = {0, 0, -1, 0, -1, -1, -1, -1};
    private volatile boolean[] buttons = new boolean[16];
    private volatile boolean plugged = true;
    private volatile boolean frozen;
    private volatile int polls;
    /** The most throttle this radio gives, as a channel value (1000 .. 2000); 0: no ceiling. */
    private volatile int ceiling;
    /** The same on the throttle axis; with no ceiling, more than any axis value. */
    private volatile float ceilingAxis = Float.POSITIVE_INFINITY;
    private volatile boolean handsOff;
    /** Why the hands are off for the rest of this radio's life; null: {@link #handsOn()} may put them back. */
    private volatile String forGood;
    /** The axis of the ARM switch (AUX1 unless said). */
    private volatile int armAxis = AUX1;
    private final AtomicInteger clamped = new AtomicInteger(), refused = new AtomicInteger();

    public FakeRadio() {
        this(0, NAME, "bench0000000000000000000000000000");
    }

    public FakeRadio(int id, String name, String guid) {
        info = new Joystick.Info(id, name, guid);
    }

    public Joystick.Info info() {
        return info;
    }

    // ------------------------------------------------------------------ the test's hands

    /** One axis, -1 .. +1. */
    public FakeRadio axis(int index, double value) {
        synchronized (hands) {
            float[] a = axes.clone();
            a[index] = (float) Math.max(-1, Math.min(1, value));
            put(a);
        }
        return this;
    }

    /** Roll, pitch and yaw around the centre, throttle from -1 (down) to +1 (full). */
    public FakeRadio sticks(double roll, double pitch, double yaw, double throttle) {
        synchronized (hands) {
            float[] a = axes.clone();
            a[ROLL] = (float) roll;
            a[PITCH] = (float) pitch;
            a[YAW] = (float) yaw;
            a[THROTTLE] = (float) throttle;
            put(a);
        }
        return this;
    }

    /** A move's result put back as the sticks, under the limits (the caller holds {@link #hands}). */
    private void put(float[] a) {
        float[] held = limited(a);
        Runnable caught = midMove;
        if (caught != null) caught.run();
        axes = held;
    }

    /**
     * Only for the bench's own test of the hard stop (BenchSelfTest): run inside a move, after its sticks are worked
     * out and before they are put back - the one moment a stop from another thread could be lost in. Null otherwise.
     */
    volatile Runnable midMove;

    /**
     * Sticks centred, throttle down; the switches stay. (This was called handsOff until the hardware rig came: that
     * name now belongs to the hard stop, which also puts ARM off and holds.)
     */
    public FakeRadio sticksAtRest() {
        return sticks(0, 0, 0, -1);
    }

    /** A switch (AUX1 .. AUX4 as 1 .. 4): -1 = low, 0 = middle, +1 = high. */
    public FakeRadio aux(int number, double value) {
        return axis(AUX1 + number - 1, value);
    }

    public FakeRadio button(int index, boolean down) {
        synchronized (hands) {
            boolean[] b = buttons.clone();
            b[index] = down;
            buttons = b;
        }
        return this;
    }

    /** Pulled out of the USB, or back in. */
    public FakeRadio plugged(boolean in) {
        plugged = in;
        return this;
    }

    /** The radio hangs: still there, every reading exactly the last one. */
    public FakeRadio frozen(boolean hangs) {
        frozen = hangs;
        return this;
    }

    /** How often the app has asked GLFW for events: it is alive and polling. */
    public int polls() {
        return polls;
    }

    // ------------------------------------------------------------------ the hard limits (a real flight controller)

    /**
     * The most throttle this radio ever gives, as a channel value: 1000 (down) .. 2000 (full) - what the app sends
     * for it with the rig's own stick settings ({@link AppRig}: throttle on axis 2, measured -1 .. 0 .. +1). From now
     * on no reading has more, whatever is asked: a request above it is cut to it and counted ({@link #clamped()}).
     * It only ever goes down - a scenario can neither lift nor drop it. None unless called; a hardware run has
     * {@link Hardware#THROTTLE_CEILING}.
     */
    public FakeRadio ceiling(int throttleMax) {
        if (throttleMax < 1000 || throttleMax > 2000) {
            throw new IllegalArgumentException("A throttle ceiling is a channel value, 1000 .. 2000: " + throttleMax);
        }
        synchronized (hands) {
            if (ceiling != 0 && throttleMax > ceiling) {
                throw new IllegalStateException("The radio's throttle ceiling only goes down: it is " + ceiling + ", not to be " + throttleMax);
            }
            float top = (float) ((throttleMax - 1500) / 500.0);
            while (1500.0 + 500.0 * top > throttleMax) top = Math.nextDown(top); // the float just under, never just over
            ceiling = throttleMax;
            ceilingAxis = top;
            float[] a = axes.clone();
            if (a[THROTTLE] > top) { // what is held now comes under it too (no request: not counted)
                a[THROTTLE] = top;
                axes = a;
            }
        }
        return this;
    }

    /** The ceiling as a channel value; 0 when there is none. */
    public int ceiling() {
        return ceiling;
    }

    /** How many requests asked for more throttle than the ceiling and were cut to it. */
    public int clamped() {
        return clamped.get();
    }

    /**
     * The hard stop, from any thread (a watch on the battery, a dead man's switch, a scenario's last line): the ARM
     * switch off and the throttle at its minimum in one move, roll, pitch and yaw let go to their centres - and held:
     * until {@link #handsOn()} every request for throttle or for ARM is refused and counted ({@link #refused()}), also
     * one made in the same moment from another thread. The other switches stay.
     */
    public FakeRadio handsOff() {
        handsOff = true; // at once, before the sticks are waited for: the very next reading is hands off
        synchronized (hands) {
            handsOff = true; // and again in turn: hands put back on in the same moment do not undo a stop
            float[] a = axes.clone();
            a[ROLL] = 0;
            a[PITCH] = 0;
            a[YAW] = 0;
            a[THROTTLE] = -1;
            a[armAxis] = -1;
            axes = a;
        }
        return this;
    }

    /**
     * {@link #handsOff()} for the rest of this radio's life: {@link #handsOn()} throws from now on, with the reason.
     * For a watch that has seen what must end the run's flying - the battery under its floor, the keeper gone.
     */
    public FakeRadio handsOffForGood(String why) {
        synchronized (hands) {
            if (forGood == null) forGood = why == null || why.isBlank() ? "no reason given" : why;
        }
        return handsOff();
    }

    /**
     * The hands back on the radio after {@link #handsOff()}: requests count again. Nothing moves by it - ARM is off
     * and the throttle down until a scenario asks otherwise. For the one place that opens a window for arming.
     *
     * @throws IllegalStateException after {@link #handsOffForGood}
     */
    public FakeRadio handsOn() {
        synchronized (hands) {
            if (forGood != null) throw new IllegalStateException("The radio's hands are off for good: " + forGood);
            handsOff = false;
        }
        return this;
    }

    public boolean isHandsOff() {
        return handsOff;
    }

    /** How many requests for throttle or ARM were refused because the hands were off. */
    public int refused() {
        return refused.get();
    }

    /** Which switch arms (AUX1 .. AUX4 as 1 .. 4; AUX1 unless said): the one {@link #handsOff()} puts off and holds off. */
    public FakeRadio armSwitch(int number) {
        if (number < 1 || number > 4) throw new IllegalArgumentException("The radio's switches are AUX1 .. AUX4: " + number);
        synchronized (hands) {
            armAxis = AUX1 + number - 1;
            put(axes.clone());
        }
        return this;
    }

    /** The limits and what they stopped, in words for a film or a report; "" for a radio that has none. */
    public String limits() {
        StringBuilder s = new StringBuilder();
        if (ceiling != 0) s.append("throttle ceiling ").append(ceiling).append(", ").append(clamped.get()).append(" request(s) above it cut");
        if (handsOff || refused.get() > 0) {
            String why = forGood;
            s.append(s.length() > 0 ? "; " : "").append(why != null ? "hands off for good (" + why + ")" : handsOff ? "hands off" : "hands on")
                    .append(", ").append(refused.get()).append(" request(s) for throttle or ARM refused while off");
        }
        return s.toString();
    }

    /** A move's result brought under the limits (the caller holds {@link #hands}); a request that was stopped is counted. */
    private float[] limited(float[] a) {
        if (a[THROTTLE] > ceilingAxis) {
            a[THROTTLE] = ceilingAxis;
            clamped.incrementAndGet();
        }
        if (handsOff && (a[THROTTLE] > -1f || a[armAxis] > -1f)) {
            a[THROTTLE] = -1f;
            a[armAxis] = -1f;
            refused.incrementAndGet();
        }
        return a;
    }

    // ------------------------------------------------------------------ what the app sees

    @Override
    public boolean init() {
        return true;
    }

    @Override
    public void terminate() {
    }

    @Override
    public void poll() {
        polls++;
    }

    @Override
    public List<Joystick.Info> list() {
        return plugged ? List.of(info) : List.of();
    }

    @Override
    public Joystick.State readSame(Joystick.Info device) {
        return plugged && info.sameDevice(device) ? state() : null;
    }

    @Override
    public Joystick.State read(int jid) {
        return plugged && jid == info.id() ? state() : null;
    }

    private Joystick.State state() {
        float[] a = axes.clone();
        if (!frozen) {
            synchronized (noise) {
                for (int i = 0; i < 4; i++) a[i] = Math.max(-1f, Math.min(1f, a[i] + (noise.nextFloat() - 0.5f) * 0.004f));
            }
        }
        // the limits once more, where the app reads: the noise must not lift the throttle over its ceiling, and hands
        // off is hands off whatever is held this instant
        float top = ceilingAxis;
        if (a[THROTTLE] > top) a[THROTTLE] = top;
        if (handsOff) {
            a[THROTTLE] = -1f;
            a[armAxis] = -1f;
        }
        return new Joystick.State(a, buttons.clone());
    }
}
