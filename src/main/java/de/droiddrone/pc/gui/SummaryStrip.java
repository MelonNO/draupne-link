package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightSummary;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.List;

/**
 * PREVIOUS FLIGHTS' summary of the chosen flight ({@link FlightSummary}): its figures side by side, each a small
 * heading over its value, there without playing anything. A figure the flight's files don't tell is written as the
 * summary gives it ("-"), dimmed. One row where the window is wide enough; on a narrower one two rows of five
 * (three rows on a very narrow one) - each column as wide as its widest figure, the room left over shared
 * out between them.
 */
final class SummaryStrip extends JPanel {
    /** The headings before a flight is chosen (a flight's own say which altitude and which voltage). */
    private static final String[] NAMES = {"DURATION", "ARMED", "DISTANCE", "FURTHEST", "MAX ALT", "MIN CELL", "LINK LOSSES", "FAILSAFES", "WORST PING", "CLOSEST"};
    /** The columns tried, widest first. */
    private static final int[] COLUMNS = {NAMES.length, 5, 3};
    static final int GAP = 14, ROW_GAP = 6;

    final JLabel[] labels = new JLabel[NAMES.length], values = new JLabel[NAMES.length];
    private final JPanel[] cells = new JPanel[NAMES.length];

    SummaryStrip() {
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE), BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        setLayout(new Rows());
        for (int i = 0; i < NAMES.length; i++) {
            cells[i] = new JPanel(new BorderLayout());
            cells[i].setOpaque(false);
            labels[i] = Theme.label(NAMES[i], Theme.headingFont(10.5f), Theme.MUTED);
            values[i] = Theme.label(FlightSummary.UNKNOWN, Theme.monoFont(Font.BOLD, 13.5f), Theme.MUTED);
            cells[i].add(labels[i], BorderLayout.NORTH);
            cells[i].add(values[i], BorderLayout.CENTER);
            add(cells[i]);
        }
    }

    /** The flight's figures; null: none chosen (every figure unknown). */
    void show(FlightSummary summary) {
        List<FlightSummary.Figure> figures = summary == null ? null : summary.figures();
        for (int i = 0; i < NAMES.length; i++) {
            FlightSummary.Figure f = figures != null && i < figures.size() ? figures.get(i) : null;
            labels[i].setText(f == null ? NAMES[i] : f.label());
            values[i].setText(f == null ? FlightSummary.UNKNOWN : f.value());
            values[i].setForeground(f != null && f.known() ? Theme.TEXT : Theme.MUTED); // not known: dimmed
            cells[i].setToolTipText(null);
        }
        // which altitude the highest one is: its heading says it short, the tooltip in full
        if (summary != null && summary.maxAltKind != null) cells[4].setToolTipText(summary.maxAltLabel() + ": " + summary.maxAltKind.meaning);
        // AC-14: which drone came that near, sideways and in height
        if (summary != null && summary.closest != null) cells[9].setToolTipText("Closest other drone: " + summary.closest.text());
        revalidate();
        repaint();
    }

    /** The rows it takes at this width (its own, borders and all). */
    int rowsAt(int width) {
        Insets in = getInsets();
        return rows(columns(width - in.left - in.right));
    }

    private static int rows(int columns) {
        return (NAMES.length + columns - 1) / columns;
    }

    /** Each column's width with n figures to a row: its widest figure's. */
    private int[] widths(int n) {
        int[] w = new int[n];
        for (int i = 0; i < cells.length; i++) w[i % n] = Math.max(w[i % n], cells[i].getPreferredSize().width);
        return w;
    }

    private static int total(int[] widths) {
        int sum = GAP * (widths.length - 1);
        for (int w : widths) sum += w;
        return sum;
    }

    /** The most figures to a row that fit this width; the fewest when none does. */
    private int columns(int width) {
        for (int n : COLUMNS) if (total(widths(n)) <= width) return n;
        return COLUMNS[COLUMNS.length - 1];
    }

    private int cellHeight() {
        int h = 0;
        for (JPanel c : cells) h = Math.max(h, c.getPreferredSize().height);
        return h;
    }

    private final class Rows implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        /** As high as its rows at the width it has (BorderLayout gives the width first, then asks); one row before it has one. */
        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int n = parent.getWidth() > 0 ? columns(parent.getWidth() - in.left - in.right) : COLUMNS[0];
            int rows = rows(n);
            return new Dimension(total(widths(COLUMNS[0])) + in.left + in.right, rows * cellHeight() + (rows - 1) * ROW_GAP + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int n = COLUMNS[COLUMNS.length - 1], rows = rows(n);
            return new Dimension(total(widths(n)) + in.left + in.right, rows * cellHeight() + (rows - 1) * ROW_GAP + in.top + in.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int width = parent.getWidth() - in.left - in.right;
            int n = columns(width);
            int[] w = widths(n);
            int spare = width - total(w), h = cellHeight();
            if (spare < 0) { // narrower than three figures side by side: each column gives way alike, nothing runs out
                for (int c = 0; c < n; c++) w[c] = Math.max(0, w[c] + spare / n);
                spare = 0;
            }
            int[] x = new int[n];
            for (int c = 0, at = in.left; c < n; c++) {
                x[c] = at;
                w[c] += spare / n; // the room left over, shared out
                at += w[c] + GAP;
            }
            for (int i = 0; i < cells.length; i++) cells[i].setBounds(x[i % n], in.top + (i / n) * (h + ROW_GAP), w[i % n], h);
        }
    }
}
