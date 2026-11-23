package de.droiddrone.bench.hw;

import de.droiddrone.bench.Ui;
import de.droiddrone.pc.video.LatencyMeter;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The app's own Latency test (TOOLS &gt; Latency test) as the bench reads it, read only: the screen shows only the
 * median of its last readings, and the glass-to-glass table wants each one. Started and ended by the bench as a pilot
 * does (the menu); here only looked at - the video view's meter while the test runs, the readings it keeps (its last
 * {@link HwGlassToGlass#METER_KEEPS}, taken under the meter's own lock), and the view's time from a picture handed over
 * to painted, which the test adds to each reading and the OSD to its G2G. By reflection: nothing of it is the app's to
 * hand out, and nothing is changed. Found by type, not by name: a change in the app fails here, saying so.
 */
final class MeterTap {
    static final String VIEW = "de.droiddrone.pc.gui.VideoView";

    private MeterTap() {
    }

    /** The app's video view among the showing windows (the main window's, a popped-out or a full-screen one); null for none. */
    static Component videoView(Ui ui) {
        for (Component c : ui.components()) if (c.getClass().getName().equals(VIEW)) return c;
        return null;
    }

    /** The view's meter while the Latency test runs; null while it does not. */
    static LatencyMeter meter(Component view) {
        try {
            return (LatencyMeter) field(view.getClass(), LatencyMeter.class).get(view);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("The video view's latency meter could not be read: " + e, e);
        }
    }

    /** The readings the meter keeps now, oldest first (ms, camera to screen). */
    @SuppressWarnings("unchecked")
    static List<Double> readings(LatencyMeter m) {
        Field f = field(LatencyMeter.class, ArrayDeque.class);
        try {
            synchronized (m) { // the meter adds a reading under its own lock
                return new ArrayList<>((Collection<Double>) f.get(m));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("The latency meter's readings could not be read: " + e, e);
        }
    }

    /** The view's measured time from a picture handed over to painted, ms (added to each reading, and by the OSD to its G2G). */
    static double displayMs(Component view) {
        try {
            Method m = view.getClass().getDeclaredMethod("displayMs");
            m.setAccessible(true);
            return ((Number) m.invoke(view)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    private static Field field(Class<?> in, Class<?> type) {
        Field found = null;
        for (Field f : in.getDeclaredFields()) {
            if (f.getType() != type) continue;
            if (found != null) throw new IllegalStateException(in.getName() + " has two fields of the type " + type.getSimpleName() + ": the bench's tap does not know which");
            found = f;
        }
        if (found == null) throw new IllegalStateException(in.getName() + " has no field of the type " + type.getSimpleName() + " any more: the bench's tap needs a new look");
        found.setAccessible(true);
        return found;
    }
}
