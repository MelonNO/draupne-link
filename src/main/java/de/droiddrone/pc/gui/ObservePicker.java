package de.droiddrone.pc.gui;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.observe.ObserveSet;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OBSERVATION's picker (1.0.48): the phones of the name map with a tick each, up to {@link ObserveSet#MAX}, the
 * remembered set ticked. Idle phones only: one another PC flies (or with an app older than pc.37) is shown greyed with
 * why. A phone in deep sleep can be ticked: it joins at its next check-in.
 */
final class ObservePicker {
    private ObservePicker() {
    }

    /**
     * @param refusals why a phone can't be added now (absent: it can)
     * @param asleep   the phones in deep sleep (they join at their check-in)
     * @return the phones chosen, in the list's order; null when cancelled
     */
    static List<String> pick(Component owner, List<Names.Entry> phones, ObserveSet current, Map<String, String> refusals,
                             java.util.Set<String> asleep) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel head = new JLabel("<html>Up to " + ObserveSet.MAX + " drones in the grid. Idle drones only: one another PC flies can't be added.</html>");
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        panel.add(head);
        List<JCheckBox> boxes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Runnable limit = () -> {
            int ticked = 0;
            for (JCheckBox b : boxes) if (b.isSelected()) ticked++;
            for (int i = 0; i < boxes.size(); i++) {
                JCheckBox b = boxes.get(i);
                boolean refused = refusals.containsKey(names.get(i));
                b.setEnabled(!refused && (b.isSelected() || ticked < ObserveSet.MAX));
            }
        };
        for (Names.Entry e : phones) {
            String why = refusals.get(e.name());
            String state = why != null ? "  -  " + why : asleep.contains(e.name()) ? "  -  deep sleep: joins at its next check-in"
                    : e.online() ? "  -  online" : "  -  not seen lately";
            JCheckBox b = new JCheckBox(e.name() + (e.isVirtual() ? "  VIRTUAL" : "") + state, why == null && current.contains(e.name()));
            b.setFont(b.getFont().deriveFont(why == null ? Font.PLAIN : Font.ITALIC));
            b.addActionListener(ev -> limit.run());
            boxes.add(b);
            names.add(e.name());
            panel.add(b);
        }
        if (phones.isEmpty()) panel.add(new JLabel("No phone seen yet: open DRAUPNE Flight on the phones, on Tailscale."));
        limit.run();
        int answer = JOptionPane.showConfirmDialog(owner, panel, "Observation - choose the drones", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return null;
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) chosen.add(names.get(i));
        return chosen;
    }
}
