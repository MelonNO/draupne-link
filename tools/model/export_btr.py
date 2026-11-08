"""Turns the BTR OBJ (cadnav.com, see THIRD-PARTY.md) and its textures into DRAUPNE's resources for the tour's virtual
overwatch scene (the vehicle drawn real, textured and lit, in a simulation-style world):
model/btr.bin.gz (gzip, big-endian as Java's DataInputStream reads it): 'BTR1', vertex count, per vertex x y z float32
(the vehicle's axes: x forward, y right, z up, metres, z 0 at the wheels' bottom, centred along and across), u v
float32 (OBJ's), a texture byte (0 hull, 1 wheels, 2 gun, 3 lights); triangle count, indices uint16.
model/btr-0.jpg .. btr-3.jpg: those four textures (the hull's brought down to 512 square).
The original files are not committed. Needs Pillow only.

python export_btr.py "<folder with BTR.obj + the jpgs>" src/main/resources/model"""
import sys, os, struct, gzip
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
LENGTH_M = 7.65  # a BTR-80's length

# the OBJ's groups: their texture (what each is, from where it sits: hull, wheels, gun barrel, lights)
PARTS = {"mesh_2": 0, "mesh_3": 1, "mesh_0": 2, "mesh_1": 3}
TEXTURES = [("btr8chas.jpg", 512), ("btr8whl.jpg", 256), ("btr8cal.jpg", 128), ("btr8lite.jpg", 64)]

verts, uvs, tris = [], [], []  # tris: (a, b, c, texture)
group = None
with open(os.path.join(src, "BTR.obj")) as f:
    for line in f:
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            verts.append([float(p[1]), float(p[2]), float(p[3])])
        elif p[0] == "vt":
            uvs.append([float(p[1]), float(p[2])])
        elif p[0] == "g":
            group = p[1].rsplit(".", 1)[-1]
        elif p[0] == "f":
            if group not in PARTS:
                continue  # mesh_4: four flat decal triangles
            idx = [int(t.split("/")[0]) - 1 for t in p[1:]]
            assert all(int(t.split("/")[1]) - 1 == i for t, i in zip(p[1:], idx)), "v and vt indices differ"
            for k in range(1, len(idx) - 1):
                tris.append((idx[0], idx[k], idx[k + 1], PARTS[group]))
assert len(verts) == len(uvs) < 65536

# OBJ: x backward (the gun points to -x), y, z up -> x forward, y right, z up (x and y both turned: the winding stays)
d = [[-v[0], -v[1], v[2]] for v in verts]
lo = [min(p[i] for p in d) for i in range(3)]
hi = [max(p[i] for p in d) for i in range(3)]
k = LENGTH_M / (hi[0] - lo[0])
off = [(hi[0] + lo[0]) / 2, (hi[1] + lo[1]) / 2, lo[2]]
d = [[(p[i] - off[i]) * k for i in range(3)] for p in d]
tex = [0] * len(d)
for a, b, c, t in tris:
    tex[a] = tex[b] = tex[c] = t
print("size m", [round((hi[i] - lo[i]) * k, 2) for i in range(3)], "vertices", len(d), "triangles", len(tris))

os.makedirs(dst, exist_ok=True)
with gzip.open(os.path.join(dst, "btr.bin.gz"), "wb", compresslevel=9) as f:
    f.write(b"BTR1")
    f.write(struct.pack(">i", len(d)))
    for p, uv, t in zip(d, uvs, tex):
        f.write(struct.pack(">fffffB", p[0], p[1], p[2], uv[0], uv[1], t))
    f.write(struct.pack(">i", len(tris)))
    for a, b, c, _ in tris:
        f.write(struct.pack(">HHH", a, b, c))
for i, (name, size) in enumerate(TEXTURES):
    im = Image.open(os.path.join(src, name)).convert("RGB")
    w, h = im.size
    if max(w, h) > size:
        s = size / max(w, h)
        im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    im.save(os.path.join(dst, "btr-%d.jpg" % i), quality=85)
total = sum(os.path.getsize(os.path.join(dst, n)) for n in os.listdir(dst) if n.startswith("btr"))
print("written", total, "bytes in btr.bin.gz + btr-0..3.jpg")
