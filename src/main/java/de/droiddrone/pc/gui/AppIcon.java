package de.droiddrone.pc.gui;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The window icon: the DRAUPNE Link mark (resources/icon, made from its SVG at each size, so the small ones stay
 * crisp); drawn from {@link Logo} if a resource is missing. The .ico for the .exe and installer comes from the same set.
 */
public final class AppIcon {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcon() {
    }

    static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int s : SIZES) images.add(load(s));
        return images;
    }

    /** Writes the icon as a Windows .ico (PNG-compressed entries, 16-256 px) for the installer and the .exe. */
    public static void writeIco(java.nio.file.Path file) throws IOException {
        java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        try (InputStream in = AppIcon.class.getResourceAsStream("/icon/draupne-link.ico")) {
            if (in != null) {
                java.nio.file.Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            ImageIO.write(load(s), "png", png);
            pngs.add(png.toByteArray());
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(6 + 16 * SIZES.length + pngs.stream().mapToInt(p -> p.length).sum())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            b.put((byte) (SIZES[i] >= 256 ? 0 : SIZES[i])).put((byte) (SIZES[i] >= 256 ? 0 : SIZES[i]));
            b.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            b.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        pngs.forEach(b::put);
        java.nio.file.Files.write(file, b.array());
    }

    private static BufferedImage load(int size) {
        try (InputStream in = AppIcon.class.getResourceAsStream("/icon/draupne-link-" + size + ".png")) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) return img;
            }
        } catch (IOException ignored) {
        }
        return draw(size);
    }

    /** The mark on its dark rounded tile, as the SVG has it. */
    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Theme.smooth(img.createGraphics());
        g.setColor(Logo.DARK);
        int r = Math.round(size * 112f / 512);
        g.fillRoundRect(0, 0, size, size, r, r);
        Logo.paint(g, 0, 0, size, 1f);
        g.dispose();
        return img;
    }
}
