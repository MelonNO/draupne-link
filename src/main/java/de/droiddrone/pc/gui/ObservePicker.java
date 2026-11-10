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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OBSERVATION's picker (1.0.48): the phones of the name map with a tick each, up to {@link ObserveSet#MAX}, the
 * remembered set ticked. Idle phones only: one another PC flies (or with an app older than pc.37) is shown greyed with
 * why. A phone in deep sleep can be ticked: it joins at its next check-in. In the tour's CHOOSE DRONES step (2026-11-10,
 * the pilot: "the six drones that should be selected is marked") the tour's drones come first, marked TOUR with a red
 * bar.
 */
final class ObservePicker {
    private ObservePicker() {
    }

    /**
     * @param refusals why a phone can't be added now (absent: it can)
     * @param asleep   the phones in deep sleep (they join at their check-in)
     * @param marked   the tour's drones to tick (empty outside its step)
     * @return the phones chosen, in the list's order; null when cancelled
     */
    static List<String> pick(Component owner, List<Names.Entry> phones, ObserveSet current, Map<String, String> refusals,
                             Set<String> asleep, Set<String> marked) {
        phones = order(phones, marked);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel head = new JLabel("<html>" + (marked.isEmpty() ? "Up to " + ObserveSet.MAX + " drones. Idle drones only, none another PC flies."
                : "Tick the " + marked.size() + " marked TOUR.") + "</html>");
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
            String state = why != null ? "  -  " + why : asleep.contains(e.name()) ? "  -  deep sleep: joins at next check-in"
                    : e.online() ? "  -  online" : "  -  not seen lately";
            boolean tour = isMarked(e.name(), marked);
            JCheckBox b = new JCheckBox(label(e, state, tour), why == null && current.contains(e.name()));
            b.setFont(b.getFont().deriveFont((why == null ? Font.PLAIN : Font.ITALIC) | (tour ? Font.BOLD : 0)));
            if (tour) {
                b.setBorderPainted(true);
                b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.ACCENT),
                        BorderFactory.createEmptyBorder(2, 6, 2, 0)));
            }
            b.addActionListener(ev -> limit.run());
            boxes.add(b);
            names.add(e.name());
            panel.add(b);
        }
        if (phones.isEmpty()) panel.add(new JLabel("No phone seen yet: open DRAUPNE Flight, on Tailscale."));
        limit.run();
        int answer = Dialogs.confirm(owner, panel, "Observation - choose the drones", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return null;
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) chosen.add(names.get(i));
        return chosen;
    }

    /** The tour's drones first (in the list's order), then the rest. */
    static List<Names.Entry> order(List<Names.Entry> phones, Set<String> marked) {
        if (marked.isEmpty()) return phones;
        List<Names.Entry> out = new ArrayList<>();
        for (Names.Entry e : phones) if (isMarked(e.name(), marked)) out.add(e);
        for (Names.Entry e : phones) if (!isMarked(e.name(), marked)) out.add(e);
        return out;
    }

    /** A row's text: the name, TOUR when the tour asks for it, VIRTUAL, its state. */
    static String label(Names.Entry e, String state, boolean tour) {
        return e.name() + (tour ? "  TOUR" : "") + (e.isVirtual() ? "  VIRTUAL" : "") + state;
    }

    static boolean isMarked(String name, Set<String> marked) {
        if (marked.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String m : marked) if (m.toLowerCase(Locale.ROOT).equals(n)) return true;
        return false;
    }
}
