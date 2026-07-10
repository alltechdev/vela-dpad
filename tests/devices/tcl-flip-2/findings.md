# TCL Flip 2 - findings

- **Screen:** 2.8", 320x240 LANDSCAPE, ~143 dpi. A flip phone; the main screen is landscape.
- **Emulate:** `adb shell wm size 320x240; adb shell wm density 160`
- **Auditor:** `VELA_SMALL=320x240 VELA_SMALL_DPI=160 bash tests/small_screen/audit_smallscreen.sh`

## Status: NOT YET TESTED

Landscape at 320x240 is the other hard case (very short height - vertical lists and dialogs have
little room, so clipping of buttons/rows is the main risk). To be driven as a real user (fresh
install, D-pad only) and documented here with screenshots.
