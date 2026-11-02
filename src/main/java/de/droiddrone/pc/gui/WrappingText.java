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

    /**
     * One that takes the width of the first ancestor with a width and wraps to it (for BoxLayout and the like, which
     * would otherwise give an HTML text one long line); it lays itself out again when that width changes.
     */
    static WrappingText fitting(String html, Font font, Color color) {
        WrappingText t = new WrappingText(html, font, color) {
            private int lastWidth = -1;

            @Override
            public java.awt.Dimension getPreferredSize() {
                int width = 0;
                for (java.awt.Container c = getParent(); c != null && width <= 0; c = c.getParent()) {
                    width = c.getWidth() - c.getInsets().left - c.getInsets().right;
                }
                if (width <= 0) return super.getPreferredSize();
                javax.swing.text.View v = getUI().getRootView(this);
                v.setSize(width, 0);
                return new java.awt.Dimension(width, (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
            }

            @Override
            public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }

            @Override
            public void setBounds(int x, int y, int w, int h) {
                super.setBounds(x, y, w, h);
                if (w > 0 && w != lastWidth) {
                    lastWidth = w;
                    invalidate(); // the height for this width, next layout
                    javax.swing.SwingUtilities.invokeLater(this::revalidate);
                }
            }
        };
        t.setAlignmentX(LEFT_ALIGNMENT);
        return t;
    }

    void setHtml(String html) {
        String body = "<html><body>" + (html == null || html.isEmpty() ? "&nbsp;" : html) + "</body></html>";
        if (!body.equals(getText())) {
            setText(body);
            revalidate();
        }
    }
}
