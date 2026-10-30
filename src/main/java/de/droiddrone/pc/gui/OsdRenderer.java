package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.video.VideoPipeline;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Military-style HUD drawn over the video: fixed aircraft reticle, horizon + pitch ladder, heading tape with home
 * marker, battery / link / GPS / arming elements round the edges and big centre warnings. All text is outlined so it
 * stays readable on any picture. Which elements show and where comes from the {@link OsdLayout} (SETTINGS > OSD).
 * Everything is optional: missing telemetry simply isn't drawn. When the sticks can't reach the drone (link or flight
 * controller lost) a red frame goes round the whole picture, whatever the layout.
 */
final class OsdRenderer {
    /**
     * Everything the HUD shows; built fresh for every paint.
     * @param linkHint why the link is down, when known (e.g. the phone is off Tailscale); null otherwise
     * @param channels the sticks this PC sends (PcConfig.CHANNEL_NAMES order), null when none
     * @param preview  the OSD editor's sample: warnings show a sample line, nothing blinks
     * @param linkStalled nothing from the phone for a moment while the link is up (see ControlLink.isLinkStalled)
     */
    record Data(boolean connected, boolean linkReady, int pingMs, boolean rcLive, boolean fcLost, FcTelemetry telemetry,
                VideoPipeline.Stats video, boolean invertPitch, String linkHint, short[] channels, boolean preview,
                boolean linkStalled) {}

    private static final Color ACCENT = Theme.ACCENT; // symbols and labels
    private static final Color YELLOW = Theme.WARN;  // warnings only
    private static final Color GREEN = Theme.OK;
    private static final Color RED = Theme.BAD;
    private static final Color WHITE = new Color(0xf2f2ea);
    private static final Color STALE = Theme.MUTED; // values frozen since the flight controller went quiet
    private static final Color OUTLINE = new Color(0, 0, 0, 200);
    private static final long STALE_MS = 2000;
    private static final double PITCH_FOV_DEG = 60; // degrees of pitch across the picture height
    private static final int CORE_TEMP_ALARM_C = 70; // Betaflight's default osd_core_temp_alarm

    private interface Element {
        void paint(float x, float y, OsdLayout.Align align);
    }

    private Graphics2D g;
    private float unit;
    private int w, h;
    private OsdLayout layout;
    /** The editor's: where each element was drawn this time. Null while flying. */
    private Map<OsdElement, Rectangle2D> boundsOut;
    private Rectangle2D box; // the element being drawn, while boundsOut is wanted

    void paint(Graphics2D graphics, int x, int y, int w, int h, Data d, OsdLayout layout) {
        paint(graphics, x, y, w, h, d, layout, null);
    }

    /** @param bounds filled with each element's bounds in picture coordinates (the OSD editor), or null */
    void paint(Graphics2D graphics, int x, int y, int w, int h, Data d, OsdLayout layout, Map<OsdElement, Rectangle2D> bounds) {
        if (w < 200 || h < 120 || d == null || !d.connected()) return;
        g = (Graphics2D) graphics.create(x, y, w, h);
        try {
            Theme.smooth(g);
            this.w = w;
            this.h = h;
            this.layout = layout == null ? OsdLayout.defaults() : layout;
            boundsOut = bounds;
            unit = h / 720f; // everything scales with the picture
            long now = System.currentTimeMillis();
            FcTelemetry t = d.telemetry();
            FcTelemetry.Attitude att = t == null ? null : t.getAttitude();
            boolean attFresh = att != null && now - att.receivedAt() < STALE_MS;
            FcTelemetry.Status status = t == null ? null : t.getStatus();
            boolean statusFresh = status != null && now - status.receivedAt() < STALE_MS * 2;
            FcTelemetry.Status s = statusFresh ? status : null;
            boolean fcFresh = !d.fcLost();
            FcTelemetry.Battery battery = t == null ? null : t.getBattery();
            FcTelemetry.Gps gps = t == null ? null : t.getGps();
            FcTelemetry.Altitude alt = t == null ? null : t.getAltitude();

            if (attFresh && this.layout.isOn(OsdElement.HORIZON)) horizon(att, d.invertPitch());
            if (this.layout.isOn(OsdElement.CROSSHAIR)) crosshair(g, w / 2f, h / 2f, unit);
            element(OsdElement.HEADING, (ex, ey, a) -> {
                if (attFresh) headingTape(ex, ey, a, att, gps);
            });
            element(OsdElement.BATTERY, (ex, ey, a) -> battery(ex, ey, a, t, fcFresh));
            element(OsdElement.PING, (ex, ey, a) -> ping(ex, ey, a, d.pingMs()));
            element(OsdElement.NETWORK, (ex, ey, a) -> network(ex, ey, a, t == null ? null : t.getNetwork()));
            element(OsdElement.VIDEO, (ex, ey, a) -> video(ex, ey, a, d.video()));
            element(OsdElement.PHONE_BATTERY, (ex, ey, a) -> {
                int phone = t == null ? -1 : t.getPhoneBatteryPercent();
                if (phone >= 0) phoneBattery(ex, ey, a, phone, t.isPhoneCharging());
            });
            element(OsdElement.ARMED, (ex, ey, a) -> {
                if (s == null) return;
                g.setFont(Theme.headingFont(28 * unit));
                put(s.armed() ? "ARMED" : "DISARMED", ex, ey, s.armed() ? GREEN : YELLOW, a);
            });
            element(OsdElement.FLIGHT_MODE, (ex, ey, a) -> {
                // only once Betaflight's mode names are known: without them ACRO could be wrong
                if (s != null && t.hasBoxNames()) flightMode(ex, ey, a, s);
            });
            element(OsdElement.FLY_TIME, (ex, ey, a) -> {
                int fly = t == null ? -1 : t.flySeconds(now);
                if (fly >= 0) value(String.format(Locale.ROOT, "FLY %02d:%02d", fly / 60, fly % 60), ex, ey, a, WHITE);
            });
            element(OsdElement.CORE_TEMP, (ex, ey, a) -> {
                if (s == null || !s.coreTempKnown()) return;
                int c = s.coreTempC();
                value("CORE " + c + "°C", ex, ey, a, c >= CORE_TEMP_ALARM_C ? RED : c >= CORE_TEMP_ALARM_C - 10 ? YELLOW : WHITE);
            });
            element(OsdElement.GPS_SATS, (ex, ey, a) -> {
                if (gps == null) return;
                Color c = !fcFresh ? STALE : gps.fix() >= 2 && gps.sats() >= 6 ? GREEN : gps.fix() >= 1 ? YELLOW : RED;
                value("SAT " + gps.sats() + (gps.fix() >= 2 ? " 3D" : gps.fix() == 1 ? " 2D" : " NO FIX"), ex, ey, a, c);
            });
            element(OsdElement.SPEED, (ex, ey, a) -> {
                if (gps != null) value(String.format(Locale.ROOT, "SPD %3.0fKM/H", gps.speedKmh()), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.ALTITUDE, (ex, ey, a) -> {
                if (alt == null) return;
                String vario = alt.varioMetersPerSecond() > 0.3 ? " ↑" : alt.varioMetersPerSecond() < -0.3 ? " ↓" : "";
                value(String.format(Locale.ROOT, "ALT %4.0fM%s", alt.meters(), vario), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.HOME, (ex, ey, a) -> {
                if (gps != null && gps.distanceHomeM() >= 0) home(ex, ey, a, gps, attFresh ? att : null, fcFresh);
            });

            // extras
            element(OsdElement.THROTTLE, (ex, ey, a) -> {
                short[] ch = d.channels();
                if (ch == null || ch.length <= PcConfig.THROTTLE_INDEX) return;
                int pct = Math.max(0, Math.min(100, Math.round((ch[PcConfig.THROTTLE_INDEX] - 1000) / 10f)));
                value(String.format(Locale.ROOT, "THR %3d%%", pct), ex, ey, a, WHITE);
            });
            element(OsdElement.CURRENT, (ex, ey, a) -> {
                if (battery != null) value(String.format(Locale.ROOT, "CURR %.1fA", battery.amps()), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.MAH_DRAWN, (ex, ey, a) -> {
                if (battery != null) value("USED " + battery.mahDrawn() + "MAH", ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.PACK_VOLTAGE, (ex, ey, a) -> {
                if (battery != null && battery.volts() > 0) value(String.format(Locale.ROOT, "PACK %.2fV", battery.volts()), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.POWER, (ex, ey, a) -> {
                if (battery != null && battery.volts() > 0) value(String.format(Locale.ROOT, "PWR %.0fW", battery.volts() * battery.amps()), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.DISTANCE, (ex, ey, a) -> {
                if (gps == null || gps.travelledM() < 0) return;
                int m = gps.travelledM();
                value(m < 1000 ? "DIST " + m + "M" : String.format(Locale.ROOT, "DIST %.2fKM", m / 1000.0), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.RSSI, (ex, ey, a) -> {
                int rssi = t == null ? -1 : t.getRssiPercent();
                if (rssi >= 0) value("RSSI " + rssi + "%", ex, ey, a, !fcFresh ? STALE : rssi < 30 ? RED : rssi < 60 ? YELLOW : WHITE);
            });
            element(OsdElement.CPU_LOAD, (ex, ey, a) -> {
                if (s != null) value("CPU " + s.cpuLoadPercent() + "%", ex, ey, a, s.cpuLoadPercent() >= 80 ? YELLOW : WHITE);
            });
            element(OsdElement.VARIO, (ex, ey, a) -> {
                if (alt != null) value(String.format(Locale.ROOT, "VSPD %+.1fM/S", alt.varioMetersPerSecond()), ex, ey, a, fcFresh ? WHITE : STALE);
            });
            element(OsdElement.PITCH, (ex, ey, a) -> {
                if (attFresh) value(String.format(Locale.ROOT, "PIT %+d°", Math.round(att.pitchDeg() * (d.invertPitch() ? -1 : 1))), ex, ey, a, WHITE);
            });
            element(OsdElement.ROLL, (ex, ey, a) -> {
                if (attFresh) value(String.format(Locale.ROOT, "ROL %+d°", Math.round(att.rollDeg())), ex, ey, a, WHITE);
            });
            element(OsdElement.COORDINATES, (ex, ey, a) -> {
                if (gps == null || gps.latDeg() == 0 && gps.lonDeg() == 0) return;
                Color c = fcFresh ? WHITE : STALE;
                value(String.format(Locale.ROOT, "LAT %.6f", gps.latDeg()), ex, ey, a, c);
                value(String.format(Locale.ROOT, "LON %.6f", gps.lonDeg()), ex, ey + 22 * unit, a, c);
            });
            element(OsdElement.ON_TIME, (ex, ey, a) -> {
                FcTelemetry.Timers timers = t == null ? null : t.getTimers();
                if (timers != null) value(String.format(Locale.ROOT, "ON %02d:%02d", timers.onSeconds() / 60, timers.onSeconds() % 60), ex, ey, a, WHITE);
            });
            element(OsdElement.CLOCK, (ex, ey, a) ->
                    value(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), ex, ey, a, WHITE));
            element(OsdElement.STICKS_LEFT, (ex, ey, a) -> sticks(ex, ey, a, d.channels(), 2, PcConfig.THROTTLE_INDEX));
            element(OsdElement.STICKS_RIGHT, (ex, ey, a) -> sticks(ex, ey, a, d.channels(), 0, 1));

            element(OsdElement.WARNINGS, (ex, ey, a) -> warnings(ex, ey, a, d, s, t, now));
            if (!d.linkReady() || d.linkStalled() || d.fcLost()) lostFrame();
            element(OsdElement.DEBUG, (ex, ey, a) -> debugLine(ex, ey, a, d.video()));
        } finally { // also if an element throws: no Graphics left open, no bounds kept
            boundsOut = null;
            g.dispose();
        }
    }

    /** Draws one element where the layout puts it (nothing if it is off) and notes its bounds for the editor. */
    private void element(OsdElement e, Element painter) {
        OsdLayout.Placement p = layout.get(e);
        if (!p.on()) return;
        box = boundsOut == null ? null : new Rectangle2D.Float(Float.NaN, Float.NaN, 0, 0);
        painter.paint((float) (p.x() * w), (float) (p.y() * h), p.align());
        if (box != null && !Double.isNaN(box.getX())) boundsOut.put(e, box);
        box = null;
    }

    /** Grows the current element's bounds (only while the editor asks for them). */
    private void mark(double x, double y, double width, double height) {
        if (box == null) return;
        if (Double.isNaN(box.getX())) box.setRect(x, y, width, height);
        else box.add(new Rectangle2D.Double(x, y, width, height));
    }

    // ------------------------------------------------------------------ centre

    /**
     * The fixed crosshair in the middle, Betaflight style: two wings with ticks pointing down and a dot. The same one
     * on the live OSD and on the no-video screen. unit = picture height / 720.
     */
    static void crosshair(Graphics2D g, float cx, float cy, float unit) {
        float in = 14 * unit, out = 44 * unit, tick = 10 * unit;
        float width = Math.max(2f, 3f * unit);
        Path2D p = new Path2D.Float();
        p.moveTo(cx - out, cy);
        p.lineTo(cx - in, cy);
        p.lineTo(cx - in, cy + tick);
        p.moveTo(cx + out, cy);
        p.lineTo(cx + in, cy);
        p.lineTo(cx + in, cy + tick);
        float dot = Math.max(3f, 5f * unit);
        java.awt.geom.Ellipse2D centre = new java.awt.geom.Ellipse2D.Float(cx - dot / 2, cy - dot / 2, dot, dot);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(width + 2.5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.draw(p);
        g.draw(centre);
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.draw(p);
        g.fill(centre);
    }

    /**
     * Betaflight reports pitch positive nose-DOWN (its own OSD draws the horizon row at y - pitch, rows counting
     * down), so a positive pitch moves the horizon up. osdInvertPitch=true flips it if a setup shows otherwise.
     */
    private void horizon(FcTelemetry.Attitude att, boolean invertPitch) {
        int cx = w / 2, cy = h / 2;
        double pxPerDeg = h / PITCH_FOV_DEG;
        AffineTransform saved = g.getTransform();
        g.rotate(Math.toRadians(-att.rollDeg()), cx, cy);
        g.translate(0, -att.pitchDeg() * pxPerDeg * (invertPitch ? -1 : 1));
        float len = w * 0.22f;
        float gap = 70 * unit;
        stroke(line(cx - gap - len, cy, cx - gap, cy), WHITE, 2.2f);
        stroke(line(cx + gap, cy, cx + gap + len, cy), WHITE, 2.2f);
        g.setFont(Theme.monoFont(Font.BOLD, 15 * unit));
        for (int deg = -30; deg <= 30; deg += 10) {
            if (deg == 0) continue;
            float ly = (float) (cy - deg * pxPerDeg);
            float half = 55 * unit;
            float tick = 9 * unit * (deg > 0 ? 1 : -1);
            Path2D left = new Path2D.Float();
            left.moveTo(cx - gap * 0.6 - half, ly + tick);
            left.lineTo(cx - gap * 0.6 - half, ly);
            left.lineTo(cx - gap * 0.6, ly);
            Path2D right = new Path2D.Float();
            right.moveTo(cx + gap * 0.6, ly);
            right.lineTo(cx + gap * 0.6 + half, ly);
            right.lineTo(cx + gap * 0.6 + half, ly + tick);
            float width = 1.6f;
            if (deg < 0) { // dashed below the horizon, like a real ladder
                strokeDashed(left, width);
                strokeDashed(right, width);
            } else {
                stroke(left, WHITE, width);
                stroke(right, WHITE, width);
            }
            text(Integer.toString(Math.abs(deg)), cx + gap * 0.6f + half + 6 * unit, ly + 5 * unit, WHITE);
        }
        g.setTransform(saved);
    }

    /** The tape, 90 degrees wide; y is its top. */
    private void headingTape(float x, float top, OsdLayout.Align align, FcTelemetry.Attitude att, FcTelemetry.Gps gps) {
        float span = 360 * unit;        // pixels for the visible arc
        float cx = startX(x, span, align) + span / 2;
        float pxPerDeg = span / 90f;    // 90 degrees visible
        int heading = att.headingDeg();
        g.setFont(Theme.headingFont(15 * unit));
        for (int deg = heading - 50; deg <= heading + 50; deg++) {
            if (Math.floorMod(deg, 5) != 0) continue;
            float tx = cx + (deg - heading) * pxPerDeg;
            if (Math.abs(tx - cx) > span / 2) continue;
            int norm = Math.floorMod(deg, 360);
            boolean major = norm % 30 == 0;
            stroke(line(tx, top + 22 * unit, tx, top + (major ? 34 : 29) * unit), WHITE, 1.6f);
            if (major) {
                String label = switch (norm) {
                    case 0 -> "N";
                    case 90 -> "E";
                    case 180 -> "S";
                    case 270 -> "W";
                    default -> Integer.toString(norm / 10);
                };
                centered(label, tx, top + 52 * unit, norm % 90 == 0 ? ACCENT : WHITE);
            }
        }
        if (gps != null && gps.distanceHomeM() > 1) {
            float hx = cx + wrap(gps.directionHomeDeg() - heading) * pxPerDeg;
            if (Math.abs(hx - cx) <= span / 2) centered("H", hx, top + 70 * unit, GREEN);
        }
        // current heading box
        String hdg = String.format(Locale.ROOT, "%03d", heading);
        g.setFont(Theme.monoFont(Font.BOLD, 18 * unit));
        FontMetrics fm = g.getFontMetrics();
        float bw = fm.stringWidth(hdg) + 14 * unit;
        g.setColor(OUTLINE);
        g.fillRect(Math.round(cx - bw / 2), Math.round(top - 4 * unit), Math.round(bw), Math.round(24 * unit));
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(Math.round(cx - bw / 2), Math.round(top - 4 * unit), Math.round(bw), Math.round(24 * unit));
        mark(cx - bw / 2, top - 4 * unit, bw, 24 * unit);
        mark(cx - span / 2, top, span, 1); // the whole tape, also where it has no ticks
        centered(hdg, cx, top + 14 * unit, ACCENT);
    }

    // ------------------------------------------------------------------ edges

    /** Per-cell voltage big (what the pilot flies by, whatever the pack), the pack itself on the line below. */
    private void battery(float x, float y, OsdLayout.Align a, FcTelemetry t, boolean fresh) {
        FcTelemetry.Battery b = t == null ? null : t.getBattery();
        if (b == null || b.volts() <= 0) return;
        FcTelemetry.BatteryLimits lim = t.getBatteryLimits();
        if (!b.cellCountKnown()) { // no cell count from Betaflight yet: the pack voltage, no guess
            label("BATT" + (fresh ? "" : "  NO DATA"), x, y, a);
            g.setFont(Theme.monoFont(Font.BOLD, 30 * unit));
            put(String.format(Locale.ROOT, "%.2fV", b.volts()), x, y + 32 * unit, fresh ? WHITE : STALE, a);
            g.setFont(Theme.monoFont(Font.PLAIN, 16 * unit));
            put(String.format(Locale.ROOT, "%dmAh  %.1fA", b.mahDrawn(), b.amps()), x, y + 58 * unit, fresh ? WHITE : STALE, a);
            return;
        }
        double cell = b.cellVolts();
        double warn = lim != null && lim.warningCell() > 0 ? lim.warningCell() : 3.5;
        double min = lim != null && lim.minCell() > 0 ? lim.minCell() : 3.3;
        double max = lim != null && lim.maxCell() > 0 ? lim.maxCell() : 4.3;
        Color c = !fresh ? STALE : cell <= min ? RED : cell <= warn ? YELLOW : GREEN;
        // Betaflight counted wrong (battery plugged in while it ran on USB): say so, its own warnings are off too
        label("BATT " + b.cells() + "S" + (b.fcCountWrong() ? "  (BF SAYS " + b.fcCells() + "S)" : "") + (fresh ? "" : "  NO DATA"), x, y, a);
        String cellText = String.format(Locale.ROOT, "%.2fV", cell);
        Font big = Theme.monoFont(Font.BOLD, 30 * unit), small = Theme.headingFont(14 * unit);
        float cellW = g.getFontMetrics(big).stringWidth(cellText);
        float sx = startX(x, cellW + 6 * unit + g.getFontMetrics(small).stringWidth("/CELL"), a);
        g.setFont(big);
        text(cellText, sx, y + 32 * unit, c);
        g.setFont(small);
        text("/CELL", sx + cellW + 6 * unit, y + 32 * unit, fresh ? WHITE : STALE);
        // charge bar from cell voltage
        float bw = 120 * unit, bh = 8 * unit, by = y + 42 * unit, bx = startX(x, bw, a);
        double frac = Math.max(0, Math.min(1, (cell - min) / (max - min)));
        g.setColor(OUTLINE);
        g.fillRect(Math.round(bx), Math.round(by), Math.round(bw), Math.round(bh));
        g.setColor(c);
        g.fillRect(Math.round(bx), Math.round(by), Math.round((float) (bw * frac)), Math.round(bh));
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(Math.round(bx), Math.round(by), Math.round(bw), Math.round(bh));
        mark(bx, by, bw, bh);
        g.setFont(Theme.monoFont(Font.PLAIN, 16 * unit));
        put(String.format(Locale.ROOT, "%.2fV  %dmAh  %.1fA", b.volts(), b.mahDrawn(), b.amps()), x, by + 26 * unit, fresh ? WHITE : STALE, a);
    }

    private void ping(float x, float y, OsdLayout.Align a, int pingMs) {
        label("LINK", x, y, a);
        g.setFont(Theme.monoFont(Font.BOLD, 22 * unit));
        Color pingColor = pingMs < 0 ? RED : pingMs > 150 ? RED : pingMs > 80 ? YELLOW : GREEN;
        put(pingMs < 0 ? "--- MS" : pingMs + " MS", x, y + 26 * unit, pingColor, a);
    }

    /** "LTE 82%" and four signal bars after it. */
    private void network(float x, float y, OsdLayout.Align a, FcTelemetry.Network net) {
        if (net == null) return;
        g.setFont(Theme.monoFont(Font.BOLD, 16 * unit));
        String text = net.name() + " " + net.strengthPercent() + "%";
        float tw = g.getFontMetrics().stringWidth(text);
        float sx = startX(x, tw + 34 * unit, a);
        text(text, sx, y, WHITE);
        signalBars(sx + tw + 8 * unit, y, net.strengthPercent());
    }

    private void video(float x, float y, OsdLayout.Align a, VideoPipeline.Stats v) {
        if (v == null || v.width() <= 0) return;
        g.setFont(Theme.monoFont(Font.PLAIN, 15 * unit));
        put(String.format(Locale.ROOT, "VID %dFPS %.1fMB", v.shownFps(), v.megabitsPerSecond()), x, y, WHITE, a);
    }

    /** The phone's battery: a small battery icon with its charge, and the percentage. */
    private void phoneBattery(float x, float baseline, OsdLayout.Align a, int percent, boolean charging) {
        Color c = percent < 20 ? RED : percent < 40 ? YELLOW : GREEN;
        g.setFont(Theme.monoFont(Font.BOLD, 16 * unit));
        String text = "PHONE " + percent + "%" + (charging ? " CHG" : "");
        float tw = g.getFontMetrics().stringWidth(text);
        float bw = 24 * unit, bh = 12 * unit;
        float bx = startX(x, bw + 10 * unit + tw, a), by = baseline - bh;
        text(text, bx + bw + 10 * unit, baseline, c);
        g.setColor(OUTLINE);
        g.fillRect(Math.round(bx - 1), Math.round(by - 1), Math.round(bw + 5 * unit), Math.round(bh + 2));
        g.setColor(c);
        g.fillRect(Math.round(bx + 2), Math.round(by + 2), Math.round((bw - 4) * Math.max(0, Math.min(100, percent)) / 100f), Math.round(bh - 4));
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(Math.max(1f, 1.5f * unit)));
        g.drawRect(Math.round(bx), Math.round(by), Math.round(bw), Math.round(bh));
        g.fillRect(Math.round(bx + bw + 1), Math.round(by + bh / 3), Math.round(3 * unit), Math.round(bh / 3));
        mark(bx - 1, by - 1, bw + 5 * unit, bh + 2);
    }

    private void signalBars(float x, float baseline, int percent) {
        int bars = percent <= 0 ? 0 : 1 + Math.min(3, percent / 25);
        for (int i = 0; i < 4; i++) {
            float bh = (5 + i * 4) * unit;
            float bx = x + i * 7 * unit;
            g.setColor(OUTLINE);
            g.fillRect(Math.round(bx - 1), Math.round(baseline - bh - 1), Math.round(5 * unit + 2), Math.round(bh + 2));
            g.setColor(i < bars ? (bars <= 1 ? RED : bars == 2 ? YELLOW : GREEN) : Theme.LINE);
            g.fillRect(Math.round(bx), Math.round(baseline - bh), Math.round(5 * unit), Math.round(bh));
            mark(bx - 1, baseline - bh - 1, 5 * unit + 2, bh + 2);
        }
    }

    /** The flight mode big, the other active switches small after it. */
    private void flightMode(float x, float y, OsdLayout.Align a, FcTelemetry.Status s) {
        String mode = FcTelemetry.flightMode(s.activeModes());
        String others = String.join("  ", FcTelemetry.otherModes(s.activeModes()));
        Font big = Theme.headingFont(22 * unit), small = Theme.headingFont(15 * unit);
        float mw = g.getFontMetrics(big).stringWidth(mode);
        float total = mw + (others.isEmpty() ? 0 : 12 * unit + g.getFontMetrics(small).stringWidth(others));
        float sx = startX(x, total, a);
        g.setFont(big);
        text(mode, sx, y, FcTelemetry.isEmergencyMode(mode) ? RED : mode.equals("TURTLE") ? YELLOW : WHITE);
        if (!others.isEmpty()) {
            g.setFont(small);
            text(others, sx + mw + 12 * unit, y, Theme.MUTED);
        }
    }

    /** "HOME 230M" and an arrow pointing home after it. */
    private void home(float x, float y, OsdLayout.Align a, FcTelemetry.Gps gps, FcTelemetry.Attitude att, boolean fresh) {
        g.setFont(Theme.monoFont(Font.BOLD, 18 * unit));
        String text = String.format(Locale.ROOT, "HOME %dM", gps.distanceHomeM());
        float tw = g.getFontMetrics().stringWidth(text);
        float sx = startX(x, tw + 34 * unit, a);
        text(text, sx, y, fresh ? GREEN : STALE);
        if (att != null) homeArrow(sx + tw + 22 * unit, y - 6 * unit, gps.directionHomeDeg() - att.headingDeg());
    }

    private void homeArrow(float cx, float cy, double relativeDeg) {
        float s = 11 * unit;
        Path2D p = new Path2D.Float();
        p.moveTo(0, -s);
        p.lineTo(s * 0.7, s);
        p.lineTo(0, s * 0.45);
        p.lineTo(-s * 0.7, s);
        p.closePath();
        AffineTransform at = AffineTransform.getTranslateInstance(cx, cy);
        at.rotate(Math.toRadians(relativeDeg));
        Shape arrow = at.createTransformedShape(p);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(3f));
        g.draw(arrow);
        g.setColor(GREEN);
        g.fill(arrow);
        mark(cx - s, cy - s, 2 * s, 2 * s);
    }

    /** A stick's position in a small box, Betaflight's stick overlay; y is the box's top. */
    private void sticks(float x, float y, OsdLayout.Align a, short[] ch, int xChannel, int yChannel) {
        if (ch == null || ch.length <= Math.max(xChannel, yChannel)) return;
        float size = 64 * unit, bx = startX(x, size, a);
        float px = bx + size * Math.max(0, Math.min(1, (ch[xChannel] - 1000) / 1000f));
        float py = y + size * (1 - Math.max(0, Math.min(1, (ch[yChannel] - 1000) / 1000f)));
        stroke(new Rectangle2D.Float(bx, y, size, size), WHITE, 1.2f);
        g.setColor(Theme.alpha(WHITE, 90));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(Math.round(bx + size / 2), Math.round(y + 4 * unit), Math.round(bx + size / 2), Math.round(y + size - 4 * unit));
        g.drawLine(Math.round(bx + 4 * unit), Math.round(y + size / 2), Math.round(bx + size - 4 * unit), Math.round(y + size / 2));
        float dot = 9 * unit;
        java.awt.geom.Ellipse2D knob = new java.awt.geom.Ellipse2D.Float(px - dot / 2, py - dot / 2, dot, dot);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(3f));
        g.draw(knob);
        g.setColor(ACCENT);
        g.fill(knob);
    }

    // ------------------------------------------------------------------ warnings

    private void warnings(float x, float y, OsdLayout.Align a, Data d, FcTelemetry.Status s, FcTelemetry t, long now) {
        List<String> big = new ArrayList<>();
        if (!d.linkReady()) big.add("LINK LOST");
        else if (d.linkStalled()) big.add("LINK STALL");
        if (d.fcLost()) big.add("NO FLIGHT CONTROLLER");
        // Betaflight's real failsafe (stage 2: drop / land / disarm). RXLOSS alone is a pause of 100 ms or more in the
        // sticks: Betaflight holds the last sticks for 300 ms, and keeps the flag 0.5 s after they return
        boolean failsafe = s != null && s.blockers().stream().anyMatch(b -> b.osdName().equals("FAILSAFE"));
        boolean rcGap = !failsafe && s != null && s.armed() && s.blockers().stream().anyMatch(b -> b.osdName().equals("RXLOSS"));
        if (failsafe) big.add("FAILSAFE");
        if (d.linkReady() && !d.rcLive()) big.add("RC OFF - THROTTLE DOWN");
        FcTelemetry.Battery b = t == null ? null : t.getBattery();
        FcTelemetry.BatteryLimits lim = t == null ? null : t.getBatteryLimits();
        if (!d.fcLost() && b != null && b.volts() > 0 && b.cellCountKnown()
                && b.cellVolts() <= (lim != null && lim.warningCell() > 0 ? lim.warningCell() : 3.5)) big.add("LOW BATTERY");
        // the phone is the radio: when it dies, the link goes
        if (t != null && t.getPhoneBatteryPercent() >= 0 && t.getPhoneBatteryPercent() <= 15 && !t.isPhoneCharging()) big.add("PHONE BATTERY LOW");
        if (d.preview() && big.isEmpty()) big.add("LOW BATTERY"); // the editor: something to see and drag
        boolean blink = d.preview() || (now / 400) % 2 == 0;
        // the whole block stays in the picture, also when placed low: it moves up by what doesn't fit
        float below = !big.isEmpty() ? (big.size() - 1) * 38 * unit : 0;
        if (!d.linkReady() && d.linkHint() != null) below += 38 * unit + 4 * unit;
        else if (d.fcLost()) below += 38 * unit + 28 * unit;
        else if (rcGap || (s != null && !s.armed() && !s.blockers().isEmpty())) below += 38 * unit + 4 * unit;
        y = Math.min(y, h - 14 * unit - below);
        g.setFont(Theme.headingFont(34 * unit));
        for (String text : big) {
            if (blink) put(text, x, y, text.equals("RC OFF - THROTTLE DOWN") || text.equals("PHONE BATTERY LOW") ? YELLOW : RED, a);
            else mark(x, y, 1, 1);
            y += 38 * unit;
        }
        if (!d.linkReady() && d.linkHint() != null) { // steady, under LINK LOST: why
            g.setFont(Theme.headingFont(18 * unit));
            put(d.linkHint(), x, y + 4 * unit, YELLOW, a);
            return;
        }
        if (d.fcLost()) { // steady, under the blinking title: what it means and where to look
            g.setFont(Theme.headingFont(18 * unit));
            put("THE PHONE LOST ITS USB LINK TO THE FC - YOUR STICKS DON'T REACH THE DRONE", x, y + 4 * unit, YELLOW, a);
            put("CHECK THE OTG CABLE. KEEPS HAPPENING? SETUP > DRONE SETUP", x, y + 28 * unit, YELLOW, a);
            return;
        }
        if (rcGap) {
            g.setFont(Theme.headingFont(22 * unit));
            put("RC SIGNAL GAP", x, y + 4 * unit, YELLOW, a);
            return;
        }
        if (s != null && !s.armed() && !s.blockers().isEmpty()) {
            g.setFont(Theme.headingFont(18 * unit));
            String names = s.blockers().stream().map(FcTelemetry.Blocker::osdName).reduce((p, c) -> p + " " + c).orElse("");
            put("ARMING BLOCKED: " + names, x, y + 4 * unit, YELLOW, a);
        }
    }

    /** Steady red border: the sticks aren't reaching the drone. Seen even in the corner of your eye. */
    private void lostFrame() {
        float t = 7 * unit;
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(t + 4 * unit));
        g.drawRect(Math.round(t / 2), Math.round(t / 2), Math.round(w - t), Math.round(h - t));
        g.setColor(RED);
        g.setStroke(new BasicStroke(t));
        g.drawRect(Math.round(t / 2), Math.round(t / 2), Math.round(w - t), Math.round(h - t));
    }

    private void debugLine(float x, float y, OsdLayout.Align a, VideoPipeline.Stats v) {
        if (v == null || v.width() == 0) return;
        String line = String.format(Locale.ROOT, "%s %dx%d  DEC %.1fMS  DRAW %.1fMS  DMG %d  LOST %d",
                v.hevc() ? "H265" : "H264", v.width(), v.height(), v.decodeMs(), v.convertMs(), v.damaged(), v.lost());
        g.setFont(Theme.monoFont(Font.PLAIN, 12 * unit));
        put(line, x, y, Theme.MUTED, a);
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Where something width wide starts, to sit left-aligned at x, centred on x or right-aligned at x. */
    private static float startX(float x, float width, OsdLayout.Align a) {
        return switch (a) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        };
    }

    private static float wrap(double deg) {
        double d = ((deg + 180) % 360 + 360) % 360 - 180;
        return (float) d;
    }

    private static Path2D line(float x1, float y1, float x2, float y2) {
        Path2D p = new Path2D.Float();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private void stroke(Shape s, Color c, float width) {
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((width + 2.5f) * Math.max(1f, unit), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.draw(s);
        g.setColor(c);
        g.setStroke(new BasicStroke(width * Math.max(1f, unit), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.draw(s);
        if (box != null) mark(s.getBounds2D().getX(), s.getBounds2D().getY(), s.getBounds2D().getWidth(), s.getBounds2D().getHeight());
    }

    private void strokeDashed(Shape s, float width) {
        float dash = 7 * unit;
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((width + 2.5f) * Math.max(1f, unit), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{dash, dash}, 0));
        g.draw(s);
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(width * Math.max(1f, unit), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{dash, dash}, 0));
        g.draw(s);
    }

    /** A red element label (BATT, LINK). */
    private void label(String text, float x, float y, OsdLayout.Align a) {
        g.setFont(Theme.headingFont(14 * unit));
        put(text, x, y, ACCENT, a);
    }

    /** A one-line value in the OSD's value font. */
    private void value(String text, float x, float y, OsdLayout.Align a, Color c) {
        g.setFont(Theme.monoFont(Font.BOLD, 18 * unit));
        put(text, x, y, c, a);
    }

    /** Text aligned at x. */
    private void put(String s, float x, float y, Color c, OsdLayout.Align a) {
        text(s, startX(x, g.getFontMetrics().stringWidth(s), a), y, c);
    }

    /** Text with a black outline. */
    private void text(String s, float x, float y, Color c) {
        g.setColor(OUTLINE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) g.drawString(s, x + dx * 1.5f, y + dy * 1.5f);
            }
        }
        g.setColor(c);
        g.drawString(s, x, y);
        if (box != null) {
            FontMetrics fm = g.getFontMetrics();
            mark(x - 1.5, y - fm.getAscent() - 1.5, fm.stringWidth(s) + 3, fm.getAscent() + fm.getDescent() + 3);
        }
    }

    private void centered(String s, float cx, float y, Color c) {
        text(s, cx - g.getFontMetrics().stringWidth(s) / 2f, y, c);
    }
}
