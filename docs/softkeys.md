# Hardware softkeys (Yapchik) - record + rollout plan

Feature / kosher / keypad phones (Qin F21, Sonim x320, TCL flip, Kyocera) carry two hardware
SOFT keys - usually the top-left and top-right buttons - that have no touch equivalent. Issue #65
(the Sonim x320 review) asked for those keys to zoom the map, because reaching Vela's on-screen
`+`/`-` buttons by D-pad takes 4+ keypresses. This document records what shipped and the plan for
the rest.

## What this is built on

[Yapchik](https://github.com/theOnionsAreWatching/yapchik) - a zero-dependency (framework-only,
no androidx/appcompat) Kotlin softkey engine. It renders a feature-phone LEFT/RIGHT label bar at
the screen bottom and drives it from the physical soft keys, wrapping the Activity's
`Window.Callback` so the host never overrides `dispatchKeyEvent`. It ships a `KeyProfile` /
calibration system for the fact that keypad phones route their soft keys through DIFFERENT
keycodes (`SOFT_LEFT`/`SOFT_RIGHT`, `MENU`, `F1`/`F2`, vendor `MULTIFUNC_*`) - which is exactly the
per-vendor problem the parked softkey-vendor-guides notes were about.

- **Vendored as `:yapchik`** (a source-identical copy of the upstream library module, its own
  package `com.theonionsarewatching.yapchik`, LGPL-3.0 - see `yapchik/LICENSE` + `yapchik/NOTICE`).
  Kept as its own module so it stays a cleanly replaceable library (LGPL) and can be re-synced from
  upstream without touching `:app`. LGPL-3.0 inside Vela's GPL-3.0 is compatible, and F-Droid-eligible.
  **NB the license history:** yapchik was PolyForm Noncommercial (GPL-incompatible) as of 2026-07-16,
  then the author RELICENSED it to LGPL-3.0-or-later on 2026-07-17 - which is why vendoring became
  possible. Verified against the GitHub API + the actual `LICENSE` file + the README before vendoring;
  re-check the upstream license on any future re-sync.
- **No keycode conflict with Vela.** Vela uses `KEYCODE_MENU` / `SOFT_LEFT/RIGHT` / `F1/F2`
  NOWHERE; the D-pad arrows, OK and BACK stay Vela's. Yapchik's `ReservedKeys` refuses to ever bind
  those, and `UNIVERSAL` deliberately excludes BACK.

## What shipped

Contextual hardware soft keys for keypad phones, gated to keypad devices, touch byte-identical. Built
on the vendored `:yapchik` engine; ALL the UX below is Vela's own, built beside the engine (yapchik
stays source-identical for re-sync).

- `app/ui/softkey/VelaSoftkeys.kt` (the whole integration):
  - `init()` (called from `VelaApp.onCreate`): `Yapchik.install`, then `Yapchik.autoDetector =
    { isDpadFirstDevice(it) }`. **The engine is gated on Vela's OWN conservative detector** (the same
    `detectDpadFirst` rule + `vela_force_dpad` test override the Compose D-pad affordances use), so
    the bar NEVER appears on a touch phone. Mode stays `AUTO`; AUTO resolves through that detector.
  - `autoInsetContent = true` - the bar reserves its height by padding the content view rather than
    overlaying the map's bottom chrome (locate FAB / scale bar). Only affects keypad devices.
  - A single-ink `style` that FOLLOWS the app theme (dark bar in dark, light in light). Yapchik
    colours the bar at construction, so `MapSoftkeys` REBUILDS it on a theme flip (`clear()` then
    `set()` in one effect body is atomic - no flicker); the bind effect re-keys on `isAppInDarkTheme()`.
  - `Key(label, onPress)` + `MapSoftkeys(left, right)` (composable, called once in `MapScreen`): the
    seam the map binds through. `MapScreen` computes the two `Key`s from state; a null slot is unbound,
    both null shows no bar; cleared on leave. Re-keys on the labels, the theme, and modal depth (below).
  - `SuppressBarWhile(bool)`: force the bar OFF for a whole SCREEN drawn over the still-composed map
    (Settings), via the engine's per-screen mode override (still yields to the user's global OFF).
  - `SuppressBarForModal()` + a reactive `modalDepth` counter: a `VelaDialog` bumps the count while
    open; `MapSoftkeys` watches it and CLEARS the bar while any modal is up (its keys go to the dialog
    window). A `VelaMenu` (the Options popups) is NOT a `VelaDialog`, so it keeps the bar.
- **Only the map binds keys**, and the engine shows a bar only when a screen has bindings
  (`currentBindings.isNotEmpty()`), so every other screen stays bar-free with no per-screen config.
- **Contextual per surface.** `MapScreen` picks the two keys from state (the `when` at the top of the
  composable):

  | Surface | LEFT | RIGHT |
  |---|---|---|
  | Bare map | Options (Zoom · Recenter · Layers · Settings) | Search |
  | Place sheet | Options (all secondary actions) | Directions |
  | Choose-on-map | Cancel | Set start / stop |
  | Route preview | Steps | Start |
  | Turn list | — | Start |
  | Turn-by-turn nav | Options (Mute/Unmute · Steps · Recenter · End) | Zoom mode |
  | Search overlay | — (no bar) | — |

  Settings draws OVER the still-composed map, so `VelaRoot` forces the bar off while it's up
  (`SuppressBarWhile`). Labels are LOCALIZED into all 14 locales (the new words - Options / Search /
  Recenter / Mute / Unmute; the rest reuse existing strings; the symbol-heavy zoom-mode label stays
  English). Device-verified on a physical M5 across every row.
- **On/off + calibration setting (Settings -> Navigation, keypad devices only).** A `Hardware
  softkeys` switch (shown only when `rememberDpadMode()`) backs `Yapchik.mode`: ON = AUTO (bar on
  keypad phones, hidden on touch), OFF = disabled everywhere. A `Calibrate soft keys` row runs
  `SoftkeyProfileChooser.startCalibration` (press LEFT then RIGHT; capture is key-press-driven, saved
  as a custom profile) for phones whose soft keys emit non-standard keycodes. `VelaSoftkeys` mirrors
  the engine's resolved state into a Compose `mutableStateOf` (seeded + a `StateListener`), so the
  map's `+`/`-` gate reacts live to the toggle. Device-verified: toggle both ways, calibration
  captured `L:SOFT_LEFT R:SOFT_RIGHT`.
  - NB calibration uses Yapchik's own framework `AlertDialog`. That's acceptable here because the
    interaction is "press your soft key" (window-level key capture, not focus navigation). If the
    profile CHOOSER (a navigable single-choice list) is ever surfaced, give it a Vela-native
    `VelaDialog`/`VelaMenu` instead - a bare `AlertDialog` list can't auto-focus under D-pad.
- **The bare map (first screen) has its own Options menu + a Search key.** LEFT = Options, RIGHT =
  Search. The Options menu (same bottom-left bordered VelaMenu) holds Zoom / Recenter / Layers /
  Settings. **Zoom** enters a mode where the D-pad LEFT/RIGHT zoom out/in (the map is engaged +
  focused so the keys reach `MapDpadController`, and the one BackHandler peels the mode first), with
  a top-of-screen hint; BACK exits. **Search** opens the search field focused via a new
  `armFieldSignal` on `SearchBar` (bumping it arms the field, exactly like an OK on the bar). (Other
  surfaces per the table above: route preview = Steps/Start, turn-by-turn = Options/Zoom, place sheet
  = Options/Directions, search overlay = no bar.)
- **The LEFT soft key is an Options menu; the place sheet loses ALL its on-screen buttons.** Feature-
  phone convention: on a place sheet the RIGHT key is the one primary action (Directions), the LEFT
  key opens an **Options** menu (the auto-focusing D-pad `VelaMenu`) holding every secondary action -
  Street View, Call, Website, Save, Share, Set Home/Work - and Close is hardware BACK. So on keypad the
  place-sheet header collapses to just the name (no star / share / ⋮) and there's no pill row at all;
  the sheet is name + address + the soft-key bar. `MapScreen` hoists `placeOptionsOpen`, the LEFT key
  sets it, and `PlaceSheet` drives the same header `VelaMenu` from it. Touch keeps every button (the
  menu items only appear when `softkeysActive`). Device-verified: Options opens auto-focused, Street
  View from the menu opened the pano, Directions routed.
- **Redundant touch buttons are trimmed when a soft key covers them.** The rule "hardware key
  present -> drop the on-screen button, free the screen" applies to every contextual action, not just
  zoom: while the place-sheet soft keys are active, the on-screen **Directions** pill (both the full
  and minimized-detent copies) and the header **Close** X are hidden - the soft keys do them. Gated
  on `VelaSoftkeys.isActive()`, so touch phones keep every button. Device-verified: the place sheet
  drops Directions + X (only Street View remains) and the Close soft key dismisses it.
- **The on-screen `+`/`-` are hidden when the bar is up.** On a structurally touchless device the
  softkey bar covers zoom, so the on-screen D-pad `+`/`-` buttons (`MapScreen`, gated on `dpadMode`)
  would be a redundant second affordance and waste bottom-right space at 240x320. They now also gate
  on `!dpadFirst`: hidden on a truly touchless phone (bar handles it), kept on a HYBRID touch+keypad
  phone (`dpadMode` true but has touch, so `isDpadFirstDevice` is false and the bar is NOT shown) and
  as the fallback where soft keys aren't recognized (zoom still reachable via the engaged crosshair +
  hardware zoom keys). When a softkeys on/off setting lands, gate this on the resolved softkey state
  instead of the raw detector.

Shared-file edits are minimal and anchored: one `VelaSoftkeys.init` line in `VelaApp`; the
`MapSoftkeys()` call + the contextual `when` + the Options-menu `VelaMenu`s in `MapScreen`; one
`SuppressBarForModal()` call in `VelaDialog`; an `armFieldSignal` param on `SearchBar`; and one public
`isDpadFirstDevice(context)` accessor in `DpadFocus.kt` (non-composable view of the private
`detectDpadFirst`). Companion #65 tuning: the D-pad map pan step in `MapScreen` (`0.22 -> 0.11`).

## Testing done

- CI gates: `:core:test`, `:app:assembleStandardRelease` + `:app:assembleRestrictedRelease`, D-pad
  `audit_static.sh` 0 violations.
- The `softkey` phase in `full_coverage.sh` captures the Options menus + zoom mode; reference frames
  committed for both geometries x both flavors (the restricted place menu drops Street View/Website).
- On device, forced via `adb shell settings put global vela_force_dpad 1`:
  - Touch (gate NOT met): no bar - confirmed (physical M5, real detection off).
  - D-pad gate met: end-to-end on a physical keypad+touch phone (M5, 480x640) - every contextual
    surface, the nav Options menu with Mute toggling to Unmute live, Start begins the drive,
    Choose-on-map sets the point, and a `VelaDialog` hides the bar while up.
  - Full coverage matrix at 240x320 and 480x854, standard AND restricted (10/10, no touch regression).

## Rollout status

Most of this is DONE (marked below); what remains is hardware-blocked or optional. Each item kept the
hard rules: touch byte-identical, gated on the D-pad detector, both geometries x both flavors + eyeball.

1. **Contextual surfaces. DONE.** Every map context is wired:
   - **Bare map** -> Options / Search (Options = Zoom / Recenter / Layers / Settings; Zoom = a D-pad
     zoom mode).
   - **Place sheet** -> Options / Directions (Close is BACK).
   - **Choose-on-map** (crosshair to set a route origin/stop) -> Cancel / Set start|stop.
   - **Route preview** -> Steps / Start (Start begins the drive, notification-permission-aware).
   - **Turn-list overlay** -> Start on the right.
   - **Turn-by-turn nav** -> Options / Zoom. The nav Options menu is Mute/Unmute (live mute state),
     Steps, Recenter, End - a keypad driver's full control set, which was the real gap (you can't
     walk focus while driving). Zoom on the right enters zoom mode mid-nav.
   - **Search overlay** -> no bar; **Settings** -> suppressed.

   Labels are LOCALIZED (Options / Search / Recenter / Mute / Unmute translated into all 14 locales;
   Directions / Layers / Settings / Start / Steps / End / Recenter reuse existing strings; the
   symbol-heavy zoom-mode label stays English). Companion #65 tuning also landed: the D-pad map pan
   step dropped 0.22 -> 0.11 (finer control; the coarse step over-shot).

   One edge case is deliberately NOT wired yet: the full-screen TRANSIT-guidance sheet
   (`state.transitNav`) still falls through to the browse keys. It should suppress the bar (map not
   visible), same as the search overlay - but reaching it needs a live transit route + GPS fix, which
   the test device couldn't get indoors, so it's left unverified rather than shipped blind.
3. **Theme-reactive style.** DONE. Yapchik colours the bar at CONSTRUCTION and `refresh()` only
   re-binds labels, so to repaint we REBUILD: `VelaSoftkeys.MapSoftkeys` re-keys its bind effect on
   `isAppInDarkTheme()` and does `clear()` then `set()` in one effect body (atomic - both run before
   the next frame, no flicker) after applying the theme colours. Dark toolbar in dark, light in
   light. (A cleaner upstream fix would be to re-tint in `SoftkeyController.refresh`/`SoftkeyBar`;
   worth sending, but not needed - we don't hand-patch the vendored copy.)
4. **On/off setting + calibration entry.** DONE (see "What shipped" above) - a Settings toggle
   backing `Yapchik.mode` and a `startCalibration` row. Remaining sub-work: a Vela-native profile
   chooser if we ever surface the full list, and wiring the calibrated custom profile into
   `res/xml/yapchik_devices.xml` presets (item 5).
5. **Known-device profiles (blocked on hardware, calibration covers it).** A
   `res/xml/yapchik_devices.xml` would let named phones skip calibration, but the useful entries need
   REAL keycodes we don't have: the parked vendor notes (the softkey-vendor-guides memory) show the
   Sonim x320's upper-key codes are UNKNOWN (needs a `getevent`/KeyEvent dump on the device) and the
   Kyocera route is a reflection API, not a plain keycode (out of scope for Yapchik's profile model).
   TCL is `SOFT_LEFT=1`/`SOFT_RIGHT=2` = already UNIVERSAL. So a devices XML today would only restate
   UNIVERSAL; the real answer for odd-keycode phones is the shipped CALIBRATION flow. Add named
   entries once a target phone is dumped.
6. **Dialog coverage. RESOLVED.** Yapchik wraps the ACTIVITY window; a `VelaDialog`/`VelaMenu` is a
   separate window, and the physical soft keys go to whichever window is focused - so a bar sitting
   behind a dialog's scrim is inert. The decision: a MODAL answers itself. `VelaDialog` hides the bar
   while up (a `SuppressBarForModal` reactive counter that `MapSoftkeys` watches and `clear()`s for -
   setting `screenMode` doesn't remove an already-drawn bar) and restores it on dismiss; the dialog's
   own D-pad-focusable buttons + BACK are the answer path. `VelaMenu` (the bottom-left Options popups)
   is intentionally NOT a modal here - it grows flush from the bar and keeps it. Device-verified
   (first-run voice/offline dialogs show no bar; the Options menu keeps its bar).
7. **Auditor integration. DONE (capture).** `full_coverage.sh` has a `softkey` phase that presses the
   LEFT key to open the bare-map Options menu, enters D-pad zoom mode, and opens the place Options
   menu - the surfaces the other phases never reach because they don't press a soft key (the bar
   itself is already in every frame, since the tour forces D-pad). Reference frames are committed for
   both geometries x both flavors (the restricted place menu shows Street View/Website correctly
   dropped). `K_SOFT_LEFT`/`K_SOFT_RIGHT` (1/2) added to `tests/dpad/lib.sh`. Still open: a numeric
   clip assertion for the bar's reserved band at 240x320 (today it's eyeballed from the frames).

## Open questions to resolve on real hardware

- Which keycodes do the target phones' corner keys actually emit? (Some vendor keys are non-standard
  kernel scancodes; calibration must capture them.)
- Does the bar sit correctly under Vela's results-sheet detents / nav chrome at 240x320 without
  tripping the small-screen clip auditor?
- Z-order over the MapLibre `SurfaceView` on real devices (verified fine on the emulator).
