package app.vela.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import androidx.hilt.navigation.compose.hiltViewModel
import app.vela.ui.map.MapScreen
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.SettingsScreen

/**
 * Root composable. One [MapViewModel] instance is shared between the map and
 * settings (settings tweaks the same map/voice state), so we drive a single
 * boolean rather than a NavHost with cross-graph VM scoping. The first-run
 * [WelcomeScreen] gates everything else; the one-time [DonatePrompt] overlays the
 * map once the app has earned it (see [Onboarding]).
 */
@Composable
fun VelaRoot(vm: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current

    if (!Onboarding.welcomeDone.value) {
        WelcomeScreen(onGetStarted = { Onboarding.completeWelcome(context) })
        return
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsOpenOffline by rememberSaveable { mutableStateOf(false) }
    Box {
        // MapScreen stays composed even while Settings is open, and Settings draws OVER it as an
        // opaque overlay. Swapping the two out instead disposed the remembered MapLibre MapView, so
        // returning from Settings rebuilt the map from scratch and it snapped back to the stale
        // center at the default zoom, losing the user's pan/zoom (a reported bug).
        MapScreen(vm = vm, onOpenSettings = { showSettings = true })
        // The map stays composed under Settings, so its contextual soft keys would otherwise keep a
        // Zoom bar on the Settings overlay. Force the bar off while Settings is up (keypad phones only;
        // no-op on touch). Restores the map's context when Settings closes.
        app.vela.ui.softkey.VelaSoftkeys.SuppressBarWhile(showSettings)
        if (showSettings) {
            SettingsScreen(
                vm = vm,
                onBack = { showSettings = false; settingsOpenOffline = false },
                openOffline = settingsOpenOffline,
            )
        } else {
            if (Onboarding.showVoicePrompt.value) {
                VoicePrompt(
                    // The Vela voice is recommended for EVERYONE - the same prompt regardless of
                    // whether the phone has a system TTS engine, so every install ends up on the
                    // same consistent voice unless they deliberately change it in Settings.
                    ttsSizeMb = vm.defaultVoiceSizeMb(),
                    micSizeMb = app.vela.voice.AsrModel.SIZE_MB,
                    onChoose = { voice, mic ->
                        vm.downloadOnboardingModels(voice, mic)
                        Onboarding.dismissVoicePrompt(context)
                    },
                    onSkip = { Onboarding.dismissVoicePrompt(context) },
                )
            } else if (Onboarding.showOfflinePrompt.value) {
                OfflinePrompt(
                    onSetup = {
                        Onboarding.dismissOfflinePrompt(context)
                        settingsOpenOffline = true
                        showSettings = true
                    },
                    onSkip = { Onboarding.dismissOfflinePrompt(context) },
                )
            } else if (Onboarding.showDonatePrompt.value) {
                DonatePrompt(
                    onDonate = {
                        runCatching {
                            app.vela.ui.ExternalLinks.open(context, Onboarding.DONATE_URL)
                        }
                        Onboarding.dismissDonatePrompt(context)
                    },
                    onDismiss = { Onboarding.dismissDonatePrompt(context) },
                )
            } else if (Onboarding.showDiagPrompt.value) {
                DiagPrompt(
                    onChoose = { diag, trips ->
                        if (diag) vm.setDiagnostics(true)
                        if (trips) vm.setTripRecording(true)
                        Onboarding.dismissDiagPrompt(context)
                    },
                    onDismiss = { Onboarding.dismissDiagPrompt(context) },
                )
            }
        }
    }
}

/** One-time, opt-in nudge with TWO separate choices - basic diagnostics (default on)
 * and the more-invasive trip recording (default off, since it captures your exact
 * routes). Both stay on-device; "Not now" enables neither. */
@Composable
private fun DiagPrompt(onChoose: (diagnostics: Boolean, trips: Boolean) -> Unit, onDismiss: () -> Unit) {
    var diag by remember { mutableStateOf(true) }
    var trips by remember { mutableStateOf(false) }
    VelaDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.root_diag_title),
        confirmText = stringResource(R.string.root_diag_save),
        onConfirm = { onChoose(diag, trips) },
        dismissText = stringResource(R.string.root_not_now),
        onDismiss = onDismiss,
        text = {
            Column {
                Text(stringResource(R.string.root_diag_body))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = diag, onCheckedChange = { diag = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.root_diag_share_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.root_diag_share_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trips, onCheckedChange = { trips = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.root_diag_trips_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.root_diag_trips_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/**
 * One tick-able option in [VoicePrompt]: the ROW is the single focus stop, not the checkbox.
 *
 * The checkbox is display-only (`onCheckedChange = null`). A separately-focusable Checkbox makes the
 * row TWO focus stops, and a horizontal D-pad move into that nested target clears focus with no way
 * back - the same defect the 2026-07-08 audit found on the Settings radio rows, whose fix is the
 * `dpadClickable` row + display-only indicator pattern copied here. It also gets the app-wide ORANGE
 * ring: a bare Material Checkbox self-indicates with a faint grey state layer, which on a 240x320
 * screen is nearly invisible and disagrees with every other focusable surface in the app (one ring
 * colour everywhere, no exceptions). `dpadClickable` rather than `clickable` so the row does not
 * draw the grey ripple layer AND the ring at once while a key user is driving.
 */
@Composable
private fun VoiceOptionRow(checked: Boolean, title: String, desc: String, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .dpadHighlight(RoundedCornerShape(6.dp))
            .dpadClickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One-time, first-run offer of Vela's TWO on-device speech models: the neural VOICE that speaks
 * directions, and the MIC that dictates searches. Both are offered to everyone, on ONE screen
 * rather than two consecutive dialogs.
 *
 * That is deliberate. The two share the "Vela Voice" name, and a user who downloaded the voice in
 * setup then found the search mic still handing off to Google reported it as the setup download
 * being broken (2026-07-21) - it was not, it was the other model. Split across two dialogs the pair
 * reads as the same question asked twice; side by side, the FUNCTION labels ("Speak directions
 * aloud" / "Search by speaking") disambiguate them by layout, which no wording alone achieved.
 *
 * The voice is checked by default - recommended for everyone, and it preserves the old one-tap
 * behaviour for anyone who just confirms. The mic is NOT: it is a [micSizeMb] MB download that
 * lands far bigger on disk, and opt-OUT is the wrong default for that on a feature phone. Skipping
 * either leaves it one tap away in Settings, and the mic re-offers itself on the first mic tap.
 * [ttsSizeMb]/[micSizeMb] are the real download sizes, so neither number can go stale. */
@Composable
private fun VoicePrompt(
    ttsSizeMb: Int,
    micSizeMb: Int,
    onChoose: (voice: Boolean, mic: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    // rememberSaveable, not remember: a rotation or a config change mid-dialog must not silently
    // reset the user's ticks back to the defaults (the diagnostics prompt has the same hazard).
    var voice by rememberSaveable { mutableStateOf(true) }
    var mic by rememberSaveable { mutableStateOf(false) }
    VelaDialog(
        onDismissRequest = onSkip,
        title = stringResource(R.string.root_voice_title),
        confirmText = stringResource(R.string.root_voice_download),
        onConfirm = { onChoose(voice, mic) },
        // "Not now" rather than the old "Use system voice": with two checkboxes the dismiss button
        // can no longer name one model's fallback. The body carries that sentence instead.
        dismissText = stringResource(R.string.root_not_now),
        onDismiss = onSkip,
        dismissLowEmphasis = true,
        text = {
            // Same shape as DiagPrompt above (checkbox + title + small description per row), which
            // is the one in-dialog checkbox layout already proven D-pad-correct on a 240x320 screen.
            // On a feature phone the two option rows are what matter; the intro is context. Tighten
            // the gaps there so the second row's third line cannot be pushed under the fold.
            val compact = AdaptiveDensity.applied
            Column {
                Text(stringResource(R.string.root_voice_body_intro))
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                VoiceOptionRow(
                    checked = voice,
                    title = stringResource(R.string.root_voice_tts_title),
                    desc = stringResource(R.string.root_voice_tts_desc, ttsSizeMb),
                    onToggle = { voice = !voice },
                )
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                VoiceOptionRow(
                    checked = mic,
                    title = stringResource(R.string.root_voice_mic_title),
                    desc = stringResource(R.string.root_voice_mic_desc, micSizeMb),
                    onToggle = { mic = !mic },
                )
            }
        },
    )
}

/** One-time, first-run offer to set up offline maps. Vela's live data comes from Google, so without a
 * connection only downloaded areas work. Surfacing this during onboarding means people find it before
 * they lose signal on the road, not after. "Set up" opens Settings straight to the Offline section. */
@Composable
private fun OfflinePrompt(onSetup: () -> Unit, onSkip: () -> Unit) {
    VelaDialog(
        onDismissRequest = onSkip,
        title = stringResource(R.string.root_offline_title),
        confirmText = stringResource(R.string.root_offline_setup),
        onConfirm = onSetup,
        dismissText = stringResource(R.string.root_not_now),
        onDismiss = onSkip,
        text = { Text(stringResource(R.string.root_offline_body)) },
    )
}
