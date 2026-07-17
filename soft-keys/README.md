# Soft-key API reference

Each OEM ships its own softkey bar and its own closed API. This folder documents
the APIs we need, one subfolder per phone family. All three are accessed via
reflection -- no compile-time dependency, no SDK stub.

## Phones covered

| Folder | Phones | Android | API class |
|--------|--------|---------|-----------|
| `kyocera/` | DuraXV Extreme E4810, E4811, DuraXV+ | 9 (API 28) | `jp.kyocera.kcfp.util.KCfpSoftkeyGuide` |
| `tcl/` | 4058L1 (Gflip6_USCC) | 11 (API 30) | `android.widget.MenuBar` |
| `sonim/` | XP3plus, X320, XP5s | 9-11 | broadcast `android.intent.action.CHANGE_NAV_BAR` |

## Key differences at a glance

**Kyocera** -- window-scoped guide object, must call `invalidate()` to flush.
Priority system (0-4) arbitrates between app/dialog/IME layers. Listener per key
index. Get in `onResume()`, not `onCreate()`.

**TCL** -- `MenuBar` widget already in every `DecorView`. No invalidate; setters
apply immediately. One `MenuBarListener` for all three keys. Must forward
`onKeyUp` from Activity so physical keys reach the bar.

**Sonim** -- no guide object at all. Fire a broadcast and the system bar redraws.
Cheapest to integrate; no listener for key presses (use `onKeyDown`/`onKeyUp`).

## Layout

```
soft-keys/
  README.md                             (this file)
  kyocera/
    KYOCERA_SOFTKEYS_COMPLETE_GUIDE.md  (full integration guide)
    KCfpSoftkeyGuide.java               (decompiled framework source)
  tcl/
    TCL_4058L1_SOFTKEYS_COMPLETE_GUIDE.md
  sonim/
    SONIM_SOFTKEYS_GUIDE.md
```

