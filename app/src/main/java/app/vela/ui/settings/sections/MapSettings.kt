package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow

/** Map sub-screen: the map-layer toggles (traffic, transit, topography, layers button, flock, 3D). */
@Composable
internal fun MapSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_map), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_live_traffic),
            checked = app.vela.ui.Traffic.on.value,
            onCheckedChange = { app.vela.ui.Traffic.set(context, it) },
            hint = stringResource(R.string.settings_live_traffic_hint),
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_transit_layer),
            checked = app.vela.ui.TransitLayer.on.value,
            onCheckedChange = { app.vela.ui.TransitLayer.set(context, it) },
            hint = stringResource(R.string.settings_transit_layer_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_topography),
            checked = app.vela.ui.Topography.on.value,
            onCheckedChange = { app.vela.ui.Topography.set(context, it) },
            hint = stringResource(R.string.settings_topography_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_layers_button),
            checked = app.vela.ui.LayersButton.on.value,
            onCheckedChange = { app.vela.ui.LayersButton.set(context, it) },
            hint = stringResource(R.string.settings_layers_button_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock),
            checked = app.vela.ui.Flock.on.value,
            onCheckedChange = { app.vela.ui.Flock.set(context, it) },
            hint = stringResource(R.string.settings_flock_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_flock_route_alert),
            checked = app.vela.ui.FlockRouteAlert.on.value,
            onCheckedChange = { app.vela.ui.FlockRouteAlert.set(context, it) },
            hint = stringResource(R.string.settings_flock_route_alert_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_buildings_3d),
            checked = app.vela.ui.Buildings3d.on.value,
            onCheckedChange = { app.vela.ui.Buildings3d.set(context, it) },
            hint = stringResource(R.string.settings_buildings_3d_hint),
        )
        }
        Spacer(Modifier.height(24.dp))
    }
}
