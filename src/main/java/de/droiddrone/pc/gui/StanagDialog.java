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
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MENU > LINK > STANAG 4609 settings (1.0.39): where the stream goes, its mission id and country, and the camera's
 * angles - the view on the map and the stream's frame centre and corners come from them.
 */
final class StanagDialog extends JDialog {
    static final String TARGET_KEY = "stanag.target", MISSION_KEY = "stanag.missionId", COUNTRY_KEY = "stanag.country",
            ENABLED_KEY = "stanag.enabled";
    static final String DEFAULT_TARGET = "127.0.0.1:15000";

    private final JTextField target = new JTextField(18);
    private final JTextField mission = new JTextField(18);
    private final JTextField country = new JTextField(4);
    private final JTextField hfov = new JTextField(5);
    private final JTextField tilt = new JTextField(5);
    private final Consumer<Map<String, String>> saver;

    static String target(PcConfig config) {
        String t = config.getString(TARGET_KEY).trim();
        return t.isEmpty() ? DEFAULT_TARGET : t;
    }

    static String country(PcConfig config) {
        String c = config.getString(COUNTRY_KEY).trim().toUpperCase(Locale.ROOT);
        return c.isEmpty() ? "NOR" : c;
    }

    /** @param saver gets the changed settings (saved, and the stream and the map follow them) */
    StanagDialog(JFrame owner, PcConfig config, Consumer<Map<String, String>> saver) {
        super(owner, "STANAG 4609", ModalityType.MODELESS);
        this.saver = saver;
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(Theme.label("STANAG 4609 STREAM", Theme.headingFont(14f), Theme.ACCENT), BorderLayout.NORTH);
        head.add(new WrappingText("The phone's video as MPEG-TS with MISB ST 0601 metadata, over UDP to one address. "
                + "Plays in ATAK / WinTAK, QGIS, VLC (udp://@:PORT). Multicast (239.x.x.x) is local only; Tailscale is unicast. "
                + "Uses this PC's upload: on a weak link it can delay the sticks.",
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
        row(form, 0, "SEND TO", target, "host:port - a TAK device's Tailscale address, or 127.0.0.1 here");
        row(form, 1, "MISSION ID", mission, "Optional, shown by the player");
        row(form, 2, "COUNTRY", country, "ISO 3166, three letters. Marking is always UNCLASSIFIED");
        row(form, 3, "CAMERA ANGLE ACROSS", hfov, "Degrees across the picture (phone main camera: ~70)");
        row(form, 4, "CAMERA TILT UP", tilt, "FPV uptilt in degrees (0 = straight ahead)");
        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(close);
        JButton save = Theme.primaryButton("Save");
        save.addActionListener(e -> save());
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);

        setSize(new Dimension(720, 440));
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static void row(JPanel form, int y, String name, JTextField field, String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 0, 5, 12);
        form.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED), c);
        c.gridx = 1;
        form.add(field, c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel h = Theme.label(hint, Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        h.setToolTipText(hint);
        form.add(h, c);
    }

    /** Checks the fields; a wrong one is said and nothing is saved. */
    private void save() {
        String t = target.getText().trim();
        if (!t.contains("://") && !t.matches("[^\\s:]+:\\d{1,5}")) {
            complain("SEND TO must be host:port, e.g. 100.64.0.5:15000.");
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
        Map<String, String> m = new LinkedHashMap<>();
        m.put(TARGET_KEY, t);
        m.put(MISSION_KEY, mission.getText().trim().replaceAll("[^\\x20-\\x7e]", ""));
        m.put(COUNTRY_KEY, cc);
        m.put(CameraView.HFOV_KEY, number(h));
        m.put(CameraView.TILT_KEY, number(u));
        saver.accept(m);
        dispose();
    }

    private void complain(String what) {
        Dialogs.message(this, what, "STANAG 4609", JOptionPane.WARNING_MESSAGE);
    }
}
