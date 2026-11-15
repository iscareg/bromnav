#!/bin/bash
# Refresh routing data (BRouter rebuilds weekly) and map tiles (Protomaps daily build).
set -e
cd "$(dirname "$0")/../data"
curl -fL -o segments/E5_N50.rd5.new https://brouter.de/brouter/segments4/E5_N50.rd5 && mv segments/E5_N50.rd5.new segments/E5_N50.rd5
B=$(curl -s https://build-metadata.protomaps.dev/builds.json | python3 -c "import sys,json; print(json.load(sys.stdin)[-1]['key'])")
../tools/vendor/pmtiles extract "https://build.protomaps.com/$B" tiles/groningen.pmtiles.new --bbox=6.15,52.95,7.25,53.55 --maxzoom=15
mv tiles/groningen.pmtiles.new tiles/groningen.pmtiles
echo "updated; now run tools/push_data.sh"
