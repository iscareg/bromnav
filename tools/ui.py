#!/usr/bin/env python3
"""Tiny adb UI driver for testing BromNav on the phone.
usage: ui.py tap "<regex on text>" | type "<text>" | back | shot <file> | texts"""
import os, re, subprocess, sys, time
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")

def sh(*a): return subprocess.run([ADB, *a], capture_output=True, text=True).stdout

def nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = sh("shell", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        yield m.group(1), tuple(map(int, m.groups()[1:]))

def tap(rx, tries=6):
    for _ in range(tries):
        for t, (a, b, c, d) in nodes():
            if re.search(rx, t):
                sh("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2)); time.sleep(1.0); return t
        time.sleep(1.0)
    sys.exit(f"not found: {rx}")

cmd = sys.argv[1]
if cmd == "tap": print("tapped:", tap(sys.argv[2]))
elif cmd == "type": sh("shell", "input", "text", sys.argv[2].replace(" ", "%s")); time.sleep(0.5)
elif cmd == "back": sh("shell", "input", "keyevent", "111"); time.sleep(0.5)
elif cmd == "shot": open(sys.argv[2], "wb").write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)
elif cmd == "texts": [print(t, b) for t, b in nodes() if t]
