#!/usr/bin/env bash
# verify_ring.sh <pkg> <w>x<h> <dens> <outfile>
# Walks Settings until the focus ring lands on the "Check for updates" Material button and captures it.
# The stopping condition is what makes this reliable: target text on screen AND orange ring present.
cd /home/asternheim/velamaps/Vela
export VELA_PKG="$1"
source tests/dpad/lib.sh >/dev/null 2>&1
source tests/dpad/nav.sh >/dev/null 2>&1
GEO="$2"; DENS="$3"; OUT="$4"
adb shell wm size "$GEO" >/dev/null 2>&1; adb shell wm density "$DENS" >/dev/null 2>&1
adb shell settings put global vela_force_dpad 1 >/dev/null 2>&1
adb shell am force-stop "$1" >/dev/null 2>&1
adb shell am start -n "$1/app.vela.MainActivity" >/dev/null 2>&1
sleep 7
dismiss_onboarding >/dev/null 2>&1; sleep 2
open_settings >/dev/null 2>&1
for i in $(seq 1 200); do
  key "$K_DOWN" 0
  [ $((i % 5)) -ne 0 ] && continue
  on_screen "Check for updates" || continue
  adb exec-out screencap -p > "$OUT.tmp.png"
  r=$(python3 - "$OUT.tmp.png" <<'PY'
import sys
from PIL import Image
im=Image.open(sys.argv[1]).convert('RGB'); W,H=im.size
rows=[y for y in range(0,H,2)
      if sum(1 for x in range(0,W,3) if (lambda p: p[0]>210 and 80<p[1]<150 and p[2]<70)(im.getpixel((x,y))))>25]
print(f"{min(rows)}-{max(rows)}" if rows else "none")
PY
)
  if [ "$r" != "none" ]; then mv "$OUT.tmp.png" "$OUT"; echo "OK  $1 $GEO@$DENS  ring rows $r  (step $i)"; exit 0; fi
done
rm -f "$OUT.tmp.png"
echo "MISS $1 $GEO@$DENS - ring never landed on the button"
exit 1
