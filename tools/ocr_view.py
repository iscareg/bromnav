#!/usr/bin/env python3
"""OCR debug viewer: runs the phone's OCR on a receipt photo (via the debug bridge), draws every
recognised line on the (rotated) photo and colour-codes what the parser used, then opens it.

usage: tools/ocr_view.py <photo.jpg> [<photo2.jpg> ...]     (photos on this PC)
Output PNGs go to /tmp/claude-ocr-view/ (not into the repo: receipts contain customer data)."""
import json, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
REMOTE = "/sdcard/Android/data/nl.bromnav/files/receipts_test"
OUT = "/tmp/claude-ocr-view"

def ocr(path):
    name = os.path.basename(path)
    subprocess.run([ADB, "shell", "mkdir", "-p", REMOTE], check=True)
    subprocess.run([ADB, "push", path, f"{REMOTE}/{name}"], check=True, capture_output=True)
    out = subprocess.run([os.path.join(HERE, "dbg.sh"), "ocr", f"receipts_test/{name}"], capture_output=True, text=True).stdout
    subprocess.run([ADB, "shell", "rm", f"{REMOTE}/{name}"])
    return json.loads(out)

def role(text, d):
    t = text.replace(" ", "").upper()
    if d.get("postcode") and d["postcode"] in t: return "postcode", (0, 160, 0)
    if d.get("street") and d["street"].replace(" ", "").upper() in t: return "street", (0, 90, 255)
    if d.get("deliveryTime") and d["deliveryTime"] in text: return "time", (230, 120, 0)
    if d.get("orderNo") and d["orderNo"].replace("-", "") in t.replace("-", ""): return "order", (170, 0, 200)
    if d.get("note") and text.strip() and text.strip() in d["note"]: return "note", (200, 0, 100)
    return None, (220, 0, 0)

def render(path, d):
    img = Image.open(path).convert("RGB").rotate(-d.get("rotation", 0), expand=True)
    dr = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=18)
    for raw in d.get("raw", []):
        box, text = raw.split(" ", 1)
        l, t, r, b = map(int, box.split(","))
        tag, col = role(text, d)
        dr.rectangle([l, t, r, b], outline=col, width=3)
        if tag: dr.text((l, max(0, t - 20)), tag, fill=col, font=font)
    summary = "PARSED" if d.get("parsed") else "NOT PARSED"
    if d.get("parsed"):
        summary += f"  {d.get('street')} {d.get('houseNo')}  {d.get('postcode')}  #{d.get('orderNo')}  {d.get('deliveryTime')}  BAG: {d.get('bag')}"
    bar = Image.new("RGB", (img.width, 34), (20, 20, 20))
    ImageDraw.Draw(bar).text((8, 7), f"rot {d.get('rotation')}°  {d.get('ms')} ms  {summary}", fill=(255, 255, 255), font=font)
    out = Image.new("RGB", (img.width, img.height + 34)); out.paste(bar, (0, 0)); out.paste(img, (0, 34))
    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, os.path.splitext(os.path.basename(path))[0] + "_ocr.png")
    out.save(dst)
    return dst

if __name__ == "__main__":
    files = sys.argv[1:] or sys.exit(__doc__)
    outs = []
    for f in files:
        d = ocr(f)
        dst = render(f, d)
        outs.append(dst)
        print(dst, "|", "PARSED" if d.get("parsed") else "NOT PARSED",
              {k: d.get(k) for k in ("rotation", "street", "houseNo", "postcode", "orderNo", "deliveryTime", "asap", "note", "bag")})
    if os.environ.get("NO_OPEN") != "1":
        for o in outs: subprocess.Popen(["xdg-open", o], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
