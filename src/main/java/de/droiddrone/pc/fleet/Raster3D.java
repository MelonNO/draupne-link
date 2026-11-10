package de.droiddrone.pc.fleet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Meshes drawn into a virtual drone's picture through a depth buffer (the tour's scenes, 2026-11-09; textured for the
 * tactical scenes, 2026-11-10): the models of /model/tactical.bin.gz (tools/model/export_tactical.py, see
 * THIRD-PARTY.md) - textured, each texel lit by its vertices' light (Gouraud), alpha-masked where the texture has
 * holes (trees, nets), fading with distance into the memory's white ({@link SimVoid}) - and the FPV drone of Drone
 * setup &gt; Orientation (/model/fpv-drone.bin.gz, its texture baked into a colour per triangle, its mesh thinned for
 * the video's speed) in its own colours. Under them the ground ({@link #ground}: a textured field and tracks, per
 * pixel), shadows cast along the sun and soft puffs (smoke, dust, a muzzle flash); the land an island on the memory's
 * floor ({@link Memory}), far trees its ghosts ({@link #ghost}). Seen through the drone's {@link Scene.Camera}.
 * <p>
 * Model axes: x forward, y left, z up, metres. Placed by north, east, up, heading (clockwise from north), pitch (nose
 * up +), roll (right side down +) and a scale. One {@link Frame} per thread and picture size: the video's and the
 * motion detection's pictures are drawn at once on their own threads; its scratch arrays are reused picture to picture.
 */
final class Raster3D {
    /** A texture as a mip chain (level 0 the image, each next half its size, box-filtered), ARGB; alpha 0 is a hole. */
    record Texture(int[][] level, int[] w, int[] h, boolean alpha) {}

    /**
     * A mesh: vertices (x y z, frame 0), triangles (three indices each), one RGB colour per triangle (a flat one), or
     * per vertex u v and per triangle a texture (a textured one); frames: the vertices of each pose (frame 0 = p),
     * normals: each frame's vertex normals; flags {@link #CARD} / {@link #TWO_SIDED}.
     */
    record Mesh(float[] p, int[] tri, int[] rgb, float[] uv, byte[] tex, Texture[] textures, float[][] frames,
                float[][] normals, int flags, float[] hull) {
        Mesh(float[] p, int[] tri, int[] rgb) {
            this(p, tri, rgb, null, null, null, null, null, 0, null);
        }

        int triangles() {
            return tri.length / 3;
        }

        int frameCount() {
            return frames == null ? 1 : frames.length;
        }

        /** Its height over its lowest point (frame 0). */
        float height() {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 2; i < p.length; i += 3) {
                lo = Math.min(lo, p[i]);
                hi = Math.max(hi, p[i]);
            }
            return hi - lo;
        }
    }

    /** An impostor card: a quad turned to face the camera about its upright, lit evenly. */
    static final int CARD = 1;
    /** Seen from both sides (a net): the back lit as its own face. */
    static final int TWO_SIDED = 2;
    /** Part of the ground (a rise): lit as the flat ground is where it is level, so it meets it without an edge. */
    static final int TERRAIN = 4;

    /** The sun: from the south-west, 35 degrees up (north, east, up). */
    static final double[] SUN = unit(new double[]{-0.574, -0.574, 0.70});

    private Raster3D() {
    }

    // ------------------------------------------------------------------ the meshes

    private static Map<String, Mesh> tactical;
    private static Mesh fpv;

    /** A model of the tactical scenes by its name (btr80, ural, soldier, spruce-0 ...). */
    static Mesh model(String name) {
        Mesh m = tactical().get(name);
        if (m == null) throw new IllegalArgumentException("no model " + name);
        return m;
    }

    private static Map<String, Texture> groundTextures;

    /** One of the ground's textures (ground-grass, ground-dirt). */
    static Texture groundTexture(String name) {
        tactical();
        Texture t = groundTextures.get(name);
        if (t == null) throw new IllegalArgumentException("no texture " + name);
        return t;
    }

    private static synchronized Map<String, Mesh> tactical() {
        if (tactical == null) {
            try (InputStream in = Raster3D.class.getResourceAsStream("/model/tactical.bin.gz")) {
                if (in == null) throw new IOException("/model/tactical.bin.gz missing");
                Map<String, Texture> named = new HashMap<>();
                tactical = loadTactical(new DataInputStream(new GZIPInputStream(in, 1 << 16)), named);
                groundTextures = named;
            } catch (IOException e) {
                throw new IllegalStateException("The tactical models could not be read: " + e.getMessage(), e);
            }
        }
        return tactical;
    }

    /** TAC1 (export_tactical.py): the textures (named ones into named), then the meshes. */
    static Map<String, Mesh> loadTactical(DataInputStream in, Map<String, Texture> named) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("TAC1")) throw new IOException("not a TAC1 file");
        int nt = in.readInt();
        Texture[] textures = new Texture[nt];
        for (int k = 0; k < nt; k++) {
            String name = in.readUTF();
            int flags = in.readUnsignedByte();
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) throw new IOException("texture " + name + " unreadable");
            textures[k] = mips(img, (flags & 1) != 0);
            named.put(name, textures[k]);
        }
        int nm = in.readInt();
        Map<String, Mesh> out = new HashMap<>();
        for (int k = 0; k < nm; k++) {
            String name = in.readUTF();
            int flags = in.readUnsignedByte();
            int nv = in.readInt(), ntri = in.readInt(), nf = in.readInt();
            float[][] frames = new float[nf][nv * 3], normals = new float[nf][nv * 3];
            for (float[] f : frames) for (int i = 0; i < f.length; i++) f[i] = in.readShort() / 1000f;
            for (float[] f : normals) for (int i = 0; i < f.length; i++) f[i] = in.readByte() / 127f;
            float[] uv = new float[nv * 2];
            for (int i = 0; i < uv.length; i++) uv[i] = in.readFloat();
            int[] tri = new int[ntri * 3];
            byte[] tex = new byte[ntri];
            int lo = 255, hi = 0;
            for (int t = 0; t < ntri; t++) {
                tri[t * 3] = in.readUnsignedShort();
                tri[t * 3 + 1] = in.readUnsignedShort();
                tri[t * 3 + 2] = in.readUnsignedShort();
                tex[t] = in.readByte();
                lo = Math.min(lo, tex[t] & 0xff);
                hi = Math.max(hi, tex[t] & 0xff);
            }
            // the mesh's own textures, its triangles' indices into them
            Texture[] own = Arrays.copyOfRange(textures, lo, hi + 1);
            for (int t = 0; t < ntri; t++) tex[t] = (byte) ((tex[t] & 0xff) - lo);
            int[] rgb = new int[ntri];
            for (int t = 0; t < ntri; t++) rgb[t] = average(own[tex[t]]);
            out.put(name, new Mesh(frames[0], tri, rgb, uv, tex, own, nf > 1 ? frames : null, normals, flags, hull(frames[0])));
        }
        return out;
    }

    private static int average(Texture t) {
        int[] px = t.level()[t.level().length - 1];
        return px[0] & 0xffffff;
    }

    /** A texture's mip chain, alpha kept as a mask (a level's texel is a hole when most of its four are). */
    static Texture mips(BufferedImage img, boolean alpha) {
        List<int[]> levels = new ArrayList<>();
        List<Integer> ws = new ArrayList<>(), hs = new ArrayList<>();
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        if (!alpha) for (int i = 0; i < px.length; i++) px[i] |= 0xff000000;
        while (true) {
            levels.add(px);
            ws.add(w);
            hs.add(h);
            if (w == 1 && h == 1) break;
            int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
            int[] next = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    int r = 0, g = 0, b = 0, n = 0, solid = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int c = px[Math.min(h - 1, y * 2 + dy) * w + Math.min(w - 1, x * 2 + dx)];
                            r += (c >> 16) & 0xff;
                            g += (c >> 8) & 0xff;
                            b += c & 0xff;
                            n++;
                            if (c >>> 24 != 0) solid++;
                        }
                    }
                    int a = !alpha || solid >= 2 ? 0xff000000 : 0;
                    next[y * nw + x] = a | (r / n << 16) | (g / n << 8) | b / n;
                }
            }
            px = next;
            w = nw;
            h = nh;
        }
        return new Texture(levels.toArray(new int[0][]), ws.stream().mapToInt(Integer::intValue).toArray(),
                hs.stream().mapToInt(Integer::intValue).toArray(), alpha);
    }

    /** Vertex normals: each vertex's triangles' normals, area weighted. */
    static float[] normals(float[] p, int[] tri) {
        float[] n = new float[p.length];
        for (int t = 0; t < tri.length; t += 3) {
            int a = tri[t] * 3, b = tri[t + 1] * 3, c = tri[t + 2] * 3;
            float ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2];
            float fx = p[c] - p[a], fy = p[c + 1] - p[a + 1], fz = p[c + 2] - p[a + 2];
            float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            for (int v : new int[]{a, b, c}) {
                n[v] += nx;
                n[v + 1] += ny;
                n[v + 2] += nz;
            }
        }
        for (int i = 0; i < n.length; i += 3) {
            float l = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (l > 0) {
                n[i] /= l;
                n[i + 1] /= l;
                n[i + 2] /= l;
            }
        }
        return n;
    }

    /** The footprint's convex hull (x y pairs, anticlockwise) and, last, the height: for the shadow. */
    static float[] hull(float[] p) {
        int nv = p.length / 3;
        Integer[] idx = new Integer[nv];
        float top = 0;
        for (int i = 0; i < nv; i++) {
            idx[i] = i;
            top = Math.max(top, p[i * 3 + 2]);
        }
        Arrays.sort(idx, (a, b) -> p[a * 3] != p[b * 3] ? Float.compare(p[a * 3], p[b * 3]) : Float.compare(p[a * 3 + 1], p[b * 3 + 1]));
        int[] h = new int[2 * nv + 1];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int j = 0; j < nv; j++) {
                int i = pass == 0 ? idx[j] : idx[nv - 1 - j];
                while (k >= start + 2) {
                    int o = h[k - 2], a = h[k - 1];
                    float cr = (p[a * 3] - p[o * 3]) * (p[i * 3 + 1] - p[o * 3 + 1]) - (p[a * 3 + 1] - p[o * 3 + 1]) * (p[i * 3] - p[o * 3]);
                    if (cr > 0) break;
                    k--;
                }
                h[k++] = i;
            }
            k--;
        }
        float[] out = new float[k * 2 + 1];
        for (int j = 0; j < k; j++) {
            out[j * 2] = p[h[j] * 3];
            out[j * 2 + 1] = p[h[j] * 3 + 1];
        }
        out[k * 2] = top;
        return out;
    }

    /** The FPV drone (its texture baked, thinned to about a tenth), front forward, 0.3 m across. */
    static synchronized Mesh fpvDrone() {
        if (fpv == null) {
            try (InputStream in = Raster3D.class.getResourceAsStream("/model/fpv-drone.bin.gz")) {
                if (in == null) throw new IOException("/model/fpv-drone.bin.gz missing");
                fpv = thin(loadDrone(new DataInputStream(new GZIPInputStream(in, 1 << 16))), FPV_CELL_M);
            } catch (IOException e) {
                throw new IllegalStateException("The drone model could not be read: " + e.getMessage(), e);
            }
        }
        return fpv;
    }

    /** The FPV mesh's vertices are welded on a grid this fine (59 000 triangles to some thousands). */
    static final double FPV_CELL_M = 0.006;
    private static final int TEX = 1024, FRONT_PROP = 4, TEXTURE_BITS = 3;

    /** DroneModelView's DRM1 (x forward, y right, z down) into this class's axes, a colour per triangle from its texture. */
    private static Mesh loadDrone(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("DRM1")) throw new IOException("not a DRM1 mesh");
        int nv = in.readInt();
        float[] p = new float[nv * 3], u = new float[nv], v = new float[nv];
        byte[] flag = new byte[nv];
        for (int i = 0; i < nv; i++) {
            p[i * 3] = in.readFloat();
            p[i * 3 + 1] = -in.readFloat(); // right to left
            p[i * 3 + 2] = -in.readFloat(); // down to up
            u[i] = in.readFloat();
            v[i] = in.readFloat();
            flag[i] = in.readByte();
        }
        int[] tri = new int[in.readInt() * 3];
        for (int i = 0; i < tri.length; i++) tri[i] = in.readUnsignedShort();
        int[][] tex = new int[2][];
        for (int k = 0; k < 2; k++) {
            try (InputStream t = Raster3D.class.getResourceAsStream("/model/fpv-drone-" + k + ".jpg")) {
                if (t == null) throw new IOException("fpv-drone-" + k + ".jpg missing");
                BufferedImage img = ImageIO.read(t);
                if (img == null || img.getWidth() != TEX || img.getHeight() != TEX) throw new IOException("fpv-drone-" + k + ".jpg is not " + TEX + " square");
                tex[k] = img.getRGB(0, 0, TEX, TEX, null, 0, TEX);
            }
        }
        int nt = tri.length / 3;
        int[] rgb = new int[nt];
        for (int t = 0; t < nt; t++) {
            int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
            int which = flag[a] & TEXTURE_BITS;
            int col = 0x3a3d40;
            if (which < 2) {
                float uu = (u[a] + u[b] + u[c]) / 3, vv = (v[a] + v[b] + v[c]) / 3;
                int tx = (int) ((uu - Math.floor(uu)) * TEX) & (TEX - 1), ty = (int) ((1 - (vv - Math.floor(vv))) * TEX) & (TEX - 1);
                col = tex[which][ty * TEX + tx] & 0xffffff;
            }
            if ((flag[a] & FRONT_PROP) != 0) col = ((col >> 1) & 0x7f7f7f) + ((0xdc3c32 >> 1) & 0x7f7f7f);
            rgb[t] = col;
        }
        return new Mesh(p, tri, rgb);
    }

    /**
     * Vertex clustering: every vertex moved to its cell's first, triangles that collapse dropped, a triangle's twin
     * (same corners) dropped. The shape stays to the cell's size; a small far quad looks the same.
     */
    static Mesh thin(Mesh m, double cell) {
        int nv = m.p().length / 3;
        Map<Long, Integer> cells = new HashMap<>();
        int[] remap = new int[nv];
        List<Float> pts = new ArrayList<>();
        for (int i = 0; i < nv; i++) {
            long kx = Math.round(m.p()[i * 3] / cell), ky = Math.round(m.p()[i * 3 + 1] / cell), kz = Math.round(m.p()[i * 3 + 2] / cell);
            long key = (kx & 0x1fffff) << 42 | (ky & 0x1fffff) << 21 | (kz & 0x1fffff);
            Integer at = cells.get(key);
            if (at == null) {
                at = pts.size() / 3;
                cells.put(key, at);
                pts.add(m.p()[i * 3]);
                pts.add(m.p()[i * 3 + 1]);
                pts.add(m.p()[i * 3 + 2]);
            }
            remap[i] = at;
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<int[]> tris = new ArrayList<>();
        for (int t = 0; t < m.triangles(); t++) {
            int a = remap[m.tri()[t * 3]], b = remap[m.tri()[t * 3 + 1]], c = remap[m.tri()[t * 3 + 2]];
            if (a == b || b == c || a == c) continue;
            int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
            if (!seen.add(((long) lo << 42) | ((long) mid << 21) | hi)) continue;
            tris.add(new int[]{a, b, c, m.rgb()[t]});
        }
        float[] p = new float[pts.size()];
        for (int i = 0; i < p.length; i++) p[i] = pts.get(i);
        int[] tri = new int[tris.size() * 3], rgb = new int[tris.size()];
        for (int t = 0; t < tris.size(); t++) {
            int[] x = tris.get(t);
            tri[t * 3] = x[0];
            tri[t * 3 + 1] = x[1];
            tri[t * 3 + 2] = x[2];
            rgb[t] = x[3];
        }
        return new Mesh(p, tri, rgb, null, null, null, null, null, 0, hull(p));
    }

    /** Boxes made into one mesh: each {x0, y0, z0, x1, y1, z1} with its colour. */
    static Mesh boxes(double[][] boxes, int[] colours) {
        float[] p = new float[boxes.length * 8 * 3];
        int[] tri = new int[boxes.length * 12 * 3], rgb = new int[boxes.length * 12];
        int[][] faces = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4}, {2, 3, 7}, {2, 7, 6}, {1, 2, 6}, {1, 6, 5}, {0, 4, 7}, {0, 7, 3}};
        for (int b = 0; b < boxes.length; b++) {
            double[] q = boxes[b];
            double[][] c = {{q[0], q[1], q[2]}, {q[3], q[1], q[2]}, {q[3], q[4], q[2]}, {q[0], q[4], q[2]},
                    {q[0], q[1], q[5]}, {q[3], q[1], q[5]}, {q[3], q[4], q[5]}, {q[0], q[4], q[5]}};
            for (int i = 0; i < 8; i++) {
                p[(b * 8 + i) * 3] = (float) c[i][0];
                p[(b * 8 + i) * 3 + 1] = (float) c[i][1];
                p[(b * 8 + i) * 3 + 2] = (float) c[i][2];
            }
            for (int f = 0; f < 12; f++) {
                tri[(b * 12 + f) * 3] = b * 8 + faces[f][0];
                tri[(b * 12 + f) * 3 + 1] = b * 8 + faces[f][1];
                tri[(b * 12 + f) * 3 + 2] = b * 8 + faces[f][2];
                rgb[b * 12 + f] = colours[b];
            }
        }
        return new Mesh(p, tri, rgb, null, null, null, null, null, 0, hull(p));
    }

    /**
     * Copies of a textured mesh made into one (a sandbag wall from one bag): each placement {x, y, z, heading
     * degrees, scale} in the new mesh's axes.
     */
    static Mesh compose(Mesh m, double[][] at) {
        int nv = m.p().length / 3, nt = m.triangles();
        float[] p = new float[nv * 3 * at.length], uv = new float[nv * 2 * at.length], nr = new float[nv * 3 * at.length];
        float[] n0 = m.normals()[0];
        int[] tri = new int[nt * 3 * at.length], rgb = new int[nt * at.length];
        byte[] tex = new byte[nt * at.length];
        for (int k = 0; k < at.length; k++) {
            double c = Math.cos(Math.toRadians(at[k][3])), s = Math.sin(Math.toRadians(at[k][3])), sc = at[k][4];
            for (int i = 0; i < nv; i++) {
                double x = m.p()[i * 3] * sc, y = m.p()[i * 3 + 1] * sc, z = m.p()[i * 3 + 2] * sc;
                // heading clockwise from x seen from above: x forward, y left
                p[(k * nv + i) * 3] = (float) (at[k][0] + x * c + y * s);
                p[(k * nv + i) * 3 + 1] = (float) (at[k][1] - x * s + y * c);
                p[(k * nv + i) * 3 + 2] = (float) (at[k][2] + z);
                double nx = n0[i * 3], ny = n0[i * 3 + 1];
                nr[(k * nv + i) * 3] = (float) (nx * c + ny * s);
                nr[(k * nv + i) * 3 + 1] = (float) (-nx * s + ny * c);
                nr[(k * nv + i) * 3 + 2] = n0[i * 3 + 2];
                uv[(k * nv + i) * 2] = m.uv()[i * 2];
                uv[(k * nv + i) * 2 + 1] = m.uv()[i * 2 + 1];
            }
            for (int t = 0; t < nt * 3; t++) tri[k * nt * 3 + t] = m.tri()[t] + k * nv;
            System.arraycopy(m.rgb(), 0, rgb, k * nt, nt);
            System.arraycopy(m.tex(), 0, tex, k * nt, nt);
        }
        return new Mesh(p, tri, rgb, uv, tex, m.textures(), null, new float[][]{nr}, m.flags(), hull(p));
    }

    // ------------------------------------------------------------------ drawing

    /** A picture's depth buffer (1 / distance, 0 = nothing), one per thread and size; and the vertex scratch. */
    static final class Frame {
        final int w, h;
        final float[] depth;
        int[] px;
        // per vertex: camera space, picture, light (front / back)
        float[] cx = new float[0], cy, cz, sx, sy, lf, lb, wx, wy, wz, nx, ny, nz;
        /** A ghost's blocks ({@link #ghost}). */
        float[] blocks = new float[256];

        Frame(int w, int h) {
            this.w = w;
            this.h = h;
            depth = new float[w * h];
        }

        /** The picture's pixels drawn into from now on (TYPE_INT_RGB), the depth cleared. */
        void begin(BufferedImage img) {
            px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            Arrays.fill(depth, 0f);
        }

        void room(int nv) {
            if (cx.length >= nv) return;
            int n = Math.max(nv, cx.length * 3 / 2);
            cx = new float[n];
            cy = new float[n];
            cz = new float[n];
            sx = new float[n];
            sy = new float[n];
            lf = new float[n];
            lb = new float[n];
            wx = new float[n];
            wy = new float[n];
            wz = new float[n];
            nx = new float[n];
            ny = new float[n];
            nz = new float[n];
        }
    }

    private static final ThreadLocal<Frame> FRAMES = new ThreadLocal<>();

    /** This thread's frame for the picture, cleared (img: TYPE_INT_RGB). */
    static Frame frame(BufferedImage img) {
        Frame f = FRAMES.get();
        if (f == null || f.w != img.getWidth() || f.h != img.getHeight()) {
            f = new Frame(img.getWidth(), img.getHeight());
            FRAMES.set(f);
        }
        f.begin(img);
        return f;
    }

    /** The nearest the camera draws: closer triangles are left out. */
    private static final double NEAR_M = 0.15;
    /** Light: what a face turned away from the sun gets, and what the sun adds. */
    private static final float AMBIENT = 0.50f, DIRECT = 0.58f;
    /** The flat ground's brightness (its texture as it is, a little dimmed), and a rise's light scaled to meet it. */
    static final float GROUND_LIGHT = 0.92f;
    private static final float TERRAIN_K = GROUND_LIGHT / (AMBIENT + DIRECT * (float) 0.70);
    /**
     * The memory's fog (2026-11-10): nothing fades to FOG_FROM_M, then things go into the void's white,
     * 1 - e^(-((d - from) / FOG_M)^1.4) - the island round the action clear, the floor white to the horizon.
     */
    static final double FOG_FROM_M = 40, FOG_M = 45;

    /** The mesh at north, east, up, as {@link #draw(Frame, Scene.Camera, Mesh, int, int, float, double, double, double, double, double, double, double, int)} in its frame 0. */
    static void draw(Frame f, Scene.Camera cam, Mesh m, double n, double e, double u, double headingDeg, double pitchDeg,
                     double rollDeg, double scale, int tint) {
        draw(f, cam, m, 0, 0, 0, n, e, u, headingDeg, pitchDeg, rollDeg, scale, tint);
    }

    /**
     * The mesh at north, east, up (metres, the scene's), turned by heading / pitch / roll (degrees), scale times its
     * size, posed between its frames a and b (blend 0..1); tint (an RGB, or -1 for its own colours) mixed in by half.
     * A {@link #CARD} turns to face the camera.
     */
    static void draw(Frame f, Scene.Camera cam, Mesh m, int fa, int fb, float blend, double n, double e, double u,
                     double headingDeg, double pitchDeg, double rollDeg, double scale, int tint) {
        if ((m.flags() & CARD) != 0) {
            card(f, cam, m, n, e, u, scale);
            return;
        }
        boolean card = false, terrain = (m.flags() & TERRAIN) != 0;
        // a quick reject: the mesh's middle well behind the camera
        double[] mid = cam.cam(n, e, u);
        if (mid[2] < -8 * Math.max(1, scale)) return;
        double h = Math.toRadians(headingDeg), pt = Math.toRadians(pitchDeg), rl = Math.toRadians(rollDeg);
        double ch = Math.cos(h), sh = Math.sin(h), cp = Math.cos(pt), sp = Math.sin(pt), cr = Math.cos(rl), sr = Math.sin(rl);
        // forward, left, up after heading, pitch and roll (north, east, up)
        double f0 = ch * cp, f1 = sh * cp, f2 = sp;
        double u0 = -ch * sp, u1 = -sh * sp, u2 = cp;
        double l0 = sh * cr + u0 * sr, l1 = -ch * cr + u1 * sr, l2 = u2 * sr;
        double v0 = u0 * cr - sh * sr, v1 = u1 * cr + ch * sr, v2 = u2 * cr;
        float[] pa = m.frames() == null ? m.p() : m.frames()[Math.min(fa, m.frames().length - 1)];
        float[] pb = m.frames() == null ? pa : m.frames()[Math.min(fb, m.frames().length - 1)];
        float[] na = m.normals() == null ? null : m.normals()[m.frames() == null ? 0 : Math.min(fa, m.normals().length - 1)];
        float[] nb = m.normals() == null ? null : m.normals()[m.frames() == null ? 0 : Math.min(fb, m.normals().length - 1)];
        boolean lerp = pa != pb && blend > 0;
        int nv = pa.length / 3;
        f.room(nv);
        double[][] r = cam.r;
        double sunN = SUN[0], sunE = SUN[1], sunU = SUN[2];
        float cardLight = AMBIENT + DIRECT * 0.55f;
        for (int i = 0; i < nv; i++) {
            int o = i * 3;
            double x = pa[o], y = pa[o + 1], z = pa[o + 2];
            if (lerp) {
                x += (pb[o] - x) * blend;
                y += (pb[o + 1] - y) * blend;
                z += (pb[o + 2] - z) * blend;
            }
            x *= scale;
            y *= scale;
            z *= scale;
            double wn = n + x * f0 + y * l0 + z * v0, we = e + x * f1 + y * l1 + z * v1, wu = u + x * f2 + y * l2 + z * v2;
            f.wx[i] = (float) wn;
            f.wy[i] = (float) we;
            f.wz[i] = (float) wu;
            double dn = wn - cam.cn, de = we - cam.ce, du = wu - cam.cu;
            float kx = (float) (r[0][0] * dn + r[0][1] * de + r[0][2] * du);
            float ky = (float) (r[1][0] * dn + r[1][1] * de + r[1][2] * du);
            float kz = (float) (r[2][0] * dn + r[2][1] * de + r[2][2] * du);
            f.cx[i] = kx;
            f.cy[i] = ky;
            f.cz[i] = kz;
            if (kz > NEAR_M) {
                f.sx[i] = (float) (cam.cx + cam.f * kx / kz);
                f.sy[i] = (float) (cam.cy + cam.f * ky / kz);
            }
            if (card) {
                f.lf[i] = cardLight;
                f.lb[i] = cardLight;
            } else if (na != null) {
                double ax = na[o], ay = na[o + 1], az = na[o + 2];
                if (lerp) {
                    ax += (nb[o] - ax) * blend;
                    ay += (nb[o + 1] - ay) * blend;
                    az += (nb[o + 2] - az) * blend;
                }
                double wn0 = ax * f0 + ay * l0 + az * v0, wn1 = ax * f1 + ay * l1 + az * v1, wn2 = ax * f2 + ay * l2 + az * v2;
                f.nx[i] = (float) wn0;
                f.ny[i] = (float) wn1;
                f.nz[i] = (float) wn2;
                double s = wn0 * sunN + wn1 * sunE + wn2 * sunU;
                f.lf[i] = AMBIENT + DIRECT * (float) Math.max(0, s);
                f.lb[i] = AMBIENT + DIRECT * (float) Math.max(0, -s);
                if (terrain) {
                    f.lf[i] *= TERRAIN_K;
                    f.lb[i] *= TERRAIN_K;
                }
            }
        }
        int[] tri = m.tri(), rgb = m.rgb();
        boolean textured = m.uv() != null;
        int haze = SimVoid.HORIZON;
        for (int t = 0, q = 0; t < tri.length; t += 3, q++) {
            int a = tri[t], b = tri[t + 1], c = tri[t + 2];
            if (f.cz[a] < NEAR_M || f.cz[b] < NEAR_M || f.cz[c] < NEAR_M) continue;
            // off the picture: all three corners past the same edge
            float sxa = f.sx[a], sxb = f.sx[b], sxc = f.sx[c], sya = f.sy[a], syb = f.sy[b], syc = f.sy[c];
            if ((sxa < 0 && sxb < 0 && sxc < 0) || (sxa >= f.w && sxb >= f.w && sxc >= f.w)
                    || (sya < 0 && syb < 0 && syc < 0) || (sya >= f.h && syb >= f.h && syc >= f.h)) continue;
            // which side faces the camera
            float ex = f.wx[b] - f.wx[a], ey = f.wy[b] - f.wy[a], ez = f.wz[b] - f.wz[a];
            float gx = f.wx[c] - f.wx[a], gy = f.wy[c] - f.wy[a], gz = f.wz[c] - f.wz[a];
            float nx = ey * gz - ez * gy, ny = ez * gx - ex * gz, nz = ex * gy - ey * gx;
            float toCam = (float) (nx * (cam.cn - f.wx[a]) + ny * (cam.ce - f.wy[a]) + nz * (cam.cu - f.wz[a]));
            float la, lb, lc;
            if (na == null && !card) {
                // flat: the face's own light
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl == 0) continue;
                double s = (nx * sunN + ny * sunE + nz * sunU) / nl * (toCam < 0 ? -1 : 1);
                la = lb = lc = AMBIENT + DIRECT * (float) Math.max(0, s);
            } else if (!card && (nx * (f.nx[a] + f.nx[b] + f.nx[c]) + ny * (f.ny[a] + f.ny[b] + f.ny[c]) + nz * (f.nz[a] + f.nz[b] + f.nz[c]) < 0) == (toCam < 0)) {
                // the side the vertices' normals are on faces the camera (whichever way the triangle is wound)
                la = f.lf[a];
                lb = f.lf[b];
                lc = f.lf[c];
            } else {
                la = f.lb[a];
                lb = f.lb[b];
                lc = f.lb[c];
            }
            float fog = (float) fogOf((f.cz[a] + f.cz[b] + f.cz[c]) / 3);
            if (textured) {
                fillTextured(f, m, q, a, b, c, la, lb, lc, fog, haze);
            } else {
                int col = rgb[q];
                if (tint >= 0) col = ((col >> 1) & 0x7f7f7f) + ((tint >> 1) & 0x7f7f7f);
                fillFlat(f, a, b, c, mix(lit(col, (la + lb + lc) / 3), haze, fog));
            }
        }
    }

    /**
     * An impostor card: its texture stood upright at (n, e, u), facing the camera, drawn as the picture sees an upright
     * board there - along the line from its foot to its top (tilted when the camera rolls), its width across that - at
     * the depth of its middle, the texture stepped across it (a card is flat to the camera: no perspective to correct).
     */
    private static void card(Frame f, Scene.Camera cam, Mesh m, double n, double e, double u, double scale) {
        float[] p = m.p();
        double w = (p[4] - p[1]) * scale, hgt = p[8] * scale; // corners 0 (-y, foot) 1 (+y, foot) 2 (top)
        double[] foot = cam.cam(n, e, u), top = cam.cam(n, e, u + hgt), mid = cam.cam(n, e, u + hgt / 2);
        if (mid[2] < 1 || foot[2] < 0.5 || top[2] < 0.5) return;
        double fx = cam.cx + cam.f * foot[0] / foot[2], fy = cam.cy + cam.f * foot[1] / foot[2];
        double tx = cam.cx + cam.f * top[0] / top[2], ty = cam.cy + cam.f * top[1] / top[2];
        double ax = tx - fx, ay = ty - fy, al = Math.hypot(ax, ay);
        if (al < 1) return;
        double half = Math.abs(w) / 2 * cam.f / mid[2];
        // across the board, unit, and its half width on the picture
        double px = -ay / al * half, py = ax / al * half;
        int x0 = (int) Math.max(0, Math.floor(Math.min(Math.min(fx - px, fx + px), Math.min(tx - px, tx + px))));
        int x1 = (int) Math.min(f.w - 1, Math.ceil(Math.max(Math.max(fx - px, fx + px), Math.max(tx - px, tx + px))));
        int y0 = (int) Math.max(0, Math.floor(Math.min(Math.min(fy - py, fy + py), Math.min(ty - py, ty + py))));
        int y1 = (int) Math.min(f.h - 1, Math.ceil(Math.max(Math.max(fy - py, fy + py), Math.max(ty - py, ty + py))));
        if (x0 > x1 || y0 > y1) return;
        Texture tx0 = m.textures()[0];
        double texelsH = tx0.h()[0] / al;
        int level = texelsH <= 1.41 ? 0 : Math.min(tx0.level().length - 1, (int) Math.round(Math.log(texelsH) / Math.log(2)));
        int[] tex = tx0.level()[level];
        int tw = tx0.w()[level], th = tx0.h()[level];
        float iz = (float) (1 / mid[2]);
        float fog = (float) fogOf(mid[2]);
        float light = (AMBIENT + DIRECT * 0.55f) * (1 - fog);
        int haze = SimVoid.HORIZON;
        float hr = ((haze >> 16) & 0xff) * fog, hg = ((haze >> 8) & 0xff) * fog, hb = (haze & 0xff) * fog;
        // texture coordinates as linear functions of the pixel: v from the top (0) to the foot (1), u across
        double a2 = al * al, h2 = 2 * half * half;
        double vx = -ax / a2, vy = -ay / a2, v0 = 1 + (fx * ax + fy * ay) / a2;
        double ux = px / h2, uy = py / h2;
        double u0 = 0.5 - (fx * ux + fy * uy);
        float[] depth = f.depth;
        int[] out = f.px;
        for (int y = y0; y <= y1; y++) {
            double cyy = y + 0.5, cxx = x0 + 0.5;
            double uu = u0 + ux * cxx + uy * cyy, vv = v0 + vx * cxx + vy * cyy;
            int row = y * f.w;
            for (int x = x0; x <= x1; x++, uu += ux, vv += vx) {
                if (uu < 0 || uu >= 1 || vv < 0 || vv >= 1) continue;
                int i = row + x;
                if (iz <= depth[i]) continue;
                int t = tex[(int) (vv * th) * tw + (int) (uu * tw)];
                if (t >>> 24 == 0) continue;
                depth[i] = iz;
                int rr = (int) (((t >> 16) & 0xff) * light + hr), gg = (int) (((t >> 8) & 0xff) * light + hg), bb = (int) ((t & 0xff) * light + hb);
                out[i] = (rr > 255 ? 255 : rr) << 16 | (gg > 255 ? 255 : gg) << 8 | (bb > 255 ? 255 : bb);
            }
        }
    }

    /** A ghost's blocks: about this size (m) near. */
    static final double GHOST_BLOCK_M = 0.55;

    /**
     * A ghost's block (bu, bv of nu x nv; bv 0 at the top): -1 if there is none (the texture clear at its middle, or
     * gone: its top breaks up first, more by gone), else how far it is from going (those about to go are brighter).
     */
    private static float ghostBlock(int[] gtex, int gw, int gh, int nu, int nv, int bu, int bv, int seed, float gone) {
        if (bu < 0 || bv < 0 || bu >= nu || bv >= nv) return -1;
        if (gtex[(int) ((bv + 0.5) / nv * gh) * gw + (int) ((bu + 0.5) / nu * gw)] >>> 24 == 0) return -1;
        float keep = (SimVoid.hash(seed + bu * 73856093 + bv * 19349663) & 0xff) * (0.75f / 255) + (bv + 0.5f) / nv * 0.5f - gone;
        return keep < 0 ? -1 : keep;
    }

    /**
     * A tree card as the memory's ghost of it (2026-11-10): white blocks with light-blue lit rims where the texture is
     * solid, half see-through to what was drawn before it (the void, the floor, the land: the ghosts come after
     * everything else that stands still, nearest first, each pixel once); its top breaking up into the void, more so
     * by gone (0..1). seed: the tree's own pattern.
     */
    static void ghost(Frame f, Scene.Camera cam, Mesh m, double n, double e, double u, double scale, int seed, float gone) {
        float[] p = m.p();
        double w = Math.abs(p[4] - p[1]) * scale, hgt = p[8] * scale;
        double[] foot = cam.cam(n, e, u), top = cam.cam(n, e, u + hgt), mid = cam.cam(n, e, u + hgt / 2);
        if (mid[2] < 1 || foot[2] < 0.5 || top[2] < 0.5) return;
        double fx = cam.cx + cam.f * foot[0] / foot[2], fy = cam.cy + cam.f * foot[1] / foot[2];
        double tx = cam.cx + cam.f * top[0] / top[2], ty = cam.cy + cam.f * top[1] / top[2];
        double ax = fx - tx, ay = fy - ty, al = Math.hypot(ax, ay);
        if (al < 1) return;
        double half = w / 2 * cam.f / mid[2];
        double px = ay / al * half, py = -ax / al * half; // across, to the card's +u side
        // blocks about GHOST_BLOCK_M square (bigger further away: a block stays several pixels), each there if the
        // texture is solid at its middle (read at a coarse level)
        double block = GHOST_BLOCK_M * Math.max(1, mid[2] / 25);
        int nu = Math.max(2, (int) Math.round(w / block)), nv = Math.max(3, (int) Math.round(hgt / block));
        Texture tx0 = m.textures()[0];
        int gl = 0;
        while (gl + 1 < tx0.level().length && tx0.w()[gl + 1] >= nu * 2 && tx0.h()[gl + 1] >= nv * 2) gl++;
        int[] gtex = tx0.level()[gl];
        int gw = tx0.w()[gl], gh = tx0.h()[gl];
        if (f.blocks.length < nu * nv) f.blocks = new float[Math.max(nu * nv, f.blocks.length * 2)];
        float[] keeps = f.blocks;
        for (int bv = 0; bv < nv; bv++) {
            for (int bu = 0; bu < nu; bu++) keeps[bv * nu + bu] = ghostBlock(gtex, gw, gh, nu, nv, bu, bv, seed, gone);
        }
        // each block a square on the picture round its middle (upright: the camera's small rolls leave them square)
        double bw = 2 * half / nu, bh = al / nv;
        float iz = (float) (1 / mid[2]);
        float[] depth = f.depth;
        int[] out = f.px;
        for (int bv = 0; bv < nv; bv++) {
            for (int bu = 0; bu < nu; bu++) {
                float keep = keeps[bv * nu + bu];
                if (keep < 0) continue;
                double vc = (bv + 0.5) / nv, uc = (bu + 0.5) / nu * 2 - 1;
                double cx = tx + ax * vc + px * uc, cy = ty + ay * vc + py * uc;
                int x0 = (int) Math.round(cx - bw / 2), x1 = (int) Math.round(cx + bw / 2) - 1;
                int y0 = (int) Math.round(cy - bh / 2), y1 = (int) Math.round(cy + bh / 2) - 1;
                if (x1 < 0 || y1 < 0 || x0 >= f.w || y0 >= f.h || x1 < x0 || y1 < y0) continue;
                // its rims: lit where the silhouette ends (no block next to it), faint inside
                boolean left = bu == 0 || keeps[bv * nu + bu - 1] < 0, right = bu == nu - 1 || keeps[bv * nu + bu + 1] < 0;
                boolean up = bv == 0 || keeps[(bv - 1) * nu + bu] < 0, down = bv == nv - 1 || keeps[(bv + 1) * nu + bu] < 0;
                int body = keep < 0.08f ? 225 : 165;
                for (int y = Math.max(0, y0); y <= Math.min(f.h - 1, y1); y++) {
                    int row = y * f.w;
                    boolean rowRim = y == y0 || y == y1, rowOuter = (y == y0 && up) || (y == y1 && down);
                    for (int x = Math.max(0, x0); x <= Math.min(f.w - 1, x1); x++) {
                        int i = row + x;
                        if (iz <= depth[i]) continue;
                        int c = blend(out[i], SimVoid.GHOST, body);
                        if (rowOuter || (x == x0 && left) || (x == x1 && right)) c = blend(c, SimVoid.RIM, 205);
                        else if (rowRim || x == x0 || x == x1) c = blend(c, SimVoid.RIM, 45);
                        out[i] = c;
                        depth[i] = iz; // what is behind it stays behind (each pixel drawn once: the ghosts nearest first)
                    }
                }
            }
        }
    }

    /** One flat triangle (corners in the frame's scratch) through the depth buffer. */
    private static void fillFlat(Frame f, int a, int b, int c, int colour) {
        float x0 = f.sx[a], y0 = f.sy[a], x1 = f.sx[b], y1 = f.sy[b], x2 = f.sx[c], y2 = f.sy[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(f.w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(f.h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float inv = 1 / area, iz0 = 1 / f.cz[a], iz1 = 1 / f.cz[b], iz2 = 1 / f.cz[c];
        // the barycentric weights step by these per pixel to the right
        float d0 = -(y2 - y1) * inv, d1 = -(y0 - y2) * inv;
        float[] depth = f.depth;
        int[] px = f.px;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f, qx = minX + 0.5f;
            float w0 = ((x1 - qx) * (y2 - py) - (x2 - qx) * (y1 - py)) * inv;
            float w1 = ((x2 - qx) * (y0 - py) - (x0 - qx) * (y2 - py)) * inv;
            int row = y * f.w;
            for (int x = minX; x <= maxX; x++, w0 += d0, w1 += d1) {
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float d = w0 * iz0 + w1 * iz1 + w2 * iz2;
                int i = row + x;
                if (d <= depth[i]) continue;
                depth[i] = d;
                px[i] = colour;
            }
        }
    }

    /** Added before wrapping a texel coordinate (a power of two above any): the wrap is then a mask, no floor. */
    private static final int WRAP = 1 << 22;

    /** One textured triangle: perspective-correct, nearest texel at about one a pixel, its light across it. */
    private static void fillTextured(Frame f, Mesh m, int q, int a, int b, int c, float la, float lb, float lc, float fog, int haze) {
        float x0 = f.sx[a], y0 = f.sy[a], x1 = f.sx[b], y1 = f.sy[b], x2 = f.sx[c], y2 = f.sy[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(f.w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(f.h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        Texture tx = m.textures()[m.tex()[q]];
        float[] uv = m.uv();
        float ua = uv[a * 2], va = uv[a * 2 + 1], ub = uv[b * 2], vb = uv[b * 2 + 1], uc = uv[c * 2], vc = uv[c * 2 + 1];
        // the mip level: about one texel a pixel
        double texels = Math.abs((ub - ua) * (vc - va) - (uc - ua) * (vb - va)) * tx.w()[0] * (double) tx.h()[0];
        int level = texels <= Math.abs(area) ? 0 : (int) (0.5 * Math.log(texels / Math.abs(area)) / Math.log(2) + 0.5);
        level = Math.max(0, Math.min(tx.level().length - 1, level));
        int[] tex = tx.level()[level];
        int tw = tx.w()[level], th = tx.h()[level];
        boolean alpha = tx.alpha();
        float inv = 1 / area, iz0 = 1 / f.cz[a], iz1 = 1 / f.cz[b], iz2 = 1 / f.cz[c];
        float u0 = ua * iz0, u1 = ub * iz1, u2 = uc * iz2, v0 = va * iz0, v1 = vb * iz1, v2 = vc * iz2;
        float d0 = -(y2 - y1) * inv, d1 = -(y0 - y2) * inv;
        int hr = (haze >> 16) & 0xff, hg = (haze >> 8) & 0xff, hb = haze & 0xff;
        float keep = 1 - fog;
        float hr0 = hr * fog, hg0 = hg * fog, hb0 = hb * fog;
        float[] depth = f.depth;
        int[] px = f.px;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f, qx = minX + 0.5f;
            float w0 = ((x1 - qx) * (y2 - py) - (x2 - qx) * (y1 - py)) * inv;
            float w1 = ((x2 - qx) * (y0 - py) - (x0 - qx) * (y2 - py)) * inv;
            int row = y * f.w;
            for (int x = minX; x <= maxX; x++, w0 += d0, w1 += d1) {
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float iz = w0 * iz0 + w1 * iz1 + w2 * iz2;
                int i = row + x;
                if (iz <= depth[i]) continue;
                float z = 1 / iz;
                float u = (w0 * u0 + w1 * u1 + w2 * u2) * z, v = (w0 * v0 + w1 * v1 + w2 * v2) * z;
                int t = tex[(((int) (v * th + WRAP)) & (th - 1)) * tw + (((int) (u * tw + WRAP)) & (tw - 1))];
                if (alpha && t >>> 24 == 0) continue;
                depth[i] = iz;
                float l = (w0 * la + w1 * lb + w2 * lc) * keep;
                int rr = (int) (((t >> 16) & 0xff) * l + hr0), gg = (int) (((t >> 8) & 0xff) * l + hg0), bb = (int) ((t & 0xff) * l + hb0);
                px[i] = (rr > 255 ? 255 : rr) << 16 | (gg > 255 ? 255 : gg) << 8 | (bb > 255 ? 255 : bb);
            }
        }
    }

    // ------------------------------------------------------------------ the ground, shadows, puffs

    /** Where the ground's tracks are: a weight map (0 grass .. 255 dirt) over a square of the world. */
    record GroundMap(byte[] dirt, byte[] tone, int size, double cellM, double n0, double e0) {
        /** Its layer at (n, e), bilinear (0..255 * 256); off the map: off. */
        int sample(byte[] layer, double n, double e, int off) {
            double fi = (n - n0) / cellM - 0.5, fj = (e - e0) / cellM - 0.5;
            int i = (int) Math.floor(fi), j = (int) Math.floor(fj);
            if (i < 0 || j < 0 || i >= size - 1 || j >= size - 1) return off;
            int a = i * size + j;
            int wi = (int) ((fi - i) * 256), wj = (int) ((fj - j) * 256);
            int top = (layer[a] & 0xff) * (256 - wj) + (layer[a + 1] & 0xff) * wj;
            int bottom = (layer[a + size] & 0xff) * (256 - wj) + (layer[a + size + 1] & 0xff) * wj;
            return (top * (256 - wi) + bottom * wi) >> 8;
        }
    }

    /** The ground's texture repeats every this many metres. */
    static final double GROUND_TILE_M = 4;

    /**
     * The memory round a scene (2026-11-10, the pilot: the tour's scenes in an "Animus style environment"): the
     * realistic land is an island round the action on a white floor, a glowing grid on the floor lined up with the
     * scene. land: one cell a metre square in the scene's frame (metres ahead / right of its origin along its heading;
     * cells from a0 / r0, na x nr): {@link #LAND}, {@link #EDGE} (a tile glowing light blue where the land breaks up)
     * or 0 (the floor).
     */
    record Memory(double originN, double originE, double headingDeg, byte[] land, int a0, int r0, int na, int nr) {
        static final byte LAND = 2, EDGE = 1;

        int at(int ahead, int right) {
            int i = ahead - a0, j = right - r0;
            return i < 0 || j < 0 || i >= na || j >= nr ? 0 : land[i * nr + j];
        }
    }

    /**
     * The ground under the horizon: on the land grass, and dirt where the map says (blended), lighter and darker where
     * its tone says, the textures' mip level by distance; round it the memory's floor ({@link #memory}); both fading
     * into the void's white with distance; the depth left at 0 (the ground is behind everything on it). Worked out
     * exactly every {@link #SPAN} pixels along a row and stepped in between (along a row of a level camera the ground's
     * distance does not change: the stepping is exact then; rolled, close to it).
     */
    static void ground(Frame f, Scene.Camera cam, Texture grass, Texture dirt, GroundMap map, Memory mem) {
        double[][] r = cam.r;
        int haze = SimVoid.HORIZON;
        float hr = (haze >> 16) & 0xff, hg = (haze >> 8) & 0xff, hb = haze & 0xff;
        int levels = Math.min(grass.level().length, dirt.level().length);
        int[] px = f.px;
        // the camera's look against the scene's axes: how a pixel's footprint falls across the grid's two sets of lines
        double hd = Math.toRadians(mem.headingDeg()), ch = Math.cos(hd), sh = Math.sin(hd);
        double fl = Math.hypot(r[2][0], r[2][1]);
        double cphi = fl < 1e-3 ? 1 : (r[2][0] * ch + r[2][1] * sh) / fl, sphi = fl < 1e-3 ? 0 : (-r[2][0] * sh + r[2][1] * ch) / fl;
        double[] look = {ch, sh, cphi, sphi};
        double[] a = new double[10], b = new double[10];
        for (int y = 0; y < f.h; y++) {
            double v = (y + 0.5 - cam.cy) / cam.f;
            // the ray through (x, y): D0 + x * Dx in the world
            double d0n = r[1][0] * v + r[2][0] - r[0][0] * (cam.cx - 0.5) / cam.f, dxn = r[0][0] / cam.f;
            double d0e = r[1][1] * v + r[2][1] - r[0][1] * (cam.cx - 0.5) / cam.f, dxe = r[0][1] / cam.f;
            double d0u = r[1][2] * v + r[2][2] - r[0][2] * (cam.cx - 0.5) / cam.f, dxu = r[0][2] / cam.f;
            int row = y * f.w;
            boolean haveA = groundAt(cam, map, mem, look, d0n, d0e, d0u, dxn, dxe, dxu, 0, grass, levels, a);
            for (int x0 = 0; x0 < f.w; x0 += SPAN) {
                int x1 = Math.min(f.w, x0 + SPAN);
                boolean haveB = groundAt(cam, map, mem, look, d0n, d0e, d0u, dxn, dxe, dxu, x1, grass, levels, b);
                if (haveA && haveB) {
                    // {texel u, texel v (level 0), level, light, fog, dirt, ahead, right, footprints}: stepped across the span
                    int lev = (int) Math.min(a[2], b[2]);
                    int tw = grass.w()[lev], th = grass.h()[lev];
                    double sc = (double) tw / grass.w()[0];
                    int n = x1 - x0;
                    int iu = (int) ((a[0] * sc + WRAP_GROUND) * 256), iv = (int) ((a[1] * sc + WRAP_GROUND) * 256);
                    int su = (int) ((b[0] - a[0]) * sc * 256 / n), sv = (int) ((b[1] - a[1]) * sc * 256 / n);
                    float k = (float) a[3], sk = (float) ((b[3] - a[3]) / n), fog = (float) a[4], sf = (float) ((b[4] - a[4]) / n);
                    float wd = (float) a[5], sw = (float) ((b[5] - a[5]) / n);
                    float fa = (float) a[6], sa = (float) ((b[6] - a[6]) / n), fr = (float) a[7], sr = (float) ((b[7] - a[7]) / n);
                    float pa = (float) a[8], spa = (float) ((b[8] - a[8]) / n), pr = (float) a[9], spr = (float) ((b[9] - a[9]) / n);
                    int[] gt = grass.level()[lev], dt = dirt.level()[lev];
                    int mu = tw - 1, mv = th - 1;
                    for (int x = x0; x < x1; x++, iu += su, iv += sv, k += sk, fog += sf, wd += sw, fa += sa, fr += sr, pa += spa, pr += spr) {
                        int st = mem.at((int) Math.floor(fa), (int) Math.floor(fr));
                        int c = 0;
                        if (st == Memory.LAND) {
                            int idx = ((iv >> 8) & mv) * tw + ((iu >> 8) & mu);
                            c = gt[idx];
                            int w = (int) wd;
                            if (w > 0) c = blend(c, dt[idx], w);
                            int rr = (int) (((c >> 16) & 0xff) * k + hr * fog), gg = (int) (((c >> 8) & 0xff) * k + hg * fog), bb = (int) ((c & 0xff) * k + hb * fog);
                            c = (rr > 255 ? 255 : rr) << 16 | (gg > 255 ? 255 : gg) << 8 | (bb > 255 ? 255 : bb);
                        }
                        px[row + x] = memory(c, st, fa, fr, pa, pr, fog);
                    }
                } else {
                    // the horizon crosses the span: pixel by pixel
                    for (int x = x0; x < x1; x++) {
                        if (!groundAt(cam, map, mem, look, d0n, d0e, d0u, dxn, dxe, dxu, x, grass, levels, a)) continue;
                        int st = mem.at((int) Math.floor(a[6]), (int) Math.floor(a[7]));
                        float fog = (float) a[4];
                        int c = 0;
                        if (st == Memory.LAND) {
                            int lev = (int) a[2];
                            int tw = grass.w()[lev], th = grass.h()[lev];
                            double sc = (double) tw / grass.w()[0];
                            int idx = (((int) (a[1] * sc + WRAP_GROUND)) & (th - 1)) * tw + (((int) (a[0] * sc + WRAP_GROUND)) & (tw - 1));
                            c = grass.level()[lev][idx];
                            if (a[5] >= 1) c = blend(c, dirt.level()[lev][idx], (int) a[5]);
                            float k = (float) a[3];
                            int rr = (int) (((c >> 16) & 0xff) * k + hr * fog), gg = (int) (((c >> 8) & 0xff) * k + hg * fog), bb = (int) ((c & 0xff) * k + hb * fog);
                            c = Math.min(255, rr) << 16 | Math.min(255, gg) << 8 | Math.min(255, bb);
                        }
                        px[row + x] = memory(c, st, (float) a[6], (float) a[7], (float) a[8], (float) a[9], fog);
                    }
                    haveB = groundAt(cam, map, mem, look, d0n, d0e, d0u, dxn, dxe, dxu, x1, grass, levels, b);
                }
                double[] tmp = a;
                a = b;
                b = tmp;
                haveA = haveB;
            }
        }
    }

    /** The floor's grid: thin lines every GRID_M metres, brighter ones every GRID_MAJOR_M (they reach the horizon). */
    static final double GRID_M = 2, GRID_MAJOR_M = 8;

    /**
     * The memory at a ground pixel: the land as drawn (c), an edge tile (light blue, its rim lit) or the white floor
     * (whiter and bluer with distance: fog), and over the floor and tiles the grid's glowing lines - their width from
     * the pixel's footprint across them (pa, pr metres), thinned out where they would crowd. fa, fr: where, in the
     * scene's metres.
     */
    private static int memory(int c, int st, float fa, float fr, float pa, float pr, float fog) {
        int fi = Math.min(255, (int) (fog * 255));
        if (st == Memory.EDGE) {
            int ia = (int) Math.floor(fa), ir = (int) Math.floor(fr);
            float ua = fa - ia, ur = fr - ir, rimA = Math.max(0.05f, pa * 1.3f), rimR = Math.max(0.05f, pr * 1.3f);
            boolean rim = ua < rimA || ua > 1 - rimA || ur < rimR || ur > 1 - rimR;
            int hs = SimVoid.hash(ia * 73856093 ^ ir * 19349663) & 0xff;
            c = rim ? SimVoid.RIM : blend(SimVoid.TILE, 0xf6fbff, hs * 3 / 4);
            c = blend(c, SimVoid.HORIZON, fi);
        } else if (st != Memory.LAND) {
            c = VOID_FLOOR[fi];
        }
        if (st == Memory.LAND) return c; // the land as it is
        float g = Math.max(line(fa, pa, (float) GRID_M, 0.5f, 0.55f), line(fr, pr, (float) GRID_M, 0.5f, 0.55f));
        g = Math.max(g, Math.max(line(fa, pa, (float) GRID_MAJOR_M, 2.2f, 0.9f), line(fr, pr, (float) GRID_MAJOR_M, 2.2f, 0.9f)));
        g *= 1 - fog * 0.55f;
        return g > 0.004f ? blend(c, SimVoid.GRID, (int) (g * 255)) : c;
    }

    /**
     * How much of a line every spacing metres covers a pixel at x (metres) whose footprint across the lines is p
     * metres: a thin line about a pixel wide with a soft glow, strength at most; none once a pixel spans more than
     * crowd metres (the lines would run together).
     */
    private static float line(float x, float p, float spacing, float crowd, float strength) {
        if (p >= crowd) return 0;
        float m = x / spacing;
        float d = Math.abs(m - (float) Math.floor(m + 0.5f)) * spacing; // metres to the nearest line
        float w = Math.max(p, 0.01f);
        float cov = Math.max(0, 1 - d / (w * 0.9f)) + 0.22f * Math.max(0, 1 - d / (w * 3.5f));
        float fade = Math.min(1, (crowd - p) / (crowd * 0.6f));
        return Math.min(1, cov) * strength * fade;
    }

    /** The void's floor by fog (0..255): white near, the horizon's light blue far. */
    private static final int[] VOID_FLOOR = new int[256];

    static {
        for (int i = 0; i < 256; i++) VOID_FLOOR[i] = SimVoid.mix(SimVoid.FLOOR, SimVoid.HORIZON, i / 255.0);
    }

    /** Pixels worked out exactly along a row of the ground; those between are stepped. */
    static final int SPAN = 16;
    /** Added to a ground texel coordinate before it is wrapped (a power of two, above any texel reached). */
    private static final int WRAP_GROUND = 1 << 16;

    /**
     * The ground seen at pixel x of a row: out = {texel u, texel v at level 0, level, light, fog, dirt 0..255, metres
     * ahead and right in the scene's frame, a pixel's footprint across the grid's lines of each (metres)}; false for
     * the sky (or too far to wrap). look: the scene's heading's cos, sin; the camera's look against it, cos, sin.
     */
    private static boolean groundAt(Scene.Camera cam, GroundMap map, Memory mem, double[] look, double d0n, double d0e,
                                    double d0u, double dxn, double dxe, double dxu, int x, Texture grass, int levels, double[] out) {
        double du = d0u + x * dxu;
        if (du > -1e-4) return false;
        double t = -cam.cu / du;
        double dn = d0n + x * dxn, de = d0e + x * dxe;
        double gn = cam.cn + t * dn, ge = cam.ce + t * de;
        double len = Math.sqrt(dn * dn + de * de + du * du), dist = t * len;
        double texPerM = grass.w()[0] / GROUND_TILE_M;
        double u = ge * texPerM, v = gn * texPerM;
        if (Math.abs(u) > WRAP_GROUND / 2.0 || Math.abs(v) > WRAP_GROUND / 2.0) return false;
        // texels a pixel there, spread over the slant: the texture's level (each halves them)
        double slant = Math.max(0.02, -du / len);
        double texels = dist / cam.f / Math.max(0.25, slant) * texPerM;
        int lev = texels <= 1.5 ? 0 : Math.min(levels - 1, Math.getExponent((float) (texels / 1.5)) + 1);
        int tone = map == null ? 128 << 8 : map.sample(map.tone(), gn, ge, 128 << 8);
        double fog = fogOf(dist);
        out[0] = u;
        out[1] = v;
        out[2] = lev;
        out[3] = GROUND_LIGHT * (0.75 + tone / 131072.0) * (1 - fog);
        out[4] = fog;
        out[5] = map == null ? 0 : map.sample(map.dirt(), gn, ge, 0) / 256.0;
        double rn = gn - mem.originN(), re = ge - mem.originE();
        out[6] = rn * look[0] + re * look[1];
        out[7] = -rn * look[1] + re * look[0];
        // a pixel's footprint: along the look (stretched by the slant), across it
        double along = dist / cam.f / slant, across = dist / cam.f;
        out[8] = Math.hypot(along * look[2], across * look[3]);
        out[9] = Math.hypot(along * look[3], across * look[2]);
        return true;
    }

    /** The fog's share by distance, every half metre to 2 km (exp is dear per pixel). */
    private static final float[] FOG = new float[4096];

    static {
        for (int i = 0; i < FOG.length; i++) FOG[i] = (float) (1 - Math.exp(-Math.pow(Math.max(0, i * 0.5 - FOG_FROM_M) / FOG_M, 1.4)));
    }

    private static double fogOf(double dist) {
        int i = (int) (dist * 2);
        return FOG[i < 0 ? 0 : Math.min(FOG.length - 1, i)];
    }

    private static int blend(int a, int b, int w) {
        int k = 255 - w;
        return (((a >> 16 & 0xff) * k + (b >> 16 & 0xff) * w) / 255) << 16 | (((a >> 8 & 0xff) * k + (b >> 8 & 0xff) * w) / 255) << 8
                | ((a & 0xff) * k + (b & 0xff) * w) / 255;
    }

    /** How much a shadow darkens the ground. */
    static final double SHADOW = 0.62;

    /**
     * The mesh's shadow on the ground, as {@link #draw} places it: its footprint and that footprint cast along the
     * sun from 0.7 of its height, as one convex shape. Only the ground is darkened (pixels nothing was drawn into).
     */
    static void castShadow(Frame f, Scene.Camera cam, Mesh m, double n, double e, double headingDeg, double scale, double heightM) {
        float[] hull = m.hull();
        if (hull == null) return;
        int k = (hull.length - 1) / 2;
        double h = Math.toRadians(headingDeg), c = Math.cos(h), s = Math.sin(h);
        double lift = (heightM > 0 ? heightM : hull[hull.length - 1] * scale) * 0.7;
        double cn = -SUN[0] / SUN[2] * lift, ce = -SUN[1] / SUN[2] * lift;
        double[] pts = new double[k * 4];
        for (int i = 0; i < k; i++) {
            double x = hull[i * 2] * scale, y = hull[i * 2 + 1] * scale;
            double wn = n + x * c + y * s, we = e + x * s - y * c;
            pts[i * 2] = wn;
            pts[i * 2 + 1] = we;
            pts[(k + i) * 2] = wn + cn;
            pts[(k + i) * 2 + 1] = we + ce;
        }
        groundPolygon(f, cam, convex(pts), SHADOW * (1 - fogOf(cam.cam(n, e, 0)[2])));
    }

    /** A soft round shadow on the ground (radius metres, how dark 0..1): something in the air, a tree's crown. */
    static void shadow(Frame f, Scene.Camera cam, double n, double e, double radius, double dark) {
        double[] c = cam.cam(n, e, 0);
        if (c[2] < 0.5) return;
        double[] fwdC = cam.cam(n + radius, e, 0), sideC = cam.cam(n, e + radius, 0);
        if (fwdC[2] < 0.3 || sideC[2] < 0.3) return;
        double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2];
        double ax = cam.cx + cam.f * fwdC[0] / fwdC[2] - sx, ay = cam.cy + cam.f * fwdC[1] / fwdC[2] - sy;
        double bx = cam.cx + cam.f * sideC[0] / sideC[2] - sx, by = cam.cy + cam.f * sideC[1] / sideC[2] - sy;
        double rx = Math.max(Math.abs(ax), Math.abs(bx)), ry = Math.max(Math.abs(ay), Math.abs(by));
        if (rx < 1 || ry < 0.5) return;
        dark *= 1 - fogOf(c[2]); // none in the white far away
        if (dark < 0.01) return;
        int x0 = Math.max(0, (int) (sx - rx)), x1 = Math.min(f.w - 1, (int) (sx + rx));
        int y0 = Math.max(0, (int) (sy - ry)), y1 = Math.min(f.h - 1, (int) (sy + ry));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * f.w + x;
                if (f.depth[i] != 0) continue;
                double dx = (x - sx) / rx, dy = (y - sy) / ry, r2 = dx * dx + dy * dy;
                if (r2 >= 1) continue;
                f.px[i] = lit(f.px[i], 1 - dark * Math.min(1, (1 - r2) * 2));
            }
        }
    }

    /** The convex hull of points (n e pairs). */
    private static double[] convex(double[] pts) {
        int n = pts.length / 2;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> pts[a * 2] != pts[b * 2] ? Double.compare(pts[a * 2], pts[b * 2]) : Double.compare(pts[a * 2 + 1], pts[b * 2 + 1]));
        int[] h = new int[2 * n + 1];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int j = 0; j < n; j++) {
                int i = pass == 0 ? idx[j] : idx[n - 1 - j];
                while (k >= start + 2) {
                    int o = h[k - 2], a = h[k - 1];
                    double cr = (pts[a * 2] - pts[o * 2]) * (pts[i * 2 + 1] - pts[o * 2 + 1]) - (pts[a * 2 + 1] - pts[o * 2 + 1]) * (pts[i * 2] - pts[o * 2]);
                    if (cr > 0) break;
                    k--;
                }
                h[k++] = i;
            }
            k--;
        }
        double[] out = new double[Math.max(0, k) * 2];
        for (int j = 0; j < k; j++) {
            out[j * 2] = pts[h[j] * 2];
            out[j * 2 + 1] = pts[h[j] * 2 + 1];
        }
        return out;
    }

    /** A convex shape on the ground (n e pairs) darkened by dark on the picture's ground pixels. */
    private static void groundPolygon(Frame f, Scene.Camera cam, double[] poly, double dark) {
        int k = poly.length / 2;
        if (k < 3) return;
        double[] xs = new double[k], ys = new double[k];
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < k; i++) {
            double[] c = cam.cam(poly[i * 2], poly[i * 2 + 1], 0);
            if (c[2] < 0.5) return;
            xs[i] = cam.cx + cam.f * c[0] / c[2];
            ys[i] = cam.cy + cam.f * c[1] / c[2];
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        int y0 = Math.max(0, (int) Math.ceil(minY - 0.5)), y1 = Math.min(f.h - 1, (int) Math.floor(maxY - 0.5));
        float keep = (float) (1 - dark);
        for (int y = y0; y <= y1; y++) {
            double py = y + 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < k; i++) {
                int j = (i + 1) % k;
                double ya = ys[i], yb = ys[j];
                if ((py < ya) == (py < yb)) continue;
                double x = xs[i] + (py - ya) / (yb - ya) * (xs[j] - xs[i]);
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            if (lo > hi) continue;
            int x0 = Math.max(0, (int) Math.ceil(lo - 0.5)), x1 = Math.min(f.w - 1, (int) Math.floor(hi - 0.5));
            int row = y * f.w;
            for (int x = x0; x <= x1; x++) {
                int i = row + x;
                if (f.depth[i] != 0) continue;
                f.px[i] = lit(f.px[i], keep);
            }
        }
    }

    /**
     * A soft puff at north, east, up (radius metres): smoke, dust or a flash, its colour mixed in by up to alpha at its
     * middle, fading to its edge; behind what is nearer (the depth tested, not written). add: light added (a flash).
     */
    static void puff(Frame f, Scene.Camera cam, double n, double e, double u, double radius, int rgb, double alpha, boolean add) {
        double[] c = cam.cam(n, e, u);
        if (c[2] < 0.3 || alpha <= 0.004) return;
        double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2], rp = cam.f * radius / c[2];
        if (rp < 0.6) return;
        int x0 = Math.max(0, (int) (sx - rp)), x1 = Math.min(f.w - 1, (int) (sx + rp));
        int y0 = Math.max(0, (int) (sy - rp)), y1 = Math.min(f.h - 1, (int) (sy + rp));
        float iz = (float) (1 / c[2]);
        int pr = (rgb >> 16) & 0xff, pg = (rgb >> 8) & 0xff, pb = rgb & 0xff;
        double inv = 1 / (rp * rp);
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - sy;
            int row = y * f.w;
            for (int x = x0; x <= x1; x++) {
                double dx = x + 0.5 - sx, r2 = (dx * dx + dy * dy) * inv;
                if (r2 >= 1) continue;
                int i = row + x;
                if (f.depth[i] > iz) continue;
                double a = alpha * (1 - r2) * (1 - r2);
                int p = f.px[i];
                int r = p >> 16 & 0xff, g = p >> 8 & 0xff, b = p & 0xff;
                if (add) {
                    r = Math.min(255, (int) (r + pr * a));
                    g = Math.min(255, (int) (g + pg * a));
                    b = Math.min(255, (int) (b + pb * a));
                } else {
                    r = (int) (r + (pr - r) * a);
                    g = (int) (g + (pg - g) * a);
                    b = (int) (b + (pb - b) * a);
                }
                f.px[i] = r << 16 | g << 8 | b;
            }
        }
    }

    static int lit(int c, double k) {
        int r = Math.min(255, (int) ((c >> 16 & 0xff) * k)), g = Math.min(255, (int) ((c >> 8 & 0xff) * k)), b = Math.min(255, (int) ((c & 0xff) * k));
        return r << 16 | g << 8 | b;
    }

    private static int mix(int c, int haze, float fog) {
        if (fog <= 0) return c;
        int r = (int) ((c >> 16 & 0xff) + ((haze >> 16 & 0xff) - (c >> 16 & 0xff)) * fog);
        int g = (int) ((c >> 8 & 0xff) + ((haze >> 8 & 0xff) - (c >> 8 & 0xff)) * fog);
        int b = (int) ((c & 0xff) + ((haze & 0xff) - (c & 0xff)) * fog);
        return r << 16 | g << 8 | b;
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
