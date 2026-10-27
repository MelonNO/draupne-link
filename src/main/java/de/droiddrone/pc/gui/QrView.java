package de.droiddrone.pc.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Map;

/** A QR code the phone camera can scan, with a caption under it. */
class QrView extends JComponent {
    private BitMatrix matrix;
    private String caption = "";

    QrView(int size) {
        setPreferredSize(new Dimension(size, size + 26));
        setOpaque(false);
    }

    void setContent(String text, String caption) {
        this.caption = caption == null ? "" : caption;
        try {
            matrix = text == null || text.isEmpty() ? null
                    : new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            matrix = null;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int size = Math.min(getWidth(), getHeight() - 26);
        int x0 = (getWidth() - size) / 2;
        g.setColor(matrix == null ? Theme.PANEL_2 : Color.WHITE);
        g.fillRect(x0, 0, size, size);
        if (matrix != null) {
            int n = matrix.getWidth();
            int cell = Math.max(1, size / n);
            int offset = x0 + (size - cell * n) / 2;
            int top = (size - cell * n) / 2;
            g.setColor(Color.BLACK);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (matrix.get(x, y)) g.fillRect(offset + x * cell, top + y * cell, cell, cell);
                }
            }
        }
        g.setFont(Theme.font(Font.BOLD, 12f));
        g.setColor(Theme.MUTED);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(caption, (getWidth() - fm.stringWidth(caption)) / 2, size + 18);
        g.dispose();
    }
}
