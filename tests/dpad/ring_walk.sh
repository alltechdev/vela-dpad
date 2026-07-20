#!/usr/bin/env bash
# ring_walk.sh - walk a surface with the D-pad and prove EVERY focus stop shows the orange ring.
#
# Issue #79: "some buttons don't get orange outline". A control that takes focus but draws no ring
# leaves the user with no idea where they are. The static auditor can only see that a ring modifier
# EXISTS in the source; only the pixels prove it renders, on the right control, at this size.
#
# The check deliberately does NOT read uiautomator's focused= flag: Compose focus dumps routinely
# miss the focused node across frames (docs/dpad.md), so a focus-based assertion produces phantom
# failures. Instead it asserts the invariant that actually matters to the user - at every step of
# the walk there is a ring SOMEWHERE on screen - and saves the frame whenever there is not, so the
# miss is triaged by eye rather than by a script's guess.
#
# usage: ring_walk.sh <pkg> <label> <steps> <outdir> [key]
#   key defaults to DOWN (20); pass 22 to sweep a row with RIGHT.
# The caller is expected to have already driven the app to the surface under test.
set -u
cd "$(dirname "$0")/../.." || exit 1
PKG="$1"; LABEL="$2"; STEPS="$3"; OUT="$4"; KEY="${5:-20}"
mkdir -p "$OUT"

ring_present() { # screenshot -> "x1,y1,x2,y2" of the orange ring, or "none"
  adb exec-out screencap -p > "$1" 2>/dev/null
  python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); W, H = im.size
# Match the ring's EXACT colour (0xFFFF6D00 = 255,109,0), not "orange-ish". Vela's own POI pins are
# (227,116,0) and a loose threshold counted them as a focus ring - which made a walk across the MAP
# report a clean pass while nothing was ringed at all. Tolerance stays tight enough to exclude the
# pins and wide enough to survive screenshot compression.
def ring(p): return p[0] >= 248 and 100 <= p[1] <= 120 and p[2] <= 14
pts = [(x, y) for y in range(0, H, 2) for x in range(0, W, 2) if ring(im.getpixel((x, y)))]
print("none" if len(pts) < 20 else
      f"{min(p[0] for p in pts)},{min(p[1] for p in pts)},{max(p[0] for p in pts)},{max(p[1] for p in pts)}")
PY
}

miss=0; seen=0; prev=""
for i in $(seq 1 "$STEPS"); do
  box=$(ring_present "$OUT/.f.png")
  seen=$((seen + 1))
  if [ "$box" = "none" ]; then
    miss=$((miss + 1))
    cp "$OUT/.f.png" "$OUT/MISS-$LABEL-$(printf %02d "$i").png"
    # Not every miss is a bug: a focus stop that is not a control has no ring by design (the MAP
    # itself is one - it shows the teal "OK: move the map" bubble). Save what is on screen next to
    # the frame so each miss is triaged from evidence.
    adb shell uiautomator dump /sdcard/rw.xml >/dev/null 2>&1
    adb shell cat /sdcard/rw.xml 2>/dev/null | grep -o 'text="[^"]*"' | grep -v 'text=""' \
      > "$OUT/MISS-$LABEL-$(printf %02d "$i").txt"
    echo "  MISS $LABEL step $i - no ring on screen"
  elif [ "$box" != "$prev" ]; then
    # A new ring position is a new focus stop: keep one frame per distinct stop as the evidence.
    cp "$OUT/.f.png" "$OUT/ok-$LABEL-$(printf %02d "$i").png"
  fi
  prev="$box"
  adb shell input keyevent "$KEY" >/dev/null 2>&1
  sleep 0.6
done
rm -f "$OUT/.f.png"
echo "$LABEL: $seen steps, $miss without a ring"
[ "$miss" -eq 0 ]
