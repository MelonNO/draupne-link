"""Turns Kenney's CC0 models (github.com/KenneyNL Starter-Kit-Racing, -City-Builder, -FPS, -3D-Platformer; each kit's
models/*.glb and models/Textures/colormap.png, see THIRD-PARTY.md) into the props of the tour's virtual drones' scenes.

Each model's nodes are flattened into one mesh (their transforms applied), and each triangle takes one colour: the
kit's colour map at the middle of its texture coordinates (Kenney colours a model by flat swatches of that map).

model/props.bin.gz (gzip, big-endian as Java's DataInputStream reads it): 'PRP1', model count; per model its name
(Java's writeUTF), vertex count, triangle count, per vertex x y z float (the model's axes: x forward = glTF +Z, y left
= glTF +X, z up = glTF +Y; metres as Kenney made them), per triangle three uint16 indices and an RGB int. The original
files are not committed. Pure Python.

Run from the folder holding the kits (relative paths, no drive letters):
python export_kenney.py <repo>/src/main/resources/model/props.bin.gz name=Kit/model.glb:Kit/colormap.png ..."""
import sys, json, struct, gzip, zlib


def read_png(path):
    """8-bit RGB / RGBA, not interlaced: (width, height, rows of (r, g, b, a) tuples)."""
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n"
    at, idat, w = 8, b"", 0
    while at < len(d):
        n = struct.unpack(">I", d[at:at + 4])[0]
        kind = d[at + 4:at + 8]
        body = d[at + 8:at + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, inter = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and inter == 0, "only 8-bit RGB(A), not interlaced"
            bpp = 4 if ctype == 6 else 3
        elif kind == b"IDAT":
            idat += body
        at += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, pos = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows


COMP = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NUM = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def mat_mul(a, b):
    r = [0.0] * 16
    for c in range(4):
        for rr in range(4):
            r[c * 4 + rr] = sum(a[k * 4 + rr] * b[c * 4 + k] for k in range(4))
    return r


def trs(node):
    if "matrix" in node:
        return list(node["matrix"])
    t = node.get("translation", [0, 0, 0])
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    m = [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
         2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
         2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
         t[0], t[1], t[2], 1]
    for c in range(3):
        for r in range(3):
            m[c * 4 + r] *= s[c]
    return m


def convert(glb, colormap):
    data = open(glb, "rb").read()
    assert data[:4] == b"glTF"
    jlen = struct.unpack("<I", data[12:16])[0]
    gl = json.loads(data[20:20 + jlen])
    bin_at = 20 + jlen
    blen = struct.unpack("<I", data[bin_at:bin_at + 4])[0]
    blob = data[bin_at + 8:bin_at + 8 + blen]
    tw, th, bpp, rows = colormap

    def accessor(i):
        a = gl["accessors"][i]
        v = gl["bufferViews"][a["bufferView"]]
        fmt, size = COMP[a["componentType"]]
        n = NUM[a["type"]]
        stride = v.get("byteStride", size * n)
        base = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        return [struct.unpack_from("<" + fmt * n, blob, base + k * stride) if n > 1
                else struct.unpack_from("<" + fmt, blob, base + k * stride)[0] for k in range(a["count"])]

    def texel(u, v):
        x = min(tw - 1, max(0, int((u % 1.0) * tw)))
        y = min(th - 1, max(0, int((v % 1.0) * th)))
        r = rows[y]
        return r[x * bpp], r[x * bpp + 1], r[x * bpp + 2]

    verts, tris = [], []

    def walk(ni, parent):
        node = gl["nodes"][ni]
        m = mat_mul(parent, trs(node))
        if "mesh" in node:
            for prim in gl["meshes"][node["mesh"]]["primitives"]:
                pos = accessor(prim["attributes"]["POSITION"])
                uv = accessor(prim["attributes"]["TEXCOORD_0"]) if "TEXCOORD_0" in prim["attributes"] else None
                idx = accessor(prim["indices"]) if "indices" in prim else list(range(len(pos)))
                mat = gl["materials"][prim["material"]] if "material" in prim else {}
                factor = mat.get("pbrMetallicRoughness", {}).get("baseColorFactor", [1, 1, 1, 1])
                textured = "baseColorTexture" in mat.get("pbrMetallicRoughness", {})
                base = len(verts)
                for p in pos:
                    x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
                    y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
                    z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
                    verts.append((z, x, y))  # forward = +Z, left = +X, up = +Y
                for k in range(0, len(idx) - 2, 3):
                    a, b, c = idx[k], idx[k + 1], idx[k + 2]
                    if textured and uv:
                        u = (uv[a][0] + uv[b][0] + uv[c][0]) / 3
                        v = (uv[a][1] + uv[b][1] + uv[c][1]) / 3
                        col = texel(u, v)
                    else:
                        col = (255, 255, 255)
                    col = tuple(min(255, int(col[i] * factor[i])) for i in range(3))
                    tris.append((base + a, base + b, base + c, col))
        for ch in node.get("children", []):
            walk(ch, m)

    ident = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    scene = gl["scenes"][gl.get("scene", 0)]
    for ni in scene["nodes"]:
        walk(ni, ident)
    assert len(verts) < 65536, glb + ": too many vertices"
    return verts, tris


def main():
    out = sys.argv[1]
    maps = {}
    models = []
    for arg in sys.argv[2:]:
        name, rest = arg.split("=", 1)
        glb, cmap = rest.rsplit(":", 1)  # relative paths: run it from the folder with the kits
        if cmap not in maps:
            maps[cmap] = read_png(cmap)
        verts, tris = convert(glb, maps[cmap])
        models.append((name, verts, tris))
        print(name, len(verts), "vertices", len(tris), "triangles")
    with gzip.open(out, "wb", 9) as f:
        f.write(b"PRP1")
        f.write(struct.pack(">i", len(models)))
        for name, verts, tris in models:
            nb = name.encode("utf-8")
            f.write(struct.pack(">H", len(nb)) + nb)
            f.write(struct.pack(">ii", len(verts), len(tris)))
            for v in verts:
                f.write(struct.pack(">fff", *v))
            for a, b, c, col in tris:
                f.write(struct.pack(">HHHi", a, b, c, (col[0] << 16) | (col[1] << 8) | col[2]))


main()
