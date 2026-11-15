#!/usr/bin/env python3
"""Builds data/pois.tsv.gz: named places (shops, restaurants, landmarks, ...) of the map area from
OpenStreetMap via the Overpass API. Same query and format as MapData.downloadPois() in the app.
Data © OpenStreetMap contributors (ODbL)."""
import gzip, json, os, sys, urllib.parse, urllib.request

BBOX = (52.95, 6.15, 53.55, 7.25)   # south, west, north, east (keep in sync with MapData.kt)
KEYS = ["amenity", "shop", "tourism", "leisure", "office", "craft", "historic", "healthcare"]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "pois.tsv.gz")

def query():
    b = "%s,%s,%s,%s" % BBOX
    parts = [f"nwr[name][{k}]({b});" for k in KEYS]
    parts += [f'nwr[name][railway=station]({b});', f'nwr[name][place~"^(neighbourhood|suburb|quarter|village|hamlet|square|town|city)$"]({b});']
    return "[out:json][timeout:180];(" + "".join(parts) + ");out center tags;"

def main():
    data = urllib.parse.urlencode({"data": query()}).encode()
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", data=data, headers={"User-Agent": "BromNav"})
    els = json.load(urllib.request.urlopen(req, timeout=300))["elements"]
    rows = []
    for e in els:
        t = e.get("tags", {})
        lat = e.get("lat") or e.get("center", {}).get("lat"); lon = e.get("lon") or e.get("center", {}).get("lon")
        if lat is None or not t.get("name"): continue
        key = next((k for k in KEYS + ["railway", "place"] if k in t), "")
        alt = ";".join(t[k] for k in ("alt_name", "old_name", "short_name", "official_name", "brand") if k in t)
        f = [t["name"], key, t.get(key, ""), t.get("cuisine", ""), alt, t.get("addr:street", ""), t.get("addr:housenumber", ""),
             t.get("addr:postcode", ""), t.get("addr:city", ""), "%.6f" % lat, "%.6f" % lon]
        rows.append("\t".join(x.replace("\t", " ").replace("\n", " ") for x in f))
    with gzip.open(OUT, "wt", encoding="utf-8") as g:
        g.write("\n".join(rows))
    print(f"{len(rows)} places -> {OUT} ({os.path.getsize(OUT) // 1024} kB)")

if __name__ == "__main__":
    main()
