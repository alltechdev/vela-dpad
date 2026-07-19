#!/usr/bin/env bash
# tests/dpad/nav.sh - Vela-specific D-pad navigation helpers, sourced by tests.
# All movement is D-pad-only (the point of the suite). Kept separate from lib.sh (generic).

# dismiss_onboarding - OK through the one-time onboarding dialogs. Each VelaDialog auto-focuses its
# "Not now" button, so a single OK dismisses it; repeat for the chain (voice / offline / …).
dismiss_onboarding() {
  # POLL with settles - don't break on the first absence. The diagnostics consent card ("Help
  # improve Vela?") and some prompts appear a BEAT LATE (SPA-style), so breaking the instant
  # "Not now" isn't yet on screen let a late card slip through and block later navigation (the
  # "could not open Settings" false-fail). Keep OK-ing "Not now" whenever it shows; stop only
  # after two consecutive settled misses.
  local misses=0
  for _ in $(seq 1 8); do
    # The first-run chain: the diagnostics consent + "Offline maps" dialogs dismiss with "Not now";
    # the "Spoken navigation voice" dialog's auto-focused dismiss is "Use system voice". OK activates
    # whichever is up (both are the safe/decline choice). Keep clearing until two settled misses.
    if on_screen "Not now" || on_screen "Use system voice"; then key "$K_OK" 1.2; misses=0
    else misses=$((misses + 1)); [ "$misses" -ge 2 ] && break; sleep 0.6; fi
  done
}

# goto_map - cold launch to the bare map (onboarding dismissed). Leaves nothing focused (by design).
# Robust to first-run: if the Welcome screen shows (e.g. a prior test cleared data), its auto-focused
# Get-started advances past it, then the onboarding dialogs are dismissed.
goto_map() {
  launch_fresh 3.5
  if on_screen "Get started"; then key "$K_OK" 2; fi
  dismiss_onboarding
}

# focus_search_bar - from the bare map (nothing focused), the first DOWN lands on the search bar (the
# on-screen touch layout). NOTE: under soft-keys the bar is decluttered (#76) and the first DOWN lands
# on the category CHIPS instead - callers that want the search OVERLAY should use open_search.
focus_search_bar() { key "$K_DOWN"; }

# open_search - open the search overlay (armed field + Home/Work/recents). Under soft-keys the search
# bar is decluttered off the bare map (#76) so the RIGHT soft key opens it; otherwise DOWN focuses the
# on-screen bar and OK opens it. Use this instead of `focus_search_bar; key OK` so BOTH layouts work.
open_search() {
  if softkeys_shown; then key "$K_SOFT_RIGHT" 1.5; else focus_search_bar; key "$K_OK" 1.5; fi
}

# park_action <label> - open the Park action the layout-correct way. Under soft-keys the on-screen P
# FAB is decluttered (#76) so Park lives in the bare-map Options menu (LEFT soft key) - open it and tap
# the row; on touch the FAB itself carries the content-desc, so tap it directly. <label> is
# "Save parking spot" (no spot yet) or "Parked car" (spot set). Returns non-zero if not found.
park_action() {
  if softkeys_shown; then key "$K_SOFT_LEFT" 1; tap_center "$1"; else tap_desc "$1"; fi
}

# open_settings - robustly open Settings from anywhere. The first DOWN can land on the search FIELD or
# the gear depending on focus order (and adaptive density shifts it), and RIGHT-from-field only reaches
# the gear when the field held focus - so a single "DOWN,RIGHT,OK" flakily opened the SEARCH overlay
# instead (the "Settings unreachable" false-fail). Press RIGHT twice (reaches the rightmost gear from
# either start), confirm "Appearance", and retry the whole nav (backing out of the overlay) if not.
open_settings() {
  local a
  for a in 1 2 3 4; do
    goto_map
    if softkeys_shown; then
      # Soft-keys declutter the search bar + its gear away (#76); Settings moved into the bare-map
      # Options menu (LEFT soft key). Open it and pick Settings.
      key "$K_SOFT_LEFT" 1
      { on_screen "Settings" && tap_center "Settings"; } || key "$K_BACK" 1
    else
      # Touch/hybrid: the on-screen search-bar gear is still there. Tap it by content-desc (the old
      # layout/flavor-proof path, unchanged) - the MIC sits between field and gear, so tap_desc not RIGHT.
      tap_desc "Settings"
    fi
    for _ in 1 2 3; do on_screen "Appearance" && return 0; sleep 0.5; done
    key "$K_BACK" 1                          # opened something else? back out, retry
  done
  return 1
}

# run_coffee - from the bare map, run a category chip search; waits for results. Leaves focus in the
# results list. Returns 0 if "N results" appeared. Under soft-keys (dpad forced) the search bar is
# decluttered away (#76), so the FIRST down lands straight on the category chips row (Restaurants) -
# no search bar to pass first. Any category yields a result set; RIGHT moves to Coffee for the name.
run_coffee() {
  # Run a category chip search. Under soft-keys the search bar is decluttered so the FIRST down IS the
  # chips row - D-pad to Coffee and OK. In the touch layout the chips sit UNDER the on-screen search
  # bar, and a D-pad DOWN would focus that bar and auto-EXPAND it into the overlay (hiding the chips),
  # so TAP the chip directly instead. Either way a category search yields the same result set.
  if softkeys_shown; then
    key "$K_DOWN"; key "$K_RIGHT"; key "$K_OK" 5
  else
    tap_center "Coffee" || tap_center "Restaurants"; sleep 4
  fi
  for _ in 1 2 3 4 5 6; do
    ui_dump
    if $ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -qE 'text="[0-9]+ results"'; then return 0; fi
    sleep 1
  done
  return 1
}

# open_first_place - from the results list, open the first result's place sheet (handle focused).
open_first_place() {
  keys "$K_DOWN" "$K_DOWN" "$K_DOWN"   # search field -> filter chips -> first result row
  key "$K_OK" 2.5                       # open place sheet
}

# reach_directions - from a bare map, open the first Coffee result and its Directions panel (Drive
# tab focused). Returns non-zero if results never loaded. Leaves the directions panel open.
reach_directions() {
  run_coffee || return 1
  open_first_place
  key "$K_OK" 1                          # expand the sheet so the action pills are on screen
  # Under soft-keys the place sheet's whole button row is DROPPED and Directions IS the RIGHT soft key
  # - press it. (tap_center would land on the soft-key BAR's "Directions" label, which happened to work
  # at 480x854 but missed at 240x320 - the kyocera directions/route-steps regression.)
  # On touch, reach the Directions pill by its TEXT, not a fixed DOWN count: the sheet's row layout
  # varies by flavor (the restricted build has no photo strip and no Website pill, so a blind 3-DOWN
  # overshot the pills row - the flavor-cascade bug). tap_center is flavor/layout-proof.
  if softkeys_shown; then
    key "$K_SOFT_RIGHT" 4
  elif tap_center "Directions"; then
    sleep 4
  else
    keys "$K_DOWN" "$K_DOWN" "$K_DOWN"   # fallback: the old walk (dump raced / text off-screen)
    key "$K_LEFT"
    key "$K_OK" 5
  fi
  # Directions-panel proof. "Add stop" is the row we used, but at 240x320 it sits BELOW the fold, so a
  # working panel read as a failure (the kyocera directions/route-steps miss). Accept any of the
  # panel-only markers instead: the ETA/route summary or the Start/Steps soft keys it puts on the bar.
  on_screen "Add stop" || on_screen_contains "Arrive" || on_screen "Fastest" || on_screen "Steps"
}

# open_choose_on_map - drill all the way to the Choose-on-map pick overlay (edit the origin ->
# "Choose on map"). Returns 0 iff the "Move the map" pick overlay is showing. Deep + network-bound.
open_choose_on_map() {
  reach_directions || return 1
  key "$K_UP"                            # Drive tab -> the "Your location" (From) row
  key "$K_OK" 2                         # open the origin search overlay (pickingOrigin)
  on_screen "Choose on map" || return 1
  focus_and_ok "Choose on map" || return 1
  sleep 1
  on_screen_contains "Move the map"      # banner: "Move the map to set the start/stop" (substring)
}
