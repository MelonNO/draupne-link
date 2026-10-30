package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.Map;

/** SETTINGS: options that change how the app behaves. Each one applies at once and is saved in the settings file. */
final class SettingsDialog extends JDialog {
    static final String UPSIDE_DOWN_KEY = "videoUpsideDown";

    private SettingsDialog(MainWindow main, VideoView video) {
        super(main, "Settings", ModalityType.APPLICATION_MODAL);
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        setContentPane(root);
        root.add(Theme.label("SETTINGS", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);

        JPanel card = Theme.card(null);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.add(left(Theme.sectionTitle("Video")));
        JCheckBox upsideDown = checkBox("<b>Camera mounted upside down</b><br><span style='color:" + Theme.MUTED_HEX
                + "'>Turns the picture 180&deg;. The OSD stays the right way up. No extra delay.</span>");
        upsideDown.setSelected(video.isUpsideDown());
        upsideDown.addActionListener(e -> {
            video.setUpsideDown(upsideDown.isSelected());
            main.save(Map.of(UPSIDE_DOWN_KEY, Boolean.toString(upsideDown.isSelected())));
            main.logLine(upsideDown.isSelected() ? "Picture turned upside down." : "Picture the right way up.");
        });
        card.add(left(upsideDown));
        card.add(left(spacer()));
        card.add(left(Theme.sectionTitle("OSD")));
        card.add(left(Theme.label("<html><span style='color:" + Theme.MUTED_HEX + "'>Choose which elements the OSD shows and drag "
                + "them where you want them, like Betaflight's OSD tab. F2 still turns the whole OSD on or off.</span></html>",
                Theme.font(Font.PLAIN, 14f), Theme.TEXT)));
        JButton osd = Theme.secondaryButton("Edit OSD");
        osd.addActionListener(e -> OsdSettingsDialog.open(this, main));
        JPanel osdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
        osdRow.setOpaque(false);
        osdRow.add(osd);
        card.add(left(osdRow));
        card.add(left(spacer()));
        card.add(left(Theme.sectionTitle("Motors")));
        boolean linked = main.isLinkActive();
        card.add(left(Theme.label("<html><span style='color:" + Theme.MUTED_HEX + "'>" + (linked
                ? "Disconnect from the drone first: the motor test runs over this PC's USB, with the drone on the bench."
                : "Spin each motor, like Betaflight's motor test, to check it is in the right place and turns the right way "
                + "- and so which way round the props go.") + "</span></html>", Theme.font(Font.PLAIN, 14f), Theme.TEXT)));
        JButton motors = Theme.secondaryButton("Motor test");
        motors.setEnabled(!linked);
        motors.addActionListener(e -> MotorTestDialog.open(this, main));
        JPanel motorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
        motorRow.setOpaque(false);
        motorRow.add(motors);
        card.add(left(motorRow));
        card.add(left(spacer()));
        card.add(left(Theme.sectionTitle("About")));
        card.add(left(aboutLine("DroidDrone PC", de.droiddrone.pc.AppInfo.version())));
        card.add(left(aboutLine("Phone app it installs", "DroidDrone Flight " + de.droiddrone.pc.setup.ApkShare.VERSION)));
        card.add(left(aboutLine("Flight app protocol", Integer.toString(de.droiddrone.common.SettingsCommon.versionCompatibleCode)
                + " (DroidDrone 2.3.0)")));
        root.add(card, BorderLayout.CENTER);

        JButton close = Theme.primaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(close);
        pack();
        setSize(Math.max(getWidth(), 520), getHeight());
        setLocationRelativeTo(main);
    }

    static void open(MainWindow main, VideoView video) {
        new SettingsDialog(main, video).setVisible(true);
    }

    private static JCheckBox checkBox(String html) {
        JCheckBox box = new JCheckBox("<html>" + html + "</html>");
        box.setFont(Theme.font(Font.PLAIN, 14f));
        box.setForeground(Theme.TEXT);
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
        return box;
    }

    /** "NAME   value" - the phone app shows its own version at the bottom right of its screen. */
    private static JComponent aboutLine(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        row.add(Theme.label(name.toUpperCase(), Theme.headingFont(11f), Theme.MUTED), BorderLayout.WEST);
        row.add(Theme.label(value, Theme.font(Font.BOLD, 13f), Theme.TEXT), BorderLayout.EAST);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static JComponent spacer() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new java.awt.Dimension(1, 14));
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 14));
        return p;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
