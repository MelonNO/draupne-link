package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Which way up each phone's picture is shown (1.0.55, the pilot: "make it follow everywhere the video is shown"): one
 * setting per phone name - the one VIDEO > Camera upside down sets since 1.0.41 - read by every place that shows that
 * phone's picture: the main video, the observation tiles, the overwatch cards, clips and thumbnails, previous flights.
 * Set in one place, shown turned everywhere at once (the listeners repaint).
 */
final class CameraTurn {
    /** The PC-wide setting up to 1.0.40; a phone never set takes it. Per phone: KEY + "." + name. */
    static final String KEY = "videoUpsideDown";

    private final PcConfig config;
    /** Writes settings (MainWindow.save); null in tests: memory only. */
    private final Consumer<Map<String, String>> saver;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, Boolean>> listeners = new CopyOnWriteArrayList<>();

    CameraTurn(PcConfig config, Consumer<Map<String, String>> saver) {
        this.config = config;
        this.saver = saver;
    }

    static String key(String phone) {
        return KEY + "." + phone;
    }

    /** The phone's picture is shown turned 180 degrees. Cheap: read on every paint. */
    boolean upsideDown(String phone) {
        String name = phone == null ? "" : phone;
        Boolean b = cache.get(name.toLowerCase(Locale.ROOT));
        if (b != null) return b;
        String v = name.isEmpty() ? "" : config.getString(key(name));
        if (v.isEmpty()) v = config.getString(KEY);
        boolean on = "true".equalsIgnoreCase(v);
        cache.put(name.toLowerCase(Locale.ROOT), on);
        return on;
    }

    /** Saved for this phone (the PC-wide one when no phone is named); every view showing it is told. */
    void set(String phone, boolean on) {
        String name = phone == null ? "" : phone;
        String k = name.isEmpty() ? KEY : key(name);
        config.set(k, Boolean.toString(on));
        if (name.isEmpty()) cache.clear(); // the fallback for every phone never set
        else cache.put(name.toLowerCase(Locale.ROOT), on);
        if (saver != null) saver.accept(Map.of(k, Boolean.toString(on)));
        for (BiConsumer<String, Boolean> l : listeners) l.accept(name, on);
    }

    /** Told (phone, upside down) on every change, on the thread that made it (the Swing thread). */
    void addListener(BiConsumer<String, Boolean> l) {
        listeners.add(l);
    }

    void removeListener(BiConsumer<String, Boolean> l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ drawing turned

    /**
     * The picture into this rectangle, mirrored (flipX) and/or upside down (flipY); a turn of 180 degrees is both.
     * Scaled bilinear when the sizes differ, a plain copy when nothing turns and the size is its own.
     */
    static void draw(Graphics2D g, BufferedImage img, int x, int y, int w, int h, boolean flipX, boolean flipY) {
        if (!flipX && !flipY && w == img.getWidth() && h == img.getHeight()) {
            g.drawImage(img, x, y, null);
            return;
        }
        Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        if (w != img.getWidth() || h != img.getHeight()) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        g.drawImage(img, flipX ? x + w : x, flipY ? y + h : y, flipX ? x : x + w, flipY ? y : y + h,
                0, 0, img.getWidth(), img.getHeight(), null);
        if (interpolation != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
    }

    /** The picture's mirror for a turn: a mirrored picture turned 180 degrees is flipped only up-down. */
    static boolean flipX(boolean mirror, boolean upsideDown) {
        return mirror != upsideDown;
    }

    /**
     * A box {x, y, w, h} (0..1, the camera's own coordinates) where it shows on the picture drawn flipped so; null
     * stays null.
     */
    static float[] box(float[] b, boolean flipX, boolean flipY) {
        if (b == null || b.length < 4) return b;
        return new float[]{flipX ? 1 - b[0] - b[2] : b[0], flipY ? 1 - b[1] - b[3] : b[1], b[2], b[3]};
    }

    /**
     * The bearing of what the camera saw angleDeg right of its axis (the phone's own measure, across the sensor): a
     * phone mounted upside down has its sensor's right on the world's left.
     */
    static double bearing(double headingDeg, double angleDeg, boolean upsideDown) {
        double b = (headingDeg + (upsideDown ? -angleDeg : angleDeg)) % 360;
        return b < 0 ? b + 360 : b;
    }
}
