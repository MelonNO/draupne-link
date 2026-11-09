"""Shared parts of export_tactical.py: reading glTF (with numpy), a small software rasteriser (the trees' impostor
cards are drawn with it, and previews to look at), vertex-clustering decimation and the pack writer.

Needs numpy and Pillow at export time only (python -m pip install numpy pillow); DRAUPNE Link never runs this."""
import json, math, os, struct
import numpy as np
from PIL import Image

COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NUM = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ------------------------------------------------------------------ glTF

class Gltf:
    def __init__(self, path):
        self.path = path
        self.dir = os.path.dirname(path)
        self.gl = json.load(open(path, encoding="utf-8"))
        self.blobs = [open(os.path.join(self.dir, b["uri"]), "rb").read() for b in self.gl["buffers"]]

    def accessor(self, i):
        a = self.gl["accessors"][i]
        v = self.gl["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]]).newbyteorder("<")
        n = NUM[a["type"]]
        blob = self.blobs[v["buffer"]]
        base = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = v.get("byteStride", dt.itemsize * n)
        count = a["count"]
        if stride == dt.itemsize * n:
            arr = np.frombuffer(blob, dt, count * n, base).reshape(count, n)
        else:
            raw = np.frombuffer(blob, np.uint8, stride * (count - 1) + dt.itemsize * n, base)
            arr = np.lib.stride_tricks.as_strided(raw, (count, dt.itemsize * n), (stride, 1)).copy().view(dt).reshape(count, n)
        arr = arr.astype(np.float64) if dt.kind == "f" else arr.astype(np.int64)
        if a.get("normalized"):
            arr = arr / float(np.iinfo(dt).max)
        return arr if n > 1 else arr[:, 0]

    def image_path(self, mat_index, key="baseColorTexture"):
        """The material's base colour texture file, or None."""
        if mat_index is None:
            return None
        m = self.gl["materials"][mat_index]
        tex = m.get("pbrMetallicRoughness", {}).get(key)
        if tex is None:
            sg = m.get("extensions", {}).get("KHR_materials_pbrSpecularGlossiness", {})
            tex = sg.get("diffuseTexture")
        if tex is None:
            return None
        img = self.gl["textures"][tex["index"]]["source"]
        return os.path.join(self.dir, self.gl["images"][img]["uri"])

    def base_factor(self, mat_index):
        if mat_index is None:
            return np.ones(4)
        m = self.gl["materials"][mat_index]
        f = m.get("pbrMetallicRoughness", {}).get("baseColorFactor")
        if f is None:
            f = m.get("extensions", {}).get("KHR_materials_pbrSpecularGlossiness", {}).get("diffuseFactor", [1, 1, 1, 1])
        return np.array(f, dtype=np.float64)


def node_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    t = node.get("translation", [0, 0, 0])
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r * np.array(s)[None, :]
    m[:3, 3] = t
    return m


class Part:
    """A primitive in the scene's frame (glTF axes, Y up): positions, normals, uv, triangles, its material."""

    def __init__(self, pos, nrm, uv, tri, mat, node_name, prim=None, world=None):
        self.pos, self.nrm, self.uv, self.tri, self.mat, self.node = pos, nrm, uv, tri, mat, node_name
        self.prim, self.world = prim, world


def parts(g, keep=None, skinned_raw=False):
    """Every mesh primitive with its node's world transform applied. keep(node_name) -> bool filters nodes.
    skinned_raw: a skinned mesh's vertices are left in its bind space (the skin is applied by the caller)."""
    out = []
    gl = g.gl

    def walk(ni, parent, names):
        node = gl["nodes"][ni]
        m = parent @ node_matrix(node)
        name = node.get("name", "")
        if "mesh" in node and (keep is None or keep(name) or any(keep(n) for n in names)):
            for prim in gl["meshes"][node["mesh"]]["primitives"]:
                a = prim["attributes"]
                pos = g.accessor(a["POSITION"])
                nrm = g.accessor(a["NORMAL"]) if "NORMAL" in a else None
                uv = g.accessor(a["TEXCOORD_0"]) if "TEXCOORD_0" in a else None
                tri = (g.accessor(prim["indices"]) if "indices" in prim else np.arange(len(pos))).reshape(-1, 3)
                world = m
                if not ("skin" in node and skinned_raw):
                    pos = pos @ m[:3, :3].T + m[:3, 3]
                    if nrm is not None:
                        nm = np.linalg.inv(m[:3, :3]).T
                        nrm = nrm @ nm.T
                        nrm /= np.maximum(1e-12, np.linalg.norm(nrm, axis=1))[:, None]
                    if np.linalg.det(m[:3, :3]) < 0:
                        tri = tri[:, ::-1]
                out.append(Part(pos, nrm, uv, tri, prim.get("material"), name, prim, world))
        for ch in node.get("children", []):
            walk(ch, m, names + [name])

    for ni in gl["scenes"][gl.get("scene", 0)]["nodes"]:
        walk(ni, np.eye(4), [])
    return out


# ------------------------------------------------------------------ a mesh of ours

class Mesh:
    """Our axes: x forward, y left, z up, metres. pos (n,3), uv (n,2), tri (m,3), tex (m,) index into textures
    (a list of RGBA PIL images), frames: extra positions (k,n,3) for posed / animated models."""

    def __init__(self, pos, uv, tri, tex, textures, nrm=None):
        self.pos = np.asarray(pos, dtype=np.float64)
        self.uv = np.asarray(uv, dtype=np.float64)
        self.tri = np.asarray(tri, dtype=np.int64)
        self.tex = np.asarray(tex, dtype=np.int64)
        self.textures = textures
        self.nrm = nrm
        self.frames = None

    def triangles(self):
        return len(self.tri)


def from_parts(ps, g, axes="zxy", yaw_deg=0.0, flip_v=False):
    """Parts into one Mesh in our axes. axes: which glTF axes become our forward, left, up ('zxy': forward = +Z,
    left = +X, up = +Y; a leading '-' turns one round). Each distinct texture file is one texture."""
    textures, index = [], {}
    pos, uv, tri, tex, nrm = [], [], [], [], []
    base = 0
    for p in ps:
        path = g.image_path(p.mat)
        key = path or ("factor", tuple(g.base_factor(p.mat)))
        if key not in index:
            index[key] = len(textures)
            if path:
                img = Image.open(path).convert("RGBA")
                f = g.base_factor(p.mat)
                if np.any(f[:3] < 0.999):
                    a = np.asarray(img).astype(np.float64)
                    a[..., :3] *= f[:3]
                    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")
            else:
                c = tuple(int(255 * min(1, max(0, x))) for x in g.base_factor(p.mat)[:3]) + (255,)
                img = Image.new("RGBA", (4, 4), c)
            textures.append(img)
        pos.append(p.pos)
        nrm.append(p.nrm if p.nrm is not None else np.zeros_like(p.pos))
        uv.append(p.uv if p.uv is not None else np.zeros((len(p.pos), 2)))
        tri.append(p.tri + base)
        tex.append(np.full(len(p.tri), index[key]))
        base += len(p.pos)
    P = np.concatenate(pos)
    N = np.concatenate(nrm)
    P, N = swizzle(P, axes), swizzle(N, axes)
    T = np.concatenate(tri)
    if handedness(axes) < 0:
        T = T[:, ::-1]
    m = Mesh(P, np.concatenate(uv), T, np.concatenate(tex), textures, N)
    if yaw_deg:
        turn(m, yaw_deg)
    if flip_v:
        m.uv[:, 1] = 1 - m.uv[:, 1]
    return m


def swizzle(a, axes):
    cols, sign, i = [], [], 0
    k = 0
    s = 1
    while k < len(axes):
        if axes[k] == "-":
            s = -1
            k += 1
            continue
        cols.append("xyz".index(axes[k]))
        sign.append(s)
        s = 1
        k += 1
    return np.stack([a[:, cols[j]] * sign[j] for j in range(3)], axis=1)


def handedness(axes):
    m = np.eye(3)
    return np.sign(np.linalg.det(swizzle(m, axes)))


def turn(m, yaw_deg):
    a = math.radians(yaw_deg)
    c, s = math.cos(a), math.sin(a)
    r = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    m.pos = m.pos @ r.T
    if m.nrm is not None:
        m.nrm = m.nrm @ r.T
    if m.frames is not None:
        m.frames = m.frames @ r.T


def fit(m, length=None, height=None, width=None, centre=True, ground=True):
    """Scaled so its x extent is length (or z extent height, or y extent width), centred on x/y, standing on z 0."""
    lo, hi = m.pos.min(0), m.pos.max(0)
    size = hi - lo
    k = 1.0
    if length:
        k = length / size[0]
    elif height:
        k = height / size[2]
    elif width:
        k = width / size[1]
    m.pos = m.pos * k
    lo, hi = lo * k, hi * k
    off = np.zeros(3)
    if centre:
        off[0] = -(lo[0] + hi[0]) / 2
        off[1] = -(lo[1] + hi[1]) / 2
    if ground:
        off[2] = -lo[2]
    m.pos = m.pos + off
    return k, off


def merge(meshes):
    textures, pos, uv, tri, tex, nrm = [], [], [], [], [], []
    base = 0
    for m in meshes:
        pos.append(m.pos)
        uv.append(m.uv)
        nrm.append(m.nrm if m.nrm is not None else np.zeros_like(m.pos))
        tri.append(m.tri + base)
        tex.append(m.tex + len(textures))
        textures.extend(m.textures)
        base += len(m.pos)
    return Mesh(np.concatenate(pos), np.concatenate(uv), np.concatenate(tri), np.concatenate(tex), textures, np.concatenate(nrm))


# ------------------------------------------------------------------ decimation

def cluster(m, cell, uvq=24.0, nq=2.0, frames_too=True):
    """Vertex clustering: vertices in the same cell (and the same texture, about the same uv and normal, so seams and
    hard edges stay) become one at their mean; collapsed and duplicate triangles go. A posed model is clustered in its
    first frame and every frame follows the same merge."""
    n = len(m.pos)
    vtex = np.zeros(n, dtype=np.int64)
    vtex[m.tri.ravel()] = np.repeat(m.tex, 3)
    q = np.floor(m.pos / cell).astype(np.int64)
    u = np.floor(m.uv * uvq).astype(np.int64)
    nr = m.nrm if m.nrm is not None else np.zeros_like(m.pos)
    nk = np.round(nr * nq).astype(np.int64)
    key = np.concatenate([q, u, nk, vtex[:, None]], axis=1)
    if getattr(m, "group", None) is not None:  # parts that must not be welded to each other (a soldier's limbs)
        key = np.concatenate([key, m.group[:, None]], axis=1)
    _, inv, counts = np.unique(key, axis=0, return_inverse=True, return_counts=True)
    inv = inv.ravel()
    k = len(counts)

    def mean(a):
        out = np.zeros((k,) + a.shape[1:])
        np.add.at(out, inv, a)
        return out / counts.reshape((-1,) + (1,) * (a.ndim - 1))

    pos = mean(m.pos)
    uv = mean(m.uv)
    nrm = mean(nr)
    nrm /= np.maximum(1e-9, np.linalg.norm(nrm, axis=1))[:, None]
    t = inv[m.tri]
    ok = (t[:, 0] != t[:, 1]) & (t[:, 1] != t[:, 2]) & (t[:, 0] != t[:, 2])
    t, tx = t[ok], m.tex[ok]
    srt = np.sort(t, axis=1)
    _, first = np.unique(np.concatenate([srt, tx[:, None]], axis=1), axis=0, return_index=True)
    first = np.sort(first)
    t, tx = t[first], tx[first]
    used = np.unique(t)
    remap = np.full(k, -1)
    remap[used] = np.arange(len(used))
    out = Mesh(pos[used], uv[used], remap[t], tx, m.textures, nrm[used])
    if m.frames is not None and frames_too:
        fr = np.stack([mean(f)[used] for f in m.frames])
        out.frames = fr
    return out


def decimate(m, target, uvq=24.0, nq=2.0):
    """Clustered to about target triangles (a binary search on the cell size); unchanged if it has fewer."""
    if m.triangles() <= target:
        return m
    size = float(np.max(m.pos.max(0) - m.pos.min(0)))
    lo, hi = size / 2000, size / 4
    best = None
    for _ in range(18):
        mid = math.sqrt(lo * hi)
        c = cluster(m, mid, uvq, nq)
        if c.triangles() > target:
            lo = mid
        else:
            hi = mid
            best = c
        if best is not None and abs(best.triangles() - target) < target * 0.06:
            break
    return best if best is not None else cluster(m, hi, uvq, nq)


# ------------------------------------------------------------------ a small rasteriser (previews, impostors)

SUN = np.array([-0.45, 0.35, 0.82])
SUN = SUN / np.linalg.norm(SUN)


def render(m, w, h, view_dir, up=(0, 0, 1), box=None, frame=None, alpha_cut=0.5, bg=(0, 0, 0, 0), light=True,
           tex_cache=None):
    """An orthographic picture of the mesh seen along view_dir (from the camera towards the model), RGBA. box: the
    world rectangle (u0, v0, u1, v1) on the picture plane, else the model's extent."""
    d = np.array(view_dir, dtype=np.float64)
    d /= np.linalg.norm(d)
    upv = np.array(up, dtype=np.float64)
    right = np.cross(d, upv)
    right /= np.linalg.norm(right)
    upv = np.cross(right, d)
    P = m.pos if frame is None else m.frames[frame]
    sx, sy, sz = P @ right, P @ upv, P @ d
    if box is None:
        half = 0.52 * max(np.ptp(sx), np.ptp(sy) * w / h)
        mx, my = (sx.min() + sx.max()) / 2, (sy.min() + sy.max()) / 2
        box = (mx - half, my - half * h / w, mx + half, my + half * h / w)
    u0, v0, u1, v1 = box
    px = (sx - u0) / (u1 - u0) * w
    py = (v1 - sy) / (v1 - v0) * h
    img = np.zeros((h, w, 4), dtype=np.float64)
    img[:] = bg
    zb = np.full((h, w), np.inf)
    texs = tex_cache if tex_cache is not None else [np.asarray(t.convert("RGBA")).astype(np.float64) for t in m.textures]
    for i, (a, b, c) in enumerate(m.tri):
        x0, y0, x1, y1, x2, y2 = px[a], py[a], px[b], py[b], px[c], py[c]
        area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if abs(area) < 1e-9:
            continue
        mnx, mxx = max(0, int(math.floor(min(x0, x1, x2)))), min(w - 1, int(math.ceil(max(x0, x1, x2))))
        mny, mxy = max(0, int(math.floor(min(y0, y1, y2)))), min(h - 1, int(math.ceil(max(y0, y1, y2))))
        if mnx > mxx or mny > mxy:
            continue
        gx, gy = np.meshgrid(np.arange(mnx, mxx + 1) + 0.5, np.arange(mny, mxy + 1) + 0.5)
        w0 = ((x1 - gx) * (y2 - gy) - (x2 - gx) * (y1 - gy)) / area
        w1 = ((x2 - gx) * (y0 - gy) - (x0 - gx) * (y2 - gy)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not inside.any():
            continue
        z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
        sub = zb[mny:mxy + 1, mnx:mxx + 1]
        ok = inside & (z < sub)
        if not ok.any():
            continue
        t = texs[m.tex[i]]
        th, tw = t.shape[:2]
        uu = w0 * m.uv[a, 0] + w1 * m.uv[b, 0] + w2 * m.uv[c, 0]
        vv = w0 * m.uv[a, 1] + w1 * m.uv[b, 1] + w2 * m.uv[c, 1]
        tx = (np.mod(uu, 1.0) * tw).astype(np.int64).clip(0, tw - 1)
        ty = (np.mod(vv, 1.0) * th).astype(np.int64).clip(0, th - 1)
        col = t[ty, tx]
        ok = ok & (col[..., 3] >= alpha_cut * 255)
        if not ok.any():
            continue
        if light:
            e1, e2 = P[b] - P[a], P[c] - P[a]
            nn = np.cross(e1, e2)
            l = np.linalg.norm(nn)
            nn = nn / l if l > 0 else np.array([0, 0, 1.0])
            if nn @ d > 0:
                nn = -nn
            k = 0.55 + 0.6 * max(0.0, float(nn @ SUN))
        else:
            k = 1.0
        sub[ok] = z[ok]
        reg = img[mny:mxy + 1, mnx:mxx + 1]
        rgb = np.clip(col[..., :3] * k, 0, 255)
        reg[ok, :3] = rgb[ok]
        reg[ok, 3] = 255
    return Image.fromarray(img.clip(0, 255).astype(np.uint8), "RGBA"), box


def preview(m, path, size=320):
    """Four views side by side (front, side, top, three-quarter) on grey."""
    views = [(-1, 0, 0), (0, -1, 0), (0, 0, -1), (-1, -1, -0.6)]
    sheet = Image.new("RGB", (size * 4, size), (128, 128, 128))
    cache = [np.asarray(t.convert("RGBA")).astype(np.float64) for t in m.textures]
    for k, v in enumerate(views):
        up = (1, 0, 0) if v == (0, 0, -1) else (0, 0, 1)
        img, _ = render(m, size, size, v, up, tex_cache=cache)
        sheet.paste(img, (k * size, 0), img)
    sheet.save(path)


# ------------------------------------------------------------------ the pack

def encode_texture(img, size, alpha):
    """A texture scaled to at most size on its long side, as JPEG (opaque) or PNG (with an alpha mask)."""
    import io
    w, h = img.size
    k = min(1.0, size / max(w, h))
    nw, nh = max(1, round(w * k)), max(1, round(h * k))
    # powers of two: the mip chain halves cleanly
    nw, nh = 1 << max(0, round(math.log2(nw))), 1 << max(0, round(math.log2(nh)))
    im = img.resize((nw, nh), Image.LANCZOS)
    buf = io.BytesIO()
    if alpha:
        a = np.asarray(im.convert("RGBA")).copy()
        a[..., 3] = np.where(a[..., 3] >= 128, 255, 0)
        bleed(a)
        Image.fromarray(a, "RGBA").save(buf, "PNG", optimize=True)
    else:
        im.convert("RGB").save(buf, "JPEG", quality=84, optimize=True)
    return buf.getvalue()


def bleed(a, rounds=6):
    """Colour pushed out from the opaque texels into the clear ones, so a mip level's average has no dark fringe."""
    rgb = a[..., :3].astype(np.float64)
    mask = a[..., 3] > 0
    for _ in range(rounds):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(mask.shape)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            m2 = np.roll(mask, (dy, dx), (0, 1))
            acc += np.roll(rgb, (dy, dx), (0, 1)) * m2[..., None]
            cnt += m2
        grow = (~mask) & (cnt > 0)
        rgb[grow] = acc[grow] / cnt[grow][:, None]
        mask = mask | grow
    a[..., :3] = rgb.clip(0, 255).astype(np.uint8)


def face_normals(pos, tri):
    """Vertex normals from the faces round each vertex (area weighted, by winding)."""
    n = np.zeros_like(pos)
    f = np.cross(pos[tri[:, 1]] - pos[tri[:, 0]], pos[tri[:, 2]] - pos[tri[:, 0]])
    for k in range(3):
        np.add.at(n, tri[:, k], f)
    return n / np.maximum(1e-12, np.linalg.norm(n, axis=1))[:, None]


def vertex_normals(m, pos, given):
    """The model's own normals where it has them (decimation can flip a triangle's winding, never these), else the
    faces'."""
    out = face_normals(pos, m.tri)
    if given is not None:
        ok = np.linalg.norm(given, axis=1) > 0.5
        out[ok] = given[ok] / np.linalg.norm(given[ok], axis=1)[:, None]
    return out


def write_pack(path, textures, meshes):
    """textures: [(name, flags, bytes)]; meshes: [(name, flags, Mesh with tex indices into textures)]."""
    import gzip
    with gzip.open(path, "wb", 9) as f:
        f.write(b"TAC1")
        f.write(struct.pack(">i", len(textures)))
        for name, flags, data in textures:
            nb = name.encode("utf-8")
            f.write(struct.pack(">H", len(nb)) + nb)
            f.write(struct.pack(">Bi", flags, len(data)))
            f.write(data)
        f.write(struct.pack(">i", len(meshes)))
        for name, flags, m in meshes:
            nb = name.encode("utf-8")
            f.write(struct.pack(">H", len(nb)) + nb)
            frames = [m.pos] if m.frames is None else list(m.frames)
            nv, nt = len(m.pos), len(m.tri)
            assert nv < 65536, name + ": too many vertices"
            f.write(struct.pack(">Biii", flags, nv, nt, len(frames)))
            for fr in frames:
                mm = np.round(fr * 1000).astype(np.int64)
                assert np.abs(mm).max() < 32768, name + ": out of the int16 range"
                f.write(mm.astype(">i2").tobytes())
            given = getattr(m, "frame_nrm", None)
            for k, fr in enumerate(frames):
                nn = vertex_normals(m, fr, given[k] if given is not None else (m.nrm if k == 0 else None))
                f.write(np.clip(np.round(nn * 127), -127, 127).astype(np.int8).tobytes())
            f.write(m.uv.astype(">f4").tobytes())
            rows = np.zeros(nt, dtype=[("a", ">u2"), ("b", ">u2"), ("c", ">u2"), ("t", "u1")])
            rows["a"], rows["b"], rows["c"], rows["t"] = m.tri[:, 0], m.tri[:, 1], m.tri[:, 2], m.tex
            f.write(rows.tobytes())
