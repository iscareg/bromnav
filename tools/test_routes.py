#!/usr/bin/env python3
"""Legality regression test for bromfiets.brf.
Routes random + listed trips through a local BRouter server and checks every segment with an
independent rule set (Dutch bromfiets law as mapped in OSM). Exit code 1 on any violation."""
import json, random, sys, urllib.request, urllib.parse

SERVER = "http://localhost:17777/brouter"
# Groningen city bounding box
LON = (6.50, 6.63); LAT = (53.190, 53.250)
# Hand-made cases: (name, "lon,lat|lon,lat"). Add trips where Google got it wrong here.
CASES = [
    ("west->oost", "6.5400,53.2140|6.5850,53.2250"),
    ("helpman->oost", "6.5530,53.2040|6.5850,53.2250"),
]
PATHLIKE = {"cycleway", "footway", "path", "pedestrian", "steps", "bridleway", "busway", "platform", "corridor"}

def violation(t):
    hw = t.get("highway", ""); mo = t.get("moped", "")
    if hw in ("motorway", "motorway_link"): return "motorway"
    if t.get("motorroad") == "yes": return "motorroad (G3)"
    if mo in ("no", "use_sidepath"): return f"moped={mo}"
    if hw in PATHLIKE and mo not in ("yes", "designated"): return f"{hw} without moped access"
    if t.get("service") == "bus" and mo not in ("yes", "designated"): return "bus-only"
    return None

def route(lonlats):
    q = urllib.parse.urlencode({"lonlats": lonlats, "profile": "bromfiets", "alternativeidx": 0, "format": "geojson"})
    raw = urllib.request.urlopen(f"{SERVER}?{q}", timeout=60).read().decode()
    if not raw.startswith("{"): return None, raw.strip()[:120]
    return json.loads(raw)["features"][0]["properties"], None

def check(name, lonlats):
    p, err = route(lonlats)
    if p is None: return ("skip", f"{name}: {err}")
    h = p["messages"][0]; iw = h.index("WayTags"); lon_i = h.index("Longitude"); lat_i = h.index("Latitude")
    bad = []
    for r in p["messages"][1:]:
        t = dict(x.split("=", 1) for x in r[iw].split() if "=" in x)
        v = violation(t)
        if v: bad.append(f"{v} at {int(r[lat_i])/1e6:.5f},{int(r[lon_i])/1e6:.5f} [{r[iw]}]")
    km = int(p["track-length"]) / 1000; mins = int(p["total-time"]) / 60
    return ("fail" if bad else "ok", f"{name}: {km:.1f} km {mins:.1f} min" + ("".join("\n    " + b for b in bad)))

def main():
    random.seed(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    cases = list(CASES)
    for i in range(n):
        a = f"{random.uniform(*LON):.5f},{random.uniform(*LAT):.5f}"; b = f"{random.uniform(*LON):.5f},{random.uniform(*LAT):.5f}"
        cases.append((f"rand{i}", f"{a}|{b}"))
    res = {"ok": 0, "fail": 0, "skip": 0}
    for name, ll in cases:
        st, msg = check(name, ll); res[st] += 1
        if st != "ok": print(st.upper(), msg)
    print(res)
    sys.exit(1 if res["fail"] else 0)

main()
