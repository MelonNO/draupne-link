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

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
