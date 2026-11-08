package de.droiddrone.pc.fleet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * The virtual fleet's walking person (1.0.52, the pilot: "replace the stick man with a 3d model of a modern soldier"):
 * Quaternius' SWAT character (CC0, THIRD-PARTY.md) in a camouflage uniform with a slung rifle, its walk clip baked
 * into keyframes by tools/model/export_soldier.py. Drawn flat-shaded and lit by the same sun as the BTR, through a
 * depth buffer of its own the size of its box on the picture, then laid over the picture - small, cheap, and as real
 * on the plain grid as in the tour's simulation world.
 */
final class Soldier {
    private static final String MESH = "/model/soldier.bin.gz";
    /** The sun: from the south-west, 35 degrees up (north, east, up), as BtrScene's. */
    private static final double[] SUN = {-0.574 / 1.0, -0.574 / 1.0, 0.70};

    record Model(int vertices, int frames, float clipSeconds, float strideM, int[] tri, int[] rgb, short[][] pos) {}

    private static Model model;

    static synchronized Model model() {
        if (model == null) {
            try (InputStream in = Soldier.class.getResourceAsStream(MESH)) {
                if (in == null) throw new IOException(MESH + " missing");
                model = load(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
            } catch (IOException e) {
                throw new IllegalStateException("The soldier model could not be read: " + e.getMessage(), e);
            }
        }
        return model;
    }

    private static Model load(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("SLD1")) throw new IOException("not a SLD1 mesh");
        int nv = in.readInt(), nf = in.readInt();
        float clip = in.readFloat(), stride = in.readFloat();
        int nt = in.readInt();
        int[] tri = new int[nt * 3], rgb = new int[nt];
        for (int t = 0; t < nt; t++) {
            tri[t * 3] = in.readUnsignedShort();
            tri[t * 3 + 1] = in.readUnsignedShort();
            tri[t * 3 + 2] = in.readUnsignedShort();
            rgb[t] = in.readInt();
        }
        short[][] pos = new short[nf][nv * 3];
        for (int f = 0; f < nf; f++) for (int i = 0; i < nv * 3; i++) pos[f][i] = in.readShort();
        return new Model(nv, nf, clip, stride, tri, rgb, pos);
    }

    private static final class Buffers {
        float[] cx, cy, cz, sx, sy, wn, we, wu;
        int[] px = new int[0];
        float[] z = new float[0];
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    private Soldier() {
    }

    /**
     * Draws the soldier standing at (north, east) on the ground, walking toward headingDeg, walked metres into its
     * walk (the clip's phase), lit for the day or the night. Nothing if it is behind the camera or off the picture.
     */
    static void paint(Graphics2D g, Scene.Camera cam, double north, double east, double headingDeg, double walked, boolean night) {
        Model m = model();
        Buffers b = BUFFERS.get();
        int nv = m.vertices();
        if (b.cx == null || b.cx.length < nv) {
            b.cx = new float[nv];
            b.cy = new float[nv];
            b.cz = new float[nv];
            b.sx = new float[nv];
            b.sy = new float[nv];
            b.wn = new float[nv];
            b.we = new float[nv];
            b.wu = new float[nv];
        }
        double[] centre = cam.cam(north, east, 0.9);
        if (centre[2] < 0.5) return;
        // the clip's two keyframes either side of this phase, blended
        double phase = walked / m.strideM();
        phase -= Math.floor(phase);
        double fp = phase * m.frames();
        int f0 = (int) fp % m.frames(), f1 = (f0 + 1) % m.frames();
        float k = (float) (fp - Math.floor(fp));
        short[] p0 = m.pos()[f0], p1 = m.pos()[f1];
        double hd = Math.toRadians(headingDeg), c = Math.cos(hd), s = Math.sin(hd);
        double[][] r = cam.r;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < nv; i++) {
            int o = i * 3;
            double x = (p0[o] + (p1[o] - p0[o]) * k) / 1000.0;
            double y = (p0[o + 1] + (p1[o + 1] - p0[o + 1]) * k) / 1000.0;
            double z = (p0[o + 2] + (p1[o + 2] - p0[o + 2]) * k) / 1000.0;
            // ours: x forward, y left, z up -> world: forward (cos h, sin h), left (sin h, -cos h)
            double n = north + x * c + y * s, e = east + x * s - y * c;
            b.wn[i] = (float) n;
            b.we[i] = (float) e;
            b.wu[i] = (float) z;
            double dn = n - cam.cn, de = e - cam.ce, du = z - cam.cu;
            double kx = r[0][0] * dn + r[0][1] * de + r[0][2] * du;
            double ky = r[1][0] * dn + r[1][1] * de + r[1][2] * du;
            double kz = r[2][0] * dn + r[2][1] * de + r[2][2] * du;
            b.cx[i] = (float) kx;
            b.cy[i] = (float) ky;
            b.cz[i] = (float) kz;
            if (kz > 0.05) {
                float sx = (float) (cam.cx + cam.f * kx / kz), sy = (float) (cam.cy + cam.f * ky / kz);
                b.sx[i] = sx;
                b.sy[i] = sy;
                minX = Math.min(minX, sx);
                maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
            }
        }
        int x0 = Math.max(0, (int) Math.floor(minX)), y0 = Math.max(0, (int) Math.floor(minY));
        int x1 = Math.min(cam.w - 1, (int) Math.ceil(maxX)), y1 = Math.min(cam.h - 1, (int) Math.ceil(maxY));
        if (x0 > x1 || y0 > y1) return;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        // a soft shadow at the feet, cast away from the sun
        double[] foot = cam.screen(north - SUN[0] / SUN[2] * 0.25, east - SUN[1] / SUN[2] * 0.25);
        if (foot != null && !night) {
            double rw = 0.45 * foot[2], rh = Math.max(1.5, rw * 0.28);
            g.setColor(new Color(20, 26, 18, 85));
            g.fill(new Ellipse2D.Double(foot[0] - rw, foot[1] - rh, 2 * rw, 2 * rh));
        }
        if (b.px.length < bw * bh) {
            b.px = new int[bw * bh];
            b.z = new float[bw * bh];
        }
        Arrays.fill(b.px, 0, bw * bh, 0);
        Arrays.fill(b.z, 0, bw * bh, Float.MAX_VALUE);
        int[] tri = m.tri(), rgb = m.rgb();
        double sunN = SUN[0], sunE = SUN[1], sunU = SUN[2];
        double sl = Math.sqrt(sunN * sunN + sunE * sunE + sunU * sunU);
        sunN /= sl;
        sunE /= sl;
        sunU /= sl;
        for (int t = 0, q = 0; t < tri.length; t += 3, q++) {
            int i0 = tri[t], i1 = tri[t + 1], i2 = tri[t + 2];
            if (b.cz[i0] < 0.3 || b.cz[i1] < 0.3 || b.cz[i2] < 0.3) continue;
            double ax = b.wn[i1] - b.wn[i0], ay = b.we[i1] - b.we[i0], az = b.wu[i1] - b.wu[i0];
            double bx = b.wn[i2] - b.wn[i0], by = b.we[i2] - b.we[i0], bz = b.wu[i2] - b.wu[i0];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-12) continue;
            nx /= nl;
            ny /= nl;
            nz /= nl;
            double toCam = nx * (cam.cn - b.wn[i0]) + ny * (cam.ce - b.we[i0]) + nz * (cam.cu - b.wu[i0]);
            if (toCam < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            double sun = Math.max(0, nx * sunN + ny * sunE + nz * sunU);
            double sky = 0.5 + 0.5 * nz;
            float light = night ? (float) (0.10 + 0.08 * sky) : (float) (0.34 + 0.26 * sky + 0.72 * sun);
            int col = rgb[q];
            int cr = Math.min(255, (int) (((col >> 16) & 0xff) * light)), cg = Math.min(255, (int) (((col >> 8) & 0xff) * light)),
                    cb = Math.min(255, (int) ((col & 0xff) * light));
            raster(b, bw, bh, x0, y0, i0, i1, i2, 0xff000000 | cr << 16 | cg << 8 | cb);
        }
        BufferedImage sprite = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int[] dst = ((DataBufferInt) sprite.getRaster().getDataBuffer()).getData();
        System.arraycopy(b.px, 0, dst, 0, bw * bh);
        g.drawImage(sprite, x0, y0, null);
    }

    /** One flat triangle into the sprite's pixels through its depth buffer. */
    private static void raster(Buffers b, int bw, int bh, int ox, int oy, int i0, int i1, int i2, int argb) {
        float x0 = b.sx[i0] - ox, y0 = b.sy[i0] - oy, x1 = b.sx[i1] - ox, y1 = b.sy[i1] - oy, x2 = b.sx[i2] - ox, y2 = b.sy[i2] - oy;
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(bw - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(bh - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float iz0 = 1 / b.cz[i0], iz1 = 1 / b.cz[i1], iz2 = 1 / b.cz[i2];
        float inv = 1 / area;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = 1 / (w0 * iz0 + w1 * iz1 + w2 * iz2);
                int o = y * bw + x;
                if (z >= b.z[o]) continue;
                b.z[o] = z;
                b.px[o] = argb;
            }
        }
    }
}
