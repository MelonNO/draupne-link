"""The showcase soldier's skeleton and poses (export_tactical.py): the CC0 soldier comes as a mesh in an A-pose with no
skeleton, so it is given one here - a bone per limb, each vertex weighted to its limb by region, blended across the
joints - and posed by setting each bone's direction (arms and legs by two-bone IK to a hand or foot target). A rifle of
a few boxes rides in the hands or slung on the back, and an item (a controller, binoculars, a handset, a mortar round)
is held where the pose has one (collapsed to a point elsewhere, so every frame has the same vertices).

Frames, in order (fleet/ShowScene.java names them): WALK 0..15 (a cycle, WALK_STRIDE_M a cycle), then POSES."""
import math
import numpy as np
from tactical_lib import Mesh, merge
from PIL import Image

WALK_FRAMES = 16
WALK_STRIDE_M = 1.44
POSES = ["STAND", "KNEEL", "KNEEL_AIM", "PRONE_BINO", "PRONE_AIM", "OPERATOR", "STAND_OPERATOR", "GUNNER",
         "LOADER_ROUND", "LOADER", "COVER", "RADIO", "KNEEL_BACK"]

# the bind pose's joints (measured on the mesh scaled to 1.80 m; x forward, y left, z up)
J = {
    "pelvis": (0.0, 0.0, 0.95), "chest": (0.0, 0.0, 1.08), "head": (0.0, 0.0, 1.52),
    "shoulder.L": (-0.03, 0.18, 1.36), "elbow.L": (-0.05, 0.45, 1.17), "hand.L": (-0.02, 0.70, 1.01),
    "hip.L": (0.0, 0.10, 0.92), "knee.L": (0.02, 0.11, 0.50), "ankle.L": (-0.02, 0.12, 0.10),
}
for k in list(J):
    if k.endswith(".L"):
        x, y, z = J[k]
        J[k[:-1] + "R"] = (x, -y, z)
J = {k: np.array(v, dtype=np.float64) for k, v in J.items()}

# bones: name -> (parent, pivot joint, end joint or a direction)
BONES = {
    "pelvis": (None, "pelvis", (0, 0, 1)),
    "chest": ("pelvis", "chest", (0, 0, 1)),
    "head": ("chest", "head", (0, 0, 1)),
}
for s in "LR":
    BONES["upper." + s] = ("chest", "shoulder." + s, "elbow." + s)
    BONES["fore." + s] = ("upper." + s, "elbow." + s, "hand." + s)
    BONES["thigh." + s] = ("pelvis", "hip." + s, "knee." + s)
    BONES["shin." + s] = ("thigh." + s, "knee." + s, "ankle." + s)
    BONES["foot." + s] = ("shin." + s, "ankle." + s, (1, 0, 0))
ORDER = ["pelvis", "chest", "head", "upper.L", "fore.L", "upper.R", "fore.R", "thigh.L", "shin.L", "foot.L",
         "thigh.R", "shin.R", "foot.R"]


def unit(v):
    v = np.asarray(v, dtype=np.float64)
    return v / max(1e-12, np.linalg.norm(v))


def bind_dir(b):
    _, piv, end = BONES[b]
    return unit(J[end] - J[piv]) if isinstance(end, str) else unit(end)


def length(b):
    _, piv, end = BONES[b]
    return float(np.linalg.norm(J[end] - J[piv])) if isinstance(end, str) else 0.0


def rot_between(a, b):
    a, b = unit(a), unit(b)
    v = np.cross(a, b)
    c = float(a @ b)
    if c < -0.9999:
        axis = unit(np.cross(a, (1, 0, 0)) if abs(a[0]) < 0.9 else np.cross(a, (0, 1, 0)))
        return rot_axis(axis, math.pi)
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1 + c)


def rot_axis(axis, ang):
    x, y, z = unit(axis)
    c, s = math.cos(ang), math.sin(ang)
    return np.array([[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
                     [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
                     [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]])


# ------------------------------------------------------------------ weights

def ramp(x, a, b):
    return np.clip((x - a) / (b - a), 0, 1)


def weights(P):
    """(n, bones) weights by region, blended across the joints."""
    n = len(P)
    W = np.zeros((n, len(ORDER)))
    ix = {b: i for i, b in enumerate(ORDER)}
    x, y, z = P[:, 0], P[:, 1], P[:, 2]
    # the trunk: pelvis -> chest -> head
    t_chest = ramp(z, 1.00, 1.12)
    t_head = ramp(z, 1.47, 1.55)
    trunk = np.stack([1 - t_chest, t_chest * (1 - t_head), t_head], 1)
    # the arms: along the shoulder -> elbow -> hand line, near it
    arm = np.zeros(n, dtype=bool)
    armw = {}
    for s, sign in (("L", 1), ("R", -1)):
        S, E, H = J["shoulder." + s], J["elbow." + s], J["hand." + s]
        u = E - S
        t = (P - S) @ u / (u @ u)
        v = H - E
        t2 = (P - E) @ v / (v @ v)
        near = np.minimum(dist_seg(P, S, E), dist_seg(P, E, H + 0.4 * (H - E)))
        on = (sign * y > 0.19) & (z > 0.85) & (near < 0.18) & (t > -0.05)
        w_up = ramp(t, -0.05, 0.25)  # torso -> upper arm across the shoulder
        w_fore = ramp(t2, -0.12, 0.12)  # upper arm -> forearm across the elbow
        armw[s] = (on, w_up, w_fore)
        arm |= on
    # the legs: below the hips, by side
    leg = (z < 0.90) & ~arm
    for i, b in enumerate(("pelvis", "chest", "head")):
        W[:, ix[b]] = np.where(arm | leg, 0, trunk[:, i])
    for s, sign in (("L", 1), ("R", -1)):
        on, w_up, w_fore = armw[s]
        tw = trunk[:, 1]
        W[on, ix["chest"]] = (1 - w_up[on])
        W[on, ix["upper." + s]] = w_up[on] * (1 - w_fore[on])
        W[on, ix["fore." + s]] = w_up[on] * w_fore[on]
        side = leg & (sign * y >= 0) if s == "L" else leg & (y < 0)
        w_thigh = ramp(-z, -0.90, -0.80)  # pelvis above 0.90, thigh below 0.80
        w_shin = ramp(-z, -0.55, -0.45)
        w_foot = ramp(-z, -0.14, -0.07)
        W[side, ix["pelvis"]] = 1 - w_thigh[side]
        W[side, ix["thigh." + s]] = w_thigh[side] * (1 - w_shin[side])
        W[side, ix["shin." + s]] = w_thigh[side] * w_shin[side] * (1 - w_foot[side])
        W[side, ix["foot." + s]] = w_thigh[side] * w_shin[side] * w_foot[side]
    W /= np.maximum(1e-9, W.sum(1))[:, None]
    return W


def dist_seg(P, A, B):
    d = B - A
    t = np.clip((P - A) @ d / (d @ d), 0, 1)
    return np.linalg.norm(P - (A + t[:, None] * d), axis=1)


# ------------------------------------------------------------------ posing

def ik(root, target, l1, l2, pole):
    """Two-bone IK: the middle joint for a chain from root reaching target, bent towards pole."""
    d = target - root
    dl = float(np.linalg.norm(d))
    dl = min(max(dl, abs(l1 - l2) + 1e-3), l1 + l2 - 1e-3)
    u = unit(d)
    w = np.asarray(pole, dtype=np.float64) - (np.asarray(pole) @ u) * u
    w = unit(w)
    ca = (l1 * l1 + dl * dl - l2 * l2) / (2 * l1 * dl)
    a = math.acos(max(-1.0, min(1.0, ca)))
    return root + l1 * (math.cos(a) * u + math.sin(a) * w)


class Pose:
    def __init__(self, dz=0.0, spine=(0, 0, 1), head=None, hands=None, legs=None, body=None, rifle="hands",
                 item=None, hand_pole=None):
        self.dz, self.spine, self.head = dz, spine, head
        self.hands = hands  # {"L": target, "R": target} in the chest's frame (bind coordinates)
        self.hand_pole = hand_pole or {"L": (-0.3, 1, -0.6), "R": (-0.3, -1, -0.6)}
        self.legs = legs  # {"L": (thigh dir, shin dir[, foot dir])} or None (as bound)
        self.body = body  # a function (n,3) -> (n,3) applied last (lying down)
        self.rifle, self.item = rifle, item


def solve(p):
    """Each bone's rotation and posed pivot."""
    R, piv = {}, {}
    R["pelvis"] = np.eye(3)
    piv["pelvis"] = J["pelvis"] + (0, 0, p.dz)
    R["chest"] = rot_between((0, 0, 1), p.spine)
    piv["chest"] = piv["pelvis"] + R["pelvis"] @ (J["chest"] - J["pelvis"])
    R["head"] = rot_between((0, 0, 1), p.head) if p.head is not None else R["chest"]
    piv["head"] = piv["chest"] + R["chest"] @ (J["head"] - J["chest"])

    def chest(v):
        return piv["chest"] + R["chest"] @ (np.asarray(v, dtype=np.float64) - J["chest"])

    for s in "LR":
        piv["upper." + s] = chest(J["shoulder." + s])
        if p.hands and s in p.hands:
            target = chest(p.hands[s])
            pole = R["chest"] @ np.asarray(p.hand_pole[s], dtype=np.float64)
            elbow = ik(piv["upper." + s], target, length("upper." + s), length("fore." + s), pole)
            R["upper." + s] = rot_between(bind_dir("upper." + s), elbow - piv["upper." + s])
            R["fore." + s] = rot_between(bind_dir("fore." + s), target - elbow)
        else:  # hanging by the side
            R["upper." + s] = R["chest"] @ rot_between(bind_dir("upper." + s), (0.05, 0.25 if s == "L" else -0.25, -1))
            R["fore." + s] = R["upper." + s] @ rot_between(bind_dir("fore." + s), bind_dir("upper." + s))
        piv["fore." + s] = piv["upper." + s] + R["upper." + s] @ (J["elbow." + s] - J["shoulder." + s])
        piv["thigh." + s] = piv["pelvis"] + (J["hip." + s] - J["pelvis"])
        if p.legs and s in p.legs:
            spec = p.legs[s]
            R["thigh." + s] = rot_between(bind_dir("thigh." + s), spec[0])
            R["shin." + s] = rot_between(bind_dir("shin." + s), spec[1])
            R["foot." + s] = rot_between((1, 0, 0), spec[2]) if len(spec) > 2 else R["shin." + s]
        else:
            R["thigh." + s] = np.eye(3)
            R["shin." + s] = np.eye(3)
            R["foot." + s] = np.eye(3)
        piv["shin." + s] = piv["thigh." + s] + R["thigh." + s] @ (J["knee." + s] - J["hip." + s])
        piv["foot." + s] = piv["shin." + s] + R["shin." + s] @ (J["ankle." + s] - J["knee." + s])
    return R, piv


def hand_point(R, piv, s):
    return piv["fore." + s] + R["fore." + s] @ (J["hand." + s] - J["elbow." + s]) * 0.92


def boxes(spec):
    """Boxes {x0, y0, z0, x1, y1, z1} into vertex and triangle lists."""
    pos, tri = [], []
    faces = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4), (2, 3, 7), (2, 7, 6), (1, 2, 6), (1, 6, 5),
             (0, 4, 7), (0, 7, 3)]
    for q in spec:
        b = len(pos)
        pos += [(q[0], q[1], q[2]), (q[3], q[1], q[2]), (q[3], q[4], q[2]), (q[0], q[4], q[2]),
                (q[0], q[1], q[5]), (q[3], q[1], q[5]), (q[3], q[4], q[5]), (q[0], q[4], q[5])]
        tri += [(b + f[0], b + f[1], b + f[2]) for f in faces]
    return np.array(pos, dtype=np.float64), np.array(tri)


# the rifle: the grip at the origin, the barrel along +x, up +z
RIFLE = boxes([(-0.05, -0.022, -0.02, 0.30, 0.022, 0.055), (0.30, -0.012, 0.01, 0.58, 0.012, 0.035),
               (0.05, -0.016, -0.20, 0.13, 0.016, -0.02), (-0.30, -0.02, -0.06, -0.05, 0.02, 0.04),
               (-0.06, -0.016, -0.12, 0.0, 0.016, -0.02), (0.10, -0.03, 0.055, 0.26, 0.03, 0.075)])
ITEMS = {
    "controller": boxes([(-0.05, -0.10, -0.02, 0.05, 0.10, 0.02), (-0.04, -0.13, 0.0, 0.0, -0.10, 0.08),
                         (-0.04, 0.10, 0.0, 0.0, 0.13, 0.08)]),
    "binoculars": boxes([(-0.08, -0.07, -0.03, 0.08, -0.01, 0.03), (-0.08, 0.01, -0.03, 0.08, 0.07, 0.03)]),
    "handset": boxes([(-0.03, -0.025, -0.09, 0.03, 0.025, 0.09)]),
    "round": boxes([(-0.17, -0.03, -0.03, 0.17, 0.03, 0.03), (-0.24, -0.02, -0.02, -0.17, 0.02, 0.02)]),
}


def frame_of(origin, fwd, up=(0, 0, 1)):
    f = unit(fwd)
    l = np.cross(up, f)
    if np.linalg.norm(l) < 1e-6:
        l = np.cross((1, 0, 0), f)
    l = unit(l)
    u = np.cross(f, l)
    return np.asarray(origin, dtype=np.float64), np.stack([f, l, u], 1)


def place(geom, origin, basis, scale=1.0):
    return origin + (geom * scale) @ basis.T


def posed(base_pos, W, p, base_nrm=None, normals_out=None):
    R, piv = solve(p)
    out = np.zeros_like(base_pos)
    nout = np.zeros_like(base_pos)
    for i, b in enumerate(ORDER):
        w = W[:, i]
        if not w.any():
            continue
        pivot0 = J[BONES[b][1]]
        out += w[:, None] * (piv[b] + (base_pos - pivot0) @ R[b].T)
        if base_nrm is not None:
            nout += w[:, None] * (base_nrm @ R[b].T)
    hl, hr = hand_point(R, piv, "L"), hand_point(R, piv, "R")
    if p.rifle == "hands":
        o, B = frame_of(hr, hl - hr)
    else:  # slung across the back, muzzle up over the left shoulder
        c = piv["chest"]
        o = c + R["chest"] @ np.array([-0.20, -0.06, 0.12])
        fwd = R["chest"] @ unit((0.0, 0.5, 0.86))
        o, B = frame_of(o, fwd, R["chest"] @ np.array([-1.0, 0, 0]))
    rifle = place(RIFLE[0], o, B)
    items = []
    for name, (g, _) in ITEMS.items():
        if p.item == name:
            mid = (hl + hr) / 2
            if name == "handset":
                mid = hl
            if name == "round":
                o2, B2 = frame_of(mid, (0.4, 0, 1))
            else:
                o2, B2 = frame_of(mid, R["chest"] @ np.array([1.0, 0, 0]) if name != "binoculars" else R["head"] @ np.array([1.0, 0, 0]),
                                  R["chest"] @ np.array([0, 0, 1.0]))
            items.append(place(g, o2, B2))
        else:
            items.append(np.repeat(((hl + hr) / 2)[None, :], len(g), 0))
    allp = np.concatenate([out, rifle] + items)
    if p.body is not None:
        allp, rot = p.body(allp), p.body(np.eye(3) + 0.0, rotate_only=True)
        nout = nout @ rot.T
    if normals_out is not None:
        normals_out.append(nout)
    return allp


# ------------------------------------------------------------------ the poses

KNEEL_LEGS = {"L": ((0.95, 0.08, -0.12), (-0.05, 0.02, -1)), "R": ((0.18, -0.06, -0.98), (-1, 0, 0.06))}
KNEEL_DZ = -0.43


def lying(P, rotate_only=False):
    """Face down on the ground, head forward (+x), the hips where the feet stood."""
    r = rot_axis((0, 1, 0), math.pi / 2)
    if rotate_only:
        return r
    Q = P @ r.T
    Q[:, 0] -= 0.95
    Q[:, 2] -= Q[:, 2].min() - 0.01
    return Q


def walk_pose(phase):
    legs = {}
    for s, off in (("L", 0.0), ("R", 0.5)):
        ph = 2 * math.pi * (phase + off)
        a = math.radians(24) * math.sin(ph)
        bend = math.radians(6 + 40 * max(0.0, math.cos(ph)))
        legs[s] = ((math.sin(a), 0.1 if s == "L" else -0.1, -math.cos(a)), (math.sin(a - bend), 0.0, -math.cos(a - bend)))
    dz = -0.025 + 0.02 * math.cos(4 * math.pi * phase)
    return Pose(dz=dz, spine=(0.07, 0, 1), hands=STAND_HANDS, legs=legs)


STAND_HANDS = {"R": (0.20, -0.13, 1.06), "L": (0.44, 0.02, 1.14)}
AIM_HANDS = {"R": (0.16, -0.13, 1.38), "L": (0.50, -0.06, 1.36)}
AIM_POLE = {"L": (0, 1, -1), "R": (-0.2, -1, -0.5)}
CTRL_HANDS = {"R": (0.30, -0.09, 1.16), "L": (0.30, 0.09, 1.16)}
CTRL_POLE = {"L": (-0.3, 1, -1), "R": (-0.3, -1, -1)}


def poses():
    out = {}
    out["STAND"] = Pose(hands=STAND_HANDS)
    out["KNEEL"] = Pose(dz=KNEEL_DZ, spine=(0.12, 0, 1), hands=STAND_HANDS, legs=KNEEL_LEGS)
    out["KNEEL_AIM"] = Pose(dz=KNEEL_DZ, spine=(0.1, 0, 1), head=(0.25, -0.08, 1), hands=AIM_HANDS, hand_pole=AIM_POLE,
                            legs=KNEEL_LEGS)
    prone_legs = {"L": ((0, 0.12, -1), (0, 0.12, -1)), "R": ((0, -0.12, -1), (0, -0.12, -1))}
    out["PRONE_BINO"] = Pose(spine=(-0.30, 0, 1), head=(-0.75, 0, 0.66), legs=prone_legs, body=lying, rifle="back",
                             hands={"L": (0.16, 0.06, 1.70), "R": (0.16, -0.06, 1.70)},
                             hand_pole={"L": (1, 1, -0.6), "R": (1, -1, -0.6)}, item="binoculars")
    out["PRONE_AIM"] = Pose(spine=(-0.30, 0, 1), head=(-0.70, -0.1, 0.7), legs=prone_legs, body=lying,
                            hands={"R": (0.10, -0.12, 1.62), "L": (0.30, 0.0, 1.95)},
                            hand_pole={"L": (1, 1, 0), "R": (1, -1, 0)})
    out["OPERATOR"] = Pose(dz=KNEEL_DZ, spine=(0.15, 0, 1), head=(0.35, 0, 1), hands=CTRL_HANDS, hand_pole=CTRL_POLE,
                           legs=KNEEL_LEGS, rifle="back", item="controller")
    out["STAND_OPERATOR"] = Pose(spine=(0.05, 0, 1), head=(0.3, 0, 1), hands=CTRL_HANDS, hand_pole=CTRL_POLE,
                                 rifle="back", item="controller")
    out["GUNNER"] = Pose(dz=KNEEL_DZ, spine=(0.30, 0, 1), head=(0.45, 0, 1), legs=KNEEL_LEGS, rifle="back",
                         hands={"R": (0.42, -0.10, 1.05), "L": (0.46, 0.10, 1.18)}, hand_pole=CTRL_POLE)
    load_hands = {"R": (0.40, -0.05, 1.42), "L": (0.42, 0.06, 1.50)}
    out["LOADER_ROUND"] = Pose(dz=KNEEL_DZ, spine=(0.18, 0, 1), head=(0.3, 0, 1), legs=KNEEL_LEGS, rifle="back",
                               hands=load_hands, hand_pole=CTRL_POLE, item="round")
    out["LOADER"] = Pose(dz=KNEEL_DZ, spine=(0.18, 0, 1), head=(0.3, 0, 1), legs=KNEEL_LEGS, rifle="back",
                         hands=load_hands, hand_pole=CTRL_POLE)
    out["COVER"] = Pose(dz=KNEEL_DZ, spine=(0.45, 0, 0.9), head=(0.6, 0, 0.8), legs=KNEEL_LEGS, rifle="back",
                        hands={"R": (0.06, -0.10, 1.66), "L": (0.06, 0.10, 1.66)},
                        hand_pole={"L": (1, 0.3, -0.6), "R": (1, -0.3, -0.6)})
    out["RADIO"] = Pose(dz=KNEEL_DZ, spine=(0.08, 0, 1), head=(0.1, 0.1, 1), legs=KNEEL_LEGS, rifle="back",
                        hands={"L": (0.06, 0.11, 1.62), "R": (0.30, -0.12, 1.02)},
                        hand_pole={"L": (0.2, 1, -0.5), "R": (-0.3, -1, -0.6)}, item="handset")
    out["KNEEL_BACK"] = Pose(dz=KNEEL_DZ, spine=(0.12, 0, 1), legs=KNEEL_LEGS, rifle="back",
                             hands={"R": (0.36, -0.14, 1.00), "L": (0.40, 0.12, 1.02)}, hand_pole=CTRL_POLE)
    return out


def limbs(body):
    """Each vertex's main bone: decimation keeps the limbs apart (else a merged armpit webs the arm to the side)."""
    body.group = weights(body.pos).argmax(1)
    return body


def pose_all(body):
    """The mesh with the rifle and items added, frames: the walk cycle then POSES; its pos is the bind pose."""
    W = weights(body.pos)
    ps = poses()
    nrm = []
    frames = [posed(body.pos, W, walk_pose(k / WALK_FRAMES), body.nrm, nrm) for k in range(WALK_FRAMES)]
    frames += [posed(body.pos, W, ps[n], body.nrm, nrm) for n in POSES]
    gun = Image.new("RGBA", (8, 8), (44, 46, 42, 255))
    kit = Image.new("RGBA", (8, 8), (58, 62, 48, 255))
    extra_tri, extra_tex = [], []
    n0 = len(body.pos)
    base = n0
    extra_tri.append(RIFLE[1] + base)
    extra_tex.append(np.full(len(RIFLE[1]), len(body.textures)))
    base += len(RIFLE[0])
    for name, (g, t) in ITEMS.items():
        extra_tri.append(t + base)
        extra_tex.append(np.full(len(t), len(body.textures) + 1))
        base += len(g)
    nadd = base - n0
    bind_extra = frames[0][n0:]
    m = Mesh(np.concatenate([body.pos, bind_extra]), np.concatenate([body.uv, np.full((nadd, 2), 0.5)]),
             np.concatenate([body.tri] + extra_tri), np.concatenate([body.tex] + extra_tex), body.textures + [gun, kit],
             np.concatenate([body.nrm, np.zeros((nadd, 3))]))
    m.frames = np.stack(frames)
    # the body's normals turned with its bones; the rifle's and items' from their faces (zero: worked out on writing)
    m.frame_nrm = np.stack([np.concatenate([nb, np.zeros((nadd, 3))]) for nb in nrm])
    return m
