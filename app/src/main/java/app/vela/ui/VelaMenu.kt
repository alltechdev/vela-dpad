package app.vela.ui

// D-pad-first menu (docs/dpad.md). A Compose `DropdownMenu` Popup can't be pre-focused (~8
// approaches proven to fail), so on a D-pad-first device this renders the menu as a raw `Dialog`
// chooser instead - which DOES auto-focus its first item (the gallery/VelaDialog pattern). Under
// TOUCH it renders the ordinary anchored `DropdownMenu`, unchanged, so touch stays byte-identical.
// Same call shape as DropdownMenu: `VelaMenu(expanded, onDismissRequest) { item("A") { .. }; item("B") { .. } }`.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Scope for [VelaMenu] content - call [item] once per menu entry (same order as it should show).
 * [bordered] draws a divider between items (the feature-phone [VelaMenuPlacement.BottomStart] look). */
class VelaMenuScope internal constructor(internal val dpad: Boolean, internal val bordered: Boolean = false) {
    internal var index = 0
}

/** Where the D-pad menu chooser sits. [Center] (default) is the plain centred dialog; [BottomStart]
 * pins it flush to the bottom-left, on top of the soft-key bar - the feature-phone "Options" (LEFT
 * soft key) position. Only affects the D-pad raw-Dialog path; touch keeps the anchored DropdownMenu. */
enum class VelaMenuPlacement { Center, BottomStart }

/**
 * Drop-in [DropdownMenu] replacement that is **D-pad-first**: on a D-pad device it shows a raw
 * `Dialog` chooser whose FIRST item is focused on open (so no wasted "enter the menu" press);
 * under touch it shows the normal anchored `DropdownMenu`. Fully navigable either way: OK selects,
 * DOWN/UP walk, BACK/outside dismiss.
 */
@Composable
fun VelaMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    placement: VelaMenuPlacement = VelaMenuPlacement.Center,
    // For [VelaMenuPlacement.BottomStart]: the soft-key bar's height in window PIXELS. The dialog
    // window is lifted by this much off the bottom so its bottom edge lands on the bar. Passed in
    // because the bar is drawn at the activity's adaptive density, which a Compose LocalContext/
    // LocalDensity here doesn't see. 0 = fall back to the dialog context's own bar-height guess.
    bottomBarPx: Int = 0,
    content: @Composable VelaMenuScope.() -> Unit,
) {
    val dpadFirst = rememberDpadFirstDevice()
    if (dpadFirst) {
        if (expanded) {
            val bottomStart = placement == VelaMenuPlacement.BottomStart
            Dialog(
                onDismissRequest = onDismissRequest,
                properties = if (bottomStart) DialogProperties(usePlatformDefaultWidth = false) else DialogProperties(),
            ) {
                if (bottomStart) {
                    // A Dialog window WRAPS its content, so aligning inside it does nothing - position
                    // the WINDOW itself: gravity bottom-left, then lift it by the soft-key bar's height
                    // (in window px) so its bottom edge sits exactly on the bar. y is in the window's own
                    // pixel space, which is where the native bar is measured too, so it lands flush.
                    val dialogView = androidx.compose.ui.platform.LocalView.current
                    val barPx = if (bottomBarPx > 0) bottomBarPx
                        else com.theonionsarewatching.yapchik.Yapchik.barHeightPx(dialogView.context)
                    androidx.compose.runtime.SideEffect {
                        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                            w.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.START)
                            w.attributes = w.attributes.apply { y = barPx }
                        }
                    }
                }
                val surface = @Composable {
                    Surface(
                        // Feature-phone Options menu: square corners + a container border, no vertical
                        // padding (dividers meet the edges). Centre menus keep the rounded card.
                        shape = if (bottomStart) RectangleShape else RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        border = if (bottomStart) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                    ) {
                        // Cap to 85% of the screen and scroll - a long menu (many items) must not run off
                        // a small screen with the bottom items unreachable. Focus follows into view as
                        // DOWN walks past the fold.
                        Column(
                            Modifier
                                .width(IntrinsicSize.Max)
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = if (bottomStart) 0.dp else 8.dp),
                        ) {
                            VelaMenuScope(dpad = true, bordered = bottomStart).content()
                        }
                    }
                }
                surface()
            }
        }
    } else {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
            VelaMenuScope(dpad = false).content()
        }
    }
}

/** A TOGGLE menu entry: label + trailing Switch, the layers-panel row. Same dual rendering as
 *  [item] (plain row under touch, focusable auto-focused chooser row under D-pad, OK flips) but
 *  it does NOT dismiss - toggles are browse-several controls, the user flips a few then leaves. */
@Composable
fun VelaMenuScope.toggleItem(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    if (!dpad) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Fork adaptation: hybrid touch+keypad phones can key-walk the anchored
                // DropdownMenu too, so the touch row still needs a visible focus ring.
                .dpadHighlight(RoundedCornerShape(8.dp))
                .clickable { onToggle(!checked) }
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth(),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            VelaSwitch(checked = checked, onCheckedChange = onToggle)
        }
        return
    }
    val autoFocus = index == 0
    index++
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    if (autoFocus) {
        LaunchedEffect(dpadFirst) {
            if (dpadFirst) repeat(30) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    // The ring goes on the SWITCH, not on this row: focus lives on the row (it is the single focus
    // stop), but ringing the row wrapped the whole option instead of the toggle and disagreed with
    // Settings, where the ring hugs the pill. Track focus here, draw it there.
    var rowFocused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .onFocusEvent { rowFocused = it.hasFocus }
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onToggle(!checked); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(checked) { detectTapGestures { onToggle(!checked) } }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        // onCheckedChange = null: display-only, so the switch is not a SECOND focus stop inside a row
        // that is already one (the row's pointerInput still toggles it under touch).
        Box(
            Modifier
                .requiredHeight(32.dp)
                .dpadRingWhen(rowFocused, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** A single menu entry. Renders a `DropdownMenuItem` under touch, or a focusable chooser row
 * (auto-focused if it's the first) under D-pad. [onClick] should also dismiss the menu, exactly
 * as it did for the DropdownMenuItem it replaces. */
@Composable
fun VelaMenuScope.item(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    if (!dpad) {
        DropdownMenuItem(
            text = { Text(text) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
            onClick = onClick,
        )
        return
    }
    val autoFocus = index == 0
    // Feature-phone bordered menu: a divider between every item (drawn as a leading border on all
    // but the first, so the lines sit BETWEEN rows and the container border closes the ends).
    if (bordered && index > 0) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
    index++
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    if (autoFocus) {
        LaunchedEffect(dpadFirst) {
            if (dpadFirst) repeat(30) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            // Square highlight to match the square container (no rounded ring inside a bordered list).
            .dpadHighlight(if (bordered) RectangleShape else RoundedCornerShape(8.dp))
            // Single focus target = the explicit .focusable() (the only thing requestFocus lands
            // on in a raw Dialog); OK fires the action; touch tap via pointerInput (not .clickable,
            // which would add a 2nd focus target).
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onClick(); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
