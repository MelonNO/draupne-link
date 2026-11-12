package de.droiddrone.pc.gui;

import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.PcConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MENU > LINK > STANAG 4609 settings (1.0.39): where the streams go, their mission id and country, and the camera's
 * angles - the view on the map and the stream's frame centre and corners come from them. 1.0.65: one stream per drone,
 * each on its own port from SEND TO's on; the list shows every drone's address and what its stream shows now, and the
 * pilot can give a drone an address of its own there (the pilot: "make it possible for the pilot to change the ip and
 * port").
 */
final class StanagDialog extends JDialog {
    static final String TARGET_KEY = "stanag.target", MISSION_KEY = "stanag.missionId", COUNTRY_KEY = "stanag.country",
            ENABLED_KEY = "stanag.enabled";
    static final String DEFAULT_TARGET = "127.0.0.1:15000";

    /** One drone's line: its address to type over, and what its stream shows. */
    private record Line(StanagController.Row row, JTextField field, JLabel showing, String initial) {}

    private final JTextField target = new JTextField(18);
    private final JTextField mission = new JTextField(18);
    private final JTextField country = new JTextField(4);
    private final JTextField hfov = new JTextField(5);
    private final JTextField tilt = new JTextField(5);
    private final JPanel table = new JPanel(new GridBagLayout());
    private final List<Line> lines = new ArrayList<>();
    private final JLabel warning = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.BAD);
    private final PcConfig config;
    private final Consumer<Map<String, String>> saver;
    private final Supplier<List<StanagController.Row>> rows;
    private final Supplier<String> clash;
    private final Timer refresh = new Timer(1000, e -> showStreams());

    /**
     * The address the streams go to, as host:port. An address written as a UDP URL (up to 1.0.64 a full URL was taken:
     * "udp://239.1.2.3:5003", with or without options behind a "?") is read as its host and port; another kind of
     * URL (rtp://, a file) or anything else unusable: the default.
     */
    static String target(PcConfig config) {
        String t = plain(config.getString(TARGET_KEY));
        return usable(t) ? t : DEFAULT_TARGET;
    }

    /** "udp://@239.1.2.3:5003?ttl=4" -> "239.1.2.3:5003"; anything else as it is, trimmed. */
    static String plain(String t) {
        String s = t == null ? "" : t.trim();
        if (s.regionMatches(true, 0, "udp://", 0, 6)) {
            s = s.substring(6);
            if (s.startsWith("@")) s = s.substring(1);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** host:port, any port: one drone's own address. */
    static boolean address(String t) {
        return t != null && t.matches("[^\\s:/]+:\\d{1,5}") && StanagController.portOf(t) >= 1;
    }

    /** host:port with room for a port per drone after it: SEND TO. */
    static boolean usable(String t) {
        return address(t) && StanagController.portOf(t) <= 65535 - 2 * StanagController.FIRST_FREE_SLOT;
    }

    static String country(PcConfig config) {
        String c = config.getString(COUNTRY_KEY).trim().toUpperCase(Locale.ROOT);
        return c.isEmpty() ? "NOR" : c;
    }

    /** @param saver gets the changed settings (saved, and the streams and the map follow them) */
    StanagDialog(JFrame owner, PcConfig config, Consumer<Map<String, String>> saver) {
        this(owner, config, saver, List::of, () -> null);
    }

    /**
     * @param rows every drone's stream: its address and what it shows (also while the streams are off)
     * @param clash the line to show while another PC streams to the same address, or null
     */
    StanagDialog(JFrame owner, PcConfig config, Consumer<Map<String, String>> saver, Supplier<List<StanagController.Row>> rows,
                 Supplier<String> clash) {
        super(owner, "STANAG 4609", ModalityType.MODELESS);
        this.config = config;
        this.saver = saver;
        this.rows = rows;
        this.clash = clash;
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(Theme.label("STANAG 4609 STREAMS", Theme.headingFont(14f), Theme.ACCENT), BorderLayout.NORTH);
        head.add(new WrappingText("One stream per drone, made on this PC: MPEG-TS with MISB ST 0601 metadata over UDP, each drone on "
                + "its own port. Video with OSD while piloted or observed here; an overwatch alert's picture and clip; else the "
                + "drone's mode. Plays in ATAK / WinTAK, QGIS, VLC (udp://@:PORT). Multicast (239.x.x.x) is local only. "
                + "Video uses this PC's upload: on a weak link it can delay the sticks.",
                Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        target.setText(target(config));
        mission.setText(config.getString(MISSION_KEY));
        country.setText(country(config));
        CameraView v = new CameraView(null);
        v.configure(config);
        hfov.setText(number(v.hfovDeg()));
        tilt.setText(number(v.tiltDeg()));
        row(form, 0, "SEND TO", target, "host:first port (127.0.0.1 = this PC)",
                "host:first port - one port per drone from it. A TAK device's Tailscale address, or 127.0.0.1 here");
        row(form, 1, "MISSION ID", mission, "Optional, shown by the player", null);
        row(form, 2, "COUNTRY", country, "Three letters. Marking: UNCLASSIFIED",
                "ISO 3166, three letters. Marking is always UNCLASSIFIED");
        row(form, 3, "CAMERA ANGLE ACROSS", hfov, "Degrees across (phone: ~70)",
                "Degrees across the picture (phone main camera: ~70)");
        row(form, 4, "CAMERA TILT UP", tilt, "Degrees up (0 = straight ahead)",
                "FPV uptilt in degrees (0 = straight ahead)");

        // every drone's address (to type over) and what its stream shows now
        table.setBackground(Theme.PANEL);
        table.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(100, 190));
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        JLabel listHead = Theme.label("STREAMS - type over an address to send that drone elsewhere; empty = automatic",
                Theme.headingFont(11f), Theme.MUTED);
        list.add(listHead, BorderLayout.NORTH);
        list.add(scroll, BorderLayout.CENTER);
        list.add(warning, BorderLayout.SOUTH);

        JPanel middle = new JPanel(new BorderLayout(0, 10));
        middle.setOpaque(false);
        middle.add(form, BorderLayout.NORTH);
        middle.add(list, BorderLayout.CENTER);
        root.add(middle, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(close);
        JButton save = Theme.primaryButton("Save");
        save.addActionListener(e -> save());
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);

        buildLines(rows.get());
        showStreams();
        setSize(new Dimension(720, 700));
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
    }

    @Override
    public void setVisible(boolean on) {
        if (on) refresh.start();
        else refresh.stop();
        super.setVisible(on);
    }

    @Override
    public void dispose() {
        refresh.stop();
        super.dispose();
    }

    /** A line per drone: its name, its address in a box, what its stream shows. */
    private void buildLines(List<StanagController.Row> now) {
        table.removeAll();
        lines.clear();
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 12);
        int y = 0;
        if (now == null || now.isEmpty()) {
            c.gridy = y++;
            table.add(Theme.label("No drones known yet.", Theme.font(Font.PLAIN, 12f), Theme.MUTED), c);
        } else {
            for (StanagController.Row r : now) {
                c.gridy = y++;
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                table.add(Theme.label(r.name(), Theme.monoFont(Font.PLAIN, 12f), Theme.TEXT), c);
                JTextField field = new JTextField();
                field.setFont(Theme.monoFont(Font.PLAIN, 12f));
                field.setColumns(26); // "255.255.255.255:65535" and room to type
                field.setText(r.target());
                field.setMinimumSize(field.getPreferredSize());
                field.setToolTipText("host:port for " + r.name() + ". Empty = automatic (SEND TO's port + its letter)");
                c.gridx = 1;
                table.add(field, c);
                JLabel showing = Theme.label(r.showing(), Theme.font(Font.PLAIN, 12f), Theme.MUTED);
                showing.setMinimumSize(new Dimension(0, showing.getPreferredSize().height));
                c.gridx = 2;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                table.add(showing, c);
                lines.add(new Line(r, field, showing, r.target()));
            }
        }
        c.gridy = y;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 0;
        c.weighty = 1;
        c.fill = GridBagConstraints.NONE;
        JPanel rest = new JPanel();
        rest.setOpaque(false);
        table.add(rest, c); // the lines stay at the top
        table.revalidate();
        table.repaint();
    }

    /** Once a second: what each stream shows; the boxes are the pilot's and are left alone. */
    private void showStreams() {
        List<StanagController.Row> now = rows.get();
        if (lines.isEmpty() && now != null && !now.isEmpty()) buildLines(now); // the first drones came while it was open
        Map<String, String> showing = new HashMap<>();
        if (now != null) for (StanagController.Row r : now) showing.put(r.name().toLowerCase(Locale.ROOT), r.showing());
        for (Line l : lines) {
            String s = showing.get(l.row().name().toLowerCase(Locale.ROOT));
            l.showing().setText(s == null ? "" : s);
        }
        String w = clash.get();
        warning.setText(w == null || w.isEmpty() ? " " : w);
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /**
     * A field never gives way: GridBagLayout puts every component at its minimum size once a row doesn't fit, and a
     * text field's minimum is 15 px - the long hints did that to all five (nothing could be typed). The hint gives way
     * instead (cut with "...", whole in its tooltip).
     */
    private static void row(JPanel form, int y, String name, JTextField field, String hint, String tip) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 0, 5, 12);
        form.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED), c);
        c.gridx = 1;
        field.setMinimumSize(field.getPreferredSize());
        form.add(field, c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel h = Theme.label(hint, Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        h.setToolTipText(tip == null ? hint : tip);
        h.setMinimumSize(new Dimension(0, h.getPreferredSize().height));
        form.add(h, c);
    }

    /**
     * What the drones' boxes mean for the settings: a drone's own address where the pilot typed one, nothing (the
     * automatic one) where a box is empty, untouched or says the automatic address anyway.
     * @param base SEND TO as it will be saved
     * @param typed each line's row and its box's text
     * @param had whether a drone has an address of its own in the settings now
     * @return the settings to write ("" takes a drone's own address away), or null with why[0] set
     */
    static Map<String, String> ownAddresses(String base, List<Map.Entry<StanagController.Row, String>> typed,
                                            java.util.function.Predicate<String> had, String[] why) {
        Map<String, String> out = new LinkedHashMap<>(), taken = new HashMap<>();
        for (Map.Entry<StanagController.Row, String> e : typed) {
            StanagController.Row r = e.getKey();
            String t = plain(e.getValue());
            String auto = StanagController.hostOf(base) + ":" + (StanagController.portOf(base) + r.slot());
            boolean untouched = t.equals(r.target());
            String to;
            if (t.isEmpty() || untouched && !r.own() || t.equals(auto)) {
                if (had.test(r.name())) out.put(StanagController.TO_KEY + r.name(), "");
                to = auto;
            } else if (!address(t)) {
                why[0] = r.name() + ": host:port, e.g. 239.1.2.3:5004. Empty = automatic.";
                return null;
            } else {
                out.put(StanagController.TO_KEY + r.name(), t);
                to = t;
            }
            String other = taken.put(to.toLowerCase(Locale.ROOT), r.name());
            if (other != null) {
                why[0] = other + " and " + r.name() + " have the same address (" + to + ").";
                return null;
            }
        }
        return out;
    }

    /** Checks the fields; a wrong one is said and nothing is saved. */
    private void save() {
        String t = plain(target.getText());
        if (!usable(t)) {
            // 1.0.65: no rtp:// or file any more - one address can't carry a stream per drone (udp://host:port is read as host:port)
            complain("SEND TO must be host:port, e.g. 100.64.0.5:15000. Each drone gets its own port from that one on.");
            return;
        }
        String cc = country.getText().trim().toUpperCase(Locale.ROOT);
        if (!cc.matches("[A-Z]{3}")) {
            complain("COUNTRY must be three letters, e.g. NOR.");
            return;
        }
        double h = CameraView.number(hfov.getText(), Double.NaN, 5, 170), u = CameraView.number(tilt.getText(), Double.NaN, -90, 90);
        if (Double.isNaN(h) || Double.isNaN(u)) {
            complain("Camera angle: 5..170 degrees. Tilt: -90..90.");
            return;
        }
        List<Map.Entry<StanagController.Row, String>> typed = new ArrayList<>();
        for (Line l : lines) typed.add(Map.entry(l.row(), l.field().getText()));
        String[] why = {null};
        Map<String, String> own = ownAddresses(t, typed, name -> !config.getString(StanagController.TO_KEY + name).isEmpty(), why);
        if (own == null) {
            complain(why[0]);
            return;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put(TARGET_KEY, t);
        m.put(MISSION_KEY, mission.getText().trim().replaceAll("[^\\x20-\\x7e]", ""));
        m.put(COUNTRY_KEY, cc);
        m.put(CameraView.HFOV_KEY, number(h));
        m.put(CameraView.TILT_KEY, number(u));
        m.putAll(own);
        saver.accept(m);
        dispose();
    }

    private void complain(String what) {
        Dialogs.message(this, what, "STANAG 4609", JOptionPane.WARNING_MESSAGE);
    }
}
