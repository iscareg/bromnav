#!/bin/bash
# usage: route.sh "lon,lat|lon,lat[|...]" [profile]   -> prints summary + per-segment way tags
curl -s "http://localhost:17777/brouter?lonlats=$1&profile=${2:-bromfiets}&alternativeidx=0&format=geojson" | python3 -c '
import sys,json
raw=sys.stdin.read()
try: d=json.loads(raw)
except Exception: print("ERROR:",raw[:400]); sys.exit(1)
p=d["features"][0]["properties"]; m=p["messages"]; h=m[0]
print("length m:",p["track-length"]," time s:",p["total-time"], " (%.1f min)"%(int(p["total-time"])/60))
iw=h.index("WayTags"); idist=h.index("Distance")
prev=None
for r in m[1:]:
    tags=dict(t.split("=",1) for t in r[iw].split() if "=" in t)
    key=(tags.get("highway"),tags.get("moped"),tags.get("maxspeed"),tags.get("name",""))
    print("%6s m  %s"%(r[idist]," ".join(f"{k}={v}" for k,v in tags.items() if k in ("highway","moped","mofa","maxspeed","motor_vehicle","bicycle","cycleway","oneway","access","motorroad"))))
'
