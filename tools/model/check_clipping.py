"""Clipping check for the TX16S model, as ControllerRender poses it (after the 1.0.39 cuts).

Sticks: GL cut 1.0 mm under its inner plate's (G1) top, tipped about the frame's centre (z = frame top - 4 mm) by the
angles given (degrees, comma-separated; default 14,28 - the app uses 20) in 8 directions; G1 stays put. Levers: cut
1.2 mm under the bushing's face, thrown about their CAD direction (the CAD has them in the middle) - the front ones
+-16 degrees, the shoulder ones +-5 - and the snap's overshoot (12 % past). A point of a moving part inside any other
part counts (closed parts: trimesh's test; the shell, not closed: a majority of three ray parities). Allowed: a stick
through its own plate, a lever in its own bushing. Usage: check_clipping.py <tx16s-mkii.bin.gz> [stick degrees]
"""
import sys, gzip, struct, math
import numpy as np
import trimesh

f = gzip.open(sys.argv[1], "rb")
assert f.read(4) == b"TXM1"
count = struct.unpack(">i", f.read(4))[0]
parts = {}
for _ in range(count):
    ln = struct.unpack(">H", f.read(2))[0]
    name = f.read(ln).decode()
    f.read(12)
    nv = struct.unpack(">i", f.read(4))[0]
    pos = np.frombuffer(f.read(nv * 12), dtype=">f4").reshape(-1, 3).astype(np.float64)
    f.read(nv * 3)
    nt = struct.unpack(">i", f.read(4))[0]
    tri = np.frombuffer(f.read(nt * (6 if nv < 65536 else 12)), dtype=(">u2" if nv < 65536 else ">i4")).reshape(-1, 3).astype(np.int64)
    parts[name] = trimesh.Trimesh(vertices=pos, faces=tri, process=True)
DEG = math.pi / 180
STICK_DEGREES = [float(x) for x in sys.argv[2].split(",")] if len(sys.argv) > 2 else [14, 28]
LEVER_DEG = float(sys.argv[3]) if len(sys.argv) > 3 else 16


def box(m):
    return np.concatenate([m.vertices.min(0), m.vertices.max(0)])


def centre(m):
    b = box(m)
    return (b[:3] + b[3:]) / 2


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def points_of(m, n=2500):
    """The surface, sampled evenly (vertices alone miss a long shaft's sides)."""
    pts, _ = trimesh.sample.sample_surface_even(m, n, seed=1)
    return np.vstack([pts, m.vertices])


def inside(m, q):
    b = box(m)
    sel = np.all((q >= b[:3] - 0.01) & (q <= b[3:] + 0.01), 1)
    if not sel.any():
        return 0
    c = q[sel]
    if m.is_watertight:
        return int(np.sum(m.contains(c)))
    votes = np.zeros(len(c), int)
    for d in ([1, 0.013, 0.007], [-0.011, 1, 0.017], [0.009, -0.015, 1]):
        d = np.array(d) / np.linalg.norm(d)
        hits = m.ray.intersects_location(c, np.tile(d, (len(c), 1)), multiple_hits=True)[1]
        votes += (np.bincount(hits, minlength=len(c)) % 2 == 1)
    return int(np.sum(votes >= 2))


moving = []
frames = {k: v for k, v in parts.items() if k.startswith("Part1Gimble")}
for nm, m in parts.items():
    if not nm.startswith("GL"):  # the plates G1 stay put
        continue
    c = centre(m)
    frn = min(frames, key=lambda k: abs(centre(frames[k])[0] - c[0]))
    fr = box(frames[frn])
    pivot = np.array([(fr[0] + fr[3]) / 2, (fr[1] + fr[4]) / 2, fr[5] - 4.0])
    plate_name = next(k for k in parts if k.startswith("G1") and abs(centre(parts[k])[0] - c[0]) < 30)
    if nm.startswith("GL"):
        top = box(parts[plate_name])[5]
        m = m.slice_plane([0, 0, top - 1.0], [0, 0, 1], cap=True)
    pts = points_of(m)
    for deg in STICK_DEGREES:
        for sx, sy in [(1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]:
            if nm.startswith("GL"):
                R = rot_y(sx * deg * DEG) @ rot_x(-sy * deg * DEG)
                q = (pts - pivot) @ R.T + pivot
                allowed = {nm, plate_name}
            else:
                h = fr[5] - pivot[2]
                q = pts + np.array([h * math.tan(sx * deg * DEG), h * math.tan(sy * deg * DEG), 0])
                allowed = {nm}
            moving.append((nm, "stick %+d %+d at %d" % (sx, sy, deg), q, allowed))
bushings = {k: v for k, v in parts.items() if k.startswith("SD3:")}
for nm, m in parts.items():
    if not nm.startswith("SD3 L"):
        continue
    c = centre(m)
    bn = min(bushings, key=lambda k: np.sum((centre(bushings[k]) - c) ** 2))
    bb = box(bushings[bn])
    bc = (bb[:3] + bb[3:]) / 2
    d = m.vertices - bc
    far = d[np.argmax(np.sum(d * d, 1))]
    front = abs(far[2]) > abs(far[1])
    if front:
        # a front toggle hinges inside its bushing where its centre line meets the bushing's axis (straight out of the
        # face); the CAD has it at one end, it throws to the mirror of that
        cm = m.vertices.mean(0)
        w, vec = np.linalg.eigh(np.cov((m.vertices - cm).T))
        dl = vec[:, -1] if np.dot(vec[:, -1], far) >= 0 else -vec[:, -1]
        t = (bc[1] - cm[1]) / dl[1] if abs(dl[1]) > 1e-6 else 0
        pivot = np.array([bc[0], bc[1], min(bb[5], max(bb[2], cm[2] + t * dl[2]))])
        axis = math.pi / 2 * math.copysign(1, dl[2])
        rest = math.atan2(dl[2], dl[1]) - axis
    else:
        axis = math.atan2(far[2], far[1])  # a shoulder one: in its middle, its bushing leaning back with it
        rest = 0.0
        pivot = np.array([bc[0], bb[4] if far[1] > 0 else bb[1], bc[2]])
    ax = np.array([0, math.cos(axis), math.sin(axis)])
    m = m.slice_plane(pivot - ax * 1.2, ax, cap=True)
    pts = points_of(m, 1200)
    off = math.copysign(min(abs(rest), 22 * DEG), rest) if front else 5 * DEG  # as the app: front ones their CAD end (at most 22) and its mirror, shoulders +-5
    for label, ang in [("OFF", off), ("ON", -off), ("OFF+snap", off * 1.12), ("ON+snap", -off * 1.12)]:
        q = (pts - pivot) @ rot_x(ang - rest).T + pivot  # from the CAD pose to the angle
        moving.append((nm, label, q, {nm, bn}))

bad = 0
for nm, pose, q, allowed in moving:
    hits = []
    for st, m in parts.items():
        if st in allowed or st.startswith("SD3 L") or (st.startswith("GL") and nm.startswith("GL")):
            continue
        k = inside(m, q)
        if k:
            hits.append("%s(%d)" % (st, k))
    if hits:
        bad += 1
        print("%-12s %-20s into %s" % (nm, pose, ", ".join(hits)))
print("poses with clipping:", bad, "of", len(moving))
