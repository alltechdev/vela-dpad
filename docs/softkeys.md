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
  - `navGuardDp = 0` - DISABLE Yapchik's nav-bar guard. Vela's window theme is `android:Theme.Material`
    (a FRAMEWORK theme), so with a nav-bar-hide policy Yapchik would grow the bar by the device's
    probable navbar height to clear vendor bottom-strips. Vela draws its own edge-to-edge Compose UI
    (no strip) and already reserves the bar via autoInsetContent, so that was phantom height - it made
    the bar look DOUBLE-tall on a device with no real nav bar (tester report, fixed + confirmed).
  - A single-ink `style` that FOLLOWS the app theme (dark bar in dark, light in light). Yapchik
    colours the bar at construction, so `MapSoftkeys` REBUILDS it - but ONLY when the theme actually
    flipped (tracked via a remembered `lastDark`); a plain context switch re-`set()`s in place. An
    unconditional rebuild churned the bar view on every screen change and, under the nav-bar-hide
    policy, briefly revealed + re-hid the system bar = a visible flash (tester report, fixed).
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

  Listed in the `when`'s own PRECEDENCE ORDER - the first matching branch wins, which is why e.g.
  `arrived` has to sit above `navigating` and `routePreview`.

  | # | Surface (condition) | LEFT | RIGHT |
  |---|---|---|---|
  | 1 | Search overlay (`searchOpen`) | - | - (binds nothing, so NO bar) |
  | 2 | Choose-on-map (`pickOnMap != null`) | Cancel | Set start / stop |
  | 3 | Street View (`streetView != null \|\| streetViewLoading`) | Close | - |
  | 4 | Arrival (`arrived`) | - | Done |
  | 5 | Faster route offered (`navigating && fasterRoute != null`) | No | Switch |
  | 6 | Turn-by-turn nav (`navigating`) | Options (Mute/Unmute · Steps · Search along route · Recenter · End) | Zoom mode |
  | 7 | Place sheet (`placeSheetUp`) | Options (all secondary actions) | Directions |
  | 8 | Turn list (`showSteps`) | Close | Start |
  | 9 | Route preview (`routePreview`) | Steps | Start |
  | 10 | Resume-nav prompt (`resumeNavLabel != null`) | Dismiss | Resume |
  | 11 | Update offered (`updateInfo != null`) | Not now | Update |
  | 12 | Bare map (`else`) | Options (Move map/Zoom · Recenter · Search this area* · Layers · Park · Settings) | Search |

  \* "Search this area" appears only while `showSearchThisArea` is set, matching the on-screen button
  it replaces. Rows 3, 4, 8, 10 and 11 were added in the round-2 pass (#76), and row 5 in #79; before it, rows 3/4/9 did
  not exist (Street View and arrival INHERITED the place-sheet / route-preview keys, which is what made
  the bar offer "Directions" over a panorama and "Start" at the place you had just arrived at), and
  row 8's LEFT was `null` - a dead labelled key. Row 5 is a TRANSIENT that deliberately
  outranks navigation: its two on-screen buttons sit in a Row, and LEFT/RIGHT are keys rather than
  focus traversal, so a keypad driver could reach only one of them (#79). Options and Zoom return the
  moment the offer is answered or expires.

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
  Search. The Options menu (bottom-left bordered VelaMenu) holds Recenter / Layers / Park / Settings
  plus ONE adaptive map-motion item that reflects the mode you're in (tester's model - you never see the mode
  you're already in):
  - Bare map -> **Move map**: engages + focuses the map so the D-pad PANS it, with a top hint.
  - Moving (engaged) -> **Zoom**: enters a mode where the D-pad LEFT/RIGHT zoom out/in, own hint.
  - Zooming -> **Stop zoom**: drops back to moving.

  So the modes NEST - bare map -> Move map -> Zoom - and BACK peels one level at a time (zoom ->
  move-map -> bare map); the single map BackHandler owns that. `moveMapArm` / `mapZoomMode` drive the
  engage; `mapEngaged` is the existing pan state. **Search** opens the search field focused via a new
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

### Bare-map declutter (tester @SILB, #76)

On a keypad phone the bare map also sheds the on-screen **search bar**, the **Layers** button and the
**Park** FAB, the same way the zoom buttons already moved:

- **RIGHT soft key opens Search.** The collapsed bar is gone; the overlay it opens still carries the
  mic and the Settings gear, so nothing is lost, it just moves behind a key.
- **Layers and Park join the bare-map Options menu.** Park keeps its full hub there (Save parking
  spot with no spot; Parked car -> Find my car / Move / Earlier / Clear once set).
- **The category chips ride up** flush under the status bar to reclaim the search bar's slot
  (top overlay padding drops 12dp -> 4dp while decluttered).
- The bare-map **update card** drives from the bar too: LEFT = Not now, RIGHT = Update.

All of it gates on `VelaSoftkeys.isActive()` (the bar actually being shown), NOT on D-pad mode, so a
hybrid touch+D-pad phone with no bar keeps every on-screen control.

**The trap this refactor sets, twice:** dropping an on-screen button also drops anything declared
INSIDE that button's block. The Park hub + history sheet lived inside the Park FAB, and the Layers
panel lived inside the Layers button. Gate the button off and the panel is never composed, so the
Options entry meant to replace it sets state nobody reads and the feature goes UNREACHABLE while the
screenshots still look correct. The Layers case shipped and was found by a tester on a Kyocera DuraXe
e4830. Both panels are now siblings of their (gated) buttons, each in its own `TopEnd`/`BottomEnd`
box so the touch dropdown still anchors correctly. Before gating a control, grep what its block
contains.

It nearly bit a third time: hiding the **locate FAB** meant adding `!softkeyBarShown` to the block
it sits in - and the parking hub menu + history sheet are hoisted *inside that same block*. Gating
the block would have taken Options -> Park down exactly like Layers. The gate went on the FAB alone.

### Everywhere else the same pass reached

Same rule throughout: a control only goes away when its action is already on a soft key, on hardware
BACK, or on an Options menu. Nothing lost, screen gained.

| Surface | Dropped under soft-keys | Where the action lives now |
| --- | --- | --- |
| Bare map | locate FAB, "Search this area" | Options -> Recenter (which re-arms free-drive follow exactly as the FAB tap did), Options -> Search this area |
| Bare map | resume-nav card's Dismiss/Resume buttons | LEFT = Dismiss, RIGHT = Resume |
| Route preview | Start + Steps buttons, the top card's back arrow | RIGHT = Start, LEFT = Steps (already bound), BACK closes |
| Turn list | close X | LEFT = Close - **it was `null` before**, a dead labelled key on the one surface still drawing its own Close |
| Choose on map | Cancel X + "Set start/stop" button | LEFT = Cancel, RIGHT = Set (and BACK / OK-on-crosshair, as before) |
| In-nav | Mute / Steps / End buttons, search + recenter FABs | nav Options: Mute, Steps, **Search along route** (new), Recenter, End |

The in-nav card keeps only the ETA - which also un-breaks the headline, since the button row it used
to fight for width forced "14 min" into an ellipsis.

### Two bugs this audit turned up (not clutter)

- **Arrival had no soft-key branch at all.** `arrived` is set with `navigating` already false, so the
  card fell through to the route-preview branch and the bar offered **Start** - a drive to the place
  you had just reached. Worse, BACK routed to `clearRoute()`, which does not clear `arrived`, so the
  card could not be dismissed. Now: RIGHT = Done, and BACK calls `finishNav()`.
- **Street View inherited the place sheet's keys.** `placeSheetUp` stays true under the panorama (the
  sheet is merely not composed), so the bar read "Options | Directions": LEFT set state nothing
  rendered, and RIGHT started routing from behind the pano. Now LEFT = Close, RIGHT unbound.

### Focus-ring polish (tester @SILB, x320)

- **The ring must hug the control, not Material's touch target.** `minimumInteractiveComponentSize()`
  pads a chip's layout to 48dp while it draws at 32dp (buttons: 40dp), so a ring on the control's own
  modifier measured the inflated box and floated clear of it. `DpadRingBox` / the `VelaSwitch` pattern
  puts the ring on a parent pinned to the real height - `hasFocus` still propagates from the child,
  which keeps its full touch target by overflowing the shorter, non-clipping box.
- **Match the shape, too:** the category chips are `CircleShape` pills but were ringed with an 8dp
  rounded rect.
- **One focus signal, not two.** `dpadHighlight(...).clickable(...)` drew Material's grey focus state
  layer *and* the orange ring on the same row. `dpadClickable` drops the indication while input is
  key-driven, so touch keeps its press ripple.
- **Ring the toggle, not the row.** A `VelaMenu` toggle row is one focus stop (the row), so the ring
  wrapped the whole option while Settings hugged the switch pill. The row now tracks focus and hands
  it to a ring around the switch, which is also read-only there (`onCheckedChange = null`) so it is
  not a second focus stop inside a row that is already one.

## Gotchas (hard-won; read before touching this)

0. **THE BAR MUST NEVER TAKE D-PAD FOCUS.** `SoftkeyBar` sets `isClickable = true` so stray touches do
   not fall through to the views underneath - and since API 26 a clickable View with the default
   `focusable="auto"` resolves to FOCUSABLE. The bar was therefore a focus target: walking DOWN past
   the last control moved focus off the content and painted the framework's focus plate across the
   whole bar (tester, 2026-07-20). It is a dead end - the bar is operated by the PHYSICAL soft keys,
   so OK does nothing there and the user has to blindly walk back up. It also masqueraded as the
   thing being hunted: `ring_walk.sh` reported nine "controls with no ring" on the place sheet, and
   every one was really focus sitting in the bar. Fixed in `SoftkeyBar.init` with `isFocusable=false`
   + `FOCUS_BLOCK_DESCENDANTS` + `defaultFocusHighlightEnabled=false`. **If you add a view to the bar,
   it inherits the block - do not re-enable focus on it.**

1. **PROGRAMMATIC FOCUS DOES NOT LAND WHILE THE BAR IS ACTIVE.** Unresolved as of 2026-07-20; tracked as **issue #77**, which carries the full instrumentation record and the five disproven hypotheses. A
   `FocusRequester.requestFocus()` issued on open returns WITHOUT THROWING and never takes; the first
   real KEY PRESS then places focus normally. With `Yapchik.mode = OFF` the same code focuses fine.
   Consequence: any surface relying on auto-focus-on-open (the search overlay, Settings) opens with
   nothing highlighted, costing one keypress. Surfaces whose focus arrives via a keypress (the bare
   map) are unaffected. **Do not "fix" this by retrying harder** - measured, `requestFocus` is called
   40 times over 2.6s and never lands. Five hypotheses have been disproven on device: single-shot
   requestFocus; the `VelaMenu` raw-Dialog dismissal race (fails identically with no dialog involved);
   re-arming the effect on bar-state change; the ComposeView losing ANDROID VIEW focus (real - with the
   bar up `rootView.findFocus()` is NONE - but restoring it does NOT restore Compose focus); and the
   bar TEARDOWN being the trigger (binding a key so the bar stays up does not help). Prime remaining
   suspect: `Yapchik.install` wrapping the Activity `Window.Callback`, leaving Compose's focus owner
   uninitialised until a real key event. **Corollary: do NOT add a bar to the Welcome screen** - it
   auto-focuses "Get started" correctly today precisely because it has no bar.

2. **`tests/dpad/lib.sh focused()` DOES NOT RELIABLY REPORT COMPOSE FOCUS.** It reads uiautomator's
   `focused="true"`, and Compose nodes can hold focus - with a visible ring - while it returns empty
   (reproduced on main's search bar). Several hours were lost to conclusions drawn from it. **Confirm
   focus VISUALLY from a screenshot.** The dynamic auditor's focus assertions inherit this weakness;
   its reachability checks (matching on text) are unaffected.

3. **A `VelaMenu` does NOT suppress the bar.** It is a raw `Dialog`, not a `VelaDialog`, so
   `modalDepth` never moves and the map's bar stays live behind the parking hub / layers panel /
   Options popups. Deliberate, but it means the hardware keys still drive the surface UNDERNEATH.

4. **`placeSheetUp` stays true when the sheet is merely not composed.** Street View sits over it, so
   before row 3 existed the bar showed the place sheet's keys and RIGHT started routing from behind
   the panorama. If you add a full-screen surface over the sheet, give it its own branch.

5. **`arrived` is set with `navigating` already false.** Without its own branch it falls through to
   `routePreview`. `clearRoute()` does not clear `arrived` either, so BACK could not dismiss the card
   until that case was added to the BackHandler.

6. **The restricted flavor ships no Street View.** `full_coverage.sh` marks those two surfaces `n/a`
   for restricted rather than MISSED; without that, the restricted legs can never score FULLY COVERED.

7. **The harness's touch (`SOFTKEYS=off`) legs cannot drive first-run or directions** (issue #78). Both phases
   advance the UI with D-pad OK, which correctly does nothing when nothing is focused in touch. The app
   is fine there (verified by hand); the legs simply give no verdict for those surfaces.

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
  - Full coverage matrix, re-run for the round-2 pass (2026-07-20) on the FOUR committed geometries:
    480x854 @320 and @225, 240x320 @160 and @120. All four standard legs **27/27**; both restricted
    legs **25/25** (Street View is n/a in that flavor). That graduated sonim-x320-225 and
    kyocera-duraxe-e4830 from PARTIAL to FULLY COVERED.
  - The soft-keys-OFF (touch) legs are NOT a clean pass and must not be quoted as one: they miss the
    four first-run dialogs plus directions/route-steps, because those harness phases advance the UI
    with D-pad OK, which correctly does nothing when nothing is focused in touch. The app itself was
    verified by hand in touch (scroll reveals "Get started", tapping it advances). Harness gap, not an
    app regression - see Gotchas 7.

## Rollout status

Most of this is DONE (marked below); what remains is hardware-blocked or optional. Each item kept the
hard rules: touch byte-identical, gated on the D-pad detector, both geometries x both flavors + eyeball.

1. **Contextual surfaces. DONE.** Every map context is wired:
   - **Bare map** -> Options / Search (Options = Recenter / Layers / Settings + one adaptive item:
     Move map when idle, Zoom once moving, Stop zoom while zooming; the modes nest, BACK peels one).
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
