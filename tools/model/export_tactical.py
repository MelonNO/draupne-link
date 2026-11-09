"""Bakes the models of the tour's six showcase scenes (fleet/ShowScene.java) into model/tactical.bin.gz: vehicles,
the mortar, soldiers in poses, field gear, obstacles and the trees and bushes as impostor cards, each textured, plus
what is made here (camouflage nets, an antenna mast, concertina wire, the ground's textures). The sources and their
licences are in THIRD-PARTY.md; their original files are not committed.

What is done to them: turned to our axes (x forward, y left, z up, metres), scaled to the real thing's size, decimated
by vertex clustering (tactical_lib.decimate) to a few thousand triangles, their textures scaled down to 256-512 px
(JPEG, or PNG with a 1-bit alpha). The BTR-80 is recoloured from sand to olive; the recon quad's maker logos are
painted out. The trees and bushes are drawn here from the side (tactical_lib.render) into impostor cards, one quad
each. The soldier (a T/A-pose mesh without a skeleton) is given one here: a bone per limb, weights by region, and
posed - a walk cycle and the still poses the scenes use (kneel, aim, prone with binoculars, operator, loader, cover,
radio), with a rifle and what the pose holds.

model/tactical.bin.gz (gzip, big-endian as Java's DataInputStream reads it): 'TAC1'; texture count, per texture its
name (writeUTF), flags (1: alpha mask), byte count and the JPEG / PNG; mesh count, per mesh its name, flags (1: an
impostor card, turned to the camera; 2: two-sided), vertex count, triangle count, frame count, per frame per vertex
x y z int16 millimetres, per frame per vertex its normal (three int8, /127), per vertex u v float (glTF's: v down), per triangle three uint16 indices and a uint8 texture.

Needs numpy and Pillow (python -m pip install numpy pillow), at export time only. Run from the folder holding the
downloads - extracted/<name>/scene.gltf for each Sketchfab glTF zip (btr80, ural4320, uaz, mortar-m224, sandbags,
concrete-barriers, hedgehog, radio, recon-quad, conifer, pine, birch, bushes, soldier-rts-cc0), polyhaven/<name>/ for
the Poly Haven 1k glTFs (wooden_military_crate, ammo_box, metal_jerrycan_green) - with the repository's folder as the
argument (python -I: nothing is imported from the downloads' folders):
python -I <repo>/tools/model/export_tactical.py <repo>"""
import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tactical_lib import *  # noqa: E402

REPO = sys.argv[1]
OUT = os.path.join(REPO, "src", "main", "resources", "model", "tactical.bin.gz")
SF, PH = "extracted", "polyhaven"
rng = np.random.default_rng(4609)

textures = []  # (name, flags, bytes)
meshes = []  # (name, flags, Mesh)


def add(name, m, size=512, flags=0, alpha=False):
    """The mesh and its textures into the pack (a texture already in it, byte for byte, is shared)."""
    index = []
    for k, t in enumerate(m.textures):
        data = encode_texture(t, size, alpha)
        same = [i for i, x in enumerate(textures) if x[2] == data]
        if same:
            index.append(same[0])
        else:
            index.append(len(textures))
            textures.append((name + "-" + str(k), 1 if alpha else 0, data))
    out = Mesh(m.pos, m.uv, m.tri, np.array(index)[m.tex], [], m.nrm)
    out.frames = m.frames
    out.frame_nrm = getattr(m, "frame_nrm", None)
    meshes.append((name, flags, out))
    lo, hi = m.pos.min(0), m.pos.max(0)
    print("%-16s %6d tris %5d verts  size %s" % (name, m.triangles(), len(m.pos), np.round(hi - lo, 2)))


def load(path, keep=None, axes="zxy", yaw=0.0):
    g = Gltf(path)
    return from_parts(parts(g, keep), g, axes, yaw)


# ------------------------------------------------------------------ vehicles

def olive(img):
    """Sand camouflage to an olive drab, the texture's light and shade kept."""
    a = np.asarray(img.convert("RGBA")).astype(np.float64)
    lum = a[..., :3] @ np.array([0.299, 0.587, 0.114])
    lum = lum / max(1.0, lum.mean())
    tone = np.array([86, 92, 58], dtype=np.float64)
    a[..., :3] = np.clip(lum[..., None] * tone, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def shade(img, k, towards_mean=0.0):
    """Darker (k < 1) or lighter, its contrast eased towards its mean colour by towards_mean."""
    a = np.asarray(img.convert("RGBA")).astype(np.float64)
    mean = a[..., :3].reshape(-1, 3).mean(0)
    a[..., :3] = (a[..., :3] + (mean - a[..., :3]) * towards_mean) * k
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")


btr = load(SF + "/btr80/scene.gltf", yaw=90)
btr.textures[0] = olive(btr.textures[0])
fit(btr, length=7.65)
add("btr80", decimate(btr, 4200), 512)

ural = load(SF + "/ural4320/scene.gltf")
fit(ural, length=7.37)
add("ural", decimate(ural, 4500, uvq=12), 512)

uaz = load(SF + "/uaz/scene.gltf", yaw=90)
uaz.textures[0] = shade(uaz.textures[0], 0.78)
fit(uaz, length=4.03)
add("uaz", decimate(uaz, 4000, uvq=12), 512)

# ------------------------------------------------------------------ the mortar and its round

mortar = load(SF + "/mortar-m224/scene.gltf", yaw=180)
fit(mortar, height=1.05)
mortar = decimate(mortar, 2600)
top = mortar.pos[np.argmax(mortar.pos[:, 2])]
print("mortar muzzle at", np.round(top, 3))
add("mortar", mortar, 512)


def cylinder(radius, length, sides, colour_img, x0=0.0, axis=0, r1=None, uv_scale=1.0):
    """A closed tapered cylinder along x (from x0), radius to r1 at its end."""
    r1 = radius if r1 is None else r1
    pos, uv, tri = [], [], []
    for k in range(sides + 1):
        a = 2 * math.pi * k / sides
        for end, r in ((0, radius), (1, r1)):
            pos.append((x0 + end * length, r * math.cos(a), r * math.sin(a)))
            uv.append((k / sides * uv_scale, end))
    for k in range(sides):
        a, b, c, d = 2 * k, 2 * k + 1, 2 * k + 2, 2 * k + 3
        tri += [(a, c, b), (b, c, d)]
    for end, r in ((0, radius), (1, r1)):
        centre = len(pos)
        pos.append((x0 + end * length, 0, 0))
        uv.append((0.5, 0.5))
        for k in range(sides):
            i, j = 2 * k + end, 2 * (k + 1) + end
            tri.append((centre, j, i) if end == 0 else (centre, i, j))
    m = Mesh(np.array(pos), np.array(uv), np.array(tri), np.zeros(len(tri), int), [colour_img])
    if axis == 2:
        m.pos = m.pos[:, [1, 2, 0]]
    return m


def plain(rgb, noise=18, size=64):
    a = np.clip(np.array(rgb)[None, None, :] + rng.normal(0, noise, (size, size, 1)), 0, 255).astype(np.uint8)
    return Image.fromarray(np.concatenate([a, np.full((size, size, 1), 255, np.uint8)], 2), "RGBA")


rnd = merge([cylinder(0.03, 0.30, 8, plain((88, 94, 66)), -0.15), cylinder(0.022, 0.08, 6, plain((70, 70, 66)), -0.23)])
add("round", rnd, 64)

# ------------------------------------------------------------------ field gear and obstacles

crate = load(PH + "/wooden_military_crate/wooden_military_crate_1k.gltf", yaw=90)
fit(crate)
add("crate", decimate(crate, 600, uvq=1, nq=0.5), 256)
ammo = load(PH + "/ammo_box/ammo_box_1k.gltf")
fit(ammo)
add("ammo-box", decimate(ammo, 240, uvq=4, nq=1), 256)
can = load(PH + "/metal_jerrycan_green/metal_jerrycan_green_1k.gltf")
fit(can)
add("jerrycan", decimate(can, 300, uvq=4, nq=1), 256)
radio = load(SF + "/radio/scene.gltf")
fit(radio, length=0.42)
add("radio", decimate(radio, 300, uvq=6, nq=1), 256)

hedgehog = load(SF + "/hedgehog/scene.gltf")
fit(hedgehog, height=1.35)
add("hedgehog", decimate(hedgehog, 600), 256)

barrier = load(SF + "/concrete-barriers/scene.gltf", keep=lambda n: n.startswith("Cube.001_"))
if np.ptp(barrier.pos[:, 1]) > np.ptp(barrier.pos[:, 0]):
    turn(barrier, 90)
fit(barrier, length=2.0)
add("barrier", barrier, 256)

# one sandbag lying flat, out of the set (each bag its own piece of the mesh); the scenes stack them into walls
bags = load(SF + "/sandbags/scene.gltf")
key = np.round(bags.pos * 200).astype(np.int64)
_, vid = np.unique(key, axis=0, return_inverse=True)
vid = vid.ravel()
par = np.arange(vid.max() + 1)


def find(a):
    while par[a] != a:
        par[a] = par[par[a]]
        a = par[a]
    return a


for a, b, c in vid[bags.tri]:
    ra = find(a)
    par[find(b)] = ra
    par[find(c)] = ra
comp = np.array([find(v) for v in vid])
tc = comp[bags.tri[:, 0]]
best = None
for cid in np.unique(tc):
    sel = np.unique(bags.tri[tc == cid])
    lo, hi = bags.pos[sel].min(0), bags.pos[sel].max(0)
    flat = (hi - lo)[2]
    if lo[2] < 0.05 and (best is None or flat < best[0]):
        best = (flat, cid)
keep_t = tc == best[1]
used = np.unique(bags.tri[keep_t])
remap = np.full(len(bags.pos), -1)
remap[used] = np.arange(len(used))
bag = Mesh(bags.pos[used], bags.uv[used], remap[bags.tri[keep_t]], bags.tex[keep_t], bags.textures, bags.nrm[used])
if np.ptp(bag.pos[:, 1]) > np.ptp(bag.pos[:, 0]):
    turn(bag, 90)
bag.textures = [shade(bag.textures[0], 1.18, 0.45)]
fit(bag, length=0.62)
add("sandbag", decimate(bag, 90), 512)

# ------------------------------------------------------------------ drones

quad = load(SF + "/recon-quad/scene.gltf")
t = np.asarray(quad.textures[0]).copy()
h, w = t.shape[:2]
# the maker's logo and lettering on the gimbal and the arm: painted over in the grey around them
for x0, y0, x1, y1 in ((0.455, 0.79, 0.50, 0.84), (0.585, 0.76, 0.64, 0.875)):
    ys, xs = slice(int(y0 * h), int(y1 * h)), slice(int(x0 * w), int(x1 * w))
    patch = t[ys, xs]
    grey = np.median(patch[..., :3].reshape(-1, 3)[patch[..., :3].reshape(-1, 3).sum(1) > 60], axis=0)
    t[ys, xs, :3] = grey.astype(np.uint8)
quad.textures[0] = Image.fromarray(t, "RGBA")
fit(quad, width=0.42)
add("recon-quad", decimate(quad, 1400), 256)

# ------------------------------------------------------------------ made here: nets, mast, wire, ground

def smooth_noise(size, cells, octaves=4, seed=0):
    """Tileable value noise, 0..1."""
    r = np.random.default_rng(seed)
    out = np.zeros((size, size))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        n = cells * (1 << o)
        g = r.random((n, n))
        x = np.arange(size) * n / size
        i0 = np.floor(x).astype(int) % n
        i1 = (i0 + 1) % n
        f = x - np.floor(x)
        f = f * f * (3 - 2 * f)
        a = g[i0][:, i0] * (1 - f)[None, :] + g[i0][:, i1] * f[None, :]
        b = g[i1][:, i0] * (1 - f)[None, :] + g[i1][:, i1] * f[None, :]
        out += amp * (a * (1 - f)[:, None] + b * f[:, None])
        total += amp
        amp *= 0.5
    return out / total


def camo_texture(size=512):
    """Woodland camouflage netting: blotches of four colours, a net's mesh of holes and torn leaves."""
    n1, n2, n3 = smooth_noise(size, 5, 4, 1), smooth_noise(size, 7, 4, 2), smooth_noise(size, 40, 2, 3)
    cols = np.array([[74, 82, 52], [52, 60, 38], [96, 86, 58], [38, 40, 30]], dtype=np.float64)
    idx = np.where(n1 > 0.56, 0, np.where(n2 > 0.55, 2, np.where(n1 < 0.40, 3, 1)))
    rgb = cols[idx] * (0.8 + 0.4 * n3[..., None])
    holes = (smooth_noise(size, 48, 2, 6) > 0.60) & (n3 > 0.45)
    torn = smooth_noise(size, 20, 3, 4) > 0.64
    alpha = np.where(holes | torn, 0, 255)
    a = np.concatenate([rgb.clip(0, 255), alpha[..., None]], 2).astype(np.uint8)
    return Image.fromarray(a, "RGBA")


CAMO = camo_texture()


def net(width, depth, poles):
    """A camouflage net width (y) by depth (x) metres over poles [(x, y, height)], sagging between them, its skirts
    sloping down to the ground on every side."""
    nx, ny = 22, 26
    xs, ys = np.linspace(-depth / 2, depth / 2, nx), np.linspace(-width / 2, width / 2, ny)
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    Z = np.zeros_like(X)
    for px, py, ph in poles:
        d = np.hypot(X - px, Y - py)
        Z = np.maximum(Z, ph - 0.12 * d - 0.03 * d * d)
    edge = np.minimum(np.minimum(X + depth / 2, depth / 2 - X), np.minimum(Y + width / 2, width / 2 - Y))
    skirt = 1.8
    Z = np.where(edge < skirt, Z * (0.35 + 0.65 * np.clip(edge / skirt, 0, 1) ** 1.3), Z)
    Z = np.maximum(Z, 0.05)
    pos = np.stack([X.ravel(), Y.ravel(), Z.ravel()], 1)
    uv = np.stack([X.ravel() / 3.0, Y.ravel() / 3.0], 1)
    tri = []
    for i in range(nx - 1):
        for j in range(ny - 1):
            a, b, c, d = i * ny + j, i * ny + j + 1, (i + 1) * ny + j, (i + 1) * ny + j + 1
            tri += [(a, c, b), (b, c, d)]
    return Mesh(pos, uv, np.array(tri), np.zeros(len(tri), int), [CAMO])


add("net-large", net(11.0, 8.0, [(-1.6, -2.4, 2.9), (-1.6, 2.4, 2.9), (1.8, 0, 3.0)]), 512, flags=2, alpha=True)
add("net-small", net(7.0, 6.0, [(-1.0, -1.5, 2.1), (-1.0, 1.5, 2.1), (1.2, 0, 2.2)]), 512, flags=2, alpha=True)


def ribbon(points, width, img):
    """A thin wire through the points as two crossed strips (seen from any side)."""
    pos, uv, tri = [], [], []
    for side in ((0, 1, 0), (0, 0, 1)):
        base = len(pos)
        for k, p in enumerate(points):
            q = points[min(k + 1, len(points) - 1)] - points[max(k - 1, 0)]
            q = q / max(1e-9, np.linalg.norm(q))
            s = np.cross(q, side)
            if np.linalg.norm(s) < 1e-6:
                s = np.cross(q, (1, 0, 0))
            s = s / np.linalg.norm(s) * width / 2
            pos += [p - s, p + s]
            uv += [(k * 0.1, 0), (k * 0.1, 1)]
        for k in range(len(points) - 1):
            a = base + 2 * k
            tri += [(a, a + 2, a + 1), (a + 1, a + 2, a + 3)]
    return Mesh(np.array(pos), np.array(uv), np.array(tri), np.zeros(len(tri), int), [img])


wire_img = plain((92, 74, 58), 25)
loops, per, length, radius = 22, 14, 6.0, 0.42
tt = np.linspace(0, loops * 2 * math.pi, loops * per + 1)
helix = np.stack([tt / tt[-1] * length - length / 2, radius * np.cos(tt), radius + radius * np.sin(tt) * 0.95], 1)
add("wire", ribbon(helix, 0.016, wire_img), 64, flags=2)

mast_img = plain((78, 84, 60), 10)
parts_ = [cylinder(0.04 - 0.008 * k, 1.6, 8, mast_img, 1.5 * k, axis=2) for k in range(5)]
parts_.append(cylinder(0.006, 1.6, 4, plain((40, 40, 40), 5), 7.4, axis=2))
for a in (0, 2.1, 4.2):
    foot = np.array([2.6 * math.cos(a), 2.6 * math.sin(a), 0.02])
    parts_.append(ribbon(np.array([foot, np.array([0, 0, 5.6])]), 0.012, plain((60, 60, 55), 4)))
add("mast", merge(parts_), 64)

# the ground: grass (4 m a tile) and a dirt track (4 m), seamless
def ground_texture(cols, seed, size=512):
    a, b, c = smooth_noise(size, 6, 5, seed), smooth_noise(size, 64, 2, seed + 1), smooth_noise(size, 160, 1, seed + 2)
    base = cols[0] + (cols[1] - cols[0]) * a[..., None]
    speck = (c - 0.5)[..., None] * cols[2] + (b - 0.5)[..., None] * cols[3]
    rgb = (base + speck).clip(0, 255).astype(np.uint8)
    return Image.fromarray(np.concatenate([rgb, np.full((size, size, 1), 255, np.uint8)], 2), "RGBA")


grass = ground_texture([np.array([78, 92, 48.0]), np.array([104, 108, 60.0]), np.array([70, 80, 40.0]), np.array([30, 30, 20.0])], 11)
dirt = ground_texture([np.array([96, 84, 66.0]), np.array([122, 108, 86.0]), np.array([80, 70, 60.0]), np.array([20, 20, 16.0])], 21)
for name, img in (("ground-grass", grass), ("ground-dirt", dirt)):
    textures.append((name, 0, encode_texture(img, 512, False)))
    print("%-16s texture" % name)

# ------------------------------------------------------------------ trees and bushes: impostor cards

def card(name, m, view_yaw, px_w, px_h, size_hint):
    """The model seen from the side (from view_yaw degrees) drawn into a texture, on a quad of its size."""
    d = (-math.cos(math.radians(view_yaw)), -math.sin(math.radians(view_yaw)), 0)
    lo, hi = m.pos.min(0), m.pos.max(0)
    r = max(-lo[0], hi[0], -lo[1], hi[1]) * 1.02
    box = (-r, lo[2], r, hi[2] + 0.02)
    img, _ = render(m, px_w * 2, px_h * 2, d, (0, 0, 1), box=box, alpha_cut=0.45)
    img = img.resize((px_w, px_h), Image.LANCZOS)
    # a little darker low down and inside: the crown's own shade
    a = np.asarray(img).astype(np.float64)
    yy = np.linspace(0.78, 1.05, px_h)[:, None]
    a[..., :3] *= yy[..., None]
    img = Image.fromarray(a.clip(0, 255).astype(np.uint8), "RGBA")
    # cropped to what is drawn, evenly either side of the trunk (the card stands on its middle)
    cols = np.where(np.asarray(img)[..., 3].max(0) >= 128)[0]
    half = max(px_w / 2 - cols.min(), cols.max() + 1 - px_w / 2) if len(cols) else px_w / 2
    half = int(min(px_w / 2, half + 2))
    img = img.crop((px_w // 2 - half, 0, px_w // 2 + half, px_h))
    wq, hq = 2 * r * (2 * half) / px_w, hi[2] + 0.02 - lo[2]
    pos = np.array([[0, -wq / 2, 0], [0, wq / 2, 0], [0, wq / 2, hq], [0, -wq / 2, hq]])
    # u runs left to right as seen from the front (+x): left is +y
    uv = np.array([[1, 1], [0, 1], [0, 0], [1, 0]], dtype=np.float64)
    q = Mesh(pos, uv, np.array([[0, 1, 2], [0, 2, 3]]), np.zeros(2, int), [img])
    add(name, q, size_hint, flags=1, alpha=True)


def recentre(m):
    lo, hi = m.pos.min(0), m.pos.max(0)
    trunk = m.pos[m.pos[:, 2] < lo[2] + 0.5]
    c = trunk.mean(0) if len(trunk) else (lo + hi) / 2
    m.pos = m.pos - np.array([c[0], c[1], lo[2]])
    return m


spruce = recentre(load(SF + "/conifer/scene.gltf"))
pine = recentre(load(SF + "/pine/scene.gltf"))
birch = recentre(load(SF + "/birch/scene.gltf"))
for k, yaw in enumerate((0, 120)):
    card("spruce-%d" % k, spruce, yaw, 256, 512, 512)
    card("pine-%d" % k, pine, yaw, 256, 512, 512)
    card("birch-%d" % k, birch, yaw, 256, 512, 512)
gb = Gltf(SF + "/bushes/scene.gltf")
for k, which in enumerate(("Bush_big_001", "Bush_big_003", "Bush_medium_002", "Bush_medium_004")):
    bm = recentre(from_parts(parts(gb, keep=lambda n, w=which: n.startswith(w + "_LOD0_")), gb))
    card("bush-%d" % k, bm, 30 * k, 256, 256, 256)

# ------------------------------------------------------------------ the soldier

import soldier_rig  # noqa: E402

for lod, node, target in (("soldier", "soldier1_low.002", 2400), ("soldier-far", "soldier1_low.003", 984)):
    body = load(SF + "/soldier-rts-cc0/scene.gltf", keep=lambda n, w=node: n.startswith(w))
    fit(body, height=1.80)
    add(lod, soldier_rig.pose_all(decimate(soldier_rig.limbs(body), target)), 512)

write_pack(OUT, textures, meshes)
print(OUT, os.path.getsize(OUT), "bytes,", len(textures), "textures,", len(meshes), "meshes")
