"""Convert the Blender export of Kadr hall 2 to a WebGL asset."""
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


def armrests(points, indices):
    parents = list(range(len(points)))

    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    seen = {}
    for i, p in enumerate(points):
        key = tuple(round(v, 4) for v in p)
        parents[find(i)] = find(seen.setdefault(key, i))
    for a, b, c in zip(indices[::3], indices[1::3], indices[2::3]):
        parents[find(b)] = find(a)
        parents[find(c)] = find(a)
    components = defaultdict(list)
    for i, point in enumerate(points):
        components[find(i)].append(point)
    rows = defaultdict(list)
    for component in components.values():
        low, high = [tuple(bound(axis) for axis in zip(*component)) for bound in (min, max)]
        dx, dy, dz = (b-a for a, b in zip(low, high))
        if 0.9 < dx < 1.1 and 0.45 < dy < 0.65 and 0.14 < dz < 0.22:
            rows[round(low[0], 1)].append((low[0], low[1], (low[2]+high[2])/2))
    seats = []
    for _, row in sorted(rows.items()):
        row.sort(key=lambda p: -p[2])
        assert len(row) == 12
        for left, right in zip(row[::2], row[1::2]):
            assert 0.75 < left[2]-right[2] < 0.9
            seats.append((left[0]+0.77, left[1], (left[2]+right[2])/2))
    assert len(seats) == 42
    return seats


data = {"model": "vip2", "rows": 7, "cols": 6, "aisles": [2, 4],
        "eyeBack": 0, "eyeUp": 1.06, "parts": [], "chair": [], "images": {}, "lights": []}
scene = doc["scenes"][doc.get("scene", 0)]
assert all("children" not in doc["nodes"][i] for i in scene["nodes"]), "Expected flat scene nodes"
for index in scene["nodes"]:
    node = doc["nodes"][index]
    if "KHR_lights_punctual" in node.get("extensions", {}):
        data["lights"].append(transform((0, 0, 0), node))
    if "mesh" not in node:
        continue
    for p in doc["meshes"][node["mesh"]]["primitives"]:
        assert p.get("mode", 4) == 4
        attributes = p["attributes"]
        points = [transform(v, node) for v in accessor(attributes["POSITION"])]
        indices = accessor(p["indices"])
        mat = doc["materials"][p["material"]]
        pbr = mat.get("pbrMetallicRoughness", {})
        name = mat["name"]
        part = {"name": name, "pos": encode(points, "f"), "idx": encode(indices, "I"), "n": len(indices),
                "color": [math.pow(v, 1/2.2) for v in pbr.get("baseColorFactor", (1, 1, 1))[:3]], "mode": 0}
        if name == "Пуфик":
            data["seats"] = encode(armrests(points, [i[0] for i in indices]), "f")
        if name == "Экран":
            bounds = [tuple(f(axis) for axis in zip(*points)) for f in (min, max)]
            low, high = bounds
            data["screen"] = [(a+b)/2 for a, b in zip(low, high)]
            uv = [((high[2]-z)/(high[2]-low[2]), ((high[1]-y)/(high[1]-low[1])-.5)*7/6+.5) for _, y, z in points]
            part.update(mode=7, uv=encode(uv, "f"))
        elif "baseColorTexture" in pbr:
            image_index = doc["textures"][pbr["baseColorTexture"]["index"]]["source"]
            im = doc["images"][image_index]
            key = str(image_index)
            if key not in data["images"]:
                view = doc["bufferViews"][im["bufferView"]]
                start = view.get("byteOffset", 0)
                data["images"][key] = "data:" + im["mimeType"] + ";base64," + base64.b64encode(binary[start:start+view["byteLength"]]).decode()
            part.update(mode=3, tex=key, uv=encode(accessor(attributes["TEXCOORD_0"]), "f"))
        elif "emissiveFactor" in mat:
            colors = {
                "Боковые лампы": (0.94, 0.94, 0.94),
                "Эвакуация": (0.92, 0.69, 0.26),
                "светильник": (1.0, 0.78, 0.37),
            }
            part.update(mode=1, color=colors.get(name, mat["emissiveFactor"]))
        data["parts"].append(part)
assert len(data["lights"]) == 21 and "screen" in data and "seats" in data
output.write_text("var HALL = " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
