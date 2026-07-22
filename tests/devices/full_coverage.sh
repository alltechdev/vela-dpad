#!/usr/bin/env bash
# tests/devices/full_coverage.sh - the FULL-COVERAGE gate for calling a device "fully supported".
#
# A device is FULLY SUPPORTED only when EVERY screen is reachable, D-pad-navigable, and clip-free at
# that device's screen size. This drives every surface at the device's simulated geometry, captures a
# labeled screenshot of each, and prints a coverage checklist (COVERED / MISSED). "Fully supported"
# requires ALL rows COVERED - a MISSED row (including network-blocked search/place/nav/transit) means
# NOT fully supported until it's re-run with that surface reachable. See AGENTS.md
# "Fully supported (HARD REQUIREMENT)".
#
#   bash tests/devices/full_coverage.sh <device-id>     # e.g. kyocera-e4810
#   bash tests/devices/full_coverage.sh all
#
# Iterating on ONE slow/flaky phase? Re-capture just it (reuses the other frames, same 01..16 names):
#   PHASES=settings bash tests/devices/full_coverage.sh sonim-x320
#   PHASES="search place" bash tests/devices/full_coverage.sh kyocera-e4810
# Sweep the OTHER axes of the support matrix (#76 declutter is gated on the soft-key bar / density):
#   SOFTKEYS=off bash ... sonim-x320   # the TOUCH layout (search bar/gear/Park FAB on screen) -> full-touch/
#   DENS=225     bash ... sonim-x320   # the reported 480x854 @ 225 dpi variant -> full-dens225/
# (Both write to their own dir so they never clobber the default soft-key/@320 committed goldens.)
# Phases: firstrun map search place directions settings voice parking. A partial run reports
# PARTIAL, not a verdict - only a full run (no PHASES) can call a device FULLY COVERED.
# RULE: every NEW feature adds its surfaces as its OWN phase here (own numbered frames), so
# verifying it on a device never needs the full tour.
#
# Content surfaces (search/place/directions/nav/transit) need live search+routing; run with network up.
DEVICES="
kyocera-e4810|240x320|160
kyocera-duraxv|240x320|160
sonim-x320|480x854|320
sonim-x320-225|480x854|225
kyocera-duraxe-e4830|240x320|120
sonim-xp3|240x320|160
tcl-flip-2|240x320|160
"   # every committed target is its own row - each profile gets its own full-coverage record
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../dpad/lib.sh"; source "$HERE/../dpad/nav.sh"
$ADB get-state >/dev/null 2>&1 || { echo "No device."; exit 2; }
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

cover_one() {
  local id="$1" geom dens out i=0 covered=0 missed=0 checklist=""
  geom="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $2}')"
  local defdens; defdens="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $3}')"
  # DENS overrides the device's default density: the sonim-x320 panel was reported at 480x854 @ 225 dpi
  # as well as @320 (a real user's AdaptiveDensity setup), so the same pixels get swept at both.
  dens="${DENS:-$defdens}"
  [ -z "$geom" ] && { echo "unknown device '$id'"; return 1; }
  local SW="${geom%%x*}" SH="${geom##*x}"
  # PHASES = which surface groups to (re)capture, so a single slow/flaky phase can be re-run WITHOUT
  # redoing the whole ~13 min tour (e.g. PHASES=settings to iterate just the deep Settings sub-sections).
  # Each phase pins its own screenshot NUMBER base, so a subset writes the SAME 01..16 filenames a full
  # run would - a partial run overwrites only its slice and leaves the rest in place. Default = all.
  local ALLPHASES="firstrun map search place streetview directions settings voice parking softkey"
  local phases="${PHASES:-$ALLPHASES}" full=0; [ "$phases" = "$ALLPHASES" ] && full=1
  phase() { case " $phases " in *" $1 "*) return 0;; *) return 1;; esac; }
  # Flavor-aware output: a run against the RESTRICTED flavor writes to its own directory so it can
  # never clobber the committed standard-flavor frames (a restricted full run's rm -f once wiped
  # them - device-seen). The standard set stays the support-verdict record; the restricted set is
  # the restricted-flavor record, kept side by side.
  case "${VELA_PKG:-}" in
    *restricted*) out="$HERE/$id/screenshots/full-restricted" ;;
    *)            out="$HERE/$id/screenshots/full" ;;
  esac
  # SOFTKEYS=off (default on) tests the TOUCH layout instead - search bar / gear / Park FAB ON screen,
  # NOT decluttered - to prove the #76 declutter is correctly gated OFF for touch phones. It forces
  # vela_force_dpad 0 and writes to a SEPARATE "-touch" dir so it never clobbers the committed keypad
  # (soft-key) goldens; the softkey phase is skipped there (there is no bar to press).
  local softkeys="${SOFTKEYS:-on}"
  [ "$softkeys" = "off" ] && out="${out}-touch"
  # A non-default density (e.g. DENS=225 on the sonim) routes to its own "-dens<N>" dir so the density
  # sweep never clobbers the default-density committed goldens.
  [ -n "${DENS:-}" ] && [ "$DENS" != "$defdens" ] && out="${out}-dens${DENS}"
  mkdir -p "$out"; [ "$full" = 1 ] && rm -f "$out"/*.png
  echo "################ FULL COVERAGE: $id ($geom @ ${dens}dpi, softkeys=$softkeys) ################"
  $ADB shell wm size "$geom" >/dev/null 2>&1; $ADB shell wm density "$dens" >/dev/null 2>&1
  bash "$HERE/../dpad/setup.sh" >/dev/null 2>&1   # perms + mock GPS for search/routing
  # setup.sh force-sets vela_force_dpad 1 (D-pad-first, the suite's default). Apply the SOFTKEYS choice
  # AFTER it so SOFTKEYS=off actually reaches the touch layout (dpad 0) instead of being clobbered back
  # to 1 - the reason an early "off" run still rendered the decluttered soft-key bar.
  if [ "$softkeys" = "off" ]; then $ADB shell settings put global vela_force_dpad 0 >/dev/null 2>&1
  else $ADB shell settings put global vela_force_dpad 1 >/dev/null 2>&1; fi
  warm_up

  shot() { i=$((i+1)); local n; n="$(printf '%02d' "$i")"; $ADB exec-out screencap -p > "$out/$n-$1.png"; }
  # mark <label> <reached 0|1> - capture + record COVERED/MISSED on the checklist.
  mark() { local label="$1" reached="$2"
    if [ "$reached" = 1 ]; then shot "$label"; covered=$((covered+1)); checklist="$checklist\n  COVERED  $label"
    else missed=$((missed+1)); checklist="$checklist\n  MISSED   $label"; fi
  }
  # na <label> <why> - the surface DOES NOT EXIST in this build, so it is neither covered nor a gap.
  # Without this, a flavor that legitimately drops a feature can never score FULLY COVERED: the
  # restricted build has no Street View at all (its goldens have never contained a streetview frame),
  # yet both restricted legs were reported "NOT fully covered - streetview MISSED". That is the
  # harness scoring a build against a feature it does not ship.
  na() { checklist="$checklist\n  n/a      $1  ($2)"; }

  # --- first-run (fresh install) -------------------------------------------------------------
  if phase firstrun; then i=0
  $ADB shell pm clear "$PKG" >/dev/null 2>&1
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do $ADB shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1; done
  launch_fresh 6
  on_screen "Get started" && mark "welcome" 1 || mark "welcome" 0
  # The first-run voice offer's dismiss is "Use system voice" (the honest two-way offer, upstream
  # 605ade01), not "Not now" - accept either so this dialog is captured, not skipped.
  key "$K_OK" 2; { on_screen "Not now" || on_screen "Use system voice"; } && mark "voice-dialog" 1 || mark "voice-dialog" 0
  key "$K_OK" 2; on_screen "Not now" && mark "offline-dialog" 1 || mark "offline-dialog" 0
  key "$K_OK" 2
  launch_fresh 6
  on_screen_contains "Help improve Vela" && { mark "consent-dialog" 1; key "$K_OK" 2; } || mark "consent-dialog" 0
  fi

  # --- bare map -------------------------------------------------------------------------------
  if phase map; then i=4
  # DOWN lands the focus ring on a chip for the D-pad frame; in the touch (SOFTKEYS=off) layout DOWN
  # would focus the on-screen search bar and auto-EXPAND it into the overlay, so skip it there and
  # capture the plain collapsed bare map (search bar + chips + Layers + Park FAB all on screen).
  goto_map; softkeys_shown && key "$K_DOWN"; mark "bare-map" 1
  fi

  # --- search overlay + results ---------------------------------------------------------------
  local haveResults=0
  if phase search; then i=5
  # open_search opens the overlay the layout-correct way: RIGHT soft key under soft-keys (the bar is
  # decluttered, #76), else DOWN onto the on-screen search bar + OK - so touch runs still pass too.
  goto_map; open_search; on_screen "Home" || on_screen_contains "Search"; mark "search-overlay" 1; key "$K_BACK" 1
  goto_map; if run_coffee; then haveResults=1; mark "search-results" 1; else mark "search-results" 0; fi
  # Search-as-you-type against your OWN history (upstream 40873d96): the coffee search above left
  # "coffee" in recents, so typing a prefix now surfaces it as a local (History-icon) match ABOVE the
  # network suggestions - instant, offline, no fetch. Best-effort: only assert when the recent exists.
  if [ "$haveResults" = 1 ]; then
    goto_map; open_search; $ADB shell input text "cof" >/dev/null 2>&1; sleep 1.5
    if on_screen "coffee"; then mark "search-history-astype" 1; else mark "search-history-astype" 0; fi
    key "$K_BACK" 1
  else mark "search-history-astype" 0; fi
  fi

  # --- place sheet (+ expand) -----------------------------------------------------------------
  if phase place; then i=7
  # standalone (search phase not run this pass): get a result set first
  [ "$haveResults" = 0 ] && { goto_map; run_coffee && haveResults=1; }
  if [ "$haveResults" = 1 ]; then
    open_first_place; mark "place-sheet" 1
    key "$K_OK" 1; mark "place-sheet-expanded" 1
    key "$K_BACK" 1
  else mark "place-sheet" 0; mark "place-sheet-expanded" 0; fi
  fi

  # --- in-app Street View (keyless GL panorama) -----------------------------------------------
  # Drop a pin (clean 2-pill sheet so Street View is on-screen without scrolling the pill row),
  # tap it, wait for the tile stitch + GL render, then D-pad look-around (OK engages, RIGHT pans).
  # NEEDS network (tile CDN) + a mock fix over a covered area; MISSED if the pill/render doesn't show.
  if phase streetview; then i=20
  case "${VELA_PKG:-}" in
    *restricted*)
      # The restricted flavor ships no Street View (RESTRICTED_BUILD drops the pill and the screen),
      # so there is nothing to reach - not a coverage gap. Skip, do not fail.
      na "streetview" "not in the restricted build"
      na "streetview-dpad" "not in the restricted build"
      ;;
    *)
  goto_map
  # long-press the map centre -> a reverse-geocoded pin sheet with Directions + Street View
  $ADB shell input swipe "$((SW/2))" "$((SH/3))" "$((SW/2))" "$((SH/3))" 800 >/dev/null 2>&1; sleep 3
  # Under soft-keys the pin sheet's Street View pill is dropped into its Options menu (LEFT soft key),
  # like the rest of the place-sheet button row; on touch it stays an on-screen pill. Open Options first
  # in the soft-key layout so the row is reachable either way.
  softkeys_shown && key "$K_SOFT_LEFT" 1
  if tap_center "Street View"; then
    sleep 9  # nearest-pano metadata + tile fetch + stitch
    mark "streetview" 1
    key "$K_OK"; for _ in 1 2 3 4 5 6; do key "$K_RIGHT"; done; sleep 1; mark "streetview-dpad" 1
    key "$K_BACK" 1; key "$K_BACK" 1  # disengage, then close
  else mark "streetview" 0; mark "streetview-dpad" 0; fi
      ;;
  esac
  fi

  # --- directions + steps ---------------------------------------------------------------------
  if phase directions; then i=9
  goto_map
  if reach_directions; then
    mark "directions" 1
    # steps sheet: from the Drive tab, DOWN to Steps and OK (best-effort)
    if focus_and_ok "Steps"; then mark "route-steps" 1; key "$K_BACK" 1; else mark "route-steps" 0; fi
    key "$K_BACK" 1
  else mark "directions" 0; mark "route-steps" 0; fi
  fi

  # --- Settings + sub-screens -----------------------------------------------------------------
  if phase settings; then i=11
  if open_settings; then
    mark "settings-top" 1
    # walk down a bit to capture the mid + lower sections
    for _ in 1 2 3 4 5 6; do key "$K_DOWN"; key "$K_DOWN"; key "$K_DOWN"; done; shot "settings-lower"; covered=$((covered+1)); checklist="$checklist\n  COVERED  settings-lower"
    key "$K_BACK" 1
    # Deep sub-sections sit near the BOTTOM of a long list: Voice library, Offline (collapsible), and
    # Saved places (a plain SectionTitle). Per-row DOWN polling is too slow (uiautomator dump ~2.6s each)
    # and overshoots a non-focusable header; instead reach each from a FRESH Settings by controlled drags
    # (swipe_up_to - checks on_screen once per short slow drag, which can't fling a thin header past),
    # then nudge it up so the row is fully framed (not clipped at the fold). We DON'T tap-to-expand the
    # collapsibles: their expand/collapse state PERSISTS, so a tap is a non-deterministic TOGGLE (it
    # collapsed as often as it expanded). This captures each sub-section HEADER rendering clip-free at the
    # geometry - the coverage question here; the expanded pickers (voice catalog, offline region list)
    # are entered + navigated by audit_dynamic.sh's D-pad tour.
    for sub in "Voice library" "Offline" "Saved places"; do
      if open_settings && swipe_up_to "$sub"; then nudge_up; mark "settings-$(echo "$sub"|tr ' A-Z' '-a-z')" 1; key "$K_BACK" 1
      else mark "settings-$(echo "$sub"|tr ' A-Z' '-a-z')" 0; fi
    done
    # On-device ASR ENGINE PICKER (Search section): the Whisper/SenseVoice/Moonshine rows with
    # Download / Use / Active / Remove - the pickable-engines surface (upstream 5d2a6636). "Whisper
    # tiny" is always present (the default catalog entry) so it anchors the swipe regardless of what
    # is installed.
    if open_settings && swipe_up_to "Whisper tiny"; then nudge_up; mark "settings-asr-engines" 1; key "$K_BACK" 1
    else mark "settings-asr-engines" 0; fi
  else mark "settings-top" 0; mark "settings-lower" 0
       mark "settings-voice-library" 0; mark "settings-offline" 0; mark "settings-saved-places" 0
       mark "settings-asr-engines" 0; fi
  fi  # phase settings

  # --- Voice search (mic + capture sheet) ------------------------------------------------------
  if phase voice; then i=16
  $ADB shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1
  goto_map
  # The mic sits in the search bar when the field is empty. Under soft-keys that bar is decluttered
  # off the bare map (#76), so open the search overlay first (RIGHT soft key) - the mic rides in it;
  # touch keeps the mic on the bare map, so open nothing there. Then find + tap it by content-desc.
  softkeys_shown && key "$K_SOFT_RIGHT" 1.5
  if tap_desc "Voice search"; then
    # With the on-device model absent AND no provider the tap offers the download dialog; with
    # either present it opens the capture sheet or the provider - all three are the mic working.
    # POLL up to ~8s: the first listen loads the Whisper model (~4s on a slow phone) before the
    # sheet appears - a single early check read as MISSED while the feature was fine.
    ok=0
    for _ in 1 2 3 4; do
      # Accept ANY of the three mic outcomes: the on-device capture sheet, the Vela Voice download
      # offer, or a system voice-input app showing our "Speak to search" prompt (a post-pm-clear
      # device has no model, so Auto routes to the provider - that IS the mic working).
      # ANY mic outcome = the mic working: Vela's capture sheet, the Vela Voice download offer, OR
      # a system recognizer opening (its prompt, or - it hears the test silence and advances fast -
      # its "converts audio to text" blurb / "Didn't catch that" / "Try again" result). The old poll
      # only matched the pre-listening strings and missed the silence-result state (device-seen: a
      # working mic marked MISSED because the Google dialog had already advanced by the dump).
      # A system recognizer is its OWN window: after a successful mic tap, the foreground having
      # LEFT Vela is itself the mic working (device-seen: com.google.android.tts draws a Google-logo
      # mic dialog whose listening state has NO matchable text at all, just a "• • •" node - every
      # string above missed it and a working mic was marked MISSED).
      if on_screen "Listening…" || on_screen "Getting ready…" || on_screen_contains "Vela Voice" \
         || on_screen_contains "Speak to search" || on_screen_contains "Google Speech Services" \
         || on_screen_contains "Didn't catch" || on_screen "Try again" \
         || ! in_app; then ok=1; break; fi
      sleep 2
    done
    if [ "$ok" = 1 ]; then mark "voice-capture-sheet" 1; key "$K_BACK" 1
    else mark "voice-capture-sheet" 0; fi
  else mark "voice-capture-sheet" 0; fi
  fi

  # --- Parking (P button, hub menu, parked-car sheet) -------------------------------------------
  if phase parking; then i=17
  goto_map; softkeys_shown && key "$K_DOWN"   # DOWN focuses the map for the LEFT-soft-key Options menu; in touch it would expand the search overlay and hide the FAB, so skip it
  # Park: under soft-keys the on-screen P FAB is decluttered away (#76) and Park lives in the bare-map
  # Options menu (LEFT soft key); on touch the FAB is still there. park_action opens it the right way
  # per layout. Drive the hub: save -> re-open -> Find my car -> car sheet -> re-open -> clear (clean).
  # IDEMPOTENT START: a spot left over from an earlier run (or a run that failed before its cleanup)
  # makes the entry read "Parked car", not "Save parking spot", so this phase used to fail forever
  # after one bad run - device-seen on the restricted 240x320 leg. Clear any existing spot first.
  if park_action "Parked car"; then sleep 1.2; tap_center "Clear parking"; sleep 1.2; fi
  if park_action "Save parking spot"; then
    sleep 1.5; mark "parking-saved" 1              # pin + "Parked" toast
    if park_action "Parked car" && sleep 1.5 && on_screen "Find my car"; then
      mark "parking-menu" 1                         # the hub: Find my car / Move / (Earlier) / Clear
      tap_center "Find my car"; sleep 2
      on_screen "Parked car" && mark "parking-car-sheet" 1 || mark "parking-car-sheet" 0
      key "$K_BACK" 1
    else mark "parking-menu" 0; mark "parking-car-sheet" 0; fi
    park_action "Parked car" && sleep 1.5 && tap_center "Clear parking" && sleep 1   # clean up: clear the spot
  else mark "parking-saved" 0; mark "parking-menu" 0; mark "parking-car-sheet" 0; fi
  fi

  # --- hardware soft-key menus (keypad bar) ---------------------------------------------------
  # The base phases capture the BAR in every frame (dpad is forced), but never PRESS a soft key, so
  # the Options popups + zoom mode go uncaptured. Drive them here: LEFT opens the bottom-left menu.
  if phase softkey && [ "$softkeys" = on ]; then i=22   # no soft-key bar to press in the touch (SOFTKEYS=off) layout
  goto_map; key "$K_DOWN"
  key "$K_SOFT_LEFT" 1                              # bare map: LEFT -> Options menu
  if on_screen "Recenter"; then
    mark "softkey-map-options" 1                     # bare-map menu: Move map (no Zoom yet) / Recenter / ...
    key "$K_OK" 1; mark "softkey-move-map" 1         # Move map (first item) -> engage to pan + hint
    key "$K_SOFT_LEFT" 1; key "$K_OK" 1              # now MOVING: Options -> Zoom is the first item
    mark "softkey-zoom-mode" 1                       # -> D-pad zoom mode + hint
    key "$K_BACK" 1; key "$K_BACK" 1                 # zoom -> move-map -> bare map
    # ACTIVATE the Options entries that open something, don't just prove the menu renders. The menu
    # drawing correctly is NOT evidence its items work: Layers was decluttered off the map but its
    # PANEL stayed nested in the (now uncomposed) button block, so the entry set state into the void
    # and Layers was unreachable on every keypad phone - and this phase still passed (tester report,
    # Kyocera DuraXe e4830). Anything the menu opens gets opened here now.
    key "$K_SOFT_LEFT" 1
    if tap_center "Layers"; then
      sleep 1
      if on_screen_contains "Satellite" || on_screen_contains "Terrain"; then mark "softkey-layers-panel" 1
      else mark "softkey-layers-panel" 0; fi
      key "$K_BACK" 1
    else mark "softkey-layers-panel" 0; fi
  else mark "softkey-map-options" 0; mark "softkey-move-map" 0; mark "softkey-zoom-mode" 0; mark "softkey-layers-panel" 0; fi
  [ "$haveResults" = 0 ] && { goto_map; run_coffee && haveResults=1; }
  if [ "$haveResults" = 1 ]; then
    open_first_place; key "$K_SOFT_LEFT" 1           # place sheet: LEFT -> place Options menu
    on_screen_contains "Directions" || on_screen "Street View"; mark "softkey-place-options" 1
    key "$K_BACK" 1; key "$K_BACK" 1
  else mark "softkey-place-options" 0; fi
  fi

  echo "-- coverage: $id ($phases) --"; printf '%b\n' "$checklist"
  echo "  => $covered COVERED, $missed MISSED"
  if [ "$full" != 1 ]; then
    echo "  RESULT: PARTIAL run (PHASES='$phases') - re-run without PHASES for the full-support verdict ($id)"
  elif [ "$missed" -eq 0 ]; then echo "  RESULT: FULLY COVERED ($id)"
  else echo "  RESULT: NOT fully covered - $missed surface(s) MISSED ($id)"; fi
  echo "  screenshots: $out"
  return "$missed"
}

rc=0
if [ "${1:-}" = "all" ]; then
  printf '%s\n' "$DEVICES" | while IFS='|' read -r id _; do [ -n "$id" ] && cover_one "$id" </dev/null; done
else
  [ -n "${1:-}" ] || { echo "usage: full_coverage.sh <device-id|all>"; exit 2; }
  cover_one "$1"; rc=$?
fi
exit "$rc"
