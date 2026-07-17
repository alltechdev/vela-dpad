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

1. **Contextual per-screen softkeys.** *(Also fixes a known prototype quirk: because the map stays
   composed under Settings/other full-screen surfaces, its `Zoom -/+` bar currently shows there too -
   harmless, the keys just zoom the map underneath, but it reads oddly on Settings.)* Move beyond map
   zoom: bind slots per Vela surface via
   `Softkeys.of(activity).define(name)` / `.activate(name)` or `whenFocused(viewId)`, driven from a
   `LaunchedEffect` keyed on the current screen. Candidates: search overlay -> `Clear` / `Search`;
   place sheet -> `Directions` / `Back`; directions -> `Start` / `Back`; nav -> `Overview` /
   `End` (or `Zoom -/+` kept during nav). Each needs its own eyeball pass.
3. **Theme-reactive style.** The engine `style` is a global, not theme-aware. Update `Yapchik.style`
   + `Yapchik.refreshAll()` from `AppTheme` changes so the bar follows Vela light/dark.
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
