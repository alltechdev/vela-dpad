package app.vela.ui.softkey

// Vela's thin integration layer over the vendored Yapchik softkey engine (:yapchik, LGPL-3.0).
// Keypad / feature phones (Qin, Sonim, kosher phones) carry two hardware SOFT keys with no
// touch equivalent; issue #65 asked for the top-left/right keys to zoom the map so you do not
// have to walk focus to the on-screen +/- buttons (4+ keypresses) first. This wires those two
// keys to the map's zoom seam. New behaviour lives here (merge-friendly); the only shared-file
// edits are Yapchik.init in VelaApp and one MapZoomSoftkeys() call in MapScreen.

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.vela.ui.isDpadFirstDevice
import com.theonionsarewatching.yapchik.SoftkeyMode
import com.theonionsarewatching.yapchik.SoftkeyProfileChooser
import com.theonionsarewatching.yapchik.Softkeys
import com.theonionsarewatching.yapchik.Yapchik

object VelaSoftkeys {

    /** Reactive mirror of the engine's resolved on/off state (Yapchik has no Compose state of its
     * own). Drives the map's +/- consolidation so turning softkeys OFF restores the on-screen
     * buttons. Same process-wide-holder shape as [app.vela.ui.theme.AppTheme]. */
    private var activeState by mutableStateOf(false)

    /** Whether the softkey bar is currently resolved-active (mode ON, or AUTO on a keypad device).
     * Composable read so a mode change recomposes the map's zoom-button gate. */
    @Composable
    fun isActive(): Boolean = activeState

    /** Settings: is the feature enabled (AUTO or ON, i.e. not explicitly OFF)? */
    fun isEnabled(): Boolean = Yapchik.mode != SoftkeyMode.OFF

    /** Settings toggle: ON leaves detection to AUTO (shows on keypad phones, hidden on touch); OFF
     * disables softkeys everywhere. Persists via the engine and fires the state listener below. */
    fun setEnabled(enabled: Boolean) {
        Yapchik.mode = if (enabled) SoftkeyMode.AUTO else SoftkeyMode.OFF
    }

    /** Settings: run the engine's press-your-keys calibration so a phone whose soft keys emit
     * non-standard keycodes can be detected. Capture is driven by the key press itself. */
    fun calibrate(activity: Activity) = SoftkeyProfileChooser.startCalibration(activity)

    /** One-time engine setup, called from [app.vela.VelaApp.onCreate]. */
    fun init(app: Application) {
        Yapchik.install(app)
        // Softkeys are strictly a KEYPAD / D-pad-first feature. Gate the whole engine on Vela's
        // OWN conservative detector (same rule + `vela_force_dpad` test override as the Compose
        // D-pad affordances) so the bar NEVER appears on a touch phone - touch stays byte-identical.
        // Mode stays AUTO (the default, unless the user turned it off); AUTO resolves through this.
        Yapchik.autoDetector = { ctx -> isDpadFirstDevice(ctx) }
        // Mirror the resolved state into Compose: seed it, then track mode changes.
        activeState = Yapchik.isActive
        Yapchik.addStateListener { active -> activeState = active }
        // Reserve bar space by padding the content view while the bar is up, rather than letting it
        // overlay the map's bottom chrome (locate FAB / scale bar). Only affects keypad devices,
        // since the bar only ever shows there.
        Yapchik.autoInsetContent = true
        // A single-ink dark bar that reads in both app themes (the engine's style is global, not
        // theme-reactive yet - see the rollout plan for the theme-follow follow-up).
        Yapchik.style.apply {
            backgroundColor = 0xFF14343A.toInt()
            textColor = 0xFFECECEC.toInt()
            pressedTextColor = 0xFF4DD0C4.toInt()
            heightDp = 44
            textSizeSp = 15f
            bold = true
        }
    }

    /** One soft-key binding: a label and its action. */
    class Key(val label: String, val onPress: () -> Unit)

    /**
     * CONTEXTUAL map soft keys. Pass the LEFT/RIGHT [Key] for the current map context (or null to
     * leave that slot - and the whole bar when BOTH are null - unbound, so no bar shows). Re-binds
     * when the labels change; cleared when the map leaves composition. No-op on a touch phone (the
     * AUTO detector keeps the bar hidden). Labels are the re-bind key; the actions are captured
     * live (`rememberUpdatedState`) so a changing lambda doesn't churn the binding.
     */
    @Composable
    fun MapSoftkeys(left: Key?, right: Key?) {
        val activity = LocalContext.current as? Activity ?: return
        val leftNow by rememberUpdatedState(left)
        val rightNow by rememberUpdatedState(right)
        LaunchedEffect(left?.label, right?.label) {
            val ctl = Softkeys.of(activity)
            if (left == null && right == null) {
                ctl.clear()
            } else {
                ctl.set {
                    leftNow?.let { k -> left(k.label) { k.onPress() } }
                    rightNow?.let { k -> right(k.label) { k.onPress() } }
                }
            }
        }
        DisposableEffect(activity) { onDispose { Softkeys.of(activity).clear() } }
    }

    /**
     * Force the bar OFF on this screen while [suppressed] (e.g. Settings drawn OVER the still-composed
     * map). Uses the engine's per-screen mode override, which outranks the map's bindings but still
     * yields to the user's global OFF. Restores (null = inherit) when no longer suppressed.
     */
    @Composable
    fun SuppressBarWhile(suppressed: Boolean) {
        val activity = LocalContext.current as? Activity ?: return
        LaunchedEffect(suppressed) {
            Softkeys.of(activity).screenMode = if (suppressed) SoftkeyMode.OFF else null
        }
    }
}
