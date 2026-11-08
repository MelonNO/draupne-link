package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;

/**
 * A figure that looks like a label but can be selected and copied (1.0.51, the pilot: "make the text and grid in the
 * bottom of the screen copy pastable"): drag to select, double-click selects it all, Ctrl+C or right-click > Copy.
 */
final class CopyableText extends JTextField {
    /** Right-click: this menu (Copy is added to it), or null for Copy alone. */
    private Supplier<JPopupMenu> menu;

    CopyableText(String text, Font font, Color color, Color background) {
        super(text);
        setEditable(false);
        setFont(font);
        setForeground(color);
        setBackground(background);
        setBorder(BorderFactory.createEmptyBorder());
        setSelectionColor(Theme.alpha(Theme.ACCENT, 150));
        setSelectedTextColor(Theme.TEXT);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) SwingUtilities.invokeLater(() -> selectAll());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;
                JPopupMenu m = menu == null ? TacticalCombo.menu() : menu.get();
                m.setBackground(Theme.PANEL_2);
                if (m.getComponentCount() > 0) m.addSeparator();
                JMenuItem copy = new JMenuItem("Copy");
                copy.setEnabled(!copyText().isBlank());
                copy.addActionListener(a -> copyToClipboard());
                m.add(copy);
                TacticalCombo.show(m, CopyableText.this, e.getX(), e.getY());
            }
        });
    }

    /** Right-click shows this menu with Copy under it. */
    void setMenu(Supplier<JPopupMenu> menu) {
        this.menu = menu;
    }

    /** The selection, or all of it when nothing is selected. */
    String copyText() {
        String s = getSelectedText();
        return (s == null || s.isEmpty() ? getText() : s).trim();
    }

    private void copyToClipboard() {
        String text = copyText();
        if (text.isEmpty() || text.equals("-")) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Text and colour, only when they change (like MainWindow.setLabel); the tooltip shows the whole line. */
    void show(String text, Color color) {
        if (!text.equals(getText())) {
            setText(text);
            setCaretPosition(0);
            setToolTipText(text.isBlank() ? null : text);
        }
        if (!color.equals(getForeground())) setForeground(color);
    }
}
