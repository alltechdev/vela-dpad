#!/usr/bin/env bash
# Settings must open ALREADY focused (on the back button, top of screen) - the original bug was it
# opened with nothing focused, wasting the first keypress (docs/dpad.md).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; source "$D/lib.sh"; source "$D/nav.sh"
echo "TEST 02: Settings opens focused on the back button"

goto_map
# open_settings, not a hand-rolled DOWN/RIGHT/OK: the mic button sits between the search field
# and the gear, so RIGHT-once lands on VOICE SEARCH when the first DOWN focused the field - the
# exact flake nav.sh's open_settings was written to absorb (it RIGHTs to the rightmost control
# and retries if Settings did not appear).
open_settings
assert_on_screen "Appearance"                        # we're in Settings
assert_focus_ytop_between 30 130 "Settings back button (top-left)"
report
