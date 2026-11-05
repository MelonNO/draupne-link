"""Tessellates a STEP assembly into DRAUPNE's mesh resource (gzip):
'TXM1', part count; per part: name (UTF), r g b (float), vertex count, xyz float32 (mm), normals int8 x3,
triangle count, indices (uint16 if the part has <65536 vertices, else int32)."""
import sys, struct, gzip, json
from OCP.STEPCAFControl import STEPCAFControl_Reader
from OCP.TDocStd import TDocStd_Document
from OCP.TCollection import TCollection_ExtendedString
from OCP.XCAFDoc import XCAFDoc_DocumentTool, XCAFDoc_ColorType
from OCP.TDF import TDF_LabelSequence, TDF_Label
from OCP.TDataStd import TDataStd_Name
from OCP.Quantity import Quantity_Color
from OCP.BRepMesh import BRepMesh_IncrementalMesh
from OCP.BRep import BRep_Tool
from OCP.TopExp import TopExp_Explorer
from OCP.TopAbs import TopAbs_FACE, TopAbs_REVERSED
from OCP.TopoDS import TopoDS
from OCP.TopLoc import TopLoc_Location
from OCP.IFSelect import IFSelect_RetDone
from OCP.BRepLib import BRepLib_ToolTriangulatedShape

src, dst, deflection = sys.argv[1], sys.argv[2], float(sys.argv[3])
doc = TDocStd_Document(TCollection_ExtendedString("XmlOcaf"))
reader = STEPCAFControl_Reader()
reader.SetColorMode(True)
reader.SetNameMode(True)
assert reader.ReadFile(src) == IFSelect_RetDone
reader.Transfer(doc)
shapes = XCAFDoc_DocumentTool.ShapeTool_s(doc.Main())
colours = XCAFDoc_DocumentTool.ColorTool_s(doc.Main())


def name(label):
    attr = TDataStd_Name()
    if label.FindAttribute(TDataStd_Name.GetID_s(), attr):
        return attr.Get().ToExtString()
    return "?"


def colour(label, shape):
    c = Quantity_Color()
    for t in (XCAFDoc_ColorType.XCAFDoc_ColorSurf, XCAFDoc_ColorType.XCAFDoc_ColorGen):
        for call in (lambda: colours.GetColor_s(label, t, c), lambda: colours.GetColor(shape, t, c)):
            try:
                if call():
                    return (c.Red(), c.Green(), c.Blue())
            except TypeError:
                pass
    return None


parts = []


def mesh(shape):
    BRepMesh_IncrementalMesh(shape, deflection, False, 0.35, True)
    pos, nrm, tri = [], [], []
    exp = TopExp_Explorer(shape, TopAbs_FACE)
    while exp.More():
        face = TopoDS.Face_s(exp.Current())
        loc = TopLoc_Location()
        t = BRep_Tool.Triangulation_s(face, loc)
        exp.Next()
        if t is None:
            continue
        if not t.HasNormals():
            BRepLib_ToolTriangulatedShape.ComputeNormals_s(face, t)
        trsf = loc.Transformation()
        rev = face.Orientation() == TopAbs_REVERSED
        base = len(pos)
        for i in range(1, t.NbNodes() + 1):
            p = t.Node(i).Transformed(trsf)
            pos.append((p.X(), p.Y(), p.Z()))
            n = t.Normal(i)
            v = n.Transformed(trsf) if hasattr(n, "Transformed") else n
            nx, ny, nz = v.X(), v.Y(), v.Z()
            if rev:
                nx, ny, nz = -nx, -ny, -nz
            nrm.append((nx, ny, nz))
        for i in range(1, t.NbTriangles() + 1):
            a, b, c = t.Triangle(i).Get()
            if rev:
                b, c = c, b
            tri.append((base + a - 1, base + b - 1, base + c - 1))
    return pos, nrm, tri


def walk(label, loc, path):
    if shapes.IsReference_s(label):
        ref = TDF_Label()
        shapes.GetReferredShape_s(label, ref)
        walk(ref, loc.Multiplied(shapes.GetLocation_s(label)), path + [name(label)])
        return
    if shapes.IsAssembly_s(label):
        kids = TDF_LabelSequence()
        shapes.GetComponents_s(label, kids)
        for i in range(1, kids.Length() + 1):
            walk(kids.Value(i), loc, path)
        return
    raw = shapes.GetShape_s(label)
    col = colour(label, raw)
    shape = raw.Moved(loc)
    pos, nrm, tri = mesh(shape)
    if tri:
        nm = next((q for q in reversed(path) if not q.startswith("=>")), name(label))
        parts.append((nm, col or (0.14, 0.14, 0.14), pos, nrm, tri))


roots = TDF_LabelSequence()
shapes.GetFreeShapes(roots)
for i in range(1, roots.Length() + 1):
    walk(roots.Value(i), TopLoc_Location(), [])

with gzip.open(dst, "wb", compresslevel=9) as f:
    f.write(b"TXM1")
    f.write(struct.pack(">i", len(parts)))
    total = 0
    for nm, col, pos, nrm, tri in parts:
        b = nm.encode("utf-8")
        f.write(struct.pack(">H", len(b)) + b)
        f.write(struct.pack(">3f", *col))
        f.write(struct.pack(">i", len(pos)))
        f.write(b"".join(struct.pack(">3f", *p) for p in pos))
        f.write(b"".join(struct.pack(">3b", *(max(-127, min(127, round(c * 127))) for c in n)) for n in nrm))
        f.write(struct.pack(">i", len(tri)))
        fmt = ">3H" if len(pos) < 65536 else ">3i"
        f.write(b"".join(struct.pack(fmt, *t) for t in tri))
        total += len(tri)
        print("%-24s %7d verts %7d tris" % (nm, len(pos), len(tri)))
    print("parts", len(parts), "triangles", total)
