# Sonim Softkey Implementation Guide

Sonim phones (XP3plus, X320, XP5s) expose their three-key softkey bar through a
**system broadcast**, not through a guide object or widget. There is no handle to
acquire -- just send the broadcast and the bar redraws. Verified on X320 (Android 9).

```
+----------+----------+----------+
|   Left   |  Center  |  Right   |
| "Menu"   |  (OK)    | "Back"   |
+----------+----------+----------+
```

---

## Setting labels

```kotlin
val intent = Intent("android.intent.action.CHANGE_NAV_BAR").apply {
    putExtra("left",         "Menu")    // left softkey label
    putExtra("center",       "")        // center label (usually blank -- it is the OK/D-pad key)
    putExtra("right",        "Back")    // right softkey label
    putExtra("from_package", packageName)
}
context.sendBroadcast(intent)
```

Pass an empty string for any key you want hidden. The system bar redraws synchronously.

---

## Receiving key presses

Unlike Kyocera/TCL there is no listener interface. Physical key events arrive in
`onKeyDown`/`onKeyUp` as normal Android keycodes. Map them to your actions there:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_SOFT_LEFT  -> { onLeftSoftkey(); true }
        KeyEvent.KEYCODE_SOFT_RIGHT -> { onRightSoftkey(); true }
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER      -> { onOk(); true }
        KeyEvent.KEYCODE_BACK       -> { onBack(); true }
        else -> super.onKeyDown(keyCode, event)
    }
}
```

Typical keycode mapping on Sonim:

| Keycode constant | Value | Physical key |
|-----------------|-------|--------------|
| `KEYCODE_SOFT_LEFT` | 1 | Left softkey |
| `KEYCODE_SOFT_RIGHT` | 2 | Right softkey |
| `KEYCODE_DPAD_CENTER` | 23 | Center/OK key |
| `KEYCODE_ENTER` | 66 | (also center) |
| `KEYCODE_BACK` | 4 | Back (hardware) |

---

## Setup

No initialization, no `onResume()` hook, no class reflection. Send the broadcast
whenever your state changes. A good pattern:

```kotlin
private fun refreshSoftkeys() {
    val (left, right) = when {
        inSelectionMode -> Pair("Done", "Cancel")
        else            -> Pair("Menu", "Back")
    }
    Intent("android.intent.action.CHANGE_NAV_BAR").also { i ->
        i.putExtra("left",         left)
        i.putExtra("center",       "")
        i.putExtra("right",        right)
        i.putExtra("from_package", packageName)
        sendBroadcast(i)
    }
}
```

Call `refreshSoftkeys()` after every state change and in `onResume()`.

---

## Compose integration (Vela pattern)

In Vela, Compose drives state and the Activity is just a shell. Wire the broadcast
from `MainActivity.onResume()` and from a `SideEffect` that fires whenever the
relevant UI state changes:

```kotlin
// In MapScreen or whichever top-level composable manages nav state:
SideEffect {
    activity.refreshSoftkeys(navigating, searchOpen)
}
```

```kotlin
// In MainActivity:
fun refreshSoftkeys(navigating: Boolean, searchOpen: Boolean) {
    val (l, r) = when {
        navigating  -> Pair("Steps", "Stop")
        searchOpen  -> Pair("",      "Clear")
        else        -> Pair("",      "")
    }
    Intent("android.intent.action.CHANGE_NAV_BAR").also { i ->
        i.putExtra("left",  l); i.putExtra("right", r)
        i.putExtra("center", ""); i.putExtra("from_package", packageName)
        sendBroadcast(i)
    }
}
```

---

## Common pitfalls

**1. Forgetting `from_package`.**
Some Sonim firmware versions ignore the broadcast if `from_package` is missing or
does not match the calling app's package name.

**2. Setting a label for the center key.**
The center column is the D-pad OK button. The system typically owns its label.
Pass `""` and leave it alone.

**3. Expecting a listener.**
There is none. Key presses are raw Android key events. Handle them in
`onKeyDown`/`onKeyUp`.

**4. Not re-sending on resume.**
The system may restore the default bar label on window re-focus. Call
`refreshSoftkeys()` in `onResume()` to re-assert your labels.

---

Source: reverse-engineered from Sonim launcher APK intent filters and confirmed
against a live X320 (Android 9, firmware 2.5.x). The broadcast action and extras
are stable across the XP3plus/X320/XP5s family.
