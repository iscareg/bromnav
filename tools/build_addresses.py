#!/usr/bin/env python3
"""Offline address book for BromNav: every address with postcode in the delivery area, from
OpenStreetMap (the Dutch BAG address register is imported into OSM).
Output data/addresses.tsv.gz, one line per postcode + street:
    postcode <TAB> street <TAB> city <TAB> number:lat5:lon5;number:lat5:lon5;...
(lat5/lon5 = degrees * 1e5 as integers). Same format as MapData.downloadAddresses() on the phone."""
import json, gzip, sys, urllib.request, urllib.parse, collections, os, time

BBOX = (53.10, 6.40, 53.33, 6.82)      # Groningen municipality incl. Haren / Ten Boer, with margin
OUT = os.path.join(os.path.dirname(__file__), "..", "data", "addresses.tsv.gz")

q = '[out:json][timeout:600];nwr["addr:housenumber"]["addr:postcode"](%s,%s,%s,%s);out center tags;' % BBOX
CACHE = os.path.join(os.path.dirname(OUT), "addresses_raw.json")
def fetch():
    for attempt in range(6):
        for url in ("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"):
            try:
                req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(),
                                             headers={"User-Agent": "BromNav/0.5 (offline address book builder)"})
                return urllib.request.urlopen(req, timeout=900).read()
            except Exception as e:
                print("  %s: %s" % (url, e), file=sys.stderr)
        time.sleep(20 * (attempt + 1))
    sys.exit("Overpass unavailable")
if "--cached" in sys.argv and os.path.exists(CACHE):
    raw = open(CACHE, "rb").read()
else:
    raw = fetch(); open(CACHE, "wb").write(raw)
els = json.loads(raw)["elements"]
groups = collections.defaultdict(dict)
for e in els:
    t = e.get("tags", {})
    pc = t.get("addr:postcode", "").replace(" ", "").upper()
    street = t.get("addr:street", "").strip()
    if len(pc) != 6 or not street: continue
    lat = e.get("lat") or e.get("center", {}).get("lat"); lon = e.get("lon") or e.get("center", {}).get("lon")
    if lat is None: continue
    city = t.get("addr:city", "")
    for num in t["addr:housenumber"].replace(" ", "").split(";"):
        if num: groups[(pc, street, city)][num.upper()] = (round(lat * 1e5), round(lon * 1e5))
def numkey(n):
    d = ""
    for c in n:
        if not c.isdigit(): break
        d += c
    return (int(d) if d else 0, n)
n = 0
with gzip.open(OUT, "wt", encoding="utf-8") as f:
    for (pc, street, city) in sorted(groups):
        nums = groups[(pc, street, city)]
        f.write("%s\t%s\t%s\t%s\n" % (pc, street, city, ";".join("%s:%d:%d" % (k, *nums[k]) for k in sorted(nums, key=numkey))))
        n += len(nums)
print("%d addresses in %d postcode/street groups -> %s (%d kB)" % (n, len(groups), OUT, os.path.getsize(OUT) // 1024))
