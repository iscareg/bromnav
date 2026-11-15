#!/bin/bash
# Copies offline routing data + map tiles to the phone (app must have been started once).
set -e
source "$(dirname "$0")/../env.sh"
D=/sdcard/Android/data/nl.bromnav/files
ROOT="$(dirname "$0")/../data"
adb shell mkdir -p $D/segments $D/tiles
adb push "$ROOT"/segments/*.rd5 $D/segments/
adb push "$ROOT"/tiles/groningen.pmtiles $D/tiles/
adb shell ls -la $D/segments $D/tiles
