package jp.kyocera.kcfp.util;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.TtmlUtils;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowManager;
import com.android.internal.R;
import java.util.Objects;
import jp.kyocera.kcfp.internal.KCfpConfig;
import jp.kyocera.kcfp.internal.softkey.KCfpSoftkeyParser;
import jp.kyocera.kcfp.internal.softkey.KCfpSoftkeyUtil;

/* JADX INFO: loaded from: /data/data/com.termux/files/usr/tmp/kyocera_ui/boot-framework_classes3.dex */
public class KCfpSoftkeyGuide {
    public static final boolean DEBUG_CHECK_INDEX = false;
    public static final boolean DEBUG_PENDING = false;
    public static final int INDEX_APP;
    public static final int INDEX_COUNT;
    public static final int INDEX_CSK = 0;
    public static final int INDEX_SK1 = 1;
    public static final int INDEX_SK2 = 2;
    public static final int INDEX_SK3 = 3;
    public static final int INDEX_SK4 = 4;
    public static final String SK_AUTO = "@SK_AUTO";
    public static final String SK_AUTO_ENTER = "@SK_AUTO_ENTER";
    public static final String SK_AUTO_MENU = "@SK_AUTO_MENU";
    public static final String SK_AUTO_SELECT = "@SK_AUTO_SELECT";
    public static final String SK_ENTER = "@SK_ENTER";
    public static final int SK_PRIORITY_DEFAULT = 0;
    public static final int SK_PRIORITY_DIALOG = 3;
    public static final int SK_PRIORITY_FW = 1;
    public static final int SK_PRIORITY_LIST = 2;
    public static final int SK_PRIORITY_TEXT = 4;
    public static final String SK_SELECT = "@SK_SELECT";
    public static final String TAG = "KCfpSoftkeyGuide";
    private Object mCarrierSoftkeyGuide;
    private final Context mContext;
    private final boolean mIsIme;
    private final Owner mOwner;
    private SoftkeyEventListener[] mSoftkeyEventListeners;
    private final Softkeys mSoftkeys;
    public static final boolean DEBUG = Build.IS_DEBUGGABLE;
    public static final int INDEX_MENU = KCfpConfig.SOFTKEY_INDEX_MENU;

    public interface SoftkeyEventListener {
        void onSoftkeySelected(int i);
    }

    static {
        INDEX_APP = KCfpConfig.SOFTKEY_INDEX_MENU == 1 ? 2 : 1;
        INDEX_COUNT = KCfpSoftkeyInfo.getSoftkeyType();
    }

    public static KCfpSoftkeyGuide getSoftkeyGuide(Window window) {
        if (window == null) {
            return null;
        }
        return window.getSoftkeyGuide();
    }

    private KCfpSoftkeyGuide(Owner owner, Context context) {
        this.mSoftkeyEventListeners = null;
        this.mOwner = owner;
        this.mContext = context;
        this.mIsIme = owner.attrs.type == 2011;
        WindowManager.LayoutParams layoutParams = owner.attrs;
        Softkeys softkeys = new Softkeys(0);
        layoutParams.softkeys = softkeys;
        this.mSoftkeys = softkeys;
    }

    public void setText(int i, CharSequence charSequence) {
        this.mSoftkeys.setText(i, adjustAutoText(i, charSequence));
    }

    public void setText(int i, int i2) {
        CharSequence text;
        try {
            text = this.mContext.getResources().getText(i2);
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
            text = "";
        }
        this.mSoftkeys.setText(i, adjustAutoText(i, text));
    }

    public CharSequence getText(int i) {
        Softkeys currentSoftkeys = getCurrentSoftkeys();
        Softkeys softkeys = this.mOwner.attrs.softkeys;
        if (currentSoftkeys == null) {
            currentSoftkeys = new Softkeys();
        }
        if (softkeys == null) {
            softkeys = new Softkeys();
        }
        return currentSoftkeys.getCurrentText(i, softkeys);
    }

    public void setEnabled(int i, boolean z) {
        this.mSoftkeys.setEnabled(i, z);
    }

    public boolean getEnabled(int i) {
        Softkeys currentSoftkeys = getCurrentSoftkeys();
        if (currentSoftkeys != null) {
            return currentSoftkeys.getEnabled(i);
        }
        return true;
    }

    public void setResource(int i) {
        new KCfpSoftkeyParser(this.mContext, "setResource").parseSoftkeys(this.mSoftkeys, i);
    }

    public void invalidate() {
        if (this.mSoftkeys.isNeedInvalidate()) {
            long jCurrentTimeMillis = System.currentTimeMillis();
            for (int i = 0; i < INDEX_COUNT; i++) {
                if (this.mSoftkeys.values[i].updated) {
                    this.mSoftkeys.values[i].time = jCurrentTimeMillis;
                }
            }
            if (DEBUG) {
                Log.d(TAG, "invalidate: " + this);
            }
            this.mOwner.setSoftkeys(this.mSoftkeys);
            this.mSoftkeys.resetUpdatedFlag();
        }
    }

    public void show() {
        try {
            Log.d(TAG, ThreadedRenderer.OVERDRAW_PROPERTY_SHOW);
            View rootView = this.mOwner.getRootView();
            if (rootView == null) {
                Log.w(TAG, "[show] window is not yet created. so, show method is not effective.");
            } else {
                rootView.setSystemUiVisibility(rootView.getSystemUiVisibility() & (-3));
            }
        } catch (Exception e) {
            Log.e(TAG, "[show] " + e);
        }
    }

    public void hide() {
        try {
            Log.d(TAG, "hide");
            View rootView = this.mOwner.getRootView();
            if (rootView == null) {
                Log.w(TAG, "[show] window is not yet created. so, hide method is not effective.");
            } else {
                rootView.setSystemUiVisibility(rootView.getSystemUiVisibility() | 2);
            }
        } catch (Exception e) {
            Log.e(TAG, "[hide] " + e);
        }
    }

    public void translucent(boolean z) {
        if (z) {
            this.mOwner.setWindowFlags(134217728, 134217728);
        } else {
            this.mOwner.setWindowFlags(0, 134217728);
        }
    }

    public Softkeys setCarrierSoftkeyGuide(Object obj) {
        this.mCarrierSoftkeyGuide = obj;
        return this.mSoftkeys;
    }

    public Object getCarrierSoftkeyGuide() {
        return this.mCarrierSoftkeyGuide;
    }

    public void setPriority(int i, int i2) {
        this.mSoftkeys.setPriority(i, i2);
    }

    public void setDrawable(int i, Bitmap bitmap) {
        this.mSoftkeys.setDrawable(i, bitmap);
    }

    public void setDrawable(int i, int i2) {
        setDrawable(i, BitmapFactory.decodeResource(this.mContext.getResources(), i2));
    }

    public void setTextDrawable(int i, CharSequence charSequence, Bitmap bitmap) {
        if (charSequence != null && charSequence.length() != 0 && charSequence.charAt(0) == '@') {
            bitmap = null;
        }
        this.mSoftkeys.setText(i, adjustAutoText(i, charSequence), false);
        this.mSoftkeys.setDrawable(i, bitmap, false);
    }

    public void setTextDrawable(int i, int i2, int i3) {
        CharSequence text;
        Resources resources = this.mContext.getResources();
        try {
            text = resources.getText(i2);
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
            text = "";
        }
        setTextDrawable(i, text, BitmapFactory.decodeResource(resources, i3));
    }

    public void setTextDrawable(int i, CharSequence charSequence, int i2) {
        setTextDrawable(i, charSequence, BitmapFactory.decodeResource(this.mContext.getResources(), i2));
    }

    public void setTextDrawable(int i, int i2, Bitmap bitmap) {
        CharSequence text;
        try {
            text = this.mContext.getResources().getText(i2);
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
            text = "";
        }
        setTextDrawable(i, text, bitmap);
    }

    public void setReadoutText(int i, CharSequence charSequence) {
        this.mSoftkeys.setReadoutText(i, charSequence);
    }

    public void setReadoutText(int i, int i2) {
        CharSequence text;
        try {
            text = this.mContext.getResources().getText(i2);
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
            text = "";
        }
        setReadoutText(i, text);
    }

    public static String getRealReadoutText() {
        Softkeys displayedSoftkeys = KCfpSoftkeyUtil.getDisplayedSoftkeys();
        return displayedSoftkeys != null ? displayedSoftkeys.getRealReadoutText(ActivityThread.currentApplication()) : "";
    }

    public void pendingApply(boolean z) {
        if (DEBUG) {
            Log.d(TAG, "pendingApply(" + z + "): " + this);
        }
        this.mSoftkeys.setPendingState(z ? 1 : 2);
    }

    public void setSoftkeyEventListener(int i, SoftkeyEventListener softkeyEventListener) {
        if (DEBUG) {
            Log.d(TAG, "setSoftkeyEventListener(" + i + "): " + softkeyEventListener);
        }
        if (!checkIndex(i)) {
            return;
        }
        if (softkeyEventListener != null) {
            if (this.mSoftkeyEventListeners == null) {
                this.mSoftkeyEventListeners = new SoftkeyEventListener[INDEX_COUNT];
            }
            this.mSoftkeyEventListeners[i] = softkeyEventListener;
        } else if (this.mSoftkeyEventListeners != null) {
            this.mSoftkeyEventListeners[i] = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean interceptSoftkeyEvent(KeyEvent keyEvent) {
        int iKeycodeToSkIndex;
        SoftkeyEventListener softkeyEventListener;
        if (this.mSoftkeyEventListeners == null || (iKeycodeToSkIndex = KCfpSoftkeyUtil.keycodeToSkIndex(keyEvent.getKeyCode())) == -1 || (softkeyEventListener = this.mSoftkeyEventListeners[iKeycodeToSkIndex]) == null) {
            return false;
        }
        int action = keyEvent.getAction();
        if (action != 0 && action != 1) {
            return false;
        }
        if (action == 1 && this.mSoftkeys.getEnabled(iKeycodeToSkIndex)) {
            softkeyEventListener.onSoftkeySelected(iKeycodeToSkIndex);
        }
        return true;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("KCfpSoftkeyGuide{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" vr=");
        View rootView = this.mOwner.getRootView();
        if (rootView != null && rootView.isAttachedToWindow()) {
            sb.append(Integer.toHexString(System.identityHashCode(rootView.getViewRootImpl())));
        } else {
            sb.append("null");
        }
        sb.append(" sk=");
        this.mSoftkeys.appendSimpleDumpString(sb);
        sb.append('}');
        return sb.toString();
    }

    private Softkeys getCurrentSoftkeys() {
        View rootView = this.mOwner.getRootView();
        if (rootView == null) {
            return this.mOwner.attrs.softkeys;
        }
        ViewRootImpl viewRootImpl = rootView.getViewRootImpl();
        return viewRootImpl != null ? viewRootImpl.getCurrentSoftkeys() : this.mOwner.attrs.softkeys;
    }

    private CharSequence adjustAutoText(int i, CharSequence charSequence) {
        if (checkIndex(i) && SK_AUTO.equals(charSequence) && !this.mIsIme) {
            return KCfpConfig.DEFAULT_SOFTKEYS[i];
        }
        if (charSequence == null && this.mIsIme) {
            return SK_AUTO;
        }
        return charSequence;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean checkIndex(int i) {
        if (i < 0 || i >= INDEX_COUNT) {
            return false;
        }
        return true;
    }

    public static class Softkeys implements Parcelable {
        public static final Parcelable.Creator<Softkeys> CREATOR = new Parcelable.Creator<Softkeys>() { // from class: jp.kyocera.kcfp.util.KCfpSoftkeyGuide.Softkeys.1
            @Override // android.os.Parcelable.Creator
            public Softkeys createFromParcel(Parcel parcel) {
                return new Softkeys(parcel);
            }

            @Override // android.os.Parcelable.Creator
            public Softkeys[] newArray(int i) {
                return new Softkeys[i];
            }
        };
        public static final int SK_GROUP_COUNT = 2;
        public static final int SK_GROUP_NONE = 0;
        public static final int SK_GROUP_PAGE_SCROLL = 1;
        public static final int SK_PENDING_DEFAULT = 0;
        public static final int SK_PENDING_FALSE = 2;
        public static final int SK_PENDING_TRUE = 1;
        private boolean isPendingUpdated;
        private int pendingState;
        private Value[] values;

        public static Softkeys from(View view) {
            if (view == null) {
                throw new NullPointerException("view is null.");
            }
            return new Softkeys((Softkeys) view.getTag(R.id.kcfp_view_softkey), 1);
        }

        public void setViewSoftkeys(View view) {
            if (view == null) {
                throw new NullPointerException("view is null.");
            }
            Softkeys softkeys = (Softkeys) view.getTag(R.id.kcfp_view_softkey);
            if (softkeys != null && !softkeys.isChanged(this)) {
                return;
            }
            if (KCfpSoftkeyGuide.DEBUG) {
                StringBuilder sb = new StringBuilder("[setViewSoftkeys] view=");
                sb.append(view.getClass().getName());
                sb.append('{');
                sb.append(Integer.toHexString(System.identityHashCode(view)));
                sb.append('}');
                sb.append(" vr=");
                if (view.isAttachedToWindow()) {
                    sb.append(Integer.toHexString(System.identityHashCode(view.getViewRootImpl())));
                } else {
                    sb.append("null");
                }
                sb.append(" sk={");
                appendSimpleDumpString(sb);
                sb.append('}');
                Log.d(KCfpSoftkeyGuide.TAG, sb.toString());
            }
            view.setTagInternal(R.id.kcfp_view_softkey, this);
            ViewParent parent = view.getParent();
            if (parent != null) {
                parent.recomputeViewAttributes(view);
            }
        }

        public Softkeys(Parcel parcel) {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(1, false);
            readToParcel(parcel);
        }

        public Softkeys(Softkeys softkeys) {
            this(softkeys, 1);
        }

        public Softkeys(Softkeys softkeys, int i) {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(i, false);
            if (softkeys != null) {
                copyFrom(softkeys);
            }
        }

        public Softkeys() {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(1, false);
        }

        public Softkeys(int i) {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(i, false);
        }

        public Softkeys(boolean z) {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(1, z);
        }

        public Softkeys(int i, boolean z) {
            this.values = new Value[KCfpSoftkeyGuide.INDEX_COUNT];
            this.pendingState = 0;
            this.isPendingUpdated = false;
            init(i, z);
        }

        private void init(int i, boolean z) {
            this.pendingState = 0;
            for (int i2 = 0; i2 < KCfpSoftkeyGuide.INDEX_COUNT; i2++) {
                Value value = new Value();
                value.priority = i;
                value.text = !z ? KCfpConfig.DEFAULT_SOFTKEYS[i2] : KCfpSoftkeyGuide.SK_AUTO;
                value.enabled = true;
                value.drawable = null;
                this.values[i2] = value;
            }
        }

        public void setSoftkeyValue(int i, CharSequence charSequence, boolean z, int i2) {
            setSoftkeyValue(i, charSequence, null, null, z, i2);
        }

        public void setSoftkeyValue(int i, CharSequence charSequence, Bitmap bitmap, boolean z, int i2) {
            setSoftkeyValue(i, charSequence, bitmap, null, z, i2);
        }

        public void setSoftkeyValue(int i, CharSequence charSequence, Bitmap bitmap, CharSequence charSequence2, boolean z, int i2) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].priority = i2;
                if (charSequence == null) {
                    this.values[i].text = "";
                } else {
                    this.values[i].text = charSequence;
                }
                this.values[i].enabled = z;
                this.values[i].time = System.currentTimeMillis();
                this.values[i].drawable = bitmap;
                this.values[i].readoutText = charSequence2;
            }
        }

        public void setPriority(int i, int i2) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].priority = i2;
            }
        }

        public void setPendingState(int i) {
            if (this.pendingState != i) {
                this.isPendingUpdated = true;
            }
            this.pendingState = i;
        }

        public boolean isPending() {
            return this.pendingState == 1;
        }

        private void copyPendingState(Softkeys softkeys, Softkeys softkeys2) {
            if (softkeys.pendingState != 0 && softkeys2.pendingState != softkeys.pendingState) {
                softkeys2.pendingState = softkeys.pendingState;
            }
        }

        public void setText(int i, CharSequence charSequence) {
            setText(i, charSequence, true);
        }

        public void setText(int i, CharSequence charSequence, boolean z) {
            if (z) {
                setDrawable(i, null, false);
            }
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].updated = true;
                Value value = this.values[i];
                if (charSequence == null) {
                    charSequence = "";
                }
                value.text = charSequence;
            }
        }

        public void setEnabled(int i, boolean z) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].updated = true;
                this.values[i].enabled = z;
            }
        }

        public CharSequence getText(int i) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                return this.values[i].text;
            }
            return null;
        }

        @Deprecated
        public CharSequence getCurrentText(int i, Softkeys softkeys) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                if (i == 0) {
                    if (softkeys.values[i].text.equals(KCfpSoftkeyGuide.SK_AUTO_SELECT)) {
                        return KCfpSoftkeyGuide.SK_AUTO_SELECT;
                    }
                    if (softkeys.values[i].text.equals(KCfpSoftkeyGuide.SK_AUTO_ENTER)) {
                        return KCfpSoftkeyGuide.SK_AUTO_ENTER;
                    }
                }
                if (i == KCfpSoftkeyGuide.INDEX_MENU && isAutoState(this.values[i]) && softkeys.values[i].text.equals(KCfpSoftkeyGuide.SK_AUTO_MENU)) {
                    return KCfpSoftkeyGuide.SK_AUTO_MENU;
                }
                return this.values[i].text;
            }
            return null;
        }

        public boolean getEnabled(int i) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                return this.values[i].enabled;
            }
            return false;
        }

        public void setDrawable(int i, Bitmap bitmap) {
            setDrawable(i, bitmap, true);
        }

        public void setDrawable(int i, Bitmap bitmap, boolean z) {
            if (z) {
                setText(i, null);
            }
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].updated = true;
                this.values[i].drawable = bitmap;
            }
        }

        public Bitmap getDrawable(int i) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                return this.values[i].drawable;
            }
            return null;
        }

        public void setReadoutText(int i, CharSequence charSequence) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                this.values[i].updated = true;
                this.values[i].readoutText = charSequence;
            }
        }

        public CharSequence getReadoutText(int i) {
            if (KCfpSoftkeyGuide.checkIndex(i)) {
                return this.values[i].readoutText;
            }
            return null;
        }

        public String getRealReadoutText(Context context) {
            StringBuilder sb = new StringBuilder();
            if (getRealReadoutText(context, sb, 1)) {
                sb.append(", ");
            }
            if (getRealReadoutText(context, sb, 0)) {
                sb.append(", ");
            }
            if (getRealReadoutText(context, sb, 2)) {
                sb.append(", ");
            }
            int length = sb.length();
            if (length > 0) {
                sb.deleteCharAt(length - 1);
                sb.deleteCharAt(length - 2);
                return sb.toString();
            }
            return null;
        }

        private boolean getRealReadoutText(Context context, StringBuilder sb, int i) {
            CharSequence readoutText = getReadoutText(i);
            if (readoutText == null || readoutText.length() == 0) {
                readoutText = getText(i);
            }
            if (readoutText != null && readoutText.length() > 0) {
                sb.append(KCfpSoftkeyUtil.getReadoutPrefix(context, i));
                sb.append(' ');
                sb.append(readoutText);
                return true;
            }
            return false;
        }

        public boolean isChanged(Softkeys softkeys) {
            if (softkeys == null) {
                return true;
            }
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                if (this.values[i].isChanged(softkeys.values[i])) {
                    return true;
                }
            }
            if (softkeys.pendingState != 0 && this.pendingState != softkeys.pendingState) {
                return true;
            }
            return false;
        }

        public void update(Softkeys softkeys) {
            if (softkeys == null) {
                init(0, false);
                return;
            }
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                if (softkeys.values[i].updated) {
                    this.values[i].copyFrom(softkeys.values[i]);
                }
            }
            copyPendingState(softkeys, this);
        }

        public void copyFrom(Softkeys softkeys) {
            if (softkeys == null) {
                init(0, false);
                return;
            }
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                this.values[i].copyFrom(softkeys.values[i]);
            }
            copyPendingState(softkeys, this);
        }

        public void copySoftkeyFrom(Softkeys softkeys, int i) {
            if (softkeys == null || !KCfpSoftkeyGuide.checkIndex(i)) {
                return;
            }
            this.values[i].copyFrom(softkeys.values[i]);
        }

        /* JADX WARN: Removed duplicated region for block: B:19:0x0039  */
        /* JADX WARN: Removed duplicated region for block: B:22:0x0042  */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        private void mergeFrom(jp.kyocera.kcfp.util.KCfpSoftkeyGuide.Softkeys r7, boolean r8) {
            /*
                r6 = this;
                if (r7 != 0) goto L3
                return
            L3:
                if (r8 == 0) goto L6
                return
            L6:
                boolean[] r8 = r6.groupModifyCheck(r7)
                r0 = 0
                r1 = r0
            Lc:
                int r2 = jp.kyocera.kcfp.util.KCfpSoftkeyGuide.INDEX_COUNT
                if (r1 >= r2) goto L83
                jp.kyocera.kcfp.util.KCfpSoftkeyGuide$Value[] r2 = r6.values
                r2 = r2[r1]
                jp.kyocera.kcfp.util.KCfpSoftkeyGuide$Value[] r3 = r7.values
                r3 = r3[r1]
                int r4 = r2.group
                r5 = 1
                if (r4 == 0) goto L23
                int r4 = r2.group
                boolean r4 = r8[r4]
                goto L62
            L23:
                int r4 = r3.group
                if (r4 == 0) goto L2c
                int r4 = r3.group
                boolean r4 = r8[r4]
                goto L62
            L2c:
                boolean r4 = r6.isAutoState(r2)
                if (r4 == 0) goto L3b
                boolean r4 = r6.isAutoState(r3)
                if (r4 != 0) goto L3b
            L39:
                r4 = r5
                goto L62
            L3b:
                boolean r4 = r6.isAutoState(r3)
                if (r4 == 0) goto L44
            L42:
                r4 = r0
                goto L62
            L44:
                boolean r4 = r6.isAutoState(r2)
                if (r4 == 0) goto L51
                boolean r4 = r6.isAutoState(r3)
                if (r4 != 0) goto L51
                goto L39
            L51:
                if (r1 != 0) goto L5e
                int r4 = r2.priority
                if (r4 != 0) goto L5e
                boolean r4 = r6.isAutoState(r2)
                if (r4 != 0) goto L5e
                goto L42
            L5e:
                boolean r4 = r6.priorityCheck(r2, r3)
            L62:
                if (r4 == 0) goto L80
                int r4 = r3.priority
                r2.priority = r4
                java.lang.CharSequence r4 = r3.text
                r2.text = r4
                boolean r4 = r3.enabled
                r2.enabled = r4
                long r4 = r3.time
                r2.time = r4
                int r4 = r3.group
                r2.group = r4
                android.graphics.Bitmap r4 = r3.drawable
                r2.drawable = r4
                java.lang.CharSequence r3 = r3.readoutText
                r2.readoutText = r3
            L80:
                int r1 = r1 + 1
                goto Lc
            L83:
                r6.copyPendingState(r7, r6)
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: jp.kyocera.kcfp.util.KCfpSoftkeyGuide.Softkeys.mergeFrom(jp.kyocera.kcfp.util.KCfpSoftkeyGuide$Softkeys, boolean):void");
        }

        private boolean[] groupModifyCheck(Softkeys softkeys) {
            boolean[] zArr = new boolean[2];
            for (int i = 1; i < 2; i++) {
                for (int i2 = 0; i2 < KCfpSoftkeyGuide.INDEX_COUNT; i2++) {
                    if (this.values[i2].group == i) {
                        if (!isAutoState(softkeys.values[i2])) {
                            zArr[i] = priorityCheck(this.values[i2], softkeys.values[i2]);
                            if (zArr[i]) {
                                break;
                            }
                        } else {
                            zArr[i] = false;
                        }
                    } else {
                        if (softkeys.values[i2].group != i) {
                            continue;
                        } else if (!isAutoState(this.values[i2])) {
                            zArr[i] = priorityCheck(this.values[i2], softkeys.values[i2]);
                            if (!zArr[i]) {
                                break;
                            }
                        } else {
                            zArr[i] = true;
                        }
                    }
                }
            }
            return zArr;
        }

        private boolean priorityCheck(Value value, Value value2) {
            return (value.priority == 0 || value.priority == 1 || value2.priority == 0 || value2.priority == 1) ? (value.priority == 1 || value2.priority != 1) && value.time < value2.time : value.priority > value2.priority;
        }

        public void setGroup(int i, int i2) {
            if (KCfpSoftkeyGuide.checkIndex(i) && i2 >= 0 && i2 < 2) {
                this.values[i].group = i2;
            }
        }

        public boolean isNeedInvalidate() {
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                if (this.values[i].updated) {
                    return true;
                }
            }
            return this.isPendingUpdated;
        }

        public void resetUpdatedFlag() {
            if (isPending()) {
                return;
            }
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                this.values[i].updated = false;
            }
            this.isPendingUpdated = false;
        }

        public void mergeViewSoftkeys(View view, int i, boolean z) {
            if (i == 0) {
                Softkeys softkeys = (Softkeys) view.getTag(R.id.kcfp_view_softkey);
                if (softkeys != null) {
                    mergeFrom(softkeys, z);
                    if (KCfpSoftkeyGuide.DEBUG) {
                        softkeys.dumpSoftkeys("[View][mergeViewSoftkeys] merge from");
                        dumpSoftkeys("[View][mergeViewSoftkeys] merge after");
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) view;
                    int childCount = viewGroup.getChildCount();
                    for (int i2 = 0; i2 < childCount; i2++) {
                        View childAt = viewGroup.getChildAt(i2);
                        mergeViewSoftkeys(childAt, childAt.getVisibility() | i, z);
                    }
                }
            }
        }

        private boolean isAutoState(Value value) {
            return value.text.toString().startsWith(KCfpSoftkeyGuide.SK_AUTO);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Softkeys{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendSimpleDumpString(sb);
            sb.append('}');
            return sb.toString();
        }

        public void dumpSoftkeys(String str) {
            if (!KCfpConfig.DUMP_SOFTKEY) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (KCfpConfig.DUMP_SOFTKEY_DETAIL) {
                sb.append('[');
                sb.append(str);
                sb.append(']');
                sb.append("--------------");
                sb.append('\n');
                sb.append("  pendingState: ");
                sb.append(this.pendingState);
                for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                    Value value = this.values[i];
                    sb.append("  [");
                    sb.append(KCfpSoftkeyUtil.skIndexToString(i));
                    sb.append(']');
                    sb.append('\n');
                    sb.append("    priority: ");
                    sb.append(value.priority);
                    sb.append('\n');
                    sb.append("    text: ");
                    sb.append(value.text);
                    sb.append('\n');
                    sb.append("    readoutText: ");
                    sb.append(value.readoutText);
                    sb.append('\n');
                    sb.append("    drawable: ");
                    sb.append(value.drawable != null);
                    sb.append('\n');
                    sb.append("    enabled: ");
                    sb.append(value.enabled);
                    sb.append('\n');
                    sb.append("    updated: ");
                    sb.append(value.updated);
                    sb.append('\n');
                }
            } else {
                sb.append('[');
                sb.append(str);
                sb.append(']');
                sb.append(' ');
                appendSimpleDumpString(sb);
            }
            Log.d(KCfpSoftkeyGuide.TAG, sb.toString());
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void appendSimpleDumpString(StringBuilder sb) {
            sb.append(TtmlUtils.TAG_P);
            sb.append(this.pendingState);
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                Value value = this.values[i];
                sb.append(' ');
                sb.append(KCfpSoftkeyUtil.skIndexToString(i));
                sb.append('{');
                sb.append(" p");
                sb.append(value.priority);
                if (value.text != null && (value.text.length() == 0 || value.text.charAt(0) != '@')) {
                    sb.append(" t:\"");
                    sb.append(value.text);
                    sb.append('\"');
                } else {
                    sb.append(" t:");
                    sb.append(value.text);
                }
                if (value.readoutText != null) {
                    sb.append(" ro:\"");
                    sb.append(value.readoutText);
                    sb.append('\"');
                } else {
                    sb.append(" ro:");
                    sb.append(value.readoutText);
                }
                sb.append(WifiEnterpriseConfig.CA_CERT_ALIAS_DELIMITER);
                char c = '-';
                sb.append(value.drawable != null ? DateFormat.DATE : '-');
                sb.append(value.enabled ? 'e' : '-');
                if (value.updated) {
                    c = 'u';
                }
                sb.append(c);
                sb.append('}');
            }
        }

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel parcel, int i) {
            for (int i2 = 0; i2 < KCfpSoftkeyGuide.INDEX_COUNT; i2++) {
                this.values[i2].writeToParcel(parcel, i);
            }
            parcel.writeInt(this.pendingState);
        }

        private void readToParcel(Parcel parcel) {
            for (int i = 0; i < KCfpSoftkeyGuide.INDEX_COUNT; i++) {
                this.values[i].readToParcel(parcel);
            }
            this.pendingState = parcel.readInt();
        }
    }

    private static class Value implements Parcelable {
        public static final Parcelable.Creator<Value> CREATOR = new Parcelable.Creator<Value>() { // from class: jp.kyocera.kcfp.util.KCfpSoftkeyGuide.Value.1
            @Override // android.os.Parcelable.Creator
            public Value createFromParcel(Parcel parcel) {
                return new Value(parcel);
            }

            @Override // android.os.Parcelable.Creator
            public Value[] newArray(int i) {
                return new Value[i];
            }
        };
        Bitmap drawable;
        boolean enabled;
        int group;
        int priority;
        CharSequence readoutText;
        CharSequence text;
        long time;
        boolean updated;

        private Value(Parcel parcel) {
            this.priority = 0;
            this.text = KCfpSoftkeyGuide.SK_AUTO;
            this.enabled = true;
            this.updated = false;
            this.time = 0L;
            this.group = 0;
            this.drawable = null;
            this.readoutText = null;
            readToParcel(parcel);
        }

        Value() {
            this.priority = 0;
            this.text = KCfpSoftkeyGuide.SK_AUTO;
            this.enabled = true;
            this.updated = false;
            this.time = 0L;
            this.group = 0;
            this.drawable = null;
            this.readoutText = null;
        }

        void copyFrom(Value value) {
            this.priority = value.priority;
            if (value.text == null) {
                this.text = "";
            } else {
                this.text = value.text;
            }
            this.enabled = value.enabled;
            this.time = value.time;
            this.group = value.group;
            this.drawable = value.drawable;
            this.readoutText = value.readoutText;
        }

        boolean isChanged(Value value) {
            if (!Objects.equals(this.text, value.text)) {
                return true;
            }
            if (this.drawable == null ? value.drawable == null : this.drawable.sameAs(value.drawable)) {
                return (Objects.equals(this.readoutText, value.readoutText) && this.enabled == value.enabled && this.priority == value.priority) ? false : true;
            }
            return true;
        }

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel parcel, int i) {
            parcel.writeInt(this.priority);
            TextUtils.writeToParcel(this.text, parcel, 0);
            parcel.writeInt(this.enabled ? 1 : 0);
            parcel.writeInt(this.updated ? 1 : 0);
            parcel.writeLong(this.time);
            parcel.writeInt(this.group);
            if (this.drawable != null) {
                parcel.writeInt(1);
                this.drawable.writeToParcel(parcel, 0);
            } else {
                parcel.writeInt(0);
            }
            TextUtils.writeToParcel(this.readoutText, parcel, 0);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void readToParcel(Parcel parcel) {
            this.priority = parcel.readInt();
            this.text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
            this.enabled = parcel.readInt() == 1;
            this.updated = parcel.readInt() == 1;
            this.time = parcel.readLong();
            this.group = parcel.readInt();
            if (parcel.readInt() == 1) {
                try {
                    this.drawable = Bitmap.CREATOR.createFromParcel(parcel);
                } catch (RuntimeException e) {
                    this.drawable = null;
                }
            } else {
                this.drawable = null;
            }
            this.readoutText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        }
    }

    public static abstract class Owner {
        final WindowManager.LayoutParams attrs;
        public final KCfpSoftkeyGuide softkeyGuide;

        protected abstract View getRootView();

        protected abstract void setWindowFlags(int i, int i2);

        protected abstract void update();

        public Owner(Context context, WindowManager.LayoutParams layoutParams) {
            this.attrs = layoutParams;
            this.softkeyGuide = new KCfpSoftkeyGuide(this, context);
            update();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setSoftkeys(Softkeys softkeys) {
            if (softkeys != null) {
                WindowManager.LayoutParams layoutParams = this.attrs;
                if (layoutParams.softkeys == null) {
                    throw new IllegalStateException("Can't access WindowManager.LayoutParams.softkeys directly.");
                }
                layoutParams.softkeys.dumpSoftkeys("[KCfpSoftkeyGuide][setSoftkeys] LayoutParams before");
                layoutParams.softkeys.update(softkeys);
                layoutParams.softkeys.dumpSoftkeys("[KCfpSoftkeyGuide][setSoftkeys] LayoutParams after");
                update();
            }
        }

        public boolean interceptSoftkeyEvent(KeyEvent keyEvent) {
            return this.softkeyGuide.interceptSoftkeyEvent(keyEvent);
        }
    }
}
