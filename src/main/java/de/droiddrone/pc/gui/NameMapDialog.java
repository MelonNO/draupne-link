package de.droiddrone.pc.gui;

import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MENU > LINK > Name map (1.0.35): every phone and PC this PC has seen on its Tailscale network - name, what it is,
 * address, device, Tailscale machine name, when it was last seen - and this PC's own name, the link port and key.
 * Refreshed as the name service hears answers.
 */
final class NameMapDialog extends JDialog {
    private static final String[] COLUMNS = {"NAME", "WHAT", "ADDRESS", "DEVICE", "TAILSCALE", "LAST SEEN"};
    private final Names names;
    private final NameService service;
    private final PcConfig config;
    private final Consumer<Map<String, String>> saver;
    private final Runnable onChanged;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<String> ownName = new JComboBox<>();
    private final JTextField portField = new JTextField(6);
    private final JTextField keyField = new JTextField(10);
    private final JLabel masterLine = Theme.label("", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private final Timer refresh = new Timer(2000, e -> fill());
    private boolean settingOwn;
    private List<Names.Entry> shown = List.of();

    NameMapDialog(JFrame owner, Names names, NameService service, PcConfig config, Consumer<Map<String, String>> saver, Runnable onChanged) {
        super(owner, "Name map", ModalityType.MODELESS);
        this.names = names;
        this.service = service;
        this.config = config;
        this.saver = saver;
        this.onChanged = onChanged;
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(Theme.label("NAME MAP", Theme.headingFont(14f), Theme.ACCENT), BorderLayout.NORTH);
        WrappingText intro = new WrappingText("Every phone and PC seen on your Tailscale network. Phones are MobileAlpha, MobileBravo ...; "
                + "the pilot PC with the highest letter online names the phones that have none. A name stays with its device; "
                + "RENAME gives a phone another name, REMOVE forgets a device.", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
        head.add(intro, BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);

        table.setFont(Theme.monoFont(Font.PLAIN, 12.5f));
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Theme.PANEL_2);
        table.setForeground(Theme.TEXT);
        table.setGridColor(Theme.LINE);
        table.getTableHeader().setFont(Theme.headingFont(11f));
        table.getTableHeader().setBackground(Theme.PANEL);
        table.getTableHeader().setForeground(Theme.MUTED);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(Theme.PANEL_2);
        root.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        JPanel own = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        own.setOpaque(false);
        own.add(Theme.label("THIS PC IS", Theme.headingFont(11f), Theme.MUTED));
        for (String letter : Names.LETTERS) ownName.addItem(Names.Kind.PC.prefix + letter);
        TacticalCombo.install(ownName);
        ownName.setToolTipText("This PC's name on the network. It names itself with the first free PC letter; change it here");
        ownName.addActionListener(e -> {
            if (settingOwn) return;
            String name = (String) ownName.getSelectedItem();
            if (name != null && !name.equals(config.getString(Names.PC_NAME_KEY))) {
                saver.accept(Map.of(Names.PC_NAME_KEY, name));
                service.scanSoon();
                fill();
            }
        });
        own.add(ownName);
        own.add(masterLine);
        south.add(own, BorderLayout.NORTH);

        JPanel advanced = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        advanced.setOpaque(false);
        advanced.add(Theme.label("PORT", Theme.headingFont(11f), Theme.MUTED));
        portField.setText(Integer.toString(config.getPort()));
        advanced.add(portField);
        advanced.add(Theme.label("KEY", Theme.headingFont(11f), Theme.MUTED));
        keyField.setText(config.getKey());
        advanced.add(keyField);
        JButton apply = Theme.secondaryButton("Apply");
        apply.setToolTipText("The link port and key, the same on every phone (Advanced in DRAUPNE Flight). A new port takes effect at the next start");
        apply.addActionListener(e -> applyAdvanced());
        advanced.add(apply);
        south.add(advanced, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        JButton rename = Theme.secondaryButton("Rename");
        rename.setToolTipText("Give the selected phone another Mobile name (it must be online: a phone keeps its name itself)");
        rename.addActionListener(e -> renameSelected());
        buttons.add(rename);
        JButton remove = Theme.secondaryButton("Remove");
        remove.setToolTipText("Forget the selected device (it comes back when it answers again)");
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || row >= shown.size()) return;
            names.remove(shown.get(row).name());
            fill();
            onChanged.run();
        });
        buttons.add(remove);
        JButton rescan = Theme.secondaryButton("Ask again");
        rescan.setToolTipText("Ask every device on the network now");
        rescan.addActionListener(e -> service.scanSoon());
        buttons.add(rescan);
        JButton close = Theme.primaryButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(close);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        service.onChange(() -> SwingUtilities.invokeLater(this::fill));
        refresh.start();
        fill();
        setSize(new Dimension(820, 460));
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
    }

    @Override
    public void dispose() {
        refresh.stop();
        super.dispose();
    }

    /** RENAME (1.0.37): the selected phone gets a free Mobile name; the phone is told, the map and the LINK card follow. */
    private void renameSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= shown.size()) return;
        Names.Entry e = shown.get(row);
        if (e.kind() != Names.Kind.PHONE) {
            JOptionPane.showMessageDialog(this, "Only phones are renamed here. This PC's name is the THIS PC IS choice; other PCs name themselves.",
                    "Rename", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!e.online() || e.ip() == null || e.ip().isEmpty()) {
            JOptionPane.showMessageDialog(this, e.name() + " is offline. A phone keeps its name itself, so it must be online to be renamed.",
                    "Rename", JOptionPane.WARNING_MESSAGE);
            return;
        }
        java.util.List<String> free = new java.util.ArrayList<>();
        for (int i = 0; i < Names.LETTERS.length; i++) {
            String n = Names.Kind.PHONE.name(i);
            if (names.get(n) == null) free.add(n);
        }
        if (free.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Every Mobile name is taken - remove a device first.", "Rename", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JComboBox<String> pick = new JComboBox<>(free.toArray(new String[0]));
        TacticalCombo.install(pick);
        String detail = e.detail();
        int answer = JOptionPane.showConfirmDialog(this, new Object[]{"New name for " + e.name() + (detail.isEmpty() ? "" : " (" + detail + ")") + ":", pick},
                "Rename", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        String to = (String) pick.getSelectedItem();
        if (to == null || !names.rename(e.name(), to)) return;
        if (e.name().equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY))) saver.accept(Map.of(Names.PHONE_NAME_KEY, to));
        service.assign(e.ip(), to);
        fill();
        onChanged.run();
    }

    private void applyAdvanced() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1024 || port > 65534) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port must be a number between 1024 and 65534.", "Name map", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String key = keyField.getText().trim();
        if (key.isEmpty() || key.length() > 16) {
            JOptionPane.showMessageDialog(this, "The key is 1 to 16 characters.", "Name map", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saver.accept(Map.of("port", Integer.toString(port), "key", key));
        service.scanSoon();
    }

    private void fill() {
        if (!isDisplayable()) return;
        settingOwn = true;
        try {
            String own = config.getString(Names.PC_NAME_KEY);
            if (own.isEmpty()) ownName.setSelectedIndex(-1);
            else ownName.setSelectedItem(own);
        } finally {
            settingOwn = false;
        }
        masterLine.setText(service.isMaster() ? "- the master: it names the phones" : "- another PC with a higher letter names the phones");
        List<Names.Entry> all = names.all();
        all.sort((a, b) -> a.kind() != b.kind() ? (a.kind() == Names.Kind.PHONE ? -1 : 1)
                : a.online() != b.online() ? (a.online() ? -1 : 1) : Integer.compare(Names.letterOf(a.name()), Names.letterOf(b.name())));
        int selected = table.getSelectedRow();
        shown = all;
        long now = System.currentTimeMillis();
        model.setRowCount(0);
        for (Names.Entry e : all) {
            model.addRow(new Object[]{e.name(), e.kind() == Names.Kind.PHONE ? "phone" : "PC", e.ip() == null ? "" : e.ip(),
                    e.device() == null ? "" : e.device(), e.host() == null ? "" : e.host(),
                    e.online() ? "online" : Names.agoText(e.lastSeen(), now)});
        }
        if (selected >= 0 && selected < model.getRowCount()) table.setRowSelectionInterval(selected, selected);
    }
}
