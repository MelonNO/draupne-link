package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.video.VideoPipeline;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Military-style HUD drawn over the video: fixed aircraft reticle, horizon + pitch ladder, heading tape with home
 * marker, battery / link / GPS / arming blocks in the corners and big centre warnings. All text is outlined so it
 * stays readable on any picture. Everything is optional: missing telemetry simply isn't drawn. When the sticks can't
 * reach the drone (link or flight controller lost) a red frame goes round the whole picture.
 */
final class OsdRenderer {
    /** Everything the HUD shows; built fresh for every paint. */
    record Data(boolean connected, boolean linkReady, int pingMs, boolean rcLive, boolean fcLost, FcTelemetry telemetry,
                VideoPipeline.Stats video, boolean invertPitch) {}

    private static final Color ACCENT = Theme.ACCENT; // symbols and labels
    private static final Color YELLOW = Theme.WARN;  // warnings only
    private static final Color GREEN = Theme.OK;
    private static final Color RED = Theme.BAD;
    private static final Color WHITE = new Color(0xf2f2ea);
    private static final Color STALE = Theme.MUTED; // values frozen since the flight controller went quiet
    private static final Color OUTLINE = new Color(0, 0, 0, 200);
    private static final long STALE_MS = 2000;
    private static final double PITCH_FOV_DEG = 60; // degrees of pitch across the picture height

    private Graphics2D g;
    private float unit;

    void paint(Graphics2D graphics, int x, int y, int w, int h, Data d) {
        if (w < 200 || h < 120 || d == null || !d.connected()) return;
        g = (Graphics2D) graphics.create(x, y, w, h);
        Theme.smooth(g);
        unit = h / 720f; // everything scales with the picture
        long now = System.currentTimeMillis();
        FcTelemetry t = d.telemetry();
        FcTelemetry.Attitude att = t == null ? null : t.getAttitude();
        boolean attFresh = att != null && now - att.receivedAt() < STALE_MS;
        FcTelemetry.Status status = t == null ? null : t.getStatus();
        boolean statusFresh = status != null && now - status.receivedAt() < STALE_MS * 2;

        if (attFresh) horizon(w, h, att, d.invertPitch());
        reticle(w, h);
        if (attFresh) headingTape(w, att, t.getGps());
        boolean fcFresh = !d.fcLost();
        battery(t, fcFresh);
        link(w, d, t);
        armState(h, statusFresh ? status : null, t);
        gps(w, h, t, attFresh ? att : null, fcFresh);
        warnings(w, h, d, statusFresh ? status : null, t, now);
        if (!d.linkReady() || d.fcLost()) lostFrame(w, h);
        debugLine(w, h, d.video());
        g.dispose();
    }

    // ------------------------------------------------------------------ centre

    private void reticle(int w, int h) {
        int cx = w / 2, cy = h / 2;
        float s = 26 * unit;
        Path2D p = new Path2D.Float(); // "W" aircraft symbol
        p.moveTo(cx - 2.2 * s, cy);
        p.lineTo(cx - s, cy);
        p.lineTo(cx - 0.5 * s, cy + 0.5 * s);
        p.lineTo(cx, cy);
        p.lineTo(cx + 0.5 * s, cy + 0.5 * s);
        p.lineTo(cx + s, cy);
        p.lineTo(cx + 2.2 * s, cy);
        stroke(p, ACCENT, 2.6f);
    }

    /**
     * Betaflight reports pitch positive nose-DOWN (its own OSD draws the horizon row at y - pitch, rows counting
     * down), so a positive pitch moves the horizon up. osdInvertPitch=true flips it if a setup shows otherwise.
     */
    private void horizon(int w, int h, FcTelemetry.Attitude att, boolean invertPitch) {
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

    private void headingTape(int w, FcTelemetry.Attitude att, FcTelemetry.Gps gps) {
        int cx = w / 2;
        float top = 16 * unit;
        float span = 360 * unit;        // pixels for the visible arc
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
        centered(hdg, cx, top + 14 * unit, ACCENT);
    }

    // ------------------------------------------------------------------ corners

    /** Per-cell voltage big (what the pilot flies by, whatever the pack), the pack itself on the line below. */
    private void battery(FcTelemetry t, boolean fresh) {
        FcTelemetry.Battery b = t == null ? null : t.getBattery();
        if (b == null || b.volts() <= 0) return;
        FcTelemetry.BatteryLimits lim = t.getBatteryLimits();
        double cell = b.cellVolts();
        double warn = lim != null && lim.warningCell() > 0 ? lim.warningCell() : 3.5;
        double min = lim != null && lim.minCell() > 0 ? lim.minCell() : 3.3;
        double max = lim != null && lim.maxCell() > 0 ? lim.maxCell() : 4.3;
        Color c = !fresh ? STALE : cell <= min ? RED : cell <= warn ? YELLOW : GREEN;
        float x = 18 * unit, y = 40 * unit;
        label("BATT " + b.cellCount() + "S" + (fresh ? "" : "  NO DATA"), x, y);
        g.setFont(Theme.monoFont(Font.BOLD, 30 * unit));
        String cellText = String.format(Locale.ROOT, "%.2fV", cell);
        text(cellText, x, y + 32 * unit, c);
        float suffixX = x + g.getFontMetrics().stringWidth(cellText) + 6 * unit;
        g.setFont(Theme.headingFont(14 * unit));
        text("/CELL", suffixX, y + 32 * unit, fresh ? WHITE : STALE);
        // charge bar from cell voltage
        float bw = 120 * unit, bh = 8 * unit, by = y + 42 * unit;
        double frac = Math.max(0, Math.min(1, (cell - min) / (max - min)));
        g.setColor(OUTLINE);
        g.fillRect(Math.round(x), Math.round(by), Math.round(bw), Math.round(bh));
        g.setColor(c);
        g.fillRect(Math.round(x), Math.round(by), Math.round((float) (bw * frac)), Math.round(bh));
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(Math.round(x), Math.round(by), Math.round(bw), Math.round(bh));
        g.setFont(Theme.monoFont(Font.PLAIN, 16 * unit));
        text(String.format(Locale.ROOT, "%.2fV  %dmAh  %.1fA", b.volts(), b.mahDrawn(), b.amps()), x, by + 26 * unit, fresh ? WHITE : STALE);
    }

    private void link(int w, Data d, FcTelemetry t) {
        float right = w - 18 * unit, y = 40 * unit;
        labelRight("LINK", right, y);
        g.setFont(Theme.monoFont(Font.BOLD, 22 * unit));
        Color pingColor = d.pingMs() < 0 ? RED : d.pingMs() > 150 ? RED : d.pingMs() > 80 ? YELLOW : GREEN;
        rightText(d.pingMs() < 0 ? "--- MS" : d.pingMs() + " MS", right, y + 26 * unit, pingColor);
        FcTelemetry.Network net = t == null ? null : t.getNetwork();
        float ly = y + 52 * unit;
        if (net != null) {
            g.setFont(Theme.monoFont(Font.BOLD, 16 * unit));
            String text = net.name() + " " + net.strengthPercent() + "%";
            rightText(text, right - 40 * unit, ly, WHITE);
            signalBars(right - 32 * unit, ly, net.strengthPercent());
            ly += 22 * unit;
        }
        g.setFont(Theme.monoFont(Font.PLAIN, 15 * unit));
        VideoPipeline.Stats v = d.video();
        if (v != null && v.width() > 0) {
            rightText(String.format(Locale.ROOT, "VID %dFPS %.1fMB", v.shownFps(), v.megabitsPerSecond()), right, ly, WHITE);
            ly += 20 * unit;
        }
        int phone = t == null ? -1 : t.getPhoneBatteryPercent();
        if (phone >= 0) {
            rightText("PHONE " + phone + "%" + (t.isPhoneCharging() ? " CHG" : ""), right, ly, phone < 20 ? RED : WHITE);
        }
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
        }
    }

    private void armState(int h, FcTelemetry.Status s, FcTelemetry t) {
        float x = 18 * unit, y = h - 92 * unit;
        if (s != null) {
            g.setFont(Theme.headingFont(28 * unit));
            text(s.armed() ? "ARMED" : "DISARMED", x, y, s.armed() ? GREEN : YELLOW);
            List<String> modes = new ArrayList<>(s.activeModes());
            modes.remove("ARM");
            if (!modes.isEmpty()) {
                g.setFont(Theme.headingFont(18 * unit));
                text(String.join("  ", modes), x, y + 26 * unit, WHITE);
            }
        }
        FcTelemetry.Timers timers = t == null ? null : t.getTimers();
        if (timers != null) {
            g.setFont(Theme.monoFont(Font.BOLD, 18 * unit));
            text(String.format(Locale.ROOT, "FLY %02d:%02d", timers.flySeconds() / 60, timers.flySeconds() % 60), x, y + 52 * unit, WHITE);
        }
    }

    private void gps(int w, int h, FcTelemetry t, FcTelemetry.Attitude att, boolean fresh) {
        if (t == null) return;
        float right = w - 18 * unit, y = h - 104 * unit;
        FcTelemetry.Altitude alt = t.getAltitude();
        FcTelemetry.Gps gps = t.getGps();
        g.setFont(Theme.monoFont(Font.BOLD, 18 * unit));
        if (gps != null) {
            Color c = !fresh ? STALE : gps.fix() >= 2 && gps.sats() >= 6 ? GREEN : gps.fix() >= 1 ? YELLOW : RED;
            rightText("SAT " + gps.sats() + (gps.fix() >= 2 ? " 3D" : gps.fix() == 1 ? " 2D" : " NO FIX"), right, y, c);
            rightText(String.format(Locale.ROOT, "SPD %3.0fKM/H", gps.speedKmh()), right, y + 22 * unit, fresh ? WHITE : STALE);
        }
        if (alt != null) {
            String vario = alt.varioMetersPerSecond() > 0.3 ? " ↑" : alt.varioMetersPerSecond() < -0.3 ? " ↓" : "";
            rightText(String.format(Locale.ROOT, "ALT %4.0fM%s", alt.meters(), vario), right, y + 44 * unit, fresh ? WHITE : STALE);
        }
        if (gps != null && gps.distanceHomeM() >= 0) {
            rightText(String.format(Locale.ROOT, "HOME %dM", gps.distanceHomeM()), right - 34 * unit, y + 70 * unit, fresh ? GREEN : STALE);
            if (att != null) homeArrow(right - 12 * unit, y + 64 * unit, gps.directionHomeDeg() - att.headingDeg());
        }
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
    }

    // ------------------------------------------------------------------ warnings

    private void warnings(int w, int h, Data d, FcTelemetry.Status s, FcTelemetry t, long now) {
        List<String> big = new ArrayList<>();
        if (!d.linkReady()) big.add("LINK LOST");
        if (d.fcLost()) big.add("NO FLIGHT CONTROLLER");
        if (s != null && s.blockers().stream().anyMatch(b -> b.osdName().equals("RXLOSS") || b.osdName().equals("FAILSAFE"))) big.add("FAILSAFE");
        if (d.linkReady() && !d.rcLive()) big.add("RC OFF - THROTTLE DOWN");
        FcTelemetry.Battery b = t == null ? null : t.getBattery();
        FcTelemetry.BatteryLimits lim = t == null ? null : t.getBatteryLimits();
        if (!d.fcLost() && b != null && b.volts() > 0
                && b.cellVolts() <= (lim != null && lim.warningCell() > 0 ? lim.warningCell() : 3.5)) big.add("LOW BATTERY");
        boolean blink = (now / 400) % 2 == 0;
        float y = h * 0.66f;
        g.setFont(Theme.headingFont(34 * unit));
        for (String text : big) {
            if (blink) centered(text, w / 2f, y, text.equals("RC OFF - THROTTLE DOWN") ? YELLOW : RED);
            y += 38 * unit;
        }
        if (d.fcLost()) { // steady, under the blinking title: what it means and where to look
            g.setFont(Theme.headingFont(18 * unit));
            centered("THE PHONE LOST ITS USB LINK TO THE FC - YOUR STICKS DON'T REACH THE DRONE", w / 2f, y + 4 * unit, YELLOW);
            centered("CHECK THE OTG CABLE AND THE FC'S POWER", w / 2f, y + 28 * unit, YELLOW);
            return;
        }
        if (s != null && !s.armed() && !s.blockers().isEmpty()) {
            g.setFont(Theme.headingFont(18 * unit));
            String names = s.blockers().stream().map(FcTelemetry.Blocker::osdName).reduce((a, c) -> a + " " + c).orElse("");
            centered("ARMING BLOCKED: " + names, w / 2f, y + 4 * unit, YELLOW);
        }
    }

    /** Steady red border: the sticks aren't reaching the drone. Seen even in the corner of your eye. */
    private void lostFrame(int w, int h) {
        float t = 7 * unit;
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(t + 4 * unit));
        g.drawRect(Math.round(t / 2), Math.round(t / 2), Math.round(w - t), Math.round(h - t));
        g.setColor(RED);
        g.setStroke(new BasicStroke(t));
        g.drawRect(Math.round(t / 2), Math.round(t / 2), Math.round(w - t), Math.round(h - t));
    }

    private void debugLine(int w, int h, VideoPipeline.Stats v) {
        if (v == null || v.width() == 0) return;
        String line = String.format(Locale.ROOT, "%s %dx%d  DEC %.1fMS  DRAW %.1fMS  DMG %d  LOST %d",
                v.hevc() ? "H265" : "H264", v.width(), v.height(), v.decodeMs(), v.convertMs(), v.damaged(), v.lost());
        g.setFont(Theme.monoFont(Font.PLAIN, 12 * unit));
        centered(line, w / 2f, h - 10 * unit, Theme.MUTED);
    }

    // ------------------------------------------------------------------ drawing helpers

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

    private void label(String text, float x, float y) {
        g.setFont(Theme.headingFont(14 * unit));
        text(text, x, y, ACCENT);
    }

    private void labelRight(String text, float right, float y) {
        g.setFont(Theme.headingFont(14 * unit));
        rightText(text, right, y, ACCENT);
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
    }

    private void rightText(String s, float right, float y, Color c) {
        text(s, right - g.getFontMetrics().stringWidth(s), y, c);
    }

    private void centered(String s, float cx, float y, Color c) {
        text(s, cx - g.getFontMetrics().stringWidth(s) / 2f, y, c);
    }
}
