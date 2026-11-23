package de.droiddrone.pc.gui;

import de.droiddrone.pc.air.AirParams;
import de.droiddrone.pc.air.GroupDesign;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;

/**
 * Fleet PRD FL-9: the group designer - one small dialog. Stacking and layer shape are the hollow cube's (shown, not
 * chosen); the pilot sets cell size, layer spacing, keep-out, leash, the distance kept from the lead, floor and ceiling,
 * and how many followers to check the layers for. The size check (AC-35) and the layer check (AC-13) run as the numbers
 * change; OK is refused while either fails.
 */
final class GroupDesignPanel extends JPanel {
    final JSpinner cell = spinner(20, 15, 60, 1), layer = spinner(10, 5, 30, 1), keepOut = spinner(20, 10, 100, 5),
            leash = spinner(120, 50, 500, 10), keep = spinner(100, 50, 400, 10), floor = spinner(40, 10, 300, 5),
            ceiling = spinner(120, 30, 500, 10), followers = spinner(8, 1, 40, 1);
    final JLabel check = Theme.label(" ", Theme.font(Font.BOLD, 12.5f), Theme.OK);
    private final AirParams base;

    GroupDesignPanel(AirParams now) {
        super(new BorderLayout(0, 10));
        base = now;
        setOpaque(false);
        JPanel form = new JPanel(new GridLayout(0, 2, 10, 5));
        form.setOpaque(false);
        row(form, "STACKING", fixed("Hollow cube, 8 a layer"));
        row(form, "LAYER SHAPE", fixed("Ring, 3 a side, north up"));
        row(form, "CELL (m)", cell);
        row(form, "LAYERS APART (m)", layer);
        row(form, "KEEP-OUT (m)", keepOut);
        row(form, "LEASH (m)", leash);
        row(form, "FROM THE LEAD (m)", keep);
        row(form, "FLOOR (m)", floor);
        row(form, "CEILING (m)", ceiling);
        row(form, "FOLLOWERS", followers);
        cell.setValue((int) Math.round(now.cellM()));
        layer.setValue((int) Math.round(now.layerM()));
        keepOut.setValue((int) Math.round(now.keepOutM()));
        leash.setValue((int) Math.round(now.leashM()));
        keep.setValue((int) Math.round(now.keepM()));
        floor.setValue((int) Math.round(now.floorM()));
        ceiling.setValue((int) Math.round(GroupDesign.ceiling(now)));
        for (JSpinner s : new JSpinner[]{cell, layer, keepOut, leash, keep, floor, ceiling, followers}) s.addChangeListener(e -> refresh());
        add(form, BorderLayout.CENTER);
        check.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        add(check, BorderLayout.SOUTH);
        refresh();
    }

    private static JSpinner spinner(int value, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, step));
    }

    private static JLabel fixed(String text) {
        return Theme.label(text, Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    }

    private static void row(JPanel form, String name, Component c) {
        form.add(Theme.label(name, Theme.headingFont(12f), Theme.MUTED));
        form.add(c);
    }

    private static double v(JSpinner s) {
        return ((Number) s.getValue()).doubleValue();
    }

    /** The settings as set now. */
    AirParams params() {
        return GroupDesign.with(base, v(cell), v(layer), v(keepOut), v(leash), v(keep), v(floor), v(ceiling));
    }

    /** What doesn't fit, or null. */
    String problems() {
        return GroupDesign.problems(params(), (int) v(followers), 0);
    }

    private void refresh() {
        String p = problems();
        check.setText(p == null ? "Fits." : "<html>" + p + "</html>");
        check.setForeground(p == null ? Theme.OK : Theme.BAD);
    }

    /** The dialog: the settings back on OK when they fit, null on CANCEL. */
    static AirParams show(Component parent, AirParams now) {
        GroupDesignPanel panel = new GroupDesignPanel(now);
        while (true) {
            if (Dialogs.confirm(parent, panel, "GROUP DESIGN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
            String p = panel.problems();
            if (p == null) return panel.params();
            Dialogs.message(parent, p, "GROUP DESIGN", JOptionPane.WARNING_MESSAGE);
        }
    }
}
