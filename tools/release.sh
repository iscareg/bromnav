#!/bin/bash
# Build a signed release APK (with offline maps inside) and publish it as a GitHub release.
#   tools/release.sh            -> uses the version from app/build.gradle.kts
#   tools/release.sh --fresh    -> first refresh routing data + map (tools/update_data.sh)
# Needs: gh (logged in), the signing key in ~/.bromnav-release/, data in data/.
set -euo pipefail
cd "$(dirname "$0")/.."
source env.sh
GH=${GH:-$(command -v gh || echo ~/opt/gh/bin/gh)}

[ "${1:-}" = "--fresh" ] && tools/update_data.sh
[ -n "$(git status --porcelain)" ] && { echo "Commit your changes first."; exit 1; }
ls data/segments/*.rd5 data/tiles/groningen.pmtiles >/dev/null || { echo "Run tools/update_data.sh first"; exit 1; }
[ -f ~/.bromnav-release/signing.properties ] || { echo "Missing ~/.bromnav-release/signing.properties"; exit 1; }

VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
TAG="v$VER"
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Tag $TAG exists: bump versionCode/versionName first."; exit 1; }

./gradlew -q testDebugUnitTest assembleRelease
mkdir -p build/release
APK="build/release/BromNav-$VER.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

PREV=$(git describe --tags --abbrev=0 2>/dev/null || true)
NOTES=$(mktemp)
{
  echo "Offline bromfiets delivery navigator — routing data and map of the Groningen area included."
  echo
  echo "Install: download the APK on the phone and open it (allow installing from this source)."
  echo "Map data date: $(date -r data/segments/$(ls data/segments | head -1) +%Y-%m-%d). Update later in the app: ☰ → Update maps from internet."
  echo
  echo "Changes:"
  git log --pretty='- %s' ${PREV:+$PREV..}HEAD
} > "$NOTES"

git tag -a "$TAG" -m "BromNav $VER"
git push -q origin "$TAG"
"$GH" release create "$TAG" "$APK" --title "BromNav $VER" --notes-file "$NOTES"
rm -f "$NOTES"
