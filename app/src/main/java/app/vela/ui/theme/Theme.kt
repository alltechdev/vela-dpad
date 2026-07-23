package app.vela.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Containers are set to teal tints too - otherwise Material's defaults leave
// primaryContainer/secondaryContainer a stock purple, which made the map FABs and
// selected chips read "weirdly purple" against the teal brand.
private val LightColors = lightColorScheme(
    // Soft teal-cast off-whites instead of pure white - a full-white page is harsh to look at
    // (user feedback); the container roles step down so bars/cards still read as layers.
    background = androidx.compose.ui.graphics.Color(0xFFF3F7F6),
    surface = androidx.compose.ui.graphics.Color(0xFFF3F7F6),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFEDF2F1),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFE7EEEC),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE1E9E7),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFDBE4E2),
    primary = VelaTeal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFB6E7DF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF00201D),
    secondary = VelaTealDark,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFCDE9E3),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF06201C),
    tertiary = VelaAmber,
)

private val DarkColors = darkColorScheme(
    primary = VelaTealLight,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003730),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF13534B),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFB6E7DF),
    secondary = VelaTeal,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1F4A44),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFCDE9E3),
    tertiary = VelaAmber,
)

// Black: the dark scheme on TRUE BLACK - every surface AND container role is #000000 ("black
// should be black", user 2026-07-23; the earlier near-black stepping read as grey areas). The
// hairline outlines and the Settings cards' thin borders carry ALL of the structure; nothing
// separates by fill any more.
private val AmoledColors = DarkColors.copy(
    background = androidx.compose.ui.graphics.Color(0xFF000000),
    surface = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceDim = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF000000),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF000000),
)

/**
 * App theme. Uses Vela's explicit teal light/dark schemes rather than Material You
 * dynamic colour: the in-app Light/Dark switch is the contract, and on some ROMs
 * (observed on GrapheneOS) `dynamicDarkColorScheme` hands back a *light* background,
 * which broke "Dark" for every MaterialTheme surface (Settings etc.). `dynamicColor`
 * stays as an opt-in param but defaults off so the switch is always honoured.
 */
@Composable
fun VelaTheme(
    darkTheme: Boolean = isAppInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        AppTheme.mode.value == ThemeMode.AMOLED -> AmoledColors
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = VelaTypography, content = content)
}
