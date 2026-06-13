package dev.pinkeys.navlock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Combined accessibility service for Key2 Tweaks.
 *
 *  - Nav Lock: while the on-screen keyboard (IME) is visible, disables the Key2's
 *    capacitive Back / Home / Recents buttons by writing the root-only sysfs node
 *    /sys/class/input/eventN/device/0dbutton (1 = on, 0 = off). The eventN index
 *    is resolved by device name (synaptics_dsx_2) so it survives reboots.
 *
 *  - PIN Input: on the lockscreen, maps physical-keyboard presses to taps on the
 *    SystemUI PIN pad so the PIN can be typed on the hardware keyboard.
 *
 * Each feature has an independent toggle stored in SharedPreferences.
 */
public class Key2AccessibilityService extends AccessibilityService {

    static final String PREFS = "key2tweaks";
    static final String KEY_NAV_LOCK = "nav_lock_enabled";
    static final String KEY_PIN_INPUT = "pin_input_enabled";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean navDisabled = false; // last state pushed to kernel
    private SharedPreferences prefs;
    private AudioFx audioFx;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if (key == null) return;
                // If nav lock is switched off, restore the buttons right away.
                if (KEY_NAV_LOCK.equals(key) && !navLockEnabled() && navDisabled) {
                    applyNavDisabled(false);
                }
                // Any audio-related change -> reconcile the DSP chain.
                if (audioFx != null
                        && (key.equals(AudioFx.KEY_ENABLED)
                            || key.startsWith("eq_")
                            || key.startsWith("bass_")
                            || key.startsWith("loud_"))) {
                    audioFx.refresh();
                }
            }
        };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        applyNavDisabled(false); // known-good start

        audioFx = new AudioFx(this, prefs);
        audioFx.refresh();
    }

    private boolean navLockEnabled() { return prefs == null || prefs.getBoolean(KEY_NAV_LOCK, true); }
    private boolean pinInputEnabled() { return prefs != null && prefs.getBoolean(KEY_PIN_INPUT, true); }

    // ---------------------------------------------------------------- Nav Lock

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!navLockEnabled()) {
            if (navDisabled) applyNavDisabled(false);
            return;
        }
        boolean imeVisible = isImeVisible();
        if (imeVisible != navDisabled) {
            applyNavDisabled(imeVisible);
        }
    }

    private boolean isImeVisible() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Exception e) {
            return false;
        }
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true;
            }
        }
        return false;
    }

    private void applyNavDisabled(final boolean disabled) {
        navDisabled = disabled;
        worker.execute(new Runnable() {
            public void run() { runRoot(disabled ? "0" : "1"); }
        });
    }

    private void writeNodeBlocking(boolean enabled) {
        navDisabled = !enabled;
        runRoot(enabled ? "1" : "0");
    }

    private void runRoot(String value) {
        String script =
            "for d in /sys/class/input/event*; do " +
            "  if [ \"$(cat \"$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
            "    echo " + value + " > \"$d/device/0dbutton\"; " +
            "  fi; " +
            "done";
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", script});
            try (OutputStream os = p.getOutputStream()) { /* close stdin */ }
            p.waitFor();
        } catch (Exception e) {
            // Root denied or su missing: leave buttons as-is.
        } finally {
            if (p != null) p.destroy();
        }
    }

    // --------------------------------------------------------------- PIN Input

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!pinInputEnabled()) return false;
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (!isDeviceLocked()) return false;
        int kc = event.getKeyCode();

        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            return clickPinEnter();
        }
        if (kc == KeyEvent.KEYCODE_DEL || kc == KeyEvent.KEYCODE_FORWARD_DEL) {
            return clickPinDelete();
        }
        String digit = keyCodeToDigit(kc);
        if (digit != null) return clickPinButton(digit);
        return false;
    }

    private boolean isDeviceLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private String keyCodeToDigit(int kc) {
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9)
            return String.valueOf(kc - KeyEvent.KEYCODE_0);
        if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9)
            return String.valueOf(kc - KeyEvent.KEYCODE_NUMPAD_0);
        // BlackBerry physical keyboard: phone-dialpad layout mapped onto QWERTY.
        // W(1) E(2) R(3) / S(4) D(5) F(6) / Z(7) X(8) C(9) / Q(0)
        switch (kc) {
            case KeyEvent.KEYCODE_Q: return "0";
            case KeyEvent.KEYCODE_W: return "1";
            case KeyEvent.KEYCODE_E: return "2";
            case KeyEvent.KEYCODE_R: return "3";
            case KeyEvent.KEYCODE_S: return "4";
            case KeyEvent.KEYCODE_D: return "5";
            case KeyEvent.KEYCODE_F: return "6";
            case KeyEvent.KEYCODE_Z: return "7";
            case KeyEvent.KEYCODE_X: return "8";
            case KeyEvent.KEYCODE_C: return "9";
            default: return null;
        }
    }

    private boolean clickPinButton(String digit) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            String[] ids = {
                "com.android.systemui:id/key" + digit,
                "com.android.systemui:id/pin_key_" + digit,
                "com.android.systemui:id/digit_" + digit
            };
            for (String id : ids) if (clickById(root, id)) return true;
            return findAndClick(root, digit);
        } finally {
            root.recycle();
        }
    }

    private boolean clickPinDelete() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            String[] ids = {
                "com.android.systemui:id/delete_button",
                "com.android.systemui:id/key_backspace",
                "com.android.systemui:id/pin_key_delete"
            };
            for (String id : ids) if (clickById(root, id)) return true;
            return findAndClickByDesc(root, new String[]{"delete", "backspace"});
        } finally {
            root.recycle();
        }
    }

    private boolean clickPinEnter() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            String[] ids = {
                "com.android.systemui:id/key_enter",
                "com.android.systemui:id/pin_key_enter",
                "com.android.systemui:id/check_button"
            };
            for (String id : ids) if (clickById(root, id)) return true;
            return findAndClickByDesc(root, new String[]{"enter", "confirm", "ok"});
        } finally {
            root.recycle();
        }
    }

    private boolean clickById(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo node : nodes) {
            try {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            } finally {
                node.recycle();
            }
        }
        return false;
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String digit) {
        if (node.isClickable()) {
            CharSequence txt = node.getText();
            if (txt != null && txt.toString().trim().equals(digit)) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = findAndClick(child, digit);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    private boolean findAndClickByDesc(AccessibilityNodeInfo node, String[] keywords) {
        if (node.isClickable()) {
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String s = desc.toString().toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (s.contains(kw)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = findAndClickByDesc(child, keywords);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- Lifecycle

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        writeNodeBlocking(true); // never leave nav buttons dead
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        writeNodeBlocking(true);
        if (audioFx != null) audioFx.shutdown();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        worker.shutdown();
        super.onDestroy();
    }
}
