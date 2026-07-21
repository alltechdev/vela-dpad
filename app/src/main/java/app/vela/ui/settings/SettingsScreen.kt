package app.vela.ui.settings
import android.content.Intent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import app.vela.ui.VelaSwitch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import app.vela.core.feedback.Haptics
import app.vela.core.model.TravelMode
import app.vela.offline.OfflineMaps
import org.maplibre.android.offline.OfflineRegion
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vela.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.ui.Onboarding
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.Units
import androidx.compose.foundation.layout.Arrangement
import app.vela.core.voice.PiperCatalog
import app.vela.core.voice.PiperVoice
import app.vela.core.voice.VoiceEngine
import app.vela.ui.map.MapUiState
import app.vela.ui.map.MapViewModel
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.ThemeMode
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.DpadRingBox
import app.vela.ui.dpadClickable
import app.vela.ui.dpadFieldEscape
import app.vela.ui.dpadSwallowHorizontal
import app.vela.ui.dpadRowSibling
import app.vela.ui.dpadAutoFocus
import app.vela.ui.rememberDpadFocusKeeper // focus handoff for swap-in controls (docs/dpad.md)
import app.vela.ui.DpadFocusHandoff
import app.vela.ui.dpadFocusKept
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape as DpadShape

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MapViewModel, onBack: () -> Unit, openOffline: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // System back should return to the map, not fall through and exit the app.
    BackHandler(onBack = onBack)
    // When arriving from the onboarding "set up offline" prompt, open the Offline section expanded and
    // scroll straight to it (it sits below the fold). We measure the section's on-screen Y and the scroll
    // viewport's top, then scroll by the difference. The effect re-runs whenever the measured Y changes,
    // so it SELF-CORRECTS as the layout settles (the earlier one-shot latch scrolled to a stale position
    // and landed mid-page); it converges once the section sits at the viewport top (the abs guard stops it).
    val scrollState = rememberScrollState()
    var viewportTopY by remember { mutableStateOf<Float?>(null) }
    var offlineSectionY by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(openOffline, viewportTopY, offlineSectionY) {
        if (openOffline) {
            val top = viewportTopY
            val sec = offlineSectionY
            if (top != null && sec != null) {
                val target = (scrollState.value + (sec - top)).toInt().coerceIn(0, scrollState.maxValue)
                if (kotlin.math.abs(scrollState.value - target) > 4) scrollState.animateScrollTo(target)
            }
        }
    }
    // D-pad-first (docs/dpad.md): Settings must open already focused - land on the back
    // button (top of screen) so the first arrow press enters the content, never a wasted
    // "wake up focus" press. Uses the ROBUST dpadAutoFocus() (confirms focus actually LANDED
    // via onFocusEvent, re-requesting up to ~2s) not the weak rememberDpadAutoFocus() (which
    // bails the instant requestFocus() doesn't throw, even when focus never landed): device-
    // verified that the weak version left Back UNfocused on open (the first arrow was wasted
    // establishing focus instead of navigating), the robust one lands it. The requester is
    // caller-owned because the top row routes its UP back to Back via
    // settingsAutoFocus.requestFocus() (below). No-op under touch.
    val settingsAutoFocus = remember { FocusRequester() }
    val topRowFocus = remember { FocusRequester() }        // first content row (Back routes its DOWN here)
    var atTopItem by remember { mutableStateOf(false) }   // top content row focused? (routes its UP to Back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // DOWN from Back must ENTER the content list. Compose's directional focus
                        // search can't cross from the TopAppBar into the scrolling Column and CLEARS
                        // focus instead (device-verified at 240x320: after one DOWN nothing was
                        // focused) - the mirror of the UP-from-top trap. So route DOWN straight to the
                        // first content row via requestFocus (proven to land; never moveFocus, which
                        // clears at a container edge).
                        modifier = Modifier
                            .dpadAutoFocus(settingsAutoFocus)
                            .onKeyEvent { ev ->
                                if (ev.key == Key.DirectionDown && ev.type == KeyEventType.KeyDown) {
                                    runCatching { topRowFocus.requestFocus() }; true
                                } else false
                            }
                            .dpadSwallowHorizontal(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                // D-pad (docs/dpad.md): Settings is a VERTICAL list - swallow bare LEFT/RIGHT so a
                // no-target horizontal move can't clear focus. The vibrate FilterChips row (a real
                // horizontal row) handles its own LEFT/RIGHT first, so this never runs for it.
                .dpadSwallowHorizontal()
                // The back button lives in the TopAppBar, a SEPARATE container Compose's directional
                // UP can't reach, so an UP from the TOP row cleared focus (auditor-found, 1/49). When
                // the top row holds focus (atTopItem) route its UP straight to Back via requestFocus
                // (proven to land - it's how opening auto-focuses Back); NEVER moveFocus, which itself
                // clears at the top edge. Every other UP falls through to normal list navigation.
                .onKeyEvent { ev ->
                    if (ev.key == Key.DirectionUp && atTopItem) {
                        if (ev.type == KeyEventType.KeyDown) runCatching { settingsAutoFocus.requestFocus() }
                        true
                    } else false
                }
                .onGloballyPositioned { viewportTopY = it.positionInRoot().y }
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            SelectableRow(
                label = stringResource(R.string.settings_follow_system),
                selected = AppTheme.mode.value == ThemeMode.SYSTEM,
                onClick = { AppTheme.set(context, ThemeMode.SYSTEM) },
                // The top focusable row: Back routes its DOWN here (focusRequester), and we track when
                // it holds focus so the Column routes its UP back to Back.
                modifier = Modifier.focusRequester(topRowFocus).onFocusEvent { atTopItem = it.isFocused },
            )
            SelectableRow(
                label = stringResource(R.string.settings_theme_light),
                selected = AppTheme.mode.value == ThemeMode.LIGHT,
                onClick = { AppTheme.set(context, ThemeMode.LIGHT) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_theme_dark),
                selected = AppTheme.mode.value == ThemeMode.DARK,
                onClick = { AppTheme.set(context, ThemeMode.DARK) },
            )
            Hint(stringResource(R.string.settings_appearance_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_map_style))
            MapStyle.values().forEach { style ->
                SelectableRow(
                    label = style.label,
                    selected = state.styleName == style.label,
                    onClick = { vm.setStyle(style) },
                )
            }
            Hint(stringResource(R.string.settings_map_style_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_units))
            SelectableRow(
                label = stringResource(R.string.settings_units_imperial),
                selected = Units.imperial.value,
                onClick = { Units.set(context, true) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_units_metric),
                selected = !Units.imperial.value,
                onClick = { Units.set(context, false) },
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_language))
            SelectableRow(
                label = stringResource(R.string.settings_follow_system),
                selected = app.vela.ui.AppLocale.language.value.isBlank(),
                onClick = { app.vela.ui.AppLocale.set(context, "") },
            )
            app.vela.ui.AppLocale.SUPPORTED.forEach { code ->
                SelectableRow(
                    label = app.vela.ui.AppLocale.endonym(code),
                    selected = app.vela.ui.AppLocale.language.value == code,
                    onClick = { app.vela.ui.AppLocale.set(context, code) },
                )
            }
            Hint(stringResource(R.string.settings_language_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_map))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_live_traffic), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.Traffic.on.value,
                    onCheckedChange = { app.vela.ui.Traffic.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_live_traffic_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_transit_layer), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.TransitLayer.on.value,
                    onCheckedChange = { app.vela.ui.TransitLayer.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_transit_layer_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_topography), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.Topography.on.value,
                    onCheckedChange = { app.vela.ui.Topography.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_topography_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_layers_button), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.LayersButton.on.value,
                    onCheckedChange = { app.vela.ui.LayersButton.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_layers_button_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_flock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.Flock.on.value,
                    onCheckedChange = { app.vela.ui.Flock.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_flock_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_flock_route_alert), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.FlockRouteAlert.on.value,
                    onCheckedChange = { app.vela.ui.FlockRouteAlert.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_flock_route_alert_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_buildings_3d), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.Buildings3d.on.value,
                    onCheckedChange = { app.vela.ui.Buildings3d.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_buildings_3d_hint))

            // Restricted flavor: all five Place pages toggles are hard-locked (Restricted.kt), so the section is hidden entirely.
            if (!app.vela.ui.RESTRICTED_BUILD) {
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_place_pages))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_show_reviews), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.ShowReviews.on.value,
                    onCheckedChange = { app.vela.ui.ShowReviews.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_show_reviews_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_read_all_reviews), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.LiveReviews.on.value,
                    onCheckedChange = { app.vela.ui.LiveReviews.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_read_all_reviews_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_load_photos), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.LoadPhotos.on.value,
                    onCheckedChange = { app.vela.ui.LoadPhotos.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_load_photos_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_hide_adult), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.HideAdult.on.value,
                    onCheckedChange = { app.vela.ui.HideAdult.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_hide_adult_hint))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_hide_external_links), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.HideExternalLinks.on.value,
                    onCheckedChange = { app.vela.ui.HideExternalLinks.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_hide_external_links_hint))
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_navigation))
            val prefs = remember { context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE) }

            // Hardware softkeys (keypad phones only). Shown just when the UI is key-driven, so a pure
            // touch user never sees an irrelevant toggle. On -> AUTO (bar shows on keypad, hidden on
            // touch); Off disables it and restores the on-screen +/-.
            if (app.vela.ui.rememberDpadMode()) {
                var softkeysOn by remember { mutableStateOf(app.vela.ui.softkey.VelaSoftkeys.isEnabled()) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_softkeys), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    VelaSwitch(
                        checked = softkeysOn,
                        onCheckedChange = {
                            softkeysOn = it
                            app.vela.ui.softkey.VelaSoftkeys.setEnabled(it)
                        },
                    )
                }
                Hint(stringResource(R.string.settings_softkeys_hint))
                if (softkeysOn) {
                    val activity = context as? android.app.Activity
                    Text(
                        stringResource(R.string.settings_softkeys_calibrate),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadHighlight(DpadShape(6.dp))
                            .dpadClickable(enabled = activity != null) { activity?.let { app.vela.ui.softkey.VelaSoftkeys.calibrate(it) } }
                            .padding(vertical = 8.dp),
                    )
                    Hint(stringResource(R.string.settings_softkeys_calibrate_hint))
                }
            }

            var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_nav", true)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_keep_screen_on), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = keepAwake,
                    onCheckedChange = {
                        keepAwake = it
                        prefs.edit().putBoolean("keep_screen_on_nav", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_keep_screen_on_hint))

            var trafficLights by remember { mutableStateOf(prefs.getBoolean("nav_traffic_lights", false)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_traffic_lights), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = trafficLights,
                    onCheckedChange = {
                        trafficLights = it
                        prefs.edit().putBoolean("nav_traffic_lights", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_traffic_lights_hint))
            Text(stringResource(R.string.settings_vibrate_on_turns), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            // One chip per travel mode (was four stacked switch rows - a lot of vertical space
            // for a setting most people touch once). Selected = that mode vibrates at turns.
            // FlowRow, not a scrollable Row: on a narrow screen / low density the fourth chip
            // rendered partially cut with no hint that the row scrolls (user report, 2026-07-16) -
            // wrapping onto a second line keeps every chip fully visible instead.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The ROOT swallows bare LEFT/RIGHT (see above), so this horizontal row drives its
                // OWN LEFT/RIGHT via FocusRequesters - requestFocus (not moveFocus) never clears at
                // the ends, and consuming the key stops it reaching the root swallow.
                val chipFocus = remember { List(4) { FocusRequester() } }
                listOf(
                    TravelMode.DRIVE to stringResource(R.string.settings_mode_driving),
                    TravelMode.WALK to stringResource(R.string.settings_mode_walking),
                    TravelMode.BICYCLE to stringResource(R.string.settings_mode_cycling),
                    TravelMode.TRANSIT to stringResource(R.string.settings_mode_transit),
                ).forEachIndexed { i, (mode, label) ->
                    var on by remember(mode) {
                        val default = if (!prefs.getBoolean(Haptics.KEY, true)) false else Haptics.defaultFor(mode)
                        mutableStateOf(prefs.getBoolean(Haptics.keyFor(mode), default))
                    }
                    FilterChip(
                        selected = on,
                        onClick = {
                            on = !on
                            prefs.edit().putBoolean(Haptics.keyFor(mode), on).apply()
                        },
                        label = { Text(label) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier
                            .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                            .focusRequester(chipFocus[i])
                            .onKeyEvent { ev ->
                                if (ev.key == Key.DirectionRight || ev.key == Key.DirectionLeft) {
                                    if (ev.type == KeyEventType.KeyDown) {
                                        if (ev.key == Key.DirectionRight && i < chipFocus.lastIndex) chipFocus[i + 1].requestFocus()
                                        if (ev.key == Key.DirectionLeft && i > 0) chipFocus[i - 1].requestFocus()
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
            }
            Hint(stringResource(R.string.settings_vibrate_hint))

            var demoDrive by remember { mutableStateOf(prefs.getBoolean("demo_drive", false)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_demo_drive), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = demoDrive,
                    onCheckedChange = {
                        demoDrive = it
                        prefs.edit().putBoolean("demo_drive", it).apply()
                    },
                )
            }
            Hint(stringResource(R.string.settings_demo_drive_hint))

            // Simulated location - pretend to be at the current map centre (for demos / screenshots
            // without leaking where you actually are). Reactive holder so the switch reflects state.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_sim_location), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.SimLocation.on,
                    onCheckedChange = { on -> if (on) vm.simulateLocationHere() else vm.stopSimulateLocation() },
                )
            }
            Hint(stringResource(R.string.settings_sim_location_hint))

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_voice))
            // Vela's own on-device neural voices - offer a one-tap download for whichever isn't
            // present yet; once downloaded each shows in the engine list below (selectable). No
            // standalone TTS app needed. Kokoro = premium/slower, Piper = fast.
            // A download in flight shows a compact progress line here too, so it's visible even when the
            // Voice library (below) is collapsed. The per-voice controls live in the library.
            state.voiceDownloadingId?.let { id ->
                val nm = vm.voiceCatalog().firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_fallback_name)
                val pct = state.kokoroDownloadPct ?: 0f
                Text(stringResource(R.string.settings_voice_downloading, nm, (pct * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            } ?: Spacer(Modifier.height(4.dp))
            // Enumerate TTS engines OFF the main thread. PackageManager.queryIntentServices + the
            // per-engine loadLabel is a binder IPC that took >5 s on the flip phone and ANR'd the UI
            // when run in composition (input-dispatch timeout). Load async (cached in VoiceGuide);
            // render nothing until ready so there's no flash of the "no engines" hint.
            val engines by produceState<List<VoiceEngine>?>(null, state.voiceDownloadingId) {
                value = withContext(Dispatchers.IO) { vm.voiceEngines() }
            }
            val engineList = engines
            if (engineList == null) {
                // still loading - render nothing
            } else if (engineList.isEmpty()) {
                Hint(stringResource(R.string.settings_voice_none_hint))
            } else {
                engineList.forEach { e ->
                    SelectableRow(
                        label = e.label,
                        selected = state.selectedEngine?.packageName == e.packageName,
                        onClick = { vm.setVoiceEngine(e) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // D-pad L/R across the pair (issue #24 - Test voice unreachable). See dpadRowSibling.
                    val voiceTestFocus = remember { List(2) { FocusRequester() } }
                    OutlinedButton(modifier = Modifier.dpadRowSibling(voiceTestFocus, 0), onClick = { vm.testVoice() }) { Text(stringResource(R.string.settings_voice_test)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        modifier = Modifier.dpadRowSibling(voiceTestFocus, 1),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent("com.android.settings.TTS_SETTINGS")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.settings_voice_system_settings)) }
                }
                Hint(stringResource(R.string.settings_voice_test_hint))
            }

            // Voice library - browse, download, switch between and remove Vela's neural voices (Piper).
            // Auto-expanded when nothing is installed so the download path is obvious.
            // Auto-expand when nothing is installed so the download path is obvious - EXCEPT when we
            // arrived to set up offline, where a big open voice list between the top and the Offline
            // section would push it around and fight the scroll-into-view.
            var voiceLibExpanded by remember { mutableStateOf(state.installedVoiceIds.isEmpty() && !openOffline) }
            CollapsibleSectionTitle(stringResource(R.string.settings_voice_library), voiceLibExpanded) { voiceLibExpanded = !voiceLibExpanded }
            if (voiceLibExpanded) VoiceLibrary(vm, state)

            if (engineList?.isNotEmpty() == true) {
                // Speed + the niche bits (playground, the multi-speaker variant picker) - most people never
                // touch these, so tuck them behind a collapsible header (collapsed by default).
                var voiceAdvExpanded by remember { mutableStateOf(false) }
                CollapsibleSectionTitle(stringResource(R.string.settings_voice_advanced), voiceAdvExpanded) { voiceAdvExpanded = !voiceAdvExpanded }
                if (voiceAdvExpanded) {
                // Playground: hear the selected voice on any text (or a nav-style sample).
                Spacer(Modifier.height(12.dp))
                var tryText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    modifier = Modifier.fillMaxWidth().dpadFieldEscape(),
                    label = { Text(stringResource(R.string.settings_voice_try_label)) },
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                val navSampleText = stringResource(R.string.settings_voice_nav_sample_text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // D-pad L/R across the pair (issue #24). See dpadRowSibling.
                    val playFocus = remember { List(2) { FocusRequester() } }
                    OutlinedButton(modifier = Modifier.dpadRowSibling(playFocus, 0), onClick = { vm.speakText(tryText) }, enabled = tryText.isNotBlank()) { Text(stringResource(R.string.settings_voice_speak)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(modifier = Modifier.dpadRowSibling(playFocus, 1), onClick = {
                        vm.speakText(navSampleText)
                    }) { Text(stringResource(R.string.settings_voice_nav_sample)) }
                }
                // Speed applies to whichever voice is selected (neural + system TTS).
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_voice_speed, "%.2fx".format(state.voiceSpeed)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val speedFocus = remember { List(2) { FocusRequester() } }  // D-pad L/R across -/+ (issue #24)
                    OutlinedButton(modifier = Modifier.dpadRowSibling(speedFocus, 0), onClick = { vm.setVoiceSpeed(-0.1f) }) { Text("−") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(modifier = Modifier.dpadRowSibling(speedFocus, 1), onClick = { vm.setVoiceSpeed(0.1f) }) { Text("+") }
                }
                Hint(stringResource(R.string.settings_voice_speed_hint))
                // Multi-speaker Vela voices (libritts_r=904, VCTK=109, Arctic=18) - let the user audition +
                // pick a variant. Hidden for single-speaker voices (lessac/hfc/…), where it's meaningless.
                if (state.selectedEngine?.packageName?.startsWith("vela.") == true && vm.voiceSpeakerCount() > 1) {
                    Spacer(Modifier.height(10.dp))
                    val cnt = vm.voiceSpeakerCount()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cnt > 0) stringResource(R.string.settings_voice_variant_of, state.voiceSpeaker, cnt)
                            else stringResource(R.string.settings_voice_variant, state.voiceSpeaker),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val speakerFocus = remember { List(2) { FocusRequester() } }  // D-pad L/R across ◀/▶ (issue #24)
                        OutlinedButton(modifier = Modifier.dpadRowSibling(speakerFocus, 0), onClick = { vm.stepSpeaker(-1) }) { Text("◀") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(modifier = Modifier.dpadRowSibling(speakerFocus, 1), onClick = { vm.stepSpeaker(1) }) { Text("▶") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Jump straight to a variant number (904 is a lot to step through).
                    var jump by remember { mutableStateOf("") }
                    val goToVariant = {
                        jump.trim().toIntOrNull()?.let { vm.setSpeaker(it) }
                        jump = ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = jump,
                            onValueChange = { s -> jump = s.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_voice_variant_field)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { goToVariant() }),
                            modifier = Modifier.width(150.dp).dpadFieldEscape(),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = goToVariant, enabled = jump.isNotBlank()) { Text(stringResource(R.string.settings_voice_variant_go)) }
                    }
                    Hint(stringResource(R.string.settings_voice_variant_hint))
                }
                } // end "Advanced voice options"
            }

            // --- Search (voice search) ------------------------------------------------------------
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_search))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_voice_search_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = app.vela.ui.VoiceSearch.enabled.value,
                    onCheckedChange = { app.vela.ui.VoiceSearch.set(context, it) },
                )
            }
            Hint(stringResource(R.string.settings_voice_search_hint))

            // On-device voice search (tier-1): download Vela Voice (Whisper) or remove it. Works with
            // no other app and uploads nothing; Auto uses it over a provider when it's installed.
            LaunchedEffect(Unit) { vm.refreshAsr() }
            Spacer(Modifier.height(8.dp))
            Hint(stringResource(R.string.settings_voice_search_model_hint, app.vela.voice.AsrModel.SIZE_MB))
            // D-pad: swap-in control like the voice/region rows - the keeper re-places focus
            // when Download becomes the progress readout and again when Remove appears.
            val asrKeeper = rememberDpadFocusKeeper()
            when {
                // QUEUED counts as busy here too. Gating this row on asrDownloadPct alone left a live
                // "Download (58 MB)" button through the whole voice download when both models were
                // picked in setup - and downloadAsrModel's own guard checks the same field, so the
                // tap sailed through and ran a SECOND download concurrently. Both stream through
                // KokoroInstaller's shared voice.download.tmp/voice.staging, so they overwrite each
                // other's archive and the first to finish deletes the other's staging mid-extract:
                // two failed installs. Closing the map mic button was not enough; this is the other
                // door into the same collision.
                state.asrDownloadPct != null || state.asrQueued -> {
                    val pct = state.asrDownloadPct ?: 0f
                    val queued = state.asrDownloadPct == null
                    DpadFocusHandoff(asrKeeper)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .dpadFocusKept(asrKeeper)
                            .dpadHighlight(RoundedCornerShape(8.dp))
                            .focusable(),
                    ) {
                        Text(
                            when {
                                queued -> stringResource(R.string.map_asr_waiting)
                                state.asrInstalling -> stringResource(R.string.settings_voice_search_installing)
                                else -> stringResource(R.string.settings_voice_search_downloading, (pct * 100).toInt())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Indeterminate while queued: nothing has started, so a 0% bar would read as stuck.
                        if (queued) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                    }
                }
                state.asrInstalled -> {
                    DpadFocusHandoff(asrKeeper)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_voice_search_model), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.deleteAsrModel() }, modifier = Modifier.dpadFocusKept(asrKeeper)) { Text(stringResource(R.string.settings_voice_search_remove)) }
                    }
                }
                else -> {
                    DpadFocusHandoff(asrKeeper)
                    OutlinedButton(onClick = { vm.downloadAsrModel() }, modifier = Modifier.dpadFocusKept(asrKeeper)) {
                        Text(stringResource(R.string.settings_voice_search_download, app.vela.voice.AsrModel.SIZE_MB))
                    }
                }
            }
            LaunchedEffect(state.asrDownloadPct != null, state.asrQueued, state.asrInstalled) { asrKeeper.retarget() }
            // The engine picker only matters when there's actually a choice (the model AND a voice app,
            // or two voice apps): "Vela Voice" = AUTO (the model wins, provider as graceful fallback),
            // "Android default" = the implicit intent Android routes, or each installed app pinned by name
            // so Android never interjects its own chooser. Enumerated off the main thread (a binder query
            // + per-app label load, the same class of call as the TTS engine list).
            val voiceProviders by produceState(initialValue = emptyList<app.vela.ui.VoiceSearch.Provider>()) {
                value = withContext(Dispatchers.IO) { app.vela.ui.VoiceSearch.providers(context) }
            }
            if ((state.asrInstalled && voiceProviders.isNotEmpty()) || voiceProviders.size > 1) {
                Spacer(Modifier.height(12.dp))
                SubHead(stringResource(R.string.settings_voice_search_engine_title))
                val eng = app.vela.ui.VoiceSearch.engine.value
                val savedPick = app.vela.ui.VoiceSearch.provider.value
                val savedValid = voiceProviders.any { it.component.flattenToString() == savedPick }
                if (state.asrInstalled) {
                    SelectableRow(stringResource(R.string.settings_voice_search_engine_auto), eng != app.vela.ui.VoiceSearch.Engine.SYSTEM, onClick = { app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.AUTO) })
                }
                if (voiceProviders.isNotEmpty()) {
                    SelectableRow(
                        stringResource(R.string.settings_voice_search_engine_default),
                        eng == app.vela.ui.VoiceSearch.Engine.SYSTEM && !savedValid,
                        onClick = {
                            app.vela.ui.VoiceSearch.clearProvider(context)
                            app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.SYSTEM)
                        },
                    )
                }
                voiceProviders.forEach { p ->
                    SelectableRow(
                        p.label,
                        eng == app.vela.ui.VoiceSearch.Engine.SYSTEM && savedValid && p.component.flattenToString() == savedPick,
                        onClick = {
                            app.vela.ui.VoiceSearch.setProvider(context, p.component)
                            app.vela.ui.VoiceSearch.setEngine(context, app.vela.ui.VoiceSearch.Engine.SYSTEM)
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp).onGloballyPositioned { offlineSectionY = it.positionInRoot().y })
            // Collapsed by default - the routing-region list can be long, so don't make the user
            // scroll past all of it to reach the sections below. Opens expanded when the onboarding
            // offline prompt sent us here.
            var offlineExpanded by remember { mutableStateOf(openOffline) }
            CollapsibleSectionTitle(stringResource(R.string.settings_offline), offlineExpanded) { offlineExpanded = !offlineExpanded }
            if (offlineExpanded) {
            Hint(stringResource(R.string.settings_offline_hint))
            SubHead(stringResource(R.string.settings_offline_map_area))
            var regions by remember { mutableStateOf<List<OfflineRegion>>(emptyList()) }
            LaunchedEffect(Unit) { OfflineMaps.list(context) { regions = it } }
            // -1 = not loaded yet; used only to decide the "saved areas predate offline addresses" nudge below.
            var offlineAddrCount by remember { mutableStateOf(-1) }
            LaunchedEffect(Unit) { vm.offlineAddressCount { offlineAddrCount = it } }
            OutlinedButton(
                modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                onClick = {
                    vm.downloadViewport()
                    onBack() // back to the map so the user sees the download progress
                },
                enabled = vm.hasViewport(),
            ) { Text(stringResource(R.string.settings_offline_download_viewport)) }
            Hint(stringResource(R.string.settings_offline_download_viewport_hint))
            if (regions.isEmpty()) {
                Hint(stringResource(R.string.settings_offline_no_areas))
            } else {
                regions.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            OfflineMaps.nameOf(r),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { OfflineMaps.delete(r) { OfflineMaps.list(context) { regions = it } } }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_offline_delete_area))
                        }
                    }
                }
            }
            // Nudge for areas saved before the offline address geocoder existed: they have tiles + POIs but no
            // address data, so offline address search/routing would silently miss. One tap re-fetches the
            // address index for every saved area. Only shown when there ARE areas and the index is still empty.
            if (regions.isNotEmpty() && offlineAddrCount == 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.settings_offline_addresses_missing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                vm.refreshOfflineDataForSavedAreas()
                                onBack() // back to the map to watch the per-area progress
                            },
                            modifier = Modifier
                                .dpadHighlight(androidx.compose.material3.ButtonDefaults.outlinedShape)
                                .padding(top = 8.dp),
                        ) { Text(stringResource(R.string.settings_offline_addresses_update)) }
                    }
                }
            }

            SubHead(stringResource(R.string.settings_routing_regions))
            LaunchedEffect(Unit) { vm.refreshRoutingRegions() }
            Hint(stringResource(R.string.settings_routing_regions_hint))
            if (state.routingRegions.isEmpty()) {
                Hint(stringResource(R.string.settings_routing_no_regions))
            } else {
                val loc = state.myLocation
                val covers = { r: app.vela.offline.RoutingRegion ->
                    loc != null && loc.lat in r.s..r.n && loc.lng in r.w..r.e
                }
                // The region you're IN = the SMALLEST bbox that contains you. Region boxes carry a Geofabrik
                // buffer that spills across borders (British Columbia's box dips into Sacramento), so "any box that
                // covers you" mislabels big neighbours - the smallest covering box is the specific one. Sort:
                // installed first (manage what you have), then that primary region, then everything by name.
                val primary = state.routingRegions.filter(covers)
                    .minByOrNull { (it.n - it.s) * (it.e - it.w) }
                val ordered = state.routingRegions.sortedWith(
                    compareByDescending<app.vela.offline.RoutingRegion> { it.id in state.routingInstalledIds }
                        .thenByDescending { it.id == primary?.id }
                        .thenBy { it.name },
                )
                // With a world-sized catalog, a name filter makes a region you're TRAVELLING to findable
                // without scrolling past a hundred others (the sort above handles where you are now).
                var routeFilter by remember { mutableStateOf("") }
                if (state.routingRegions.size > 8) {
                    OutlinedTextField(
                        value = routeFilter,
                        onValueChange = { routeFilter = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).dpadFieldEscape(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (routeFilter.isNotEmpty()) {
                                IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { routeFilter = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.settings_clear_filter))
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.settings_routing_filter_placeholder, state.routingRegions.size)) },
                    )
                }
                val shown = if (routeFilter.isBlank()) ordered
                    else ordered.filter { it.name.contains(routeFilter.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    Hint(stringResource(R.string.settings_routing_no_match, routeFilter.trim()))
                }
                shown.forEach { region ->
                    val installed = region.id in state.routingInstalledIds
                    val downloading = state.routingDownloadingId == region.id
                    val packDownloading = state.poiPackDownloadingId == region.id
                    val packInstalled = region.id in state.poiPackInstalledIds
                    // A fresher pack is published than the one installed → offer an in-place update
                    // (a small row-level delta when the manifest carries one, else a full re-download).
                    val packRegion = state.poiPackRegions.firstOrNull { it.id == region.id }
                    val updateAvailable = installed && packInstalled && packRegion != null &&
                        packRegion.rev > (state.poiPackInstalledRevs[region.id] ?: 0)
                    val here = region.id == primary?.id
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(region.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    downloading -> stringResource(R.string.settings_routing_downloading, state.routingDownloadPct)
                                    packDownloading -> stringResource(R.string.settings_routing_places_downloading, state.poiPackDownloadPct)
                                    updateAvailable -> stringResource(R.string.settings_routing_update_available)
                                    installed && packInstalled -> stringResource(R.string.settings_routing_installed_places)
                                    installed -> stringResource(R.string.settings_routing_installed)
                                    here -> stringResource(R.string.settings_routing_size_here, region.sizeMb)
                                    else -> stringResource(R.string.settings_routing_size, region.sizeMb)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if ((here && !installed && !downloading) || updateAvailable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // D-pad: same swap-in control as the voice rows (Download -> spinner ->
                        // Get places/Delete) - the keeper re-places focus on the new variant so
                        // the highlight doesn't teleport to the top of the page (user report).
                        val keeper = rememberDpadFocusKeeper()
                        when {
                            downloading || packDownloading -> {
                                DpadFocusHandoff(keeper)
                                CircularProgressIndicator(
                                    Modifier
                                        .size(20.dp)
                                        .dpadFocusKept(keeper)
                                        .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                                        .focusable(),
                                    strokeWidth = 2.dp,
                                )
                            }
                            updateAvailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                                DpadFocusHandoff(keeper)
                                OutlinedButton(
                                    onClick = { vm.downloadPoiPackFor(region, update = true) },
                                    enabled = state.routingDownloadingId == null && state.poiPackDownloadingId == null,
                                    modifier = Modifier.dpadFocusKept(keeper),
                                ) { Text(stringResource(R.string.settings_update_places)) }
                                IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                                }
                            }
                            // Installed before place packs existed (or its pack was skipped): offer just
                            // the pack, so offline search covers the region without a graph re-download.
                            installed && !packInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                                DpadFocusHandoff(keeper)
                                OutlinedButton(
                                    onClick = { vm.downloadPoiPackFor(region) },
                                    enabled = state.routingDownloadingId == null && state.poiPackDownloadingId == null,
                                    modifier = Modifier.dpadFocusKept(keeper),
                                ) { Text(stringResource(R.string.settings_get_places)) }
                                IconButton(onClick = { vm.deleteRoutingGraph(region.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                                }
                            }
                            installed -> {
                                DpadFocusHandoff(keeper)
                                IconButton(onClick = { vm.deleteRoutingGraph(region.id) }, modifier = Modifier.dpadFocusKept(keeper)) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_routing_remove))
                                }
                            }
                            else -> {
                                DpadFocusHandoff(keeper)
                                OutlinedButton(
                                    onClick = { vm.downloadRoutingGraph(region) },
                                    enabled = state.routingDownloadingId == null,
                                    modifier = Modifier.dpadFocusKept(keeper),
                                ) { Text(stringResource(R.string.settings_download)) }
                            }
                        }
                        LaunchedEffect(downloading, packDownloading, updateAvailable, installed, packInstalled) { keeper.retarget() }
                    }
                }
            }
            } // end if (offlineExpanded)

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_saved_places))
            Hint(stringResource(R.string.settings_saved_places_hint))
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val n = vm.importSavedFromUri(uri)
                    android.widget.Toast.makeText(
                        context,
                        if (n > 0) "Imported $n place${if (n == 1) "" else "s"}" else context.getString(R.string.settings_import_nothing),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // D-pad: the root Column swallows bare LEFT/RIGHT, so this button pair drives its OWN
                // L/R (issue #24 - Import was unreachable). Same pattern as the vibrate chips.
                val savedFocus = remember { List(2) { FocusRequester() } }
                OutlinedButton(
                    modifier = Modifier.dpadRowSibling(savedFocus, 0),
                    onClick = {
                        val intent = vm.exportSavedIntent()
                        if (intent != null) runCatching { context.startActivity(intent) }
                        else android.widget.Toast.makeText(context, context.getString(R.string.settings_no_saved_places), android.widget.Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.settings_export)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    modifier = Modifier.dpadRowSibling(savedFocus, 1),
                    onClick = {
                        runCatching { importLauncher.launch(arrayOf("application/json", "*/*")) }
                    },
                ) { Text(stringResource(R.string.settings_import)) }
            }
            Spacer(Modifier.height(8.dp))

            SectionTitle(stringResource(R.string.settings_data_privacy))
            Hint(stringResource(R.string.settings_data_privacy_hint))
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                app.vela.ui.ExternalLinks.open(context, "https://github.com/alltechdev/vela-dpad/blob/main/docs/PRIVACY.md")
            }) { Text(stringResource(R.string.settings_privacy_button)) }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_diagnostics))
            LaunchedEffect(Unit) { vm.refreshDiagnostics() }
            Hint(stringResource(R.string.settings_diagnostics_hint))
            var showDiagConsent by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_share_diagnostics), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = state.diagnosticsEnabled,
                    onCheckedChange = { on -> if (on) showDiagConsent = true else vm.setDiagnostics(false) },
                )
            }
            if (state.diagnosticsEnabled) {
                DpadRingBox(androidx.compose.material3.ButtonDefaults.outlinedShape) {
                    OutlinedButton(onClick = {
                        val intent = vm.diagShareIntent()
                        if (intent != null) runCatching { context.startActivity(intent) }
                        else android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_diag_nothing),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(stringResource(R.string.settings_diag_export)) }
                }
            }

            // Compatibility (TextureView) rendering - a hardware escape hatch (port of upstream
            // PimpinPumpkin/Vela 261156e2 + df2b8570). Writes the "texture_render" pref that
            // VelaMapView reads when it creates the map; needs an app restart to apply. Also flips
            // itself on via the two-crash sentinel when a GPU driver kills the map at init.
            var textureRender by remember { mutableStateOf(prefs.getBoolean("texture_render", app.vela.ui.map.fragileGpuDefault())) }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_texture_render), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = textureRender,
                    onCheckedChange = { on -> textureRender = on; prefs.edit().putBoolean("texture_render", on).apply() },
                )
            }
            Hint(stringResource(R.string.settings_texture_render_hint))

            // Trip recording - more invasive than diagnostics (it's your exact routes),
            // so it's a separate opt-in. Records nav GPS traces for replay testing.
            LaunchedEffect(Unit) { vm.refreshTripRecording() }
            var showTripConsent by remember { mutableStateOf(false) }
            var trips by remember { mutableStateOf(vm.recordedTrips()) }
            // Re-read on entry so a trip recorded since the app launched shows up without
            // a restart (the list was otherwise only refreshed after a delete).
            LaunchedEffect(Unit) { trips = vm.recordedTrips() }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_save_trips), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = state.tripRecordingEnabled,
                    onCheckedChange = { on -> if (on) showTripConsent = true else vm.setTripRecording(false) },
                )
            }
            Hint(stringResource(R.string.settings_save_trips_hint))
            if (trips.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Hint(stringResource(R.string.settings_recorded_trips_hint))
                trips.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            val recordedAt = if (t.startedAt > 0L)
                                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                                    .format(java.util.Date(t.startedAt))
                            else null
                            Hint(listOfNotNull(recordedAt, stringResource(R.string.settings_trip_points, t.fixCount)).joinToString(" · "))
                        }
                        TextButton(onClick = { vm.replayTrip(t); onBack() }) { Text(stringResource(R.string.settings_trip_replay)) }
                        // Share the raw trace off-device - works on release builds, so a
                        // drive can be handed over for replay/debug without a dev build.
                        TextButton(onClick = {
                            val intent = vm.exportTripIntent(t)
                            if (intent != null) runCatching { context.startActivity(intent) }
                            else android.widget.Toast.makeText(context, context.getString(R.string.settings_trip_read_error), android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.settings_trip_share)) }
                        IconButton(modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape), onClick = { vm.deleteTrip(t.id); trips = vm.recordedTrips() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_trip_delete))
                        }
                    }
                }
            } else if (state.tripRecordingEnabled) {
                Spacer(Modifier.height(4.dp))
                Hint(stringResource(R.string.settings_no_trips_hint))
            }
            if (showTripConsent) {
                app.vela.ui.VelaDialog(
                    onDismissRequest = { showTripConsent = false },
                    title = stringResource(R.string.settings_trip_consent_title),
                    confirmText = stringResource(R.string.settings_turn_on),
                    onConfirm = { vm.setTripRecording(true); showTripConsent = false },
                    dismissText = stringResource(R.string.settings_cancel),
                    onDismiss = { showTripConsent = false },
                    text = { Text(stringResource(R.string.settings_trip_consent_body)) },
                )
            }
            var crashReports by remember { mutableStateOf(app.vela.diag.CrashCatcher.pending(context)) }
            if (crashReports.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Hint(stringResource(R.string.settings_crash_hint))
                // Settings swallows bare LEFT/RIGHT (a no-target horizontal move would CLEAR focus),
                // so a Row of buttons needs explicit sibling wiring or only the first is ever
                // reachable - the same trap the update buttons had (issue #79, @SILB).
                val crashFocus = remember { List(2) { FocusRequester() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.outlinedShape) {
                        OutlinedButton(
                            modifier = Modifier.dpadRowSibling(crashFocus, 0),
                            onClick = {
                                app.vela.diag.CrashCatcher.shareIntent(context)?.let { runCatching { context.startActivity(it) } }
                            },
                        ) { Text(stringResource(R.string.settings_crash_export)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.textShape) {
                        TextButton(
                            modifier = Modifier.dpadRowSibling(crashFocus, 1),
                            onClick = {
                                app.vela.diag.CrashCatcher.clear(context); crashReports = emptyList()
                            },
                        ) { Text(stringResource(R.string.settings_crash_discard)) }
                    }
                }
            }
            if (showDiagConsent) {
                app.vela.ui.VelaDialog(
                    onDismissRequest = { showDiagConsent = false },
                    title = stringResource(R.string.settings_diag_consent_title),
                    confirmText = stringResource(R.string.settings_turn_on),
                    onConfirm = { vm.setDiagnostics(true); showDiagConsent = false },
                    dismissText = stringResource(R.string.settings_cancel),
                    onDismiss = { showDiagConsent = false },
                    text = { Text(stringResource(R.string.settings_diag_consent_body)) },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_about))
            Hint(stringResource(R.string.settings_about_hint))
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_support))
            Hint(stringResource(R.string.settings_support_hint))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.dpadHighlight(androidx.compose.foundation.shape.CircleShape),
                onClick = {
                    runCatching {
                        app.vela.ui.ExternalLinks.open(context, Onboarding.DONATE_URL)
                    }
                },
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.settings_support_button))
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_version))
            Text(
                stringResource(R.string.settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            // Self-updater: a launch check (throttled to ~daily) plus a manual check here.
            // The system installer does the install either way.
            var selfUpdate by remember { mutableStateOf(prefs.getBoolean("self_update_check", true)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_update_auto), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                VelaSwitch(
                    checked = selfUpdate,
                    onCheckedChange = { selfUpdate = it; prefs.edit().putBoolean("self_update_check", it).apply() },
                )
            }
            Hint(stringResource(R.string.settings_update_auto_hint))
            // A clear gap between the hint paragraph and the button (they read as one clump otherwise).
            Spacer(Modifier.height(10.dp))
            var updateStatus by remember { mutableStateOf<String?>(null) }
            val checkingText = stringResource(R.string.settings_update_checking)
            val noneText = stringResource(R.string.settings_update_none)
            // Found update: a full inline offer, right here. The map card said "go back to the
            // map to install" from Settings, which was a pointless round trip - same state, same
            // download call as the card, so the two surfaces stay in sync (start it here, the
            // card shows the same progress, and vice versa).
            state.updateInfo?.let { u ->
                Text(
                    stringResource(R.string.update_available_title, u.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                state.updateDownloadPct?.let { pct ->
                    Text(stringResource(R.string.update_downloading, pct), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                } ?: run {
                val updFocus = remember { List(2) { FocusRequester() } }
                Row(Modifier.padding(vertical = 4.dp)) {
                    // D-pad (issue #65): these were plain Material buttons with no visible focus ring,
                    // so a keypad user couldn't SEE the update button highlighted. dpadHighlight gives
                    // them the fork's focus ring, like the map UpdateCard already has. The ring shape
                    // MUST match each button's OWN shape - CircleShape drew a circle over a pill (tester
                    // report), so pass the matching ButtonDefaults shape per button type.
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.shape) {
                        Button(modifier = Modifier.dpadRowSibling(updFocus, 0), onClick = { vm.downloadUpdate() }) { Text(stringResource(R.string.update_install)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    DpadRingBox(androidx.compose.material3.ButtonDefaults.textShape) {
                        TextButton(modifier = Modifier.dpadRowSibling(updFocus, 1), onClick = { vm.dismissUpdate(); updateStatus = null }) { Text(stringResource(R.string.update_later)) }
                    }
                }
                }
            } ?: DpadRingBox(androidx.compose.material3.ButtonDefaults.outlinedShape) {
                OutlinedButton(
                    onClick = {
                        updateStatus = checkingText
                        vm.checkForUpdateNow { found -> updateStatus = if (found) null else noneText }
                    },
                ) { Text(stringResource(R.string.settings_update_check_now)) }
            }
            updateStatus?.let { Hint(it) }
            // Breathing room under the last control so the button doesn't sit right on the
            // gesture bar at the end of the scroll.
            Spacer(Modifier.height(56.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** A [SectionTitle] that toggles a collapsible body - tap the whole row; a chevron shows the state. */
@Composable
private fun CollapsibleSectionTitle(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().dpadHighlight(DpadShape(6.dp)).dpadClickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the RadioButton is display-only so the ROW is the single focus stop. A
        // separately-focusable RadioButton made the row TWO focus stops, and a horizontal (LEFT/
        // RIGHT) D-pad move into that nested target cleared focus with no way back (dpad audit
        // 2026-07-08) - the Material "clickable row + indicator" pattern.
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A lighter heading for sub-parts within a section (e.g. "Map area" / "Routing regions" under "Offline"). */
@Composable
private fun SubHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * The in-app Piper voice browser: the curated catalog grouped by accent, each row showing the voice's
 * accent/gender/quality/size + a Download / Use / Delete control and inline download progress.
 * Downloaded voices float to the top of their group; the active one is marked "In use"; ★ marks the
 * best few for navigation. A plain Column (the catalog is small and this lives inside the Settings
 * verticalScroll - a LazyColumn would fight it for height).
 */
@Composable
private fun VoiceLibrary(vm: MapViewModel, state: MapUiState) {
    val catalog = remember { vm.voiceCatalog() }
    val installed = state.installedVoiceIds
    val selected = state.selectedVoiceId
    val installedMb = catalog.filter { it.id in installed }.sumOf { it.sizeMb }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // The app's language - its voices are floated to the top of the browser (Google-style), and if none
    // is installed yet we nudge the user to grab the matching voice (so nav text + voice speak the same
    // language, not French words read by an English voice).
    val appLang = app.vela.ui.AppLocale.effective().language
    val hasAppLangVoice = catalog.any { it.langCode == appLang && it.id in installed }

    Spacer(Modifier.height(6.dp))
    Hint(
        if (installed.isEmpty())
            stringResource(R.string.settings_voice_lib_empty_hint)
        else
            "Installed: $installedMb MB · ${installed.size} voice${if (installed.size == 1) "" else "s"}. Tap Use to switch (plays a sample); the trash icon frees the space.",
    )
    if (appLang != "en" && !hasAppLangVoice) {
        val langLabel = PiperCatalog.languageLabel(appLang)
        Hint(stringResource(R.string.settings_voice_lib_lang_hint, langLabel, langLabel, langLabel))
    }
    if (catalog.size > 12) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.settings_voice_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).dpadFieldEscape(),
        )
    }
    fun matches(v: PiperVoice) = query.isBlank() ||
        listOf(v.displayName, v.region, PiperCatalog.languageLabel(v.langCode), v.note ?: "")
            .any { it.contains(query.trim(), ignoreCase = true) }

    // Grouped by language - the app's language first (Google-style), then English, then by endonym.
    // Each language is its own collapsible sub-group so the ~40-voice list isn't one long scroll: a
    // group opens by default only when it's the app's language or already has a voice installed, so you
    // see your own language + what you've got and the rest stays folded away. A search forces all open.
    val langOrder = PiperCatalog.languageCodes().let { codes ->
        if (appLang in codes) listOf(appLang) + codes.filter { it != appLang } else codes
    }
    val langExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val langShowAll = remember { mutableStateMapOf<String, Boolean>() }
    langOrder.forEach { lang ->
        val group = catalog.filter { it.langCode == lang && matches(it) }.sortedWith(
            compareByDescending<PiperVoice> { it.id in installed }
                .thenByDescending { it.id == selected }
                .thenByDescending { it.recommended }
                .thenBy { it.novelty }
                .thenByDescending { it.quality.ordinal }
                .thenBy { it.displayName },
        )
        if (group.isNotEmpty()) {
            val installedHere = group.count { it.id in installed }
            val defaultOpen = lang == appLang || installedHere > 0
            // A live search reveals every match; otherwise honour the user's toggle, falling back to the default.
            val expanded = if (query.isNotBlank()) true else (langExpanded[lang] ?: defaultOpen)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .dpadHighlight(RoundedCornerShape(8.dp))
                    .dpadClickable(enabled = query.isBlank()) { langExpanded[lang] = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PiperCatalog.languageLabel(lang),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (installedHere > 0) "$installedHere/${group.size}" else "${group.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                // Show just the top few per language (each language still has a lot, English most of
                // all); a "Show more" reveals the rest. Never hide an INSTALLED voice behind it, and a
                // search shows everything that matches.
                val showAll = query.isNotBlank() || (langShowAll[lang] ?: false)
                val limit = maxOf(3, installedHere)
                val visible = if (showAll) group else group.take(limit)
                visible.forEach { v ->
                    VoiceRow(
                        v = v,
                        installed = v.id in installed,
                        active = v.id == selected,
                        downloading = state.voiceDownloadingId == v.id,
                        downloadPct = if (state.voiceDownloadingId == v.id) state.kokoroDownloadPct ?: 0f else 0f,
                        anyDownloading = state.voiceDownloadingId != null,
                        onDownload = { vm.downloadVoice(v.id) },
                        onUse = { vm.selectVoice(v.id) },
                        onDelete = {
                            // Confirm only when it's the last voice (guidance would lose its neural voice).
                            if (installed.size == 1 && v.id == selected) confirmDeleteId = v.id else vm.deleteVoice(v.id)
                        },
                    )
                }
                if (query.isBlank() && group.size > limit) {
                    TextButton(modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.textShape), onClick = { langShowAll[lang] = !showAll }) {
                        Text(
                            if (showAll) stringResource(R.string.settings_voice_show_less)
                            else stringResource(R.string.settings_voice_show_more, group.size - limit),
                        )
                    }
                }
            }
        }
    }

    confirmDeleteId?.let { id ->
        val nm = catalog.firstOrNull { it.id == id }?.displayName ?: stringResource(R.string.settings_voice_this_voice)
        app.vela.ui.VelaDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = stringResource(R.string.settings_voice_remove_title, nm),
            confirmText = stringResource(R.string.settings_voice_remove),
            onConfirm = { vm.deleteVoice(id); confirmDeleteId = null },
            dismissText = stringResource(R.string.settings_cancel),
            onDismiss = { confirmDeleteId = null },
            text = { Text(stringResource(R.string.settings_voice_remove_body)) },
        )
    }
}

@Composable
private fun VoiceRow(
    v: PiperVoice,
    installed: Boolean,
    active: Boolean,
    downloading: Boolean,
    downloadPct: Float,
    anyDownloading: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val gender = when (v.gender) {
        app.vela.core.voice.VoiceGender.FEMALE -> stringResource(R.string.settings_voice_gender_female)
        app.vela.core.voice.VoiceGender.MALE -> stringResource(R.string.settings_voice_gender_male)
        app.vela.core.voice.VoiceGender.MULTI -> stringResource(R.string.settings_voice_gender_multi, v.numSpeakers)
        app.vela.core.voice.VoiceGender.NEUTRAL -> stringResource(R.string.settings_voice_gender_neutral)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (v.recommended) "★ " else "") + v.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
            val sub = when {
                downloading -> stringResource(R.string.settings_voice_row_downloading, (downloadPct * 100).toInt())
                active -> stringResource(R.string.settings_voice_row_in_use, v.region, gender, v.sizeMb)
                installed -> stringResource(R.string.settings_voice_row_downloaded, v.region, gender, v.sizeMb)
                else -> "${v.region} · $gender · ${v.quality.name.lowercase()} · ${v.sizeMb} MB" + (v.note?.let { " · $it" } ?: "")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        // D-pad: this trailing control REPLACES itself when used (Download -> spinner ->
        // Use/Delete) and Compose recovery then dumped focus at the TOP of the page (user
        // report). The keeper re-places focus on the new variant; the spinner is a focus
        // stop with a ring so the row keeps the highlight while it works.
        val keeper = rememberDpadFocusKeeper()
        when {
            downloading -> {
                DpadFocusHandoff(keeper)
                CircularProgressIndicator(
                    Modifier
                        .size(22.dp)
                        .dpadFocusKept(keeper)
                        .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                        .focusable(),
                    strokeWidth = 2.dp,
                )
            }
            active -> {
                DpadFocusHandoff(keeper)
                IconButton(onClick = onDelete, modifier = Modifier.dpadFocusKept(keeper)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            installed -> {
                DpadFocusHandoff(keeper)
                // Use + Delete sit side by side, so LEFT/RIGHT must hop between them directly (issue
                // #65: a bare horizontal move otherwise cleared focus - Delete only reachable from
                // above, Use only from below). Wire the pair with dpadRowSibling like the other
                // Settings button rows. The keeper's requester (dpadFocusKept) stays on Use so a
                // Download->Use handoff still lands the row's focus here.
                val useDeleteFocus = remember { List(2) { FocusRequester() } }
                OutlinedButton(
                    onClick = onUse,
                    modifier = Modifier.dpadFocusKept(keeper).dpadRowSibling(useDeleteFocus, 0),
                ) { Text(stringResource(R.string.settings_voice_row_use)) }
                IconButton(onClick = onDelete, modifier = Modifier.dpadRowSibling(useDeleteFocus, 1)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_voice_row_remove, v.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                DpadFocusHandoff(keeper)
                OutlinedButton(onClick = onDownload, enabled = !anyDownloading, modifier = Modifier.dpadFocusKept(keeper)) { Text(stringResource(R.string.settings_download)) }
            }
        }
        LaunchedEffect(downloading, active, installed) { keeper.retarget() }
    }
    if (downloading) LinearProgressIndicator(progress = { downloadPct }, modifier = Modifier.fillMaxWidth())
}
