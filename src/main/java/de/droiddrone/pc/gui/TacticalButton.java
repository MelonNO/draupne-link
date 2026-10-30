package de.droiddrone.pc.gui;

import javax.swing.JButton;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The app's buttons, with a military feel: on hover, corner brackets close in on the button; on click, a short flash,
 * like a target being locked. With {@link #setShowLocked}, a disabled button looks locked: grey frame, a padlock and
 * hazard stripes drifting across it, and a click on it shakes it with a red flash. The timers only run while something
 * animates.
 */
class TacticalButton extends JButton {
    private static final long HOVER_MS = 140;
    private static final long FLASH_MS = 220;
    private static final long DENIED_MS = 380;

    private final boolean primary;
    private float hover;          // 0..1, eases toward hoverTarget
    private boolean hoverTarget;
    private long flashStart;      // 0 = no flash running
    private long deniedStart;     // 0 = no "locked" shake running
    private long lastTick;
    private boolean showLocked;
    private final Timer timer = new Timer(15, e -> tick());
    /** Slow, so a locked button costs next to nothing while flying (it is locked while connected). */
    private final Timer lockedTimer = new Timer(66, e -> lockedTick());

    TacticalButton(String text, boolean primary) {
        super(text);
        this.primary = primary;
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!isEnabled()) return;
                hoverTarget = true;
                start();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverTarget = false;
                start();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled()) {
                    if (showLocked) {
                        deniedStart = System.currentTimeMillis();
                        start();
                    }
                    return;
                }
                flashStart = System.currentTimeMillis();
                start();
            }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateLockedTimer();
        });
    }

    /** Disabled = visibly locked (for buttons the pilot may wonder about, like CALIBRATE while connected). */
    void setShowLocked(boolean showLocked) {
        this.showLocked = showLocked;
        updateLockedTimer();
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateLockedTimer();
    }

    private void updateLockedTimer() {
        if (lockedTimer == null) return; // called by JButton's constructor
        if (showLocked && !isEnabled() && isShowing()) lockedTimer.start();
        else lockedTimer.stop();
    }

    private void lockedTick() {
        if (showLocked && !isEnabled() && isShowing()) repaint();
        else lockedTimer.stop();
    }

    private void start() {
        lastTick = System.currentTimeMillis();
        if (!timer.isRunning()) timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float step = (now - lastTick) / (float) HOVER_MS;
        lastTick = now;
        hover = hoverTarget ? Math.min(1, hover + step) : Math.max(0, hover - step);
        if (flashStart != 0 && now - flashStart > FLASH_MS) flashStart = 0;
        if (deniedStart != 0 && now - deniedStart > DENIED_MS) deniedStart = 0;
        repaint();
        boolean settled = (hoverTarget ? hover >= 1 : hover <= 0) && flashStart == 0 && deniedStart == 0;
        if (settled) timer.stop();
    }

    @Override
    public void paint(Graphics graphics) {
        if (deniedStart == 0) {
            super.paint(graphics);
            return;
        }
        // "no": a short sideways shake, dying out
        float t = Math.min(1, (System.currentTimeMillis() - deniedStart) / (float) DENIED_MS);
        int dx = Math.round((float) Math.sin(t * Math.PI * 6) * 4 * (1 - t));
        graphics.setColor(background(getParent()));
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.translate(dx, 0);
        super.paint(graphics);
        graphics.translate(-dx, 0);
    }

    /** Locked: a grey frame instead of the red one (red for a moment when clicked). */
    @Override
    protected void paintBorder(Graphics graphics) {
        if (isEnabled() || !showLocked) {
            super.paintBorder(graphics);
            return;
        }
        float denied = deniedStart == 0 ? 0 : 1 - Math.min(1, (System.currentTimeMillis() - deniedStart) / (float) DENIED_MS);
        graphics.setColor(denied > 0 ? Theme.alpha(Theme.ACCENT, Math.round(120 + 135 * denied)) : Theme.MUTED.darker());
        graphics.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
    }

    private static Color background(Component c) {
        while (c != null && !c.isOpaque()) c = c.getParent();
        return c == null ? Theme.BG : c.getBackground();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!isEnabled()) {
            if (showLocked) paintLocked((Graphics2D) graphics);
            return;
        }
        if (hover <= 0 && flashStart == 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int w = getWidth(), h = getHeight();
            float ease = 1 - (1 - hover) * (1 - hover);
            Color mark = primary ? Color.WHITE : Theme.ACCENT;
            // corner brackets closing in
            if (ease > 0) {
                int inset = Math.round(5 - 2 * ease);
                int arm = Math.round(3 + 7 * ease);
                g.setComposite(AlphaComposite.SrcOver.derive(0.35f + 0.65f * ease));
                g.setColor(mark);
                g.setStroke(new BasicStroke(2f));
                int r = w - 1 - inset, b = h - 1 - inset;
                g.drawLine(inset, inset, inset + arm, inset);
                g.drawLine(inset, inset, inset, inset + arm);
                g.drawLine(r, inset, r - arm, inset);
                g.drawLine(r, inset, r, inset + arm);
                g.drawLine(inset, b, inset + arm, b);
                g.drawLine(inset, b, inset, b - arm);
                g.drawLine(r, b, r - arm, b);
                g.drawLine(r, b, r, b - arm);
            }
            // lock-on flash, on click
            if (flashStart != 0) {
                float t = Math.min(1, (System.currentTimeMillis() - flashStart) / (float) FLASH_MS);
                g.setComposite(AlphaComposite.SrcOver.derive((1 - t) * (primary ? 0.45f : 0.35f)));
                g.setColor(primary ? Color.WHITE : Theme.ACCENT);
                g.fillRect(0, 0, w, h);
            }
        } finally {
            g.dispose();
        }
    }

    /** Locked: hazard stripes drifting across and a padlock; red for a moment when clicked. */
    private void paintLocked(Graphics2D graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            long now = System.currentTimeMillis();
            float denied = deniedStart == 0 ? 0 : 1 - Math.min(1, (now - deniedStart) / (float) DENIED_MS);
            Color tone = denied > 0 ? Theme.ACCENT : Theme.MUTED;
            g.clipRect(1, 1, w - 2, h - 2);
            g.setColor(Theme.alpha(tone, Math.round(38 + 90 * denied)));
            g.setStroke(new BasicStroke(3f));
            int gap = 12, shift = (int) (now / 70 % gap);
            for (int x = -h + shift; x < w + h; x += gap) g.drawLine(x, h, x + h, 0);
            g.setClip(null);
            // padlock in the left padding
            int lx = 4, ly = h / 2 - 1;
            g.setColor(denied > 0 ? Theme.ACCENT : Theme.MUTED);
            g.setStroke(new BasicStroke(1.5f));
            g.drawArc(lx + 1, ly - 6, 6, 8, 0, 180);
            g.drawLine(lx + 1, ly - 2, lx + 1, ly);
            g.drawLine(lx + 7, ly - 2, lx + 7, ly);
            g.fillRect(lx, ly, 9, 7);
        } finally {
            g.dispose();
        }
    }
}
