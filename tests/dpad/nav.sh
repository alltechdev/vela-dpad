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
    # The first-run chain: the "Vela Voice" prompt, "Offline maps", and the diagnostics consent all
    # dismiss with "Not now" - OK activates the safe/decline choice on whichever is up. (The voice
    # prompt's dismiss used to be "Use system voice"; it is "Not now" since the two-checkbox
    # redesign.) Keep clearing until two settled misses.
    if on_screen "Not now"; then
      # OK activates the focused decline under D-pad; in touch NOTHING is focused (auto-focus is
      # dpadFirst-gated) so OK is a no-op - tap the button by its text instead (issue #78).
      if softkeys_shown; then key "$K_OK" 1.2; else tap_center "Not now"; sleep 0.4; fi
      misses=0
    else misses=$((misses + 1)); [ "$misses" -ge 2 ] && break; sleep 0.6; fi
  done
}

# goto_map - cold launch to the bare map (onboarding dismissed). Leaves nothing focused (by design).
# Robust to first-run: if the Welcome screen shows (e.g. a prior test cleared data), its auto-focused
# Get-started advances past it, then the onboarding dialogs are dismissed. In the touch layout the
# button is BELOW the fold (the reveal pre-scroll is D-pad-gated) and nothing is focused, so the
# Welcome screen is detected by its always-visible tagline, dragged to reveal the button, and the
# button tapped by text (issue #78).
goto_map() {
  launch_fresh 3.5
  if softkeys_shown; then
    if on_screen "Get started"; then key "$K_OK" 2; fi
  elif on_screen "Get started" || { on_screen_contains "degoogled" && swipe_up_to "Get started" 8; }; then
    tap_center "Get started"; sleep 2
  fi
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

# results_present - true once the results overlay's "N results" count is on screen. Central so both
# the chip nav and the typed fallback agree on what "done" means. Tolerates pagination (upstream
# b3bb48fa merges nearby-ambient hits, so the count is now a variable 40-60+, not a fixed number).
results_present() {
  for _ in 1 2 3 4 5 6; do
    ui_dump
    if $ADB shell cat /sdcard/ui.xml 2>/dev/null | grep -qE 'text="[0-9]+ results"'; then return 0; fi
    sleep 1
  done
  return 1
}

# edit_field_center - x/y centre of the search overlay's EditText (Compose exposes it as an EditText
# with an empty content-desc), or empty if none is up. Used to FOCUS the field before typing: opening
# the overlay lands D-pad focus on the Close button, not the field, so `input text` would go nowhere.
edit_field_center() {
  ui_dump
  $ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re
d = sys.stdin.read()
for m in re.finditer(r"<node [^>]*/>|<node [^>]*>", d):
    s = m.group(0)
    if "android.widget.EditText" in s:
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", s)
        if b:
            x1,y1,x2,y2 = map(int, b.groups()); print((x1+x2)//2, (y1+y2)//2); break
'
}

# type_search <query> - open the search overlay and type <query>, then submit. This is the harness's
# GROUND-TRUTH search path: it drives the field the same way a user does, so it exercises pagination
# AND leaves <query> in recents (feeding the search-as-you-type history check). Under soft-keys the
# RIGHT soft key ("Search") opens the overlay; on touch, tap the search bar. Either way the field is
# NOT auto-focused (focus sits on the Close/leading control), so we TAP the EditText to focus it,
# commit the text, then ENTER (66) fires the query.
type_search() {
  local q="$1" fc
  if softkeys_shown; then
    key "$K_SOFT_RIGHT" 2                 # "Search" soft key -> overlay
  else
    tap_center "Search" || { key "$K_DOWN"; key "$K_OK" 1.5; }   # focus + expand the search bar
  fi
  fc="$(edit_field_center)"
  [ -n "$fc" ] && { $ADB shell input tap $fc >/dev/null 2>&1; sleep 1; }   # focus the text field
  $ADB shell input text "$q" >/dev/null 2>&1; sleep 1
  $ADB shell input keyevent 66 >/dev/null 2>&1   # KEYCODE_ENTER - submit the query
  sleep 4
}

# run_coffee - from the bare map, get a coffee result set on screen. Leaves focus/results up. Returns
# 0 if "N results" appeared. Tries the fast category-chip path first; if that flakes (the chip focus
# ring can race the overlay at small sizes - the 240x320 chip-nav miss), FALLS BACK to a typed search,
# which is the reliable path a real user takes and also seeds "coffee" into recents for the history
# check. Under soft-keys (dpad forced) the search bar is decluttered away (#76) so the FIRST down lands
# on the category chips row; RIGHT moves to Coffee. On touch the chips sit under the bar, so TAP them.
run_coffee() {
  if softkeys_shown; then
    key "$K_DOWN"; key "$K_RIGHT"; key "$K_OK" 5
  else
    tap_center "Coffee" || tap_center "Restaurants"; sleep 4
  fi
  results_present && return 0
  # Chip path flaked - drive the search field directly. goto_map first so the overlay opens clean
  # (a half-open chip overlay would swallow the soft key).
  goto_map
  type_search "coffee"
  results_present
}

# tap_first_result - TOUCH: tap the first search-result row by its parsed NAME. The rows carry no
# stable text (the name is whatever place matched), so it is read from the dump: the first non-empty
# text node BELOW the "N results" header and the filter-chip row (Open now / rating / Price / Sort).
# D-pad legs never need this - DOWN walks the focus order.
tap_first_result() {
  ui_dump
  local name
  name="$($ADB shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import sys, re, html
d = sys.stdin.read()
nodes = []
for m in re.finditer(r"<node [^>]*>", d):
    s = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", s); b = re.search(r"bounds=\"\[(\d+),(\d+)\]", s)
    if not (t and b): continue
    nodes.append((int(b.group(2)), html.unescape(t.group(1))))
cut = None
for y, t in nodes:
    if re.fullmatch(r"\d+ results?", t): cut = y
    if t in ("Sort", "Price", "Open now") or t.endswith("★"):
        cut = max(cut or 0, y)
if cut is not None:
    for y, t in nodes:
        if y > cut and t.strip():
            print(t); break
')"
  [ -n "$name" ] || return 1
  tap_center "$name"
}

# open_first_place - from the results list, open the first result's place sheet (handle focused).
# In TOUCH the D-pad walk is wrong twice over: nothing is focused so the first DOWN lands on the
# on-screen search bar and auto-EXPANDS the overlay (issue #78 - the phase then "covered" the
# overlay, not a place sheet), so tap the first row by its parsed name instead.
open_first_place() {
  if softkeys_shown; then
    keys "$K_DOWN" "$K_DOWN" "$K_DOWN"   # search field -> filter chips -> first result row
    key "$K_OK" 2.5                       # open place sheet
  else
    tap_first_result; sleep 2.5
  fi
}

# sheet_drag_up - TOUCH: pull a collapsed bottom sheet open by dragging from inside it (bottom of
# the screen) upward. swipe_up_to can't do this - its drag starts mid-screen, which on the place
# sheet layout is the MAP, so it pans the map and the sheet never moves (issue #78 @240x320).
sheet_drag_up() {
  local sz w h gx
  sz="$($ADB shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)"
  w="${sz%x*}"; h="${sz#*x}"; : "${w:=480}"; : "${h:=800}"
  gx=$((w*13/100)); [ "$gx" -lt 24 ] && gx=24   # left gutter: label text only, never eats the drag
  $ADB shell input swipe "$gx" $((h*88/100)) "$gx" $((h*30/100)) 500 >/dev/null 2>&1
  sleep 1
}

# reach_directions - from a bare map, open the first Coffee result and its Directions panel (Drive
# tab focused). Returns non-zero if results never loaded. Leaves the directions panel open.
reach_directions() {
  run_coffee || return 1
  open_first_place
  # Under soft-keys: OK (on the focused sheet) expands it, and the place sheet's whole button row is
  # DROPPED - Directions IS the RIGHT soft key, press it. (tap_center would land on the soft-key
  # BAR's "Directions" label, which happened to work at 480x854 but missed at 240x320 - the kyocera
  # directions/route-steps regression.)
  # On touch, OK is a NO-OP (nothing focused, issue #78): reach the Directions pill by its TEXT -
  # visible already on roomy screens, dragged into view on small ones where the collapsed sheet
  # keeps it below the fold. Text, not a fixed DOWN count: the sheet's row layout varies by flavor
  # (the restricted build has no photo strip and no Website pill, so a blind 3-DOWN overshot the
  # pills row - the flavor-cascade bug). tap_center is flavor/layout-proof.
  if softkeys_shown; then
    key "$K_OK" 1                        # expand the sheet so the action pills are on screen
    key "$K_SOFT_RIGHT" 4
  elif tap_center "Directions"; then
    sleep 4
  else
    # Collapsed sheet at small geometry keeps the pills below the fold, and the generic swipe_up_to
    # starts mid-screen - which is MAP here, so it pans instead of expanding. Drag from INSIDE the
    # sheet (bottom ~15% of the screen) to pull it open, then tap the pill.
    sheet_drag_up
    tap_center "Directions" && sleep 4
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
