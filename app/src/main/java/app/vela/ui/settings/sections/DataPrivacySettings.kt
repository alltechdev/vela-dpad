package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)

/** Data source and privacy sub-screen: the how-Vela-handles-data explainer + privacy policy link. */
@Composable
internal fun DataPrivacySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_data_privacy), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.settings_data_privacy_hint))
        SettingsGroup {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        OutlinedButton(
            // The top (and only) focusable control; on the old page this button sat beside a
            // VelaSwitch whose ring token satisfied the audit window - here it carries its own ring.
            modifier = topRow.dpadHighlight(androidx.compose.material3.ButtonDefaults.outlinedShape),
            onClick = {
                app.vela.ui.ExternalLinks.open(context, "https://github.com/alltechdev/vela-dpad/blob/main/docs/PRIVACY.md")
            },
        ) { Text(stringResource(R.string.settings_privacy_button)) }
        }
        }
        Spacer(Modifier.height(24.dp))
    }
}
