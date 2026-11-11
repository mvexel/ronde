"""Derives Ronde's muted light/dark route-map styles (mobile/src/main/assets/map) from OpenFreeMap Positron.

Usage: curl -A Ronde -o positron.json https://tiles.openfreemap.org/styles/positron
       python3 route_map_style.py positron.json ../src/main/assets/map
Colours are the design.md B m_* tokens."""
import json
import sys

src, outdir = sys.argv[1], sys.argv[2]

PALETTES = {
    "light": dict(land="#EEEBE4", minor="#FFFFFF", major="#FFFFFF", casing="#E2DED5", park="#DDE4D5",
                  water="#C8D6DF", bld="#E5E1D8", rail="#DAD6CD", label="#6B6F78", halo="#EEEBE4", boundary="#B9B5AC"),
    "dark": dict(land="#141518", minor="#1F2125", major="#2D3035", casing="#18191C", park="#121A15",
                 water="#0B1820", bld="#191A1E", rail="#24262A", label="#8E939B", halo="#141518", boundary="#3A3D42"),
}

DROP = {"highway-shield-non-us", "highway-shield-us-interstate", "road_shield_us", "airport",
        "landcover_ice_shelf", "landcover_glacier", "label_country_1", "label_country_2", "label_country_3", "label_state"}


def paint_for(layer, p):
    lid, t = layer["id"], layer["type"]
    paint = dict(layer.get("paint", {}))
    if t == "background":
        paint["background-color"] = p["land"]
    elif lid == "park" or lid.startswith("landcover_wood"):
        paint["fill-color"] = p["park"]
    elif lid == "water":
        paint["fill-color"] = p["water"]
    elif lid == "waterway":
        paint["line-color"] = p["water"]
    elif lid == "landuse_residential":
        paint["fill-color"] = p["land"]
    elif lid == "building":
        paint["fill-color"] = p["bld"]
        paint["fill-outline-color"] = p["bld"]
    elif lid.startswith("boundary"):
        paint["line-color"] = p["boundary"]
    elif lid.startswith("railway"):
        paint["line-color"] = p["rail"] if "dashline" not in lid else p["land"]
    elif t == "line" and "casing" in lid:
        paint["line-color"] = p["casing"]
    elif t == "line" and ("major" in lid or "motorway" in lid or "runway" in lid):
        paint["line-color"] = p["major"]
    elif t == "line":
        paint["line-color"] = p["minor"]
    elif t == "fill" and ("aeroway" in lid or "pier" in lid):
        paint["fill-color"] = p["minor"]
    elif t == "symbol":
        paint["text-color"] = p["label"]
        paint["text-halo-color"] = p["halo"]
        paint["text-halo-width"] = 1
        paint.pop("text-halo-blur", None)
    return paint


base = json.load(open(src))
for name, p in PALETTES.items():
    style = dict(base)
    style["name"] = f"Ronde route {name} (from OpenFreeMap Positron)"
    style["sources"] = {"openmaptiles": dict(base["sources"]["openmaptiles"],
                                             attribution="© OpenMapTiles © OpenStreetMap contributors")}
    layers = []
    for layer in base["layers"]:
        if layer["id"] in DROP or layer.get("source") == "ne2_shaded":
            continue
        layer = dict(layer)
        layer["paint"] = paint_for(layer, p)
        layers.append(layer)
    style["layers"] = layers
    with open(f"{outdir}/ronde_route_{name}.json", "w") as f:
        json.dump(style, f, ensure_ascii=False, separators=(",", ":"))
    print(name, len(layers))
