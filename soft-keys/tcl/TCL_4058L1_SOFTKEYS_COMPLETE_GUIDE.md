# TCL Flip Softkey Implementation Guide

`android.widget.MenuBar` is a TCL framework widget (not in the Android SDK) that controls
the three-key softkey bar at the bottom of the screen on TCL flip phones (4058-series,
Gflip6). Every window's `DecorView` already hosts one. It is accessed via reflection from
an ordinary app. This guide covers everything needed to drive it correctly.

Verified on: TCL 4058L1 (Gflip6_USCC), Android 11 (API 30).

```
+----------+----------+----------+
|   SK1    |   CSK    |   SK2    |
| (Left)   | (Center) | (Right)  |
| index 0  | index 1  | index 2  |
| key 1    | key 23   | key 2    |
+----------+----------+----------+
```

Note the ordering: unlike some OEM bars, the TCL indices run left-to-right
(Left=0, Center=1, Right=2). The keycode for each column is different from its index --
see below.

---

## Constants

### Softkey Indices (the `int position` argument)

```java
int LEFT_SOFT_KEY   = 0;   // SK1, left
int CENTER_SOFT_KEY = 1;   // CSK, center
int RIGHT_SOFT_KEY  = 2;   // SK2, right
```

### Softkey Keycodes (what `onKeyUp` receives)

| Keycode | Constant | Column | KeyEvent name |
|---------|----------|--------|---------------|
| 1 | LSK_KEY_CODE | Left (index 0) | KEYCODE_SOFT_LEFT |
| 2 | RSK_KEY_CODE | Right (index 2) | KEYCODE_SOFT_RIGHT |
| 23 | CSK_KEY_CODE | Center (index 1) | KEYCODE_DPAD_CENTER |
| 66 | (also center) | Center (index 1) | KEYCODE_ENTER |
| 4 | BACK_KEY_CODE | -- | KEYCODE_BACK (dismisses open menus) |

The bar maps keycode -> column internally. You pass **indices** to the setters and receive
**callbacks** (not keycodes) from the listener.

### Listener Callbacks (`MenuBarListener`)

```java
boolean onClickLSK();          // left softkey pressed
boolean onClickCSK();          // center softkey / OK pressed
boolean onClickRSK();          // right softkey pressed (only if no option menu is set)
void    onOptionMenuClick(int id);   // an overflow-menu item was chosen (id = your Pair.first)
void    onOptionMenuShow();          // overflow menu opened
void    onOptionMenuDismiss();       // overflow menu closed
```

Return `true` from the `onClick*` methods to consume the event.

---

## Setup

### Class Names

```java
private static final String MENUBAR_CLASS  = "android.widget.MenuBar";
private static final String LISTENER_CLASS = "android.widget.MenuBar$MenuBarListener";
```

### Fields to Cache

```java
private Object  mBar;              // the android.widget.MenuBar instance
private Method  mUpdateMenuBar;    // (String,String,String,List)
private Method  mSetSoftKeyName;   // (int, String)
private Method  mSetOptionMenu;    // (List)
private Method  mOnKeyUp;          // (int, KeyEvent)
private boolean mBarReady = false;
```

### Acquiring the bar

The `DecorView` of every window already owns a `MenuBar`. Get it by reflection through the
hidden `DecorView.getMenuBar()` method -- this is the analogue of a "softkey guide" handle.
The decor is not laid out in `onCreate()`, so acquire it in `onResume()`.

```java
private void initSoftkeys() {
    try {
        View decor      = getWindow().getDecorView();
        Method getBar   = decor.getClass().getMethod("getMenuBar");   // DecorView.getMenuBar()
        mBar            = getBar.invoke(decor);
        if (mBar == null) {
            mBarReady = true;      // not a TCL MenuBar device -- stop retrying
            return;
        }
        Class<?> cls   = mBar.getClass();   // android.widget.MenuBar
        mUpdateMenuBar = cls.getMethod("updateMenuBar",
                            String.class, String.class, String.class, java.util.List.class);
        mSetSoftKeyName= cls.getMethod("setSoftKeyName", int.class, String.class);
        mSetOptionMenu = cls.getMethod("setOptionMenu", java.util.List.class);
        mOnKeyUp       = cls.getMethod("onKeyUp", int.class, android.view.KeyEvent.class);

        wireListener(cls);
        mBarReady = true;
        refreshSoftkeys();
    } catch (Exception ignored) {
        mBar = null;
    }
}

@Override protected void onResume() {
    super.onResume();
    if (!mBarReady) initSoftkeys();
    else            refreshSoftkeys();
}
```

Alternative: you may also `new MenuBar(context)` via reflection and add it to your own
layout, but the decor's instance is already positioned and is what receives softkey events
through the window, so prefer `getMenuBar()`.

---

## Applying Softkeys

All three labels are set in one call. An **empty string hides that key**; a non-empty
string shows it. Unlike some OEM bars, there is **no `invalidate()` step** -- the widget
updates its `TextView`s synchronously inside the setter. `refreshMenuBar()` is a no-op.

```java
private void applySoftkeys(String left, String center, String right,
                           List<Pair<Integer,Integer>> options) {
    if (mBar == null) return;
    if (left   == null) left   = "";
    if (center == null) center = "";
    if (right  == null) right  = "";
    try {
        mUpdateMenuBar.invoke(mBar, left, center, right, options);   // options may be null
    } catch (Exception ignored) {}
}
```

Individual keys, when you only need to change one:

```java
mSetSoftKeyName.invoke(mBar, 0, "Back");     // index 0 = left
mSetSoftKeyName.invoke(mBar, 1, "OK");       // index 1 = center
mSetSoftKeyName.invoke(mBar, 2, "Options");  // index 2 = right
```

Other setters available on the class (reflect as needed):

| Method | Effect |
|--------|--------|
| `setSoftKeyName(int i, int strRes)` | Label from a string resource (0 -> empty/hidden) |
| `setSoftKeyImage(int i, Drawable d)` | Icon instead of text (clears that key's name) |
| `setSoftKeyTipsImage(int i, Drawable d)` | Badge on the LEFT key only (i=0), e.g. a red dot |
| `updateMenuBar(int lRes,int cRes,int rRes,List)` | Same as string form, from resources |
| `setSoftKeyTextColor(int color,int i)` | Per-key text color (simple mode) |
| `setMenuBarBackgroundColor(int color)` | Bar background (simple mode) |

**Rules:**
- Pass `""` for any column you want blank. A blank center key is common in list screens.
- Right-key label defaults to a localized "Options" only inside `com.android.emergency`;
  everywhere else a blank key stays blank.
- All labels must come from `res/values/strings.xml` for localization.

---

## Wiring the Listener

`MenuBarListener` is a framework interface not visible at compile time. Implement it at
runtime with `java.lang.reflect.Proxy`. Wire it **once** in `initSoftkeys()`; branch on
your current mode inside the handlers.

```java
private void wireListener(Class<?> cls) throws Exception {
    Class<?> lCls = Class.forName(LISTENER_CLASS);
    Method setListener = cls.getMethod("setMenuBarListener", lCls);
    Object proxy = java.lang.reflect.Proxy.newProxyInstance(
        lCls.getClassLoader(), new Class[]{lCls},
        new java.lang.reflect.InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "onClickLSK": onLsk(); return Boolean.TRUE;
                    case "onClickCSK": onCsk(); return Boolean.TRUE;
                    case "onClickRSK": onRsk(); return Boolean.TRUE;
                    case "onOptionMenuClick":                 // a[0] = your item id
                        onOption(((Integer) a[0]).intValue()); return null;
                    case "onOptionMenuShow":
                    case "onOptionMenuDismiss": return null;
                }
                // onClick* return boolean; everything else returns void/null
                return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        });
    setListener.invoke(mBar, proxy);
}
```

**Forward physical softkeys to the bar.** `MenuBar.onKeyUp` only runs its logic when a
listener is set, and only the Activity receives the physical key. Forward it:

```java
@Override public boolean onKeyUp(int keyCode, KeyEvent event) {
    try {
        if (mBar != null && (Boolean) mOnKeyUp.invoke(mBar, keyCode, event)) return true;
    } catch (Exception ignored) {}
    return super.onKeyUp(keyCode, event);
}
```

**How the bar dispatches (from `MenuBar.onKeyUp`):**
- keycode **1** (left) -> `onClickLSK()`; if an IME is open over an `EditText` with more
  than one input method, it opens the input-method chooser instead.
- keycode **2** (right) -> if an option menu list is set, it opens the overflow menu;
  otherwise `onClickRSK()`.
- keycode **23 / 66** (center / enter) -> `onClickCSK()`.
- keycode **4** (back) -> dismisses the option or IME menu if one is showing.
- A key with a label plays the standard click sound; a blank key is silent.

**Mode branching inside handlers** -- substitute your own modes:

```java
private void onLsk() {
    if (mListMode)      backOut();
    else                finish();
}
private void onCsk() {
    if (mListMode)      openSelected();
    else                handleDefault();
}
private void onRsk() {                 // only reached when no option menu is set
    showMyOwnMenu();
}
```

---

## Option (Overflow) Menu

If you attach an option list, the **right softkey opens it automatically** -- you do not
handle RSK yourself. Items are `List<Pair<Integer,Integer>>` = `(itemId, labelStringRes)`.
`onOptionMenuClick(itemId)` fires with your `Pair.first`.

```java
List<Pair<Integer,Integer>> opts = new ArrayList<>();
opts.add(new Pair<>(1001, R.string.opt_new));
opts.add(new Pair<>(1002, R.string.opt_delete));
opts.add(new Pair<>(1003, R.string.opt_settings));

// attach via updateMenuBar(...) last arg, or setOptionMenu(...)
applySoftkeys(getString(R.string.sk_back), getString(R.string.sk_ok),
              getString(R.string.sk_options), opts);
```

- Number keys select items directly while the menu is open: keys `1`-`9` (keycodes 8-16)
  pick items 0-8, and `0` (keycode 7) picks item index 9.
- `setOptionMenuTitle(String)` adds a title header.
- `setOptionMenuItemBackground(int resId)` themes rows.
- `showOptionMenu(int focusIndex)` / `dismissOptionMenu()` open/close it manually.

---

## IME Integration

The left softkey doubles as the **input-method switcher** for the stock kika keyboard.
When an `EditText` is focused and the IME is open, pressing the left key (keycode 1) opens
the IME chooser if more than one method is available (`Kt9/abc/Abc/ABC/123/Symbols`). The
bar drives this by broadcasting `kika.ime.change.to.*` intents.

Relevant methods if you manage IME state yourself:

| Method | Effect |
|--------|--------|
| `setAvailableInputMethodStr(String cur, String all)` | Tell the bar which IMEs exist |
| `setInputMethod(String name)` | Switch to a named method (Kt9/abc/ABC/123/Symbols) |
| `handleKeyStarEvent()` | Re-show the IME (posted 50 ms later) -- wired to the `*` key |
| `getAvailableInputMethods()` | Current list |

If your app has no text entry, you can ignore this section entirely -- the left key simply
calls `onClickLSK()` when no IME is up.

---

## D-pad Mirroring

The physical D-pad center is keycode **23**, which the bar already routes to `onClickCSK()`
through your forwarded `onKeyUp`. If you also handle D-pad in `onKeyDown` for custom
navigation, keep the actions identical:

```java
@Override public boolean onKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_ENTER:
            if (event.getRepeatCount() == 0) onCsk();   // mirror the CSK action
            return true;
        case KeyEvent.KEYCODE_BACK:
            onLsk(); return true;                        // if your LSK == Back
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            return super.onKeyDown(keyCode, event);      // normal focus navigation
    }
    return super.onKeyDown(keyCode, event);
}
```

D-pad up/down/left/right are plain Android `FocusFinder` navigation -- do not consume them
unless you are implementing custom movement. `config_wakeOnDpadKeyPress` is true, so any
D-pad press also wakes the screen.

---

## Simple Mode / "Dish" Style

Some TCL builds enable a larger accessibility bar:

- `def_menu_bar_dish` (framework bool) selects the "dish" layout.
- `Settings.Secure "def_simple_mode" == 1` turns on **simple mode**: 16sp bold labels,
  a separator line, light background.
- Call `updateMenuBarStyle()` after a mode toggle so the bar restyles.

Your labels and listener are unaffected -- only sizing/colors change.

---

## Common Pitfalls

**1. Acquiring the bar in onCreate.**
The `DecorView` is not laid out yet and `getMenuBar()` can return null / an unattached
instance. Acquire and configure in `onResume()`.

**2. Not forwarding onKeyUp.**
`MenuBar.onKeyUp` contains all softkey logic, but the physical key is delivered to your
Activity. If you don't forward `onKeyUp` to the bar, none of the callbacks fire.

**3. Expecting callbacks with no listener set.**
`onKeyUp` returns immediately (via `super`) when `mMenuBarListener == null`. Always call
`setMenuBarListener(...)` before relying on clicks.

**4. Handling RSK while an option menu is set.**
When an option list is attached, the right key opens the overflow menu and `onClickRSK()`
is **not** called. Either handle items via `onOptionMenuClick`, or don't set an option list
if you want raw RSK clicks.

**5. Confusing index order with keycode order.**
Indices are Left=0, Center=1, Right=2. Keycodes are Left=1, Right=2, Center=23. Passing a
keycode where an index is expected puts labels on the wrong key.

**6. Looking for invalidate().**
There is none. Setters apply immediately. Calling a non-existent `invalidate()` by
reflection throws and is silently swallowed, making it look like nothing happened.

**7. Hardcoded label strings.**
All softkey labels must be string resources; the bar itself localizes only a couple of
built-in defaults.

---

## Minimum Working Snippet

```java
try {
    View decor  = getWindow().getDecorView();
    Object bar  = decor.getClass().getMethod("getMenuBar").invoke(decor);
    if (bar != null) {
        Class<?> cls  = bar.getClass();
        Class<?> lCls = Class.forName("android.widget.MenuBar$MenuBarListener");

        cls.getMethod("setMenuBarListener", lCls).invoke(bar,
            java.lang.reflect.Proxy.newProxyInstance(lCls.getClassLoader(),
                new Class[]{lCls}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "onClickLSK": finish();            return true;
                        case "onClickCSK": doOk();              return true;
                        case "onClickRSK": doOptions();         return true;
                    }
                    return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }));

        cls.getMethod("updateMenuBar", String.class, String.class, String.class,
                      java.util.List.class)
           .invoke(bar, getString(R.string.sk_back),
                        getString(R.string.sk_ok),
                        getString(R.string.sk_options), null);
    }
} catch (Exception ignored) {}
```

And forward the keys:

```java
@Override public boolean onKeyUp(int code, KeyEvent e) {
    try {
        Object bar = getWindow().getDecorView().getClass()
                        .getMethod("getMenuBar").invoke(getWindow().getDecorView());
        if (bar != null && (Boolean) bar.getClass()
                .getMethod("onKeyUp", int.class, KeyEvent.class).invoke(bar, code, e))
            return true;
    } catch (Exception ignored) {}
    return super.onKeyUp(code, e);
}
```

---

Derived from decompiled `android.widget.MenuBar` (framework.jar / classes3.dex, see
companion `MenuBar.java`) and cross-checked against `com.android.launcher3` usage.
Device: TCL 4058L1 (Gflip6_USCC), Android 11, API 30. Custom softkey/flip keycodes on this
platform: SOFT_LEFT=1, SOFT_RIGHT=2, DPAD_CENTER=23, CLAMSHELL=290, QUICK_DIAL=291,
FAVORITE_CONTACTS=292, MESSENGER=289.
