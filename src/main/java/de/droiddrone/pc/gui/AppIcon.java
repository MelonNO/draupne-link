package de.droiddrone.pc.gui;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

/** Window icon drawn in code: a quad in the accent color on a dark tile. */
public final class AppIcon {
    private AppIcon() {
    }

    static List<Image> images() {
        return List.of(draw(16), draw(32), draw(48), draw(64), draw(128));
    }

    /** Writes the icon as a Windows .ico (PNG-compressed entries, 16-256 px) for the installer and the .exe. */
    public static void writeIco(java.nio.file.Path file) throws java.io.IOException {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new java.util.ArrayList<>();
        for (int s : sizes) {
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write((BufferedImage) draw(s), "png", png);
            pngs.add(png.toByteArray());
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(6 + 16 * sizes.length + pngs.stream().mapToInt(p -> p.length).sum())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            b.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            b.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            b.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        pngs.forEach(b::put);
        java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        java.nio.file.Files.write(file, b.array());
    }

    private static Image draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Theme.smooth(img.createGraphics());
        float s = size;
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, size, size);
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.08f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float a = s * 0.27f;
        float b = s * 0.73f;
        g.drawLine(Math.round(a), Math.round(a), Math.round(b), Math.round(b));
        g.drawLine(Math.round(b), Math.round(a), Math.round(a), Math.round(b));
        float r = s * 0.16f;
        g.setStroke(new BasicStroke(Math.max(1f, s * 0.06f)));
        for (float[] c : new float[][]{{a, a}, {b, a}, {a, b}, {b, b}}) {
            g.drawOval(Math.round(c[0] - r), Math.round(c[1] - r), Math.round(2 * r), Math.round(2 * r));
        }
        g.fillOval(Math.round(s / 2 - s * 0.08f), Math.round(s / 2 - s * 0.08f), Math.round(s * 0.16f), Math.round(s * 0.16f));
        g.dispose();
        return img;
    }
}
