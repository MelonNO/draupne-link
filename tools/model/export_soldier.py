"""Turns Quaternius' SWAT character (CC0, poly.pizza/m/Btfn3G5Xv4, see THIRD-PARTY.md) into the virtual fleet's walking
soldier: its "Walk" clip baked into keyframes (the skinning done here, so Java only blends two frames), the materials
recoloured as a soldier in the field (woodland camouflage on the uniform, olive-brown gear and helmet), and a rifle of our
own (a few boxes) slung across the chest, following the chest bone.

model/soldier.bin.gz (gzip, big-endian as Java's DataInputStream reads it): 'SLD1', vertex count, frame count, the
clip's seconds, metres walked per clip; triangle count, per triangle three uint16 indices and an RGB int; then per
frame per vertex x y z int16 millimetres (the soldier's axes: x forward, y left, z up; z 0 at the soles, centred on
the hips at frame 0). Vertices at the same place with the same colour are welded. The original file is not committed.
Pure Python.

python export_soldier.py "<the SWAT .glb>" src/main/resources/model/soldier.bin.gz"""
import sys, json, struct, gzip, math

src, dst = sys.argv[1], sys.argv[2]
FRAMES = 16
HEIGHT_M = 1.80

data = open(src, "rb").read()
assert data[:4] == b"glTF"
jlen = struct.unpack("<I", data[12:16])[0]
gl = json.loads(data[20:20 + jlen])
bin_at = 20 + jlen
blen = struct.unpack("<I", data[bin_at:bin_at + 4])[0]
blob = data[bin_at + 8:bin_at + 8 + blen]

COMP = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NUM = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def accessor(i):
    a = gl["accessors"][i]
    v = gl["bufferViews"][a["bufferView"]]
    fmt, size = COMP[a["componentType"]]
    n = NUM[a["type"]]
    stride = v.get("byteStride", size * n)
    base = v.get("byteOffset", 0) + a.get("byteOffset", 0)
    out = []
    for k in range(a["count"]):
        vals = struct.unpack_from("<" + fmt * n, blob, base + k * stride)
        if a.get("normalized"):
            mx = {"B": 255.0, "H": 65535.0, "b": 127.0, "h": 32767.0}[fmt]
            vals = tuple(x / mx for x in vals)
        out.append(vals if n > 1 else vals[0])
    return out


# ---- 4x4 matrices, column-major lists as glTF has them

def mat_mul(a, b):
    r = [0.0] * 16
    for c in range(4):
        for rr in range(4):
            r[c * 4 + rr] = sum(a[k * 4 + rr] * b[c * 4 + k] for k in range(4))
    return r


def trs(t, q, s):
    x, y, z, w = q
    m = [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
         2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
         2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
         0, 0, 0, 1]
    for c in range(3):
        for rr in range(3):
            m[c * 4 + rr] *= s[c]
    m[12], m[13], m[14] = t
    return m


def apply(m, p):
    x, y, z = p
    return (m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14])


def slerp(a, b, t):
    d = sum(x * y for x, y in zip(a, b))
    if d < 0:
        b = [-x for x in b]
        d = -d
    if d > 0.9995:
        r = [x + (y - x) * t for x, y in zip(a, b)]
    else:
        th = math.acos(d)
        sa, sb = math.sin((1 - t) * th) / math.sin(th), math.sin(t * th) / math.sin(th)
        r = [x * sa + y * sb for x, y in zip(a, b)]
    n = math.sqrt(sum(x * x for x in r))
    return [x / n for x in r]


nodes = gl["nodes"]
parent = {}
for i, n in enumerate(nodes):
    for c in n.get("children", []):
        parent[c] = i

clip = next(a for a in gl["animations"] if a["name"].endswith("|Walk"))
tracks = {}  # (node, path) -> (times, values, interpolation)
for ch in clip["channels"]:
    smp = clip["samplers"][ch["sampler"]]
    times = accessor(smp["input"])
    vals = accessor(smp["output"])
    interp = smp.get("interpolation", "LINEAR")
    if interp == "CUBICSPLINE":
        vals = vals[1::3]  # the values between the tangents
    tracks[(ch["target"]["node"], ch["target"]["path"])] = (times, vals, interp)
clip_s = max(t[0][-1] for t in tracks.values())


def sample(node, path, t, rest):
    tr = tracks.get((node, path))
    if tr is None:
        return rest
    times, vals, interp = tr
    if t <= times[0]:
        return list(vals[0])
    if t >= times[-1]:
        return list(vals[-1])
    k = 0
    while times[k + 1] < t:
        k += 1
    f = (t - times[k]) / (times[k + 1] - times[k])
    if interp == "STEP":
        return list(vals[k])
    if path == "rotation":
        return slerp(vals[k], vals[k + 1], f)
    return [a + (b - a) * f for a, b in zip(vals[k], vals[k + 1])]


def globals_at(t):
    local = []
    for i, n in enumerate(nodes):
        tt = sample(i, "translation", t, n.get("translation", [0, 0, 0]))
        rr = sample(i, "rotation", t, n.get("rotation", [0, 0, 0, 1]))
        ss = sample(i, "scale", t, n.get("scale", [1, 1, 1]))
        local.append(trs(tt, rr, ss))
    out = [None] * len(nodes)

    def g(i):
        if out[i] is None:
            out[i] = local[i] if i not in parent else mat_mul(g(parent[i]), local[i])
        return out[i]

    for i in range(len(nodes)):
        g(i)
    return out


# ---- the soldier's colours: (r, g, b) per SWAT material, the uniform in camouflage
SKIN = (0xb0, 0x86, 0x64)
COLOURS = {"Swat": None, "Swat_Black": (0x5a, 0x55, 0x3e), "Skin": SKIN, "Visor": (0x1c, 0x1e, 0x20)}
CAMO = [(0x4a, 0x52, 0x36), (0x5e, 0x5a, 0x3c), (0x34, 0x3a, 0x28), (0x52, 0x44, 0x32)]  # woodland (as the Norwegian M98): greens and browns


def noise(x, y, z):
    """A smooth value noise in 0..1, for the camouflage's patches."""
    def h(i, j, k):
        v = (i * 73856093) ^ (j * 19349663) ^ (k * 83492791)
        v = (v ^ (v >> 13)) * 1274126177 & 0xffffffff
        return (v & 0xffff) / 65535.0
    xi, yi, zi = math.floor(x), math.floor(y), math.floor(z)
    xf, yf, zf = x - xi, y - yi, z - zi
    s = lambda t: t * t * (3 - 2 * t)
    r = 0.0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (s(xf) if dx else 1 - s(xf)) * (s(yf) if dy else 1 - s(yf)) * (s(zf) if dz else 1 - s(zf))
                r += w * h(xi + dx, yi + dy, zi + dz)
    return r


# ---- gather the skinned primitives
prims = []  # (positions, joints, weights, indices, material name, skin)
for ni, n in enumerate(nodes):
    if "mesh" not in n:
        continue
    skin = gl["skins"][n["skin"]]
    for p in gl["meshes"][n["mesh"]]["primitives"]:
        a = p["attributes"]
        prims.append((accessor(a["POSITION"]), accessor(a["JOINTS_0"]), accessor(a["WEIGHTS_0"]),
                      accessor(p["indices"]), gl["materials"][p["material"]]["name"], skin))
skins = {}
for s in gl["skins"]:
    ibm = accessor(s["inverseBindMatrices"])
    skins[id(s)] = [list(m) for m in ibm]

times = [clip_s * k / FRAMES for k in range(FRAMES)]
frame_globals = [globals_at(t) for t in times]

# per frame: every primitive vertex skinned (glTF: joint global * inverse bind), into glTF world (y up, z forward)
posed = [[] for _ in range(FRAMES)]
for pos, joints, weights, idx, mat, skin in prims:
    ibm = skins[id(skin)]
    jn = skin["joints"]
    for f in range(FRAMES):
        G = frame_globals[f]
        jm = {}
        out = posed[f]
        for v, j4, w4 in zip(pos, joints, weights):
            x = y = z = 0.0
            for j, w in zip(j4, w4):
                if w <= 0:
                    continue
                if j not in jm:
                    jm[j] = mat_mul(G[jn[j]], ibm[j])
                px, py, pz = apply(jm[j], v)
                x += w * px
                y += w * py
                z += w * pz
            out.append((x, y, z))

# glTF (x left, y up, z forward for this character) -> ours (x forward, y left, z up)
for f in range(FRAMES):
    posed[f] = [(p[2], p[0], p[1]) for p in posed[f]]

# scale and place from frame 0: the soles at 0, HEIGHT_M tall, centred across and along
xs = [p[0] for p in posed[0]]
ys = [p[1] for p in posed[0]]
zs = [p[2] for p in posed[0]]
scale = HEIGHT_M / (max(zs) - min(zs))
ox, oy, oz = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, min(zs)
for f in range(FRAMES):
    posed[f] = [((x - ox) * scale, (y - oy) * scale, (z - oz) * scale) for x, y, z in posed[f]]

# which way does it face? the face (the visor) must look along +x; flip if it doesn't
visor = []
base = 0
for pos, joints, weights, idx, mat, skin in prims:
    if mat == "Visor":
        visor += [posed[0][base + k][0] for k in range(len(pos))]
    base += len(pos)
if visor and sum(visor) / len(visor) < 0:
    for f in range(FRAMES):
        posed[f] = [(-x, -y, z) for x, y, z in posed[f]]

# triangles with their colours (welding vertices at the same rest place with the same colour)
tri_out = []
weld = {}
vmap = []  # new vertex -> old vertex
base = 0
for pos, joints, weights, idx, mat, skin in prims:
    fixed = COLOURS.get(mat)
    for t in range(0, len(idx), 3):
        a, b, c = base + idx[t], base + idx[t + 1], base + idx[t + 2]
        if fixed is None:
            cx = sum(posed[0][k][0] for k in (a, b, c)) / 3
            cy = sum(posed[0][k][1] for k in (a, b, c)) / 3
            cz = sum(posed[0][k][2] for k in (a, b, c)) / 3
            n1 = noise(cx * 9, cy * 9, cz * 9)
            n2 = noise(cx * 23 + 5, cy * 23 + 5, cz * 23 + 5)
            col = CAMO[min(3, int((n1 * 0.7 + n2 * 0.3) * 4.2))]
        else:
            col = fixed
        rgb = (col[0] << 16) | (col[1] << 8) | col[2]
        ids = []
        for k in (a, b, c):
            key = (round(posed[0][k][0], 4), round(posed[0][k][1], 4), round(posed[0][k][2], 4), rgb)
            if key not in weld:
                weld[key] = len(vmap)
                vmap.append(k)
            ids.append(weld[key])
        if len(set(ids)) == 3:
            tri_out.append((ids[0], ids[1], ids[2], rgb))
    base += len(pos)

# ---- the rifle: boxes in the chest bone's frame, slung across the front, muzzle down to the soldier's left
chest = next(i for i, n in enumerate(nodes) if n.get("name") == "Chest")


def box(cx, cy, cz, lx, ly, lz):
    hx, hy, hz = lx / 2, ly / 2, lz / 2
    c = [(cx + sx * hx, cy + sy * hy, cz + sz * hz) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    return c, faces


# in metres along the rifle (u: muzzle +), across (v) and up (w), drawn flat then turned onto the chest
RIFLE = [((0.00, 0, 0.00), (0.42, 0.05, 0.09), (0x2a, 0x2a, 0x26)),   # receiver
         ((0.38, 0, 0.01), (0.36, 0.022, 0.022), (0x1e, 0x1e, 0x1c)),  # barrel
         ((0.22, 0, 0.00), (0.24, 0.06, 0.07), (0x6e, 0x60, 0x48)),    # handguard (coyote)
         ((-0.32, 0, -0.02), (0.24, 0.045, 0.11), (0x6e, 0x60, 0x48)), # stock
         ((0.06, 0, -0.10), (0.07, 0.035, 0.14), (0x26, 0x26, 0x22)),  # magazine
         ((-0.07, 0, -0.07), (0.04, 0.035, 0.09), (0x26, 0x26, 0x22)), # grip
         ((0.02, 0, 0.07), (0.12, 0.04, 0.05), (0x22, 0x22, 0x20))]    # optic

# the rifle's place: in front of the chest (ours: x forward), sloping from the right shoulder down to the left hip
G0 = frame_globals[0]
chest_rest = G0[chest]
front = max(p[0] for p in posed[0] if 1.15 < p[2] < 1.45) + 0.04  # the chest's front surface, metres
ang = math.radians(45)  # the slope: muzzle down to the left
rifle_local = []  # rest positions in our space, and their colours
for (cu, cv, cw), (lu, lv, lw), col in RIFLE:
    corners, faces = box(cu, cv, cw, lu, lv, lw)
    rgb = (col[0] << 16) | (col[1] << 8) | col[2]
    first = len(rifle_local)
    for u, v, w in corners:
        # along the chest: u to the left (+y) and down, w out of the chest (+x)
        y = u * math.cos(ang)
        z = 1.18 - u * math.sin(ang) + v
        x = front + w * 0.4
        rifle_local.append((x, y, z))
    for a, b, c, d in faces:
        tri_out.append((-(first + a) - 1, -(first + b) - 1, -(first + c) - 1, rgb))
        tri_out.append((-(first + a) - 1, -(first + c) - 1, -(first + d) - 1, rgb))

# the rifle follows the chest: per frame, the chest bone's motion since frame 0 (in glTF space, mapped to ours)


def to_gltf(p):
    x, y, z = p
    return (y / scale + oy, z / scale + oz, x / scale + ox)


def from_gltf(p):
    return ((p[2] - ox) * scale, (p[0] - oy) * scale, (p[1] - oz) * scale)


flip = bool(visor) and sum(visor) / len(visor) < 0
inv0 = None


def invert(m):
    # rigid (rotation + uniform scale + translation) inverse
    r = [m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]]
    s2 = r[0] * r[0] + r[1] * r[1] + r[2] * r[2]
    rt = [r[0] / s2, r[3] / s2, r[6] / s2, r[1] / s2, r[4] / s2, r[7] / s2, r[2] / s2, r[5] / s2, r[8] / s2]
    t = (m[12], m[13], m[14])
    it = (-(rt[0] * t[0] + rt[3] * t[1] + rt[6] * t[2]), -(rt[1] * t[0] + rt[4] * t[1] + rt[7] * t[2]),
          -(rt[2] * t[0] + rt[5] * t[1] + rt[8] * t[2]))
    return [rt[0], rt[1], rt[2], 0, rt[3], rt[4], rt[5], 0, rt[6], rt[7], rt[8], 0, it[0], it[1], it[2], 1]


inv0 = invert(chest_rest)
rifle_frames = []
for f in range(FRAMES):
    delta = mat_mul(frame_globals[f][chest], inv0)
    out = []
    for p in rifle_local:
        q = p if not flip else (-p[0], -p[1], p[2])
        g = apply(delta, to_gltf(q))
        r = from_gltf(g)
        out.append(r if not flip else (-r[0], -r[1], r[2]))
    rifle_frames.append(out)

# indices: the rifle's after the body's
nbody = len(vmap)
tris = []
for a, b, c, rgb in tri_out:
    fix = lambda k: k if k >= 0 else nbody + (-k - 1)
    tris.append((fix(a), fix(b), fix(c), rgb))
nv = nbody + len(rifle_local)
assert nv < 65536

# metres walked per clip: a foot's travel back and forth while planted, over the stance (about 60 % of the clip)
foot = next(i for i, n in enumerate(nodes) if n.get("name") == "Foot.L")
fx = [from_gltf(apply(frame_globals[f][foot], (0, 0, 0)))[0] for f in range(FRAMES)]
stride = max(1.0, (max(fx) - min(fx)) / 0.6)

out = bytearray()
out += b"SLD1" + struct.pack(">iiff", nv, FRAMES, clip_s, stride)
out += struct.pack(">i", len(tris))
for a, b, c, rgb in tris:
    out += struct.pack(">HHHi", a, b, c, rgb)
for f in range(FRAMES):
    for k in vmap:
        x, y, z = posed[f][k]
        out += struct.pack(">hhh", round(x * 1000), round(y * 1000), round(z * 1000))
    for x, y, z in rifle_frames[f]:
        out += struct.pack(">hhh", round(x * 1000), round(y * 1000), round(z * 1000))
with gzip.open(dst, "wb", 9) as g:
    g.write(bytes(out))
zs = [p[2] for p in posed[0]]
print("vertices", nv, "triangles", len(tris), "frames", FRAMES, "clip s", round(clip_s, 3), "stride m", round(stride, 2),
      "height", round(max(zs) - min(zs), 3), "bytes", len(out))
