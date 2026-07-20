#!/usr/bin/env bash
# ring_sweep.sh - drive each surface that owns ringed controls and walk it with ring_walk.sh.
#
# The visual half of the issue #79 focus-ring sweep: audit_static.sh proves a ring modifier exists
# in the source, this proves it RENDERS, on device, at the geometry under test. Run it per geometry
# and per flavor; every frame lands in <outdir> so the misses can be looked at rather than guessed at.
#
# usage: ring_sweep.sh <pkg> <WxH> <density> <outdir>
set -u
cd "$(dirname "$0")/../.." || exit 1
PKG="$1"; GEO="$2"; DENS="$3"; OUT="$4"
export VELA_PKG="$PKG"
. tests/dpad/lib.sh >/dev/null 2>&1
. tests/dpad/nav.sh >/dev/null 2>&1
mkdir -p "$OUT"
WALK=tests/dpad/ring_walk.sh

adb shell wm size "$GEO" >/dev/null 2>&1
adb shell wm density "$DENS" >/dev/null 2>&1
adb shell settings put global vela_force_dpad 1 >/dev/null 2>&1

fresh() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  adb shell am start -n "$PKG/app.vela.MainActivity" >/dev/null 2>&1
  sleep 8
  dismiss_onboarding >/dev/null 2>&1
  sleep 2
}

rc=0

# --- the map itself: search bar, layers, the FAB column, the recents strip
fresh
$WALK "$PKG" map "${MAP_STEPS:-14}" "$OUT" || rc=1

# --- search: the query field, the voice mic, recent rows and their clear button
fresh
open_search >/dev/null 2>&1 && sleep 2
$WALK "$PKG" search "${SEARCH_STEPS:-8}" "$OUT" || rc=1

# --- place page: save/share/close icons, the action pills, hours + reviews
fresh
if run_coffee >/dev/null 2>&1 && open_first_place >/dev/null 2>&1; then
  sleep 2
  key "$K_OK" 1   # expand so the action row is on screen
  $WALK "$PKG" place "${PLACE_STEPS:-16}" "$OUT" || rc=1
else
  echo "  SKIP place - could not reach it (network/search)"
fi

# --- directions panel: mode chips, leave-now/depart-at/arrive-by, the time + date pills
fresh
if reach_directions >/dev/null 2>&1; then
  sleep 2
  $WALK "$PKG" directions "${DIR_STEPS:-14}" "$OUT" || rc=1
  # the mode chips are a ROW: sweep it sideways too, where a missing dpadRowSibling shows up
  $WALK "$PKG" directions-row 5 "$OUT" 22 || rc=1
else
  echo "  SKIP directions - could not reach it (network/search)"
fi

# --- settings: already swept and enforced; walked here as a regression guard
fresh
open_settings >/dev/null 2>&1 && sleep 2
$WALK "$PKG" settings "${SET_STEPS:-20}" "$OUT" || rc=1

echo "=== ring_sweep $PKG $GEO@$DENS: $(ls "$OUT"/MISS-* 2>/dev/null | wc -l) miss frames, $(ls "$OUT"/ok-* 2>/dev/null | wc -l) ok frames"
exit $rc
