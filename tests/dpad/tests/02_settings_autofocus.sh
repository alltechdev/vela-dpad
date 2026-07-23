#!/usr/bin/env bash
# Settings must open ALREADY focused (on the back button, top of screen) - the original bug was it
# opened with nothing focused, wasting the first keypress (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 02: Settings opens focused on the back button"

goto_map
# PURE KEY navigation on purpose - nav.sh's open_settings taps by content-desc/center, and a
# TOUCH open must NOT auto-focus (touch UX stays byte-identical), so it cannot test this.
# The pure-key route DEPENDS ON THE LAYOUT: under soft keys the bare map is decluttered (#76 -
# no search bar, no gear) and Settings lives in the LEFT-soft-key Options menu (a VelaMenu whose
# first item auto-focuses, so focus_and_ok walks to Settings by key); in the touch layout the
# search bar is field, MIC, gear: two RIGHTs reach the gear from the field. If the first DOWN
# landed on the gear already (density-dependent), the second RIGHT is a no-op at the row end -
# either way OK opens Settings. Retry once via the mic-less count if Appearance did not show.
if softkeys_shown; then
  # One arrow on the bare map FIRST: it establishes Compose focus for the session (search bar /
  # chip). Without it this test hits the documented focus-owner WALL - when Settings is the
  # session's first focus-touching surface (soft-key menu entry: the soft key lives in the bar,
  # the menu in its own Dialog window, so the main window never held focus), requestFocus AND
  # moveFocus no-op and NOTHING can pre-place focus (same wall as the cold-open bare map,
  # docs/dpad.md; there the first key press lands on Back, ring and all, so one press is spent
  # exactly like the bare map). The assertion below covers the real keypad path: focus existed,
  # Settings must open focused on Back with NO keypress.
  key "$K_DOWN"
  key "$K_SOFT_LEFT" 1      # bare-map Options menu (first item auto-focused)
  focus_and_ok "Settings" || key "$K_BACK" 1
  if ! on_screen "Appearance"; then
    goto_map; key "$K_DOWN"; key "$K_SOFT_LEFT" 1; focus_and_ok "Settings" || true
  fi
else
  focus_search_bar          # DOWN -> search bar
  key "$K_RIGHT"            # -> voice-search mic
  key "$K_RIGHT"            # -> settings gear (rightmost)
  key "$K_OK" 1.5           # open Settings
  if ! on_screen "Appearance"; then
    key "$K_BACK" 1.5       # back out of whatever opened (voice dialog / search overlay)
    goto_map
    focus_search_bar
    key "$K_RIGHT"
    key "$K_OK" 1.5
  fi
fi
assert_on_screen "Appearance"                        # we're in Settings (the hub row list)
assert_focus_ytop_between 20 130 "Settings back button (top-left)"
report
