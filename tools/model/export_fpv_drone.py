"""Turns the FPV drone OBJ (+ its textures) into DRAUPNE's drone resources for the orientation check:
model/fpv-drone.bin.gz (gzip): 'DRM1', vertex count, per vertex x y z float32 (the drone's axes: x forward, y right,
z down, metres, centred on the motors, scaled to a 5" quad's motor spacing), u v float32 (OBJ's, unwrapped), a flag
byte (bits 0-1 the texture: 0 body, 1 frame, 2 none; bit 2 a front prop); triangle count, indices uint16.
model/fpv-drone-0.jpg / -1.jpg: the two colour textures with their ambient occlusion multiplied in, 1024 square.

python3 export_fpv_drone.py "<folder with FPV Drone.obj + the jpgs>" src/main/resources/model"""
import sys, os, struct, gzip
import numpy as np
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
ARM = 0.08  # half the motor-to-motor distance along each axis, as DroneModelView's 5" quad had it

verts, uvs, objects = [], [], []  # objects: [name, material, triangles]
material = None
with open(os.path.join(src, "FPV Drone.obj")) as f:
    for line in f:
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            verts.append([float(p[1]), float(p[2]), float(p[3])])
        elif p[0] == "vt":
            uvs.append([float(p[1]), float(p[2])])
        elif p[0] == "o":
            objects.append([p[1], None, []])
        elif p[0] == "usemtl":
            objects[-1][1] = p[1]
        elif p[0] == "f":
            idx = [int(t.split("/")[0]) - 1 for t in p[1:]]
            assert all(int(t.split("/")[1]) - 1 == i for t, i in zip(p[1:], idx)), "v and vt indices differ"
            for k in range(1, len(idx) - 1):
                objects[-1][2].append((idx[0], idx[k], idx[k + 1]))
verts, uvs = np.array(verts), np.array(uvs)
assert len(verts) == len(uvs) < 65536

TEXTURE = {"5d500388-5ad8-4a97-84d7-c5dd5bd87d67": 0, "94d7499c-18e5-4526-97d9-ea31b09f77cf": 1}

# OBJ: x left, y up, z forward -> the drone's x forward, y right, z down
d = np.stack([verts[:, 2], -verts[:, 0], -verts[:, 1]], axis=1)
flags = np.full(len(d), 2, dtype=np.uint8)
motors = []
for name, mat, tris in objects:
    used = sorted({i for t in tris for i in t})
    flags[used] = TEXTURE.get(mat, 2)
    if "#engine" in name:
        motors.append((d[used].min(axis=0) + d[used].max(axis=0)) / 2)
motors = np.array(motors)
assert len(motors) == 4
centre = motors.mean(axis=0)
d -= centre
scale = ARM / np.abs(motors[:, :2] - centre[:2]).mean()
d *= scale
for name, mat, tris in objects:
    if "#prop" in name:
        used = sorted({i for t in tris for i in t})
        if d[used, 0].mean() > 0:
            flags[used] |= 4
print("motors at", np.round((motors - centre) * scale, 4).tolist(), "lowest z", round(d[:, 2].max(), 4))

triangles = [t for _, _, tris in objects for t in tris]
out = bytearray(b"DRM1")
out += struct.pack(">i", len(d))
for (x, y, z), (u, v), fl in zip(d, uvs, flags):
    out += struct.pack(">fffffB", x, y, z, u, v, fl)
out += struct.pack(">i", len(triangles))
for t in triangles:
    out += struct.pack(">HHH", *t)
with gzip.open(os.path.join(dst, "fpv-drone.bin.gz"), "wb", 9) as f:
    f.write(out)

for k, stem in enumerate(["fpv_kamikaze_drone_1", "fpv_kamikaze_drone"]):
    colour = Image.open(os.path.join(src, stem + "_c.jpg")).convert("RGB")
    ao = Image.open(os.path.join(src, stem + "_n_ao.jpg")).convert("L").resize(colour.size)
    c = np.asarray(colour, dtype=np.float32) * (np.asarray(ao, dtype=np.float32)[..., None] / 255)
    Image.fromarray(c.clip(0, 255).astype(np.uint8)).resize((1024, 1024), Image.LANCZOS) \
        .save(os.path.join(dst, f"fpv-drone-{k}.jpg"), quality=88)
print(len(d), "vertices,", len(triangles), "triangles")
