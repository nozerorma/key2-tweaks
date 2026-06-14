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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Combined accessibility service for Key2 Tweaks.
 *
 *  - Nav Lock: while the on-screen keyboard (IME) is visible, stops accidental
 *    Back / Home / Recents presses. Two modes:
 *      * Disable (root): cut the capacitive keys via the sysfs node
 *        /sys/class/input/eventN/device/0dbutton (1 = on, 0 = off), resolved by
 *        device name (synaptics_dsx_2) so it survives reboots.
 *      * Gesture (no root): keep the keys live but gate BACK in onKeyEvent —
 *        a single tap is swallowed; only a double-tap fires it. Only Back is
 *        gateable; Home/Recents are acted on by the window policy regardless of
 *        accessibility consumption.
 *
 *  - PIN Input: on the lockscreen, maps physical-keyboard presses to taps on the
 *    SystemUI PIN pad so the PIN can be typed on the hardware keyboard.
 *
 * Each feature has an independent toggle stored in SharedPreferences.
 */
public class Key2AccessibilityService extends AccessibilityService {

    static final String PREFS = "key2tweaks";
    static final String KEY_NAV_LOCK = "nav_lock_enabled";
    static final String KEY_NAV_GESTURE = "nav_gesture_mode"; // false=disable buttons, true=double-tap gate (Back)
    static final String KEY_NAV_ALWAYS_OFF = "nav_always_off"; // disable nav buttons permanently
    static final String KEY_PIN_INPUT = "pin_input_enabled";

    private static final long LONG_PRESS_MS = 350;
    private static final long DOUBLE_TAP_MS = 300;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean navDisabled = false; // last state pushed to kernel
    private volatile boolean imeActive = false;   // keyboard currently showing
    private final Map<Integer, Long> lastNavTap = new HashMap<>(); // keycode -> last short-tap time
    private SharedPreferences prefs;
    private AudioFx audioFx;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if (key == null) return;
                // Any nav setting change -> recompute the desired button state.
                if (KEY_NAV_LOCK.equals(key) || KEY_NAV_GESTURE.equals(key)
                        || KEY_NAV_ALWAYS_OFF.equals(key)) {
                    reconcileNav();
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
        reconcileNav(); // apply the configured nav state on connect

        audioFx = new AudioFx(this, prefs);
        audioFx.refresh();
    }

    private boolean navLockEnabled() { return prefs == null || prefs.getBoolean(KEY_NAV_LOCK, true); }
    private boolean gestureMode() { return prefs != null && prefs.getBoolean(KEY_NAV_GESTURE, false); }
    private boolean alwaysOff() { return prefs != null && prefs.getBoolean(KEY_NAV_ALWAYS_OFF, false); }
    private boolean pinInputEnabled() { return prefs != null && prefs.getBoolean(KEY_PIN_INPUT, true); }

    // ---------------------------------------------------------------- Nav Lock

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        imeActive = isImeVisible();
        reconcileNav();
    }

    /** Compute and apply the desired capacitive-button state from current settings. */
    private void reconcileNav() {
        boolean desired;
        if (alwaysOff()) {
            desired = true;                       // permanently disabled
        } else if (!navLockEnabled() || gestureMode()) {
            desired = false;                      // buttons stay live (gesture mode gates in onKeyEvent)
        } else {
            desired = imeActive;                  // disable-while-typing mode
        }
        if (desired != navDisabled) applyNavDisabled(desired);
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
        if (event == null) return false;
        int kc = event.getKeyCode();

        // Nav gesture-gate (Back only): while typing, swallow a quick tap on Back
        // and fire it only on a long-press or double-tap. Home/Recents can't be
        // gated — Android's window policy acts on them regardless of consumption.
        if (navLockEnabled() && gestureMode() && imeActive
                && kc == KeyEvent.KEYCODE_BACK && !isDeviceLocked()) {
            return handleNavGesture(event, kc);
        }

        // PIN Input: map physical keys to the lockscreen PIN pad.
        if (!pinInputEnabled()) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (!isDeviceLocked()) return false;

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

    private static boolean isNavKey(int kc) {
        return kc == KeyEvent.KEYCODE_BACK
            || kc == KeyEvent.KEYCODE_HOME
            || kc == KeyEvent.KEYCODE_APP_SWITCH;
    }

    /** Consume the nav key; perform its action only on a double-tap. */
    private boolean handleNavGesture(KeyEvent event, int kc) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long duration = event.getEventTime() - event.getDownTime();
            long now = event.getEventTime();
            if (duration < LONG_PRESS_MS) { // ignore holds; count quick taps
                Long last = lastNavTap.get(kc);
                if (last != null && (now - last) <= DOUBLE_TAP_MS) {
                    performNav(kc);
                    lastNavTap.remove(kc);
                } else {
                    lastNavTap.put(kc, now); // first tap; wait for the second
                }
            }
        }
        return true; // always swallow the raw key so a single tap does nothing
    }

    private void performNav(int kc) {
        int action;
        switch (kc) {
            case KeyEvent.KEYCODE_BACK:       action = GLOBAL_ACTION_BACK;    break;
            case KeyEvent.KEYCODE_HOME:       action = GLOBAL_ACTION_HOME;    break;
            case KeyEvent.KEYCODE_APP_SWITCH: action = GLOBAL_ACTION_RECENTS; break;
            default: return;
        }
        performGlobalAction(action);
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
