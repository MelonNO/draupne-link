package de.droiddrone.pc.gui;

import de.droiddrone.pc.route.Route;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.GridLayout;

/**
 * Fleet PRD RT-15: a route's settings, saved with it - HEIGHT above ground (starts at 60 m), SMOOTHING (how far above
 * that the line may run to save waypoints, 5 m), and the selected point's own height (SAME = the route's). Edits the
 * {@link Route} it shows and tells its {@link Listener}, on the Swing thread. Climb and descent limits are not here:
 * they come from the drone's own speed parameters (RT-4).
 */
public final class RouteSettingsPanel extends JPanel {
    /** What the pilot changed. */
    public interface Listener {
        void heightChanged(double heightM);

        void smoothingChanged(double smoothingM);

        /** heightM NaN: the point takes the route's height again. */
        void pointHeightChanged(int index, double heightM);
    }

    private final JSpinner height = new JSpinner(new SpinnerNumberModel(Route.DEFAULT_HEIGHT_M, Route.MIN_HEIGHT_M, 500, 5));
    private final JSpinner smoothing = new JSpinner(new SpinnerNumberModel(Route.DEFAULT_SMOOTHING_M, 0, 50, 1));
    private final JSpinner pointHeight = new JSpinner(new SpinnerNumberModel(Route.DEFAULT_HEIGHT_M, Route.MIN_HEIGHT_M, 500, 5));
    private final JLabel pointLabel = Theme.label("POINT -", Theme.headingFont(11f), Theme.MUTED);
    private final JButton same = Theme.secondaryButton("SAME");
    private Route route;
    private int point = -1;
    private Listener listener;
    private boolean filling;

    public RouteSettingsPanel() {
        super(new GridLayout(3, 1, 0, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        height.setToolTipText("Height above bare ground. Raise it over forest and houses.");
        smoothing.setToolTipText("How far above the height the line may run, to need fewer waypoints.");
        pointHeight.setToolTipText("This point's own height above ground.");
        same.setToolTipText("This point flies at the route's height.");
        add(row(Theme.label("HEIGHT M", Theme.headingFont(11f), Theme.TEXT), height));
        add(row(Theme.label("SMOOTHING M", Theme.headingFont(11f), Theme.TEXT), smoothing));
        add(row(pointLabel, pointHeight, same));
        height.addChangeListener(e -> {
            if (filling || route == null) return;
            route.setHeightM(value(height));
            if (listener != null) listener.heightChanged(route.heightM());
            fill();
        });
        smoothing.addChangeListener(e -> {
            if (filling || route == null) return;
            route.setSmoothingM(value(smoothing));
            if (listener != null) listener.smoothingChanged(route.smoothingM());
        });
        pointHeight.addChangeListener(e -> {
            if (filling || route == null || point < 0 || point >= route.size()) return;
            route.setOwnHeight(point, value(pointHeight));
            if (listener != null) listener.pointHeightChanged(point, route.point(point).heightM());
            fill();
        });
        same.addActionListener(e -> {
            if (route == null || point < 0 || point >= route.size()) return;
            route.setOwnHeight(point, Double.NaN);
            if (listener != null) listener.pointHeightChanged(point, Double.NaN);
            fill();
        });
        fill();
    }

    private static JPanel row(java.awt.Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        for (java.awt.Component c : parts) p.add(c);
        return p;
    }

    private static double value(JSpinner s) {
        return ((Number) s.getValue()).doubleValue();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** The route these settings edit (null: none - all off). */
    public void setRoute(Route r) {
        route = r;
        point = -1;
        fill();
    }

    /** The point selected on the map (0-based; -1 none). */
    public void selectPoint(int index) {
        point = index;
        fill();
    }

    private void fill() {
        filling = true;
        try {
            boolean on = route != null;
            height.setEnabled(on);
            smoothing.setEnabled(on);
            if (on) {
                height.setValue(route.heightM());
                smoothing.setValue(route.smoothingM());
            }
            boolean pt = on && point >= 0 && point < route.size();
            pointLabel.setText(pt ? "POINT " + (point + 1) : "POINT -");
            pointHeight.setEnabled(pt);
            same.setEnabled(pt && route.point(point).hasOwnHeight());
            if (pt) pointHeight.setValue(route.heightAt(point));
        } finally {
            filling = false;
        }
    }
}
