# Kyocera e4810 - findings

- **Screen:** 2.6", 240x320 portrait, ~154 dpi.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: IN PROGRESS

### Works
- **Adaptive density fits the UI.** With `AdaptiveDensity` (the app scales its own density on small
  screens so it always has >= ~360dp of logical width), the bare map now shows ALL THREE category
  chips (Restaurants/Coffee/Gas) and the full chrome comfortably - before, only "Restaurants" + a
  sliver of Coffee fit. Before: ![cramped](screenshots/01-bare-map.png) After:
  ![adaptive](screenshots/04-bare-map-adaptive-density.png). Settings shows more per screen too:
  ![settings adaptive](screenshots/05-settings-adaptive-density.png)
- **Settings opens FOCUSED.** The Back button takes focus on open (the robust `dpadAutoFocus`
  fix) - no wasted first keypress. ![settings open](screenshots/02-settings-open-back-focused.png)

### Broken (to fix)
- **DOWN from the focused Back button CLEARS focus** instead of entering the content list. After one
  DOWN, nothing is focused (no ring on Back or on "Follow system").
  ![focus cleared](screenshots/03-settings-down-focus-cleared.png)
  Compose clears focus on a directional move that finds no in-container target; the Back button lives
  in the TopAppBar (a separate container from the scrolling content), so DOWN can't reach the first
  row and clears. Needs an explicit DOWN-from-Back -> first-content-row bridge (mirror of the existing
  UP-from-top -> Back routing), verified visually at 240x320.

### Not yet tested (as a real user, at 240x320)
- First-run flow: Welcome, voice/offline prompts, the "Help improve Vela?" diagnostics consent.
- Search overlay + results, place sheet, directions, nav, dialogs/menus.

## Screenshots
See `screenshots/` - all captured at 240x320 via `adb exec-out screencap`.
