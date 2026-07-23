package app.vela.ui

import androidx.compose.ui.graphics.Color

/**
 * One Google-style palette shared by every bottom surface - the place sheet, the
 * directions panel, the route chooser, the steps list and the nav bar - so they
 * read as one consistent sheet instead of several differently-coloured cards.
 *
 * Deliberately FIXED (not Material-You tokens) so a wallpaper tint can't wash the
 * text out; choose the variant with the in-app `isAppInDarkTheme()`. Accent colour
 * stays the theme `primary` (teal); traffic uses [TrafficGreen]/[TrafficAmber]/
 * [TrafficRed].
 */
object SheetPalette {
    val Dark = Color(0xFF1F1F1F)     // sheet / card background
    val Light = Color(0xFFFFFFFF)
    val InkDark = Color(0xFFE8EAED)  // primary text
    val InkLight = Color(0xFF202124)
    val DimDark = Color(0xFF9AA0A6)  // secondary text
    val DimLight = Color(0xFF5F6368)
    val RowDark = Color(0xFF202124)  // inset row / chip background
    val RowLight = Color(0xFFF1F3F4)
    // AMOLED: the sheets go TRUE BLACK (they are the app's largest chrome surfaces - a grey sheet
    // on a black theme defeats the mode's whole point), with the inset rows stepped to a near-black
    // so chips/rows still read as layers. Ink/Dim stay the dark values (contrast is higher on black).
    val Amoled = Color(0xFF000000)
    val RowAmoled = Color(0xFF141414)

    /** True while the app-wide theme is the AMOLED mode - a state read, so composables that call
     *  the helpers below recompose when the mode flips (same mechanism as isAppInDarkTheme). */
    private val amoled: Boolean
        get() = app.vela.ui.theme.AppTheme.mode.value == app.vela.ui.theme.ThemeMode.AMOLED

    // Shared traffic-coded colours (route ETAs, the route line, the steps header).
    val TrafficGreen = Color(0xFF1E8E3E)
    val TrafficAmber = Color(0xFFE8923D)
    val TrafficRed = Color(0xFFD93838)

    fun bg(dark: Boolean) = if (dark) { if (amoled) Amoled else Dark } else Light
    fun ink(dark: Boolean) = if (dark) InkDark else InkLight
    fun dim(dark: Boolean) = if (dark) DimDark else DimLight
    fun row(dark: Boolean) = if (dark) { if (amoled) RowAmoled else RowDark } else RowLight
}
