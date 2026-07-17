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
  upstream without touching `:app`. LGPL-3.0 inside Vela's GPL-3.0 is compatible.
- **No keycode conflict with Vela.** Vela uses `KEYCODE_MENU` / `SOFT_LEFT/RIGHT` / `F1/F2`
  NOWHERE; the D-pad arrows, OK and BACK stay Vela's. Yapchik's `ReservedKeys` refuses to ever bind
  those, and `UNIVERSAL` deliberately excludes BACK.

## What shipped (the prototype)

Map-only zoom softkeys, gated to keypad devices, touch byte-identical.

- `app/ui/softkey/VelaSoftkeys.kt` (the whole integration):
  - `init()` (called from `VelaApp.onCreate`): `Yapchik.install`, then `Yapchik.autoDetector =
    { isDpadFirstDevice(it) }`. **The engine is gated on Vela's OWN conservative detector** (the same
    `detectDpadFirst` rule + `vela_force_dpad` test override the Compose D-pad affordances use), so
    the bar NEVER appears on a touch phone. Mode stays `AUTO`; AUTO resolves through that detector.
  - `autoInsetContent = true` - the bar reserves its height by padding the content view rather than
    overlaying the map's bottom chrome (locate FAB / scale bar). Only affects keypad devices.
  - A global single-ink dark `style` that reads in both app themes.
  - `MapZoomSoftkeys(mapDpad)` (composable, called once in `MapScreen`): while the map is composed,
    binds `left("Zoom -") { mapDpad.zoomBy(-1.0) }` / `right("Zoom +") { mapDpad.zoomBy(1.0) }`,
    cleared on leave. `MapDpadController.zoomBy` eases the camera with no dependence on focus/engage,
    so the soft keys zoom with zero focus-walk - the issue #65 ask.
- **Only the map binds keys**, and the engine shows a bar only when a screen has bindings
  (`currentBindings.isNotEmpty()`), so every other screen stays bar-free with no per-screen config.
- **Contextual per surface.** The map picks its two keys from state (`MapScreen` computes them,
  `VelaSoftkeys.MapSoftkeys(left, right)` binds them; null slot = unbound, both null = no bar): a
  place sheet shows `Close` / `Directions` (zoom matters less on a pin - `Directions` fires
  `routeToSelected`, `Close` fires `clearSelection`), the search overlay shows NO bar (the map isn't
  visible), and browse / directions / nav show `Zoom -` / `Zoom +`. Settings draws OVER the still-
  composed map, so `VelaRoot` forces the bar off while it's up (`VelaSoftkeys.SuppressBarWhile`, the
  engine's per-screen mode override, which still yields to the user's global OFF). Device-verified:
  bare map = Zoom, search = no bar, pin = Close/Directions (Directions routed), Settings = no bar.
  Labels are inline English for now (localizing them is a follow-up).
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
  `armFieldSignal` on `SearchBar` (bumping it arms the field, exactly like an OK on the bar). During
  nav / a route the keys stay Zoom -/+ (no menu/search mid-drive); a place sheet keeps
  Options/Directions; the search overlay shows no bar.
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

Shared-file edits are minimal and anchored: one `VelaSoftkeys.init` line in `VelaApp`, one
`MapZoomSoftkeys()` call in `MapScreen`, and one public `isDpadFirstDevice(context)` accessor added
to `DpadFocus.kt` (non-composable view of the existing private `detectDpadFirst`).

## Testing done (prototype)

- CI gates: `:core:test`, `:app:assembleStandardRelease` + `:app:assembleRestrictedRelease`, D-pad
  `audit_static.sh` 0 violations.
- On device, forced via `adb shell settings put global vela_force_dpad 1`:
  - Touch (gate NOT met): no bar - confirmed.
  - D-pad gate met: the `Zoom -` / `Zoom +` bar appears; `adb shell input keyevent 1` (SOFT_LEFT)
    zooms out, `keyevent 2` (SOFT_RIGHT) zooms in (scale bar 200 ft <-> 10 mi).
  - Verified at 240x320 and 480x854, standard AND restricted flavor.

## Rollout plan (beyond the prototype)

Ordered by value / risk. Each phase keeps the hard rules: touch byte-identical, gated on the D-pad
detector, new behaviour in new files, both geometries x both flavors + eyeball before merge.

1. **More contextual surfaces.** The map's contexts are wired (browse/nav -> Zoom, place ->
   Close/Directions, search -> none, Settings -> suppressed). Remaining candidates: a live-nav set
   (`Overview` / `End`), a directions set (`Start` / `Back`), and search -> `Clear` / a submit. Each
   needs its own eyeball pass. Also localize the inline English labels.
3. **Theme-reactive style (BLOCKED on an upstream change).** The bar is a fixed dark toolbar (reads
   fine in both themes). Following Vela light/dark needs Yapchik to re-apply `style` colours on
   `refresh()` - today it sets them at bar CONSTRUCTION only, so `refreshAll()` re-binds labels but
   never repaints an on-screen bar (device-confirmed: the bar stayed dark after switching to Light).
   The fix is a small upstream patch (re-tint in `SoftkeyController.refresh` / `SoftkeyBar`); worth
   sending. Don't hand-patch the vendored copy - that would break re-sync.
4. **On/off setting + calibration entry.** DONE (see "What shipped" above) - a Settings toggle
   backing `Yapchik.mode` and a `startCalibration` row. Remaining sub-work: a Vela-native profile
   chooser if we ever surface the full list, and wiring the calibrated custom profile into
   `res/xml/yapchik_devices.xml` presets (item 5).
5. **Known-device profiles.** Ship `res/xml/yapchik_devices.xml` with entries for the phones we have
   evidence for (Sonim x320, Kyocera, TCL) so those devices work without calibration; keep UNIVERSAL
   as the fallback. Confirm each device's actual soft-key keycodes on hardware first.
6. **Dialog coverage (open question).** Yapchik wraps the ACTIVITY window; Vela's raw-`Dialog`
   `VelaMenu`/`VelaDialog` are separate windows, so softkeys won't appear over them without per-dialog
   wiring. Decide whether that matters (softkeys are most valuable on the map) or wire the dialogs.
7. **Auditor integration.** Teach `tests/dpad` / `tests/small_screen` about the bar: its height
   changes the clip envelope at 240x320, and a `softkey` phase in `full_coverage.sh` would capture it
   per device/flavor. Add once the surface set is stable.

## Open questions to resolve on real hardware

- Which keycodes do the target phones' corner keys actually emit? (Some vendor keys are non-standard
  kernel scancodes; calibration must capture them.)
- Does the bar sit correctly under Vela's results-sheet detents / nav chrome at 240x320 without
  tripping the small-screen clip auditor?
- Z-order over the MapLibre `SurfaceView` on real devices (verified fine on the emulator).
