"""Derives Ronde's muted light/dark route-map styles (mobile/src/main/assets/map) from OpenFreeMap Positron.

Usage: curl -A Ronde -o positron.json https://tiles.openfreemap.org/styles/positron
       python3 route_map_style.py positron.json ../src/main/assets/map
Colours are the design.md B m_* tokens (land/minor/park/water also in ui/RondeColors.kt).
Contrast targets (WCAG relative luminance), dark: minor road/land >= 1.6, major road/land >= 2.2, route fill/land >= 3;
light keeps Positron's paper look and relies on the mandatory near-black route casing.
The thumbnail frames a run at about z14-15, so street names start at z14 (Positron: z15) and labels are ink2-strength."""
import json
import sys

src, outdir = sys.argv[1], sys.argv[2]

PALETTES = {
    "light": dict(land="#E9E5DB", minor="#FFFFFF", major="#FFFFFF", casing="#CFC9BD", park="#CFDEC2",
                  water="#AFC8D8", bld="#DDD8CC", rail="#C9C4B8", label="#4A4E57", halo="#F4F2EC", boundary="#ABA79E"),
    "dark": dict(land="#16171B", minor="#383C43", major="#50555E", casing="#101114", park="#1C3324",
                 water="#12304A", bld="#1F2125", rail="#3A3D42", label="#C4C8CF", halo="#16171B", boundary="#4A4E57"),
}

# Positron's minzoom for these labels, lowered so a run-sized map shows its street names.
LABEL_MINZOOM = {"highway-name-minor": 14, "highway-name-path": 15}

ATTRIBUTION = ('<a href="https://openfreemap.org" target="_blank">OpenFreeMap</a> '
               '<a href="https://www.openmaptiles.org/" target="_blank">&copy; OpenMapTiles</a> '
               '<a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap contributors</a>')

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
        paint["text-halo-width"] = 1.5
        paint.pop("text-halo-blur", None)
    return paint


base = json.load(open(src))
for name, p in PALETTES.items():
    style = dict(base)
    style["name"] = f"Ronde route {name} (from OpenFreeMap Positron)"
    style["sources"] = {"openmaptiles": dict(base["sources"]["openmaptiles"],
                                             attribution=ATTRIBUTION)}
    layers = []
    for layer in base["layers"]:
        if layer["id"] in DROP or layer.get("source") == "ne2_shaded":
            continue
        layer = dict(layer)
        layer["paint"] = paint_for(layer, p)
        if layer["id"] in LABEL_MINZOOM:
            layer["minzoom"] = LABEL_MINZOOM[layer["id"]]
        layers.append(layer)
    style["layers"] = layers
    with open(f"{outdir}/ronde_route_{name}.json", "w") as f:
        json.dump(style, f, ensure_ascii=False, separators=(",", ":"))
    print(name, len(layers))
