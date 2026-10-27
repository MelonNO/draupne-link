package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** One checklist line in the setup wizards: status light, title, detail text and optional action buttons. */
class CheckRow extends JPanel {
    enum State { OK, WARN, BAD, PENDING, INFO }

    private final StatusLight light = new StatusLight();
    private final WrappingText detail = new WrappingText("", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private State state = State.PENDING;

    CheckRow(String title) {
        super(new BorderLayout(14, 0));
        setBackground(Theme.PANEL_2);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        light.setPreferredSize(new Dimension(26, 26));
        JPanel lightBox = new JPanel(new BorderLayout());
        lightBox.add(light, BorderLayout.NORTH);
        add(lightBox, BorderLayout.WEST);
        JPanel texts = new JPanel(new BorderLayout(0, 2));
        texts.add(Theme.label(title, Theme.font(Font.BOLD, 14.5f), Theme.TEXT), BorderLayout.NORTH);
        texts.add(detail, BorderLayout.CENTER);
        add(texts, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        Theme.transparent(lightBox, texts, actions);
    }

    CheckRow set(State state, String text) {
        this.state = state;
        detail.setHtml(text);
        light.repaint();
        return this;
    }

    State getState() {
        return state;
    }

    JButton addAction(String text, Runnable action) {
        JButton button = Theme.secondaryButton(text);
        button.addActionListener(e -> action.run());
        actions.add(button);
        return button;
    }

    JButton addPrimaryAction(String text, Runnable action) {
        JButton button = Theme.primaryButton(text);
        button.setFont(Theme.headingFont(12.5f));
        button.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        button.addActionListener(e -> action.run());
        actions.add(button);
        return button;
    }

    void addComponent(JComponent component) {
        actions.add(component);
    }

    private class StatusLight extends JComponent {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            Color c = switch (state) {
                case OK -> Theme.OK;
                case WARN -> Theme.WARN;
                case BAD -> Theme.BAD;
                case INFO -> Theme.ACCENT;
                case PENDING -> Theme.LINE;
            };
            int s = Math.min(getWidth(), getHeight()) - 2;
            g.setColor(c);
            g.fillOval(1, 1, s, s);
            g.setColor(Theme.BG);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int m = s / 2 + 1;
            switch (state) {
                case OK -> {
                    g.drawLine(m - 6, m, m - 2, m + 4);
                    g.drawLine(m - 2, m + 4, m + 6, m - 4);
                }
                case BAD -> {
                    g.drawLine(m - 5, m - 5, m + 5, m + 5);
                    g.drawLine(m + 5, m - 5, m - 5, m + 5);
                }
                case WARN, INFO -> {
                    g.drawLine(m, m - 6, m, m + 1);
                    g.fillOval(m - 2, m + 4, 4, 4);
                }
                case PENDING -> {
                    double phase = (System.currentTimeMillis() % 1200) / 1200.0 * Math.PI * 2;
                    g.fillOval((int) (m + 5 * Math.cos(phase)) - 3, (int) (m + 5 * Math.sin(phase)) - 3, 6, 6);
                    repaint(60);
                }
            }
            g.dispose();
        }
    }
}
