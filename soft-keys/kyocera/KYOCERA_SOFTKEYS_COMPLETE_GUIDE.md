# Kyocera Softkey Implementation Guide

`KCfpSoftkeyGuide` is a Kyocera framework class (not in the Android SDK) that controls
the three-key softkey bar at the bottom of the screen. It is accessed entirely via
reflection. This guide covers everything needed to implement it correctly.

```
+----------+----------+----------+
|   SK1    |   CSK    |   SK2    |
| (Left)   | (Center) | (Right)  |
| index 1  | index 0  | index 2  |
+----------+----------+----------+
```

---

## Constants

### Softkey Indices

```java
int INDEX_CSK = 0;   // Center
int INDEX_SK1 = 1;   // Left
int INDEX_SK2 = 2;   // Right
```

### Priority Levels

| Value | Constant | Use when |
|-------|----------|----------|
| 0 | SK_PRIORITY_DEFAULT | Unset |
| 1 | SK_PRIORITY_FW | Framework internal; loses to all app levels |
| 2 | SK_PRIORITY_LIST | Normal list/selection screen |
| 3 | SK_PRIORITY_DIALOG | Dialog over a list |
| 4 | SK_PRIORITY_TEXT | IME open / text input active |

### Auto-Text Sentinels

Passed as label strings. Never shown as literal text.

`@SK_AUTO` is handled inside `setText()` itself: `adjustAutoText()` replaces it with
`KCfpConfig.DEFAULT_SOFTKEYS[i]` before storing, so the device default label is stored
directly. The remaining sentinels are stored as-is and interpreted by the system
compositor.

| Sentinel | When to use |
|----------|-------------|
| `@SK_AUTO` | Stores the device default label for that index at setText() call time |
| `@SK_AUTO_ENTER` | CSK while IME is open (always disable the key alongside this) |
| `@SK_AUTO_MENU` | Key should open the system options menu |
| `@SK_AUTO_SELECT` | CSK should use the system select affordance |

The source also defines `@SK_ENTER` and `@SK_SELECT` as public constants. These are NOT
intercepted by the compositor -- they do not start with `@SK_AUTO` so `isAutoState()`
treats them as literal strings and they render verbatim on the softkey bar. Do not pass
them as label strings expecting special behavior.

---

## Setup

### Class Names

```java
private static final String GUIDE_CLASS    = "jp.kyocera.kcfp.util.KCfpSoftkeyGuide";
private static final String LISTENER_CLASS =
        "jp.kyocera.kcfp.util.KCfpSoftkeyGuide$SoftkeyEventListener";
```

### Fields to Cache

```java
private Object  mGuide;
private Method  mSetText;
private Method  mSetEnabled;
private Method  mSetPriority;
private Method  mInvalidate;
private boolean mSoftkeysReady = false;
```

### Initialization

Call from `onResume()` only. The window is not attached in `onCreate()`. All softkey
calls must be made on the main thread.

```java
private void initSoftkeys() {
    try {
        Class<?> cls    = Class.forName(GUIDE_CLASS);
        Method getGuide = cls.getMethod("getSoftkeyGuide", android.view.Window.class);
        mGuide          = getGuide.invoke(null, getWindow());
        if (mGuide == null) {
            mSoftkeysReady = true;   // not a Kyocera device -- stop retrying every onResume
            return;
        }

        mSetText     = cls.getMethod("setText",    int.class, CharSequence.class);
        mSetEnabled  = cls.getMethod("setEnabled", int.class, boolean.class);
        mSetPriority = cls.getMethod("setPriority",int.class, int.class);
        mInvalidate  = cls.getMethod("invalidate");

        wireListeners(cls);

        mSoftkeysReady = true;
        refreshSoftkeys();

    } catch (Exception ignored) {
        mGuide = null;
    }
}

@Override
protected void onResume() {
    super.onResume();
    if (!mSoftkeysReady) initSoftkeys();
    else                 refreshSoftkeys();
}
```

---

## Applying Softkeys

All label changes go through one method. Set priority, text, and enabled state for all
three keys, then call `invalidate()` once. Nothing reaches the system until `invalidate()`
is called.

```java
private static final String SK_AUTO_ENTER    = "@SK_AUTO_ENTER";
private static final int    SK_PRIORITY_LIST   = 2;
private static final int    SK_PRIORITY_DIALOG = 3;
private static final int    SK_PRIORITY_TEXT   = 4;

private void applySoftkeys(int priority, String csk, String sk1, String sk2) {
    if (mGuide == null) return;
    if (csk == null) csk = "";
    if (sk1 == null) sk1 = "";
    if (sk2 == null) sk2 = "";
    // CSK disabled when empty or @SK_AUTO_ENTER (the system owns that key)
    boolean cskEnabled = !csk.isEmpty() && !SK_AUTO_ENTER.equals(csk);
    try {
        mSetPriority.invoke(mGuide, 0, priority);
        mSetPriority.invoke(mGuide, 1, priority);
        mSetPriority.invoke(mGuide, 2, priority);
        mSetText.invoke(mGuide, 0, (CharSequence) csk);
        mSetText.invoke(mGuide, 1, (CharSequence) sk1);
        mSetText.invoke(mGuide, 2, (CharSequence) sk2);
        mSetEnabled.invoke(mGuide, 0, cskEnabled);
        mSetEnabled.invoke(mGuide, 1, !sk1.isEmpty());
        mSetEnabled.invoke(mGuide, 2, !sk2.isEmpty());
        mInvalidate.invoke(mGuide);
    } catch (Exception ignored) {}
}
```

**Priority rules:**
- Use `SK_PRIORITY_TEXT` (4) whenever the IME is open. Lower priorities get overridden
  by the system during the softkey merge.
- Use `SK_PRIORITY_DIALOG` (3) in dialog guides.
- Use `SK_PRIORITY_LIST` (2) for normal and batch-action modes.
- Set all three indices to the same priority within any single mode.

---

## Wiring Listeners

`SoftkeyEventListener` is a Kyocera interface not accessible at compile time. Use
`java.lang.reflect.Proxy` to implement it at runtime.

Wire listeners once in `initSoftkeys()`. Do not re-wire on mode changes. Branch on
current mode inside each handler.

```java
private void wireListeners(Class<?> cls) throws Exception {
    Class<?> lCls      = Class.forName(LISTENER_CLASS);
    Method setListener = cls.getMethod("setSoftkeyEventListener", int.class, lCls);

    setListener.invoke(mGuide, 0, java.lang.reflect.Proxy.newProxyInstance(
            lCls.getClassLoader(), new Class[]{lCls},
            new java.lang.reflect.InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onSoftkeySelected".equals(method.getName())) onCsk();
                    return null;
                }
            }));

    setListener.invoke(mGuide, 1, java.lang.reflect.Proxy.newProxyInstance(
            lCls.getClassLoader(), new Class[]{lCls},
            new java.lang.reflect.InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onSoftkeySelected".equals(method.getName())) onSk1();
                    return null;
                }
            }));

    setListener.invoke(mGuide, 2, java.lang.reflect.Proxy.newProxyInstance(
            lCls.getClassLoader(), new Class[]{lCls},
            new java.lang.reflect.InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onSoftkeySelected".equals(method.getName())) onSk2();
                    return null;
                }
            }));
}
```

**How listeners fire:** The framework maps physical softkey button keycodes to softkey
indices and calls `onSoftkeySelected` on `ACTION_UP`, only when the key is enabled. If a
listener is registered for an index, that key's events are consumed and do not reach
`onKeyDown`, even when the key is disabled.

**Mode branching inside handlers** -- substitute your own modes:

```java
private void onCsk() {
    if (mModeA) { handleModeAConfirm(); }
    else if (mModeB) { /* key is disabled in this mode, never called */ }
    else { handleDefaultAction(); }
}

private void onSk1() {
    if (mModeA) { handleModeASecondary(); }
    else if (!mModeB) { enterModeB(); }
}

private void onSk2() {
    if (mModeA)      { exitModeA(); }
    else if (mModeB) { exitModeB(); }
    else             { showOptions(); }
}
```

---

## Mode Switching

Keep one `refreshSoftkeys()` method. Call it after every state change and in `onResume()`
after initialization. All label strings must come from `res/values/strings.xml`.

```java
// Replace these booleans with whatever modes your app has
private boolean mModeA = false;   // e.g. batch selection
private boolean mModeB = false;   // e.g. text input / IME active

private void refreshSoftkeys() {
    if (mModeA) {
        applySoftkeys(SK_PRIORITY_LIST,
            getString(R.string.sk_mode_a_csk),
            getString(R.string.sk_mode_a_sk1),
            getString(R.string.sk_mode_a_sk2));
    } else if (mModeB) {
        applySoftkeys(SK_PRIORITY_TEXT,
            SK_AUTO_ENTER,                          // system draws IME confirm
            "",
            getString(R.string.sk_back));
    } else {
        applySoftkeys(SK_PRIORITY_LIST,
            getString(R.string.sk_default_csk),
            getString(R.string.sk_default_sk1),
            getString(R.string.sk_default_sk2));
    }
}
```

**Typical mode-to-label mapping:**

| Mode | CSK | SK1 | SK2 | Priority |
|------|-----|-----|-----|----------|
| Default | Primary action | Secondary action | Options | LIST (2) |
| IME / text input | @SK_AUTO_ENTER (disabled) | (empty) | Back | TEXT (4) |
| Batch selection | Confirm action | Alternate action | Cancel | LIST (2) |
| Dialog | OK | (optional) | Cancel | DIALOG (3) |

---

## Dialog Softkeys

Each dialog has its own window and its own independent `KCfpSoftkeyGuide`. Obtain it
from `dialog.getWindow()` inside `setOnShowListener`. The dialog window is not attached
before `show()` is called.

```java
private void configureDialogSoftkeys(final AlertDialog dialog) {
    try {
        Class<?> cls    = Class.forName(GUIDE_CLASS);
        Class<?> lCls   = Class.forName(LISTENER_CLASS);
        Method getGuide = cls.getMethod("getSoftkeyGuide", android.view.Window.class);
        Object dGuide   = getGuide.invoke(null, dialog.getWindow());
        if (dGuide == null) return;

        Method setText     = cls.getMethod("setText",    int.class, CharSequence.class);
        Method setEnabled  = cls.getMethod("setEnabled", int.class, boolean.class);
        Method setPriority = cls.getMethod("setPriority",int.class, int.class);
        Method invalidate  = cls.getMethod("invalidate");
        Method setListener = cls.getMethod("setSoftkeyEventListener", int.class, lCls);

        // Recommended: ensures these values win if view-level softkeys are also present.
        // Not strictly required for dialogs with only app-set values.
        setPriority.invoke(dGuide, 0, SK_PRIORITY_DIALOG);
        setPriority.invoke(dGuide, 1, SK_PRIORITY_DIALOG);
        setPriority.invoke(dGuide, 2, SK_PRIORITY_DIALOG);

        // CSK -- confirm
        setText.invoke(dGuide, 0, (CharSequence) getString(R.string.sk_ok));
        setEnabled.invoke(dGuide, 0, true);
        setListener.invoke(dGuide, 0, java.lang.reflect.Proxy.newProxyInstance(
                lCls.getClassLoader(), new Class[]{lCls},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onSoftkeySelected".equals(method.getName())) {
                            onDialogConfirm();
                            dialog.dismiss();
                        }
                        return null;
                    }
                }));

        // SK1 -- unused (add label + listener if needed)
        setText.invoke(dGuide, 1, (CharSequence) "");
        setEnabled.invoke(dGuide, 1, false);

        // SK2 -- cancel / dismiss
        setText.invoke(dGuide, 2, (CharSequence) getString(R.string.sk_cancel));
        setEnabled.invoke(dGuide, 2, true);
        setListener.invoke(dGuide, 2, java.lang.reflect.Proxy.newProxyInstance(
                lCls.getClassLoader(), new Class[]{lCls},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onSoftkeySelected".equals(method.getName())) {
                            dialog.dismiss();
                        }
                        return null;
                    }
                }));

        invalidate.invoke(dGuide);
    } catch (Exception ignored) {}
}
```

Call it from `setOnShowListener`:

```java
dialog.setOnShowListener(new DialogInterface.OnShowListener() {
    @Override
    public void onShow(DialogInterface d) {
        configureDialogSoftkeys((AlertDialog) d);
    }
});
dialog.show();
```

When the dialog is dismissed its window is detached and the guide is discarded. No
cleanup needed.

---

## IME Integration

When an `EditText` gains focus and the IME opens, set CSK to `@SK_AUTO_ENTER` and
disable it. The system renders the appropriate confirm action. Your listener is not called
on a disabled key.

```java
// Entering a text-input mode
mModeB = true;
mEditText.setVisibility(View.VISIBLE);
mEditText.requestFocus();
InputMethodManager imm =
        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
if (imm != null) imm.showSoftInput(mEditText, 0);
refreshSoftkeys();   // applies @SK_AUTO_ENTER + TEXT priority
```

Customize the IME's right softkey via `privateImeOptions` on the `EditText`:

```xml
<EditText
    android:privateImeOptions="KC_IME_RSK_LABEL:Close,KC_IME_RSK_ENABLE_MIN_LENGTH:0"
    android:imeOptions="actionDone" />
```

| Option | Effect |
|--------|--------|
| `KC_IME_RSK_LABEL:<text>` | Sets the IME's right softkey label |
| `KC_IME_RSK_ENABLE_MIN_LENGTH:<n>` | Enables the right softkey when text length >= n |

Restore normal softkeys when the IME closes:

```java
mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            // mContentView: whatever view should receive focus after search closes
            // (e.g. your ListView, RecyclerView, or root layout)
            mContentView.requestFocus();
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
            }
            mModeB = false;
            refreshSoftkeys();
            return true;
        }
        return false;
    }
});
```

---

## D-pad Mirroring

Softkey bar buttons fire `SoftkeyEventListener`. Physical D-pad keys fire `onKeyDown`.
Both must trigger identical actions.

```java
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_ENTER:
            if (mModeB) return true;   // IME mode -- consume, do nothing
            if (event.getRepeatCount() > 0) {
                if (!mModeA) enterModeA();   // long-press enters mode A
                return true;                 // suppress repeat ticks in all cases
            }
            onCsk();
            return true;
        case KeyEvent.KEYCODE_BACK:
            if (mModeB) { exitModeB(); return true; }
            if (mModeA) { exitModeA(); return true; }
            finish();
            return true;
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
            return super.onKeyDown(keyCode, event);
    }
    return super.onKeyDown(keyCode, event);
}
```

---

## Common Pitfalls

**1. Calling initSoftkeys() in onCreate.**
The window is not attached in `onCreate`. `getSoftkeyGuide` returns null. Always
initialize in `onResume()`.

**2. Forgetting invalidate().**
`setText` and `setEnabled` set an internal dirty flag and stage changes locally. Nothing
reaches the system until `invalidate()` is called. Call it once at the end of every
batch. Note: `setPriority` does NOT set the dirty flag -- calling only `setPriority` then
`invalidate()` is a no-op. Always include a `setText` or `setEnabled` call in the same
batch so the priority change is committed with it.

**3. Setting @SK_AUTO_ENTER without disabling the key.**
`@SK_AUTO_ENTER` is only a display hint to the softkey bar renderer. It has no effect on
event dispatch. If CSK is enabled, your listener fires and the event is fully consumed --
the system never processes the sentinel as an IME confirm action. Always pair
`@SK_AUTO_ENTER` with `setEnabled(0, false)` so the event reaches the IME via its own
separate channel rather than your listener.

**4. Using SK_PRIORITY_LIST (2) while the IME is open.**
The system can merge higher-priority values on top of your IME-mode labels, reverting CSK
unexpectedly. Use `SK_PRIORITY_TEXT` (4) whenever the IME is active.

**5. Configuring dialog softkeys before onShow.**
The dialog window does not exist until `show()` is called. `getSoftkeyGuide` returns null
if called earlier. Always configure inside `setOnShowListener`.

**6. Getting the dialog guide from the activity window.**
```java
// Wrong -- gets the activity guide
Object dGuide = getGuide.invoke(null, getWindow());

// Correct -- the dialog has its own window
Object dGuide = getGuide.invoke(null, dialog.getWindow());
```

**7. Re-wiring listeners on every mode change.**
Wire once in `initSoftkeys()`. Branch on mode inside each handler. Re-wiring introduces
race conditions and is unnecessary.

**8. Hardcoded label strings.**
All softkey labels must be string resources. Hardcoded strings break localization.

---

## Minimum Working Snippet

```java
try {
    Class<?> cls = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide");
    Object guide = cls.getMethod("getSoftkeyGuide", android.view.Window.class)
                      .invoke(null, getWindow());
    if (guide != null) {
        cls.getMethod("setPriority", int.class, int.class).invoke(guide, 0, 2);
        cls.getMethod("setPriority", int.class, int.class).invoke(guide, 1, 2);
        cls.getMethod("setPriority", int.class, int.class).invoke(guide, 2, 2);
        cls.getMethod("setText", int.class, CharSequence.class)
           .invoke(guide, 0, (CharSequence) getString(R.string.sk_csk));
        cls.getMethod("setText", int.class, CharSequence.class)
           .invoke(guide, 1, (CharSequence) getString(R.string.sk_sk1));
        cls.getMethod("setText", int.class, CharSequence.class)
           .invoke(guide, 2, (CharSequence) getString(R.string.sk_sk2));
        cls.getMethod("setEnabled", int.class, boolean.class).invoke(guide, 0, true);
        cls.getMethod("setEnabled", int.class, boolean.class).invoke(guide, 1, true);
        cls.getMethod("setEnabled", int.class, boolean.class).invoke(guide, 2, true);
        cls.getMethod("invalidate").invoke(guide);
    }
} catch (Exception ignored) {}
```

---

Derived from decompiled `KCfpSoftkeyGuide.java` (framework DEX) and verified against a
production implementation. Device: Kyocera DuraXV Extreme E4810, Android 9, API 28.
