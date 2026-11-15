#!/bin/bash
# Send a debug command to BromNav (debug build) and print its reply.
#   tools/dbg.sh state
#   tools/dbg.sh add "Vismarkt 10" 1201 02:30 [notbefore HH:MM|""] [online|cash|card]
#   tools/dbg.sh move 3 1      (stop 3 becomes first; switches to manual order)
#   tools/dbg.sh ocr receipts_test/x.jpg [add]   (OCR an image in the app's files dir)
#   tools/dbg.sh mode simple|delivery     navto "Vismarkt 10"   (simple nav)
#   tools/dbg.sh cancel 2 | restore 1 | undo
#   tools/dbg.sh vehicle 0|1|2   legal 1 [1]   road LAT LON [allow|both|forbid|reset]   camera LAT LON ZOOM
#   tools/dbg.sh auto | go | resetstats | sim | simstop | delivered | skip | end | clear | list | nolist
source "$(dirname "$0")/../env.sh"
F=/sdcard/Android/data/nl.bromnav/files/dbg_reply.txt
cmd=$1; shift
args=(--es cmd "$cmd")
if [ "$cmd" = move ]; then args+=(--es from "$1" --es to "$2"); fi
if [ "$cmd" = cancel ] || [ "$cmd" = restore ]; then args+=(--es n "$1"); fi
if [ "$cmd" = ocr ]; then args+=(--es path "$1"); [ "$2" = add ] && args+=(--es add 1); fi
if [ "$cmd" = translate ]; then args+=(--es text "'$1'"); fi
if [ "$cmd" = vehicle ]; then args+=(--es v "$1"); fi                       # 0 yellow, 1 blue, 2 e-bike
if [ "$cmd" = legal ]; then args+=(--es on "$1" --es edit "${2:-0}"); fi      # legal 1 [1=edit]
if [ "$cmd" = road ]; then args+=(--es lat "$1" --es lon "$2"); [ -n "$3" ] && args+=(--es set "$3"); fi  # road LAT LON [allow|both|forbid|reset]
if [ "$cmd" = camera ]; then args+=(--es lat "$1" --es lon "$2" --es z "$3"); fi
if [ "$cmd" = mode ]; then args+=(--es m "$1"); fi
if [ "$cmd" = search ]; then args+=(--es q "'$1'"); fi
if [ "$cmd" = navto ]; then args+=(--es q "'$1'"); fi
if [ "$cmd" = add ]; then
  args+=(--es q "'$1'"); [ -n "$2" ] && args+=(--es order "$2"); [ -n "$3" ] && args+=(--es due "$3")
  [ -n "$4" ] && args+=(--es notbefore "$4"); [ -n "$5" ] && args+=(--es pay "$5")
fi
adb shell rm -f $F
adb shell am broadcast -a nl.bromnav.DBG "${args[@]}" >/dev/null
for i in $(seq 1 160); do
  out=$(adb shell cat $F 2>/dev/null)
  [ -n "$out" ] && break; sleep 0.25
done
out=${out#"$cmd -> "}
if [[ "$out" == \{* ]]; then echo "$out" | python3 -m json.tool --no-ensure-ascii; else echo "${out:-no reply (is the app in the foreground?)}"; fi
