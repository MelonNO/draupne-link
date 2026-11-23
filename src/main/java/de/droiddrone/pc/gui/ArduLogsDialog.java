package de.droiddrone.pc.gui;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.setup.ardu.ArduLogs;
import de.droiddrone.pc.setup.ardu.ArduUsb;
import de.droiddrone.pc.setup.ardu.MavSession;
import de.droiddrone.pc.setup.ardu.TunnelWire;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

/**
 * Fleet PRD AP-10: SETUP > ArduPilot flight logs - the drone's own logs, after landing: over the link (the ArduPilot
 * drone this PC flies, disarmed: the phone passes log requests only then) or over USB on the bench. A chosen log is
 * downloaded whole and kept where PREVIOUS FLIGHTS finds it (FC LOG). Slow over the link: a big log takes minutes.
 */
final class ArduLogsDialog extends JDialog {
    private final DefaultListModel<ArduLogs.Entry> entries = new DefaultListModel<>();
    private final JList<ArduLogs.Entry> list = new JList<>(entries);
    private final JLabel status = Theme.label(" ", Theme.font(Font.BOLD, 13f), Theme.MUTED);
    private final JButton download = Theme.primaryButton("DOWNLOAD");
    /** The session the logs come over (set on the list's thread, closed on Swing's). */
    private volatile MavSession fc;
    private volatile String drone;
    /** The window is closed: a session that comes after is let go at once (it held the board's USB port till the app ended). */
    private volatile boolean closed;

    static void open(MainWindow main) {
        ArduLogsDialog d = new ArduLogsDialog(main, main.flownLink(), main.flownPhoneName());
        d.setVisible(true);
    }

    /** @param link the ArduPilot drone this PC flies (null: none - USB then); @param name its name */
    ArduLogsDialog(java.awt.Window owner, ControlLink link, String name) {
        super(owner, "ArduPilot flight logs", ModalityType.MODELESS);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(Theme.PANEL);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        list.setCellRenderer((l, e, i, sel, focus) -> {
            JLabel t = Theme.label(e.word(), Theme.font(Font.PLAIN, 13f), sel ? Theme.TEXT : Theme.MUTED);
            t.setOpaque(sel);
            t.setBackground(Theme.PANEL_2);
            return t;
        });
        root.add(status, BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(download);
        root.add(row, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(460, 320);
        setLocationRelativeTo(owner);
        download.setEnabled(false);
        download.addActionListener(e -> download());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                letGo();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        status.setText("Asking the drone for its logs ...");
        Thread t = new Thread(() -> connect(link, name), "arduLogsList");
        t.setDaemon(true);
        t.start();
    }

    private void connect(ControlLink link, String name) {
        try {
            boolean viaLink = link != null && link.getFcInfo() != null
                    && link.getFcInfo().getFcVariant() == de.droiddrone.common.FcInfo.FC_VARIANT_ARDUPILOT
                    && link.getTelemetry().isDisarmedNow(de.droiddrone.pc.MonoClock.ms(), 3000);
            MavSession s;
            if (viaLink) {
                s = new MavSession(new TunnelWire(link), false);
                drone = name == null ? "drone" : name;
            } else {
                ArduUsb.Found f = ArduUsb.find();
                if (f == null) {
                    say("No landed ArduPilot drone on the link, and none on USB.");
                    return;
                }
                s = f.session();
                drone = "USB " + f.port().systemName();
            }
            fc = s;
            if (closed) { // closed while the board was looked for
                letGo();
                return;
            }
            List<ArduLogs.Entry> l = ArduLogs.list(s, 8000);
            SwingUtilities.invokeLater(() -> {
                for (ArduLogs.Entry e : l) entries.addElement(e);
                if (!l.isEmpty()) list.setSelectedIndex(l.size() - 1);
                download.setEnabled(!l.isEmpty());
                status.setText(l.isEmpty() ? "No logs on " + drone + "." : l.size() + " logs on " + drone + (viaLink ? " (over the link: slow)" : ""));
            });
        } catch (Exception e) {
            say("Logs: " + e.getMessage());
        }
    }

    private void download() {
        ArduLogs.Entry e = list.getSelectedValue();
        if (e == null || fc == null) return;
        download.setEnabled(false);
        Thread t = new Thread(() -> {
            try {
                byte[] data = ArduLogs.download(fc, e, pct -> say("Downloading " + e.word() + ": " + pct + " %"), 30 * 60_000L);
                var f = ArduLogs.save(AppPaths.arduPilotLogsDir(), drone, e, data);
                say("Saved: " + f.getFileName() + " (PREVIOUS FLIGHTS > FC log).");
            } catch (Exception ex) {
                say("Not downloaded: " + ex.getMessage());
            }
            SwingUtilities.invokeLater(() -> download.setEnabled(true));
        }, "arduLogsDownload");
        t.setDaemon(true);
        t.start();
    }

    /** The session closed (the window's close; or a session found after it). */
    private void letGo() {
        closed = true;
        MavSession s = fc;
        if (s != null) s.close();
    }

    /** What the window says now (tests). */
    String statusText() {
        return status.getText();
    }

    /** The download button (tests). */
    JButton downloadButton() {
        return download;
    }

    private void say(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }
}
