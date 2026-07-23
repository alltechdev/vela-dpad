package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.R
import app.vela.core.data.tiles.MapStyle
import app.vela.ui.Units
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SelectableRow
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.ThemeMode

/** Appearance sub-screen: theme, map style, units, language (the four small former sections). */
@Composable
internal fun AppearanceSettingsScreen(vm: MapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_appearance), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        SelectableRow(
            label = stringResource(R.string.settings_follow_system),
            selected = AppTheme.mode.value == ThemeMode.SYSTEM,
            onClick = { AppTheme.set(context, ThemeMode.SYSTEM) },
            // The top focusable row: Back routes its DOWN here, UP from here goes back to Back.
            modifier = topRow,
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_light),
            selected = AppTheme.mode.value == ThemeMode.LIGHT,
            onClick = { AppTheme.set(context, ThemeMode.LIGHT) },
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_dark),
            selected = AppTheme.mode.value == ThemeMode.DARK,
            onClick = { AppTheme.set(context, ThemeMode.DARK) },
        )
        GroupDivider()
        SelectableRow(
            label = stringResource(R.string.settings_theme_amoled),
            selected = AppTheme.mode.value == ThemeMode.AMOLED,
            onClick = { AppTheme.set(context, ThemeMode.AMOLED) },
        )
        }
        Hint(stringResource(R.string.settings_appearance_hint))

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_map_style)) {
            MapStyle.values().toList().forEachIndexed { i, style ->
                if (i > 0) GroupDivider()
                SelectableRow(
                    label = style.label,
                    selected = state.styleName == style.label,
                    onClick = { vm.setStyle(style) },
                )
            }
        }
        Hint(stringResource(R.string.settings_map_style_hint))

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_units)) {
            SelectableRow(
                label = stringResource(R.string.settings_units_imperial),
                selected = Units.imperial.value,
                onClick = { Units.set(context, true) },
            )
            GroupDivider()
            SelectableRow(
                label = stringResource(R.string.settings_units_metric),
                selected = !Units.imperial.value,
                onClick = { Units.set(context, false) },
            )
        }

        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_language)) {
            SelectableRow(
                label = stringResource(R.string.settings_follow_system),
                selected = app.vela.ui.AppLocale.language.value.isBlank(),
                onClick = { app.vela.ui.AppLocale.set(context, "") },
            )
            app.vela.ui.AppLocale.SUPPORTED.forEach { code ->
                GroupDivider()
                SelectableRow(
                    label = app.vela.ui.AppLocale.endonym(code),
                    selected = app.vela.ui.AppLocale.language.value == code,
                    onClick = { app.vela.ui.AppLocale.set(context, code) },
                )
            }
        }
        Hint(stringResource(R.string.settings_language_hint))
        Spacer(Modifier.height(24.dp))
    }
}
