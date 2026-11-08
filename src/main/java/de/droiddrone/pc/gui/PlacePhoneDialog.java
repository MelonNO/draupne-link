package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.TileCache;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The virtual fleet's PLACE PHONE: a map with every virtual drone on it; a click puts the chosen one there, on the
 * ground (disarmed only - an armed one stays where it flies). Click again to move it again; DONE closes.
 */
final class PlacePhoneDialog extends JDialog {
    private static TileCache cache; // one for the fleet program's maps, made the first time one opens

    private final Fleet fleet;
    private final VirtualDrone drone;
    private final Consumer<String> log;
    private final MapView map;
    private final JLabel status = Theme.label(" ", Theme.font(Font.PLAIN, 12.5f), Theme.TEXT);
    private final Timer refresh = new Timer(250, e -> showDrones());

    static void open(JFrame owner, Fleet fleet, VirtualDrone drone, Consumer<String> log) {
        PlacePhoneDialog d = new PlacePhoneDialog(owner, fleet, drone, log);
        d.setVisible(true);
    }

    private PlacePhoneDialog(JFrame owner, Fleet fleet, VirtualDrone drone, Consumer<String> log) {
        super(owner, "Place " + drone.name(), ModalityType.MODELESS);
        this.fleet = fleet;
        this.drone = drone;
        this.log = log;
        if (owner != null) setIconImages(owner.getIconImages());
        if (cache == null) cache = new TileCache(TileCache.defaultDir());
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        setContentPane(root);

        JPanel head = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
        head.setOpaque(false);
        head.add(Theme.sectionTitle("Place " + drone.name()));
        head.add(Theme.label("Click where " + drone.name() + " should stand. Drag pans, wheel zooms.",
                Theme.font(Font.PLAIN, 12.5f), Theme.TEXT));
        head.add(status);
        root.add(head, BorderLayout.NORTH);

        map = new MapView(cache);
        map.setPreferredSize(new Dimension(820, 560));
        double[] at = drone.position();
        map.centreOn(at[0], at[1], 16);
        map.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) place(map.toLatLon(e.getX(), e.getY()));
            }
        });
        root.add(map, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton done = Theme.primaryButton("Done");
        done.addActionListener(e -> dispose());
        buttons.add(done);
        root.add(buttons, BorderLayout.SOUTH);

        status.setText("Now at " + Coordinates.mgrs(at[0], at[1], 5) + ".");
        showDrones();
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        refresh.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                refresh.stop();
            }
        });
    }

    private void place(double[] ll) {
        if (ll == null) return;
        String where = Coordinates.mgrs(ll[0], ll[1], 5);
        if (drone.isArmed() || !drone.moveTo(ll[0], ll[1])) {
            status.setText(drone.name() + " is armed: disarm first.");
            status.setForeground(Theme.BAD);
            return;
        }
        status.setText(drone.name() + " placed at " + where + ". Click to move, or DONE.");
        status.setForeground(Theme.TEXT);
        log.accept(drone.name() + ": placed at " + where);
        showDrones();
    }

    /** Every virtual drone as a dot on the map, the one being placed marked as such. */
    private void showDrones() {
        List<MapView.NetDrone> dots = new ArrayList<>();
        for (VirtualDrone d : fleet.drones()) {
            double[] p = d.position();
            String state = (d == drone ? "PLACING - " : "") + (d.isArmed() ? "ARMED" : "on the ground");
            dots.add(new MapView.NetDrone(d.name(), p[0], p[1], state, MapView.Kind.ACTIVE, false, null));
        }
        map.setNetDrones(dots, (name, watch) -> { });
        map.repaint();
    }
}
