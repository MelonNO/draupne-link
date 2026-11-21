package de.droiddrone.bench;

import de.droiddrone.pc.Joystick;

import java.util.List;
import java.util.Random;

/**
 * The bench's radio: what the app sees instead of GLFW's joysticks - one device shaped like a TX16S in USB joystick
 * mode (8 axes: roll, pitch, throttle, yaw, four switches; 16 buttons), with the hands of a test on it. The axes are
 * as GLFW gives them: -1 .. +1. A real radio's sticks are never perfectly still, so each reading carries a little
 * noise unless the radio is frozen (the app's RADIO FROZEN rule looks for exactly that).
 */
public final class FakeRadio implements Joystick.Source {
    public static final String NAME = "Bench TX16S Joystick";
    /** The default channel map of the app's settings: roll=axis0, pitch=axis1, throttle=axis2, yaw=axis3, aux1..4=axis4..7. */
    public static final int ROLL = 0, PITCH = 1, THROTTLE = 2, YAW = 3, AUX1 = 4, AUX2 = 5, AUX3 = 6, AUX4 = 7;

    private final Joystick.Info info;
    private final Random noise = new Random(7);
    private volatile float[] axes = {0, 0, -1, 0, -1, -1, -1, -1};
    private volatile boolean[] buttons = new boolean[16];
    private volatile boolean plugged = true;
    private volatile boolean frozen;
    private volatile int polls;

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
        float[] a = axes.clone();
        a[index] = (float) Math.max(-1, Math.min(1, value));
        axes = a;
        return this;
    }

    /** Roll, pitch and yaw around the centre, throttle from -1 (down) to +1 (full). */
    public FakeRadio sticks(double roll, double pitch, double yaw, double throttle) {
        float[] a = axes.clone();
        a[ROLL] = (float) roll;
        a[PITCH] = (float) pitch;
        a[YAW] = (float) yaw;
        a[THROTTLE] = (float) throttle;
        axes = a;
        return this;
    }

    /** Sticks centred, throttle down; the switches stay. */
    public FakeRadio handsOff() {
        return sticks(0, 0, 0, -1);
    }

    /** A switch (AUX1 .. AUX4 as 1 .. 4): -1 = low, 0 = middle, +1 = high. */
    public FakeRadio aux(int number, double value) {
        return axis(AUX1 + number - 1, value);
    }

    public FakeRadio button(int index, boolean down) {
        boolean[] b = buttons.clone();
        b[index] = down;
        buttons = b;
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
        return new Joystick.State(a, buttons.clone());
    }
}
