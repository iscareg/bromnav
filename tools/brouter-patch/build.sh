#!/bin/bash
# Rebuilds app/libs/brouter-1.7.10-ro.jar = the BRouter 1.7.10 release jar + the BromNav patch
# (per-link access overrides, see src/btools/mapaccess/WayOverride.java).
set -e
cd "$(dirname "$0")"
source ../../env.sh 2>/dev/null || true
ORIG=../vendor/brouter/brouter-1.7.10/brouter-1.7.10-ro.jar
OUT=../../app/libs/brouter-1.7.10-ro.jar
rm -rf build && mkdir -p build/classes
javac --release 11 -nowarn -cp "$ORIG" -d build/classes $(find src -name '*.java')
cp "$ORIG" build/patched.jar
(cd build/classes && jar uf ../patched.jar $(find . -name '*.class'))
cp build/patched.jar "$OUT"
rm -rf build
echo "patched jar -> $OUT"
