package de.droiddrone.pc.gui;

import javax.swing.JEditorPane;
import java.awt.Color;
import java.awt.Font;

/** Read-only HTML text that wraps to the width it is given (an HTML JLabel would clip instead). */
class WrappingText extends JEditorPane {
    WrappingText(String html, Font font, Color color) {
        super("text/html", "");
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        setFont(font);
        setForeground(color);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setBorder(null);
        setHtml(html);
    }

    void setHtml(String html) {
        String body = "<html><body>" + (html == null || html.isEmpty() ? "&nbsp;" : html) + "</body></html>";
        if (!body.equals(getText())) {
            setText(body);
            revalidate();
        }
    }
}
