"""Convert the compact IMAX Blender export to a Kadr WebGL asset."""
import base64
import json
import math
import struct
import sys
from collections import defaultdict
from pathlib import Path

source, output = map(Path, sys.argv[1:])
raw = source.read_bytes()
magic, version, size = struct.unpack_from("<4sII", raw)
assert (magic, version, size) == (b"glTF", 2, len(raw))
length, kind = struct.unpack_from("<I4s", raw, 12)
assert kind == b"JSON"
doc = json.loads(raw[20:20 + length])
bin_length, kind = struct.unpack_from("<I4s", raw, 20 + length)
assert kind == b"BIN\0"
binary = memoryview(raw)[28 + length:28 + length + bin_length]


def accessor(index):
    item = doc["accessors"][index]
    view = doc["bufferViews"][item["bufferView"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[item["type"]]
    fmt = struct.Struct("<" + {5121: "B", 5123: "H", 5125: "I", 5126: "f"}[item["componentType"]] * width)
    start = view.get("byteOffset", 0) + item.get("byteOffset", 0)
    stride = view.get("byteStride", fmt.size)
    return [fmt.unpack_from(binary, start + i * stride) for i in range(item["count"])]


def encode(groups, code):
    return base64.b64encode(b"".join(struct.pack("<" + code * len(group), *group) for group in groups)).decode("ascii")


def transform(p, node):
    if "matrix" in node:
        m = node["matrix"]
        return tuple(sum(m[4 * k + i] * p[k] for k in range(3)) + m[12 + i] for i in range(3))
    x, y, z = (a*b for a, b in zip(p, node.get("scale", (1, 1, 1))))
    qx, qy, qz, qw = node.get("rotation", (0, 0, 0, 1))
    tx, ty, tz = 2*(qy*z-qz*y), 2*(qz*x-qx*z), 2*(qx*y-qy*x)
    result = (x+qw*tx+qy*tz-qz*ty, y+qw*ty+qz*tx-qx*tz, z+qw*tz+qx*ty-qy*tx)
    return tuple(a+b for a, b in zip(result, node.get("translation", (0, 0, 0))))



def upholstery_components(points, indices):
    parents = list(range(len(points)))

    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    seen = {}
    for i, point in enumerate(points):
        key = tuple(round(v, 3) for v in point)
        parents[find(i)] = find(seen.setdefault(key, i))
    for a, b, c in zip(indices[::3], indices[1::3], indices[2::3]):
        parents[find(b)] = find(a)
        parents[find(c)] = find(a)
    components = defaultdict(list)
    for i, point in enumerate(points):
        components[find(i)].append(i)
    for vertices in components.values():
        component = [points[i] for i in vertices]
        low, high = [tuple(bound(axis) for axis in zip(*component)) for bound in (min, max)]
        dx, dy, dz = (hi - lo for lo, hi in zip(low, high))
        if dy < 0.3 and dx > 0.2 and dz > 0.2:
            yield vertices, tuple((lo + hi) / 2 for lo, hi in zip(low, high))


def upholstery_mesh(part, points, indices, cushioned, seat):
    triangles = [indices[i:i+3] for i in range(0, len(indices), 3)
                 if (indices[i][0] in cushioned) == seat]
    used = sorted({i[0] for triangle in triangles for i in triangle})
    remap = {old: new for new, old in enumerate(used)}
    return dict(part, pos=encode((points[i] for i in used), "f"),
                idx=encode(((remap[i[0]],) for triangle in triangles for i in triangle), "I"),
                n=len(triangles)*3,
                color=[0.76, 0.25, 0.33] if seat else [0.43, 0.13, 0.25])


def image_url(index):
    image = doc["images"][index]
    view = doc["bufferViews"][image["bufferView"]]
    start = view.get("byteOffset", 0)
    encoded = base64.b64encode(binary[start:start + view["byteLength"]]).decode("ascii")
    return f'data:{image["mimeType"]};base64,{encoded}'


data = {
    "model": "imax_compact", "rows": 8, "cols": 43, "aisles": [22],
    "eyeBack": 0.12, "eyeUp": 0.78, "parts": [], "chair": [], "images": {}, "lights": [],
}
rows = defaultdict(list)
scene = doc["scenes"][doc.get("scene", 0)]
assert all("children" not in doc["nodes"][i] for i in scene["nodes"])
for index in scene["nodes"]:
    node = doc["nodes"][index]
    # Blender exports Area lamps as positioned empty nodes in this file.
    if "KHR_lights_punctual" in node.get("extensions", {}) or (
        "mesh" not in node and node.get("name", "").startswith("Area.")
    ):
        data["lights"].append(transform((0, 0, 0), node))
    if "mesh" not in node:
        continue
    for primitive in doc["meshes"][node["mesh"]]["primitives"]:
        assert primitive.get("mode", 4) == 4
        attrs = primitive["attributes"]
        points = [transform(p, node) for p in accessor(attrs["POSITION"])]
        indices = accessor(primitive["indices"])
        material = doc["materials"][primitive["material"]] if "material" in primitive else {}
        pbr = material.get("pbrMetallicRoughness", {})
        name = material.get("name", node["name"])
        color = [math.pow(v, 1 / 2.2) for v in pbr.get("baseColorFactor", (0.23, 0.23, 0.27))[:3]]
        part = {"name": name, "pos": encode(points, "f"), "idx": encode(indices, "I"),
                "n": len(indices), "color": color, "mode": 0}
        if name == "Пуфик":
            cushioned = set()
            for vertices, point in upholstery_components(points, [i[0] for i in indices]):
                rows[round(point[0], 2)].append(point)
                cushioned.update(vertices)
            data["parts"].extend(upholstery_mesh(part, points, indices, cushioned, seat) for seat in (True, False))
            continue
        if node["name"] == "Экран":
            low, high = [tuple(bound(axis) for axis in zip(*points)) for bound in (min, max)]
            data["screen"] = [(lo + hi) / 2 for lo, hi in zip(low, high)]
            part.update(mode=4, uv=encode(accessor(attrs["TEXCOORD_0"]), "f"))
        elif "emissiveTexture" in material:
            image_index = doc["textures"][material["emissiveTexture"]["index"]]["source"]
            key = str(image_index)
            if key not in data["images"]:
                data["images"][key] = image_url(image_index)
            part.update(mode=8 if node["name"] == "Plane.003" else 2,
                        tex=key, uv=encode(accessor(attrs["TEXCOORD_0"]), "f"))
        elif name == "Эвакуционный выход":
            part.update(mode=1, color=[0.45, 1, 0.30])
        elif name == "Потолок и сетны":
            part["color"] = [0.17, 0.18, 0.23]
        elif name == "пол это лава а это ступеньки":
            part["color"] = [0.19, 0.18, 0.24]
        elif node["name"] == "Plane.002":
            part.update(mode=1, color=[0.82, 0.85, 0.95])
        data["parts"].append(part)

assert len(rows) == data["rows"] and "screen" in data
ordered_rows = [sorted(points, key=lambda p: -p[2]) for _, points in sorted(rows.items())]
assert all(len(row) == 43 and row[21][2] > 0 > row[22][2] for row in ordered_rows)
# Low-level pools by the central aisle and the exits illuminate the nearby rows.
for row in ordered_rows[::2]:
    x, y, _ = row[21]
    aisle = (row[21][2] + row[22][2]) / 2
    data["lights"].extend([(x, y + 1.55, aisle), (x, y + 1.35, row[0][2] + 0.8),
                           (x, y + 1.35, row[-1][2] - 0.8)])
data["seats"] = encode((p for row in ordered_rows for p in row), "f")
output.write_text("var HALL = " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + ";", encoding="utf-8")
print(f"IMAX: {len(ordered_rows)} rows × {len(ordered_rows[0])} seats, {len(data['lights'])} light positions")
