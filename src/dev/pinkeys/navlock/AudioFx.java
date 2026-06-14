package dev.pinkeys.navlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global audio DSP (EQ + BassBoost + LoudnessEnhancer), hosted in the long-lived
 * accessibility-service process.
 *
 * Like LineageOS AudioFX, effects are attached to each media app's own audio
 * session (announced by the OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts)
 * rather than only the global mix (session 0) — that's what actually processes
 * playback on Qualcomm ROMs. We also keep a session-0 set as a fallback.
 *
 * Four tuning profiles, auto-selected by output: "spk" (loudspeaker),
 * "wired" (3.5mm), "bt" (Bluetooth A2DP), "usb" (USB-C).
 */
public class AudioFx {

    static final String KEY_ENABLED = "audio_fx_enabled";
    static String kBand(String prof, int band) { return "eq_" + prof + "_" + band; }
    static String kBass(String prof)           { return "bass_" + prof; }
    static String kLoud(String prof)           { return "loud_" + prof; }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // One effect bundle per audio session id (0 = global fallback).
    private final Map<Integer, EffectSet> sessions = new HashMap<>();

    private int numBands;
    private short minLevel = -1500, maxLevel = 1500;
    private boolean bandInfoKnown = false;

    private AudioDeviceCallback devCb;
    private BroadcastReceiver sessionRx;
    private boolean receiverRegistered = false;

    AudioFx(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean enabled() { return prefs.getBoolean(KEY_ENABLED, false); }

    /** Reconcile everything with the master toggle + current prefs. */
    void refresh() {
        worker.execute(new Runnable() {
            public void run() {
                if (enabled()) {
                    registerSessionReceiver();
                    registerDeviceCallback();
                    ensureSession(0);          // global fallback
                    applyAll();
                } else {
                    unregisterSessionReceiver();
                    unregisterDeviceCallback();
                    releaseAll();
                }
            }
        });
    }

    void shutdown() {
        worker.execute(new Runnable() {
            public void run() {
                unregisterSessionReceiver();
                unregisterDeviceCallback();
                releaseAll();
            }
        });
        worker.shutdown();
    }

    // ----------------------------------------------------------- effect bundle

    private final class EffectSet {
        Equalizer eq;
        BassBoost bass;
        LoudnessEnhancer loud;

        EffectSet(int session) {
            try { eq = new Equalizer(0, session); } catch (Throwable t) { eq = null; }
            try { bass = new BassBoost(0, session); } catch (Throwable t) { bass = null; }
            try { loud = new LoudnessEnhancer(session); } catch (Throwable t) { loud = null; }
            if (eq != null && !bandInfoKnown) {
                try {
                    numBands = eq.getNumberOfBands();
                    short[] r = eq.getBandLevelRange();
                    minLevel = r[0]; maxLevel = r[1];
                    bandInfoKnown = true;
                } catch (Throwable ignored) {}
            }
        }

        void apply(String prof) {
            try {
                if (eq != null) {
                    eq.setEnabled(true);
                    int n = numBands > 0 ? numBands : eq.getNumberOfBands();
                    for (short b = 0; b < n; b++) {
                        int mb = prefs.getInt(kBand(prof, b),
                            defaultBandMb(prof, eq.getCenterFreq(b) / 1000));
                        eq.setBandLevel(b, (short) clamp(mb, minLevel, maxLevel));
                    }
                }
            } catch (Throwable ignored) {}
            try {
                if (bass != null && bass.getStrengthSupported()) {
                    bass.setEnabled(true);
                    bass.setStrength((short) clamp(prefs.getInt(kBass(prof), defaultBass(prof)), 0, 1000));
                }
            } catch (Throwable ignored) {}
            try {
                if (loud != null) {
                    loud.setEnabled(true);
                    loud.setTargetGain(clamp(prefs.getInt(kLoud(prof), defaultLoud(prof)), 0, 2000));
                }
            } catch (Throwable ignored) {}
        }

        void release() {
            try { if (eq != null) eq.release(); } catch (Throwable ignored) {}
            try { if (bass != null) bass.release(); } catch (Throwable ignored) {}
            try { if (loud != null) loud.release(); } catch (Throwable ignored) {}
            eq = null; bass = null; loud = null;
        }
    }

    private void ensureSession(int session) {
        if (sessions.containsKey(session)) return;
        sessions.put(session, new EffectSet(session));
    }

    private void releaseSession(int session) {
        EffectSet s = sessions.remove(session);
        if (s != null) s.release();
    }

    private void applyAll() {
        String prof = activeProfile();
        for (EffectSet s : sessions.values()) s.apply(prof);
    }

    private void releaseAll() {
        for (EffectSet s : sessions.values()) s.release();
        sessions.clear();
    }

    // -------------------------------------------------- per-session broadcasts

    private void registerSessionReceiver() {
        if (receiverRegistered) return;
        sessionRx = new BroadcastReceiver() {
            public void onReceive(Context c, final Intent intent) {
                final int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
                final boolean open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                    .equals(intent.getAction());
                if (session == AudioManager.AUDIO_SESSION_ID_GENERATE) return;
                worker.execute(new Runnable() {
                    public void run() {
                        if (!enabled()) return;
                        if (open) {
                            ensureSession(session);
                            EffectSet s = sessions.get(session);
                            if (s != null) s.apply(activeProfile());
                        } else {
                            releaseSession(session);
                        }
                    }
                });
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(sessionRx, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(sessionRx, f);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterSessionReceiver() {
        if (receiverRegistered && sessionRx != null) {
            try { ctx.unregisterReceiver(sessionRx); } catch (Throwable ignored) {}
        }
        receiverRegistered = false;
        sessionRx = null;
    }

    // ------------------------------------------------------- output detection

    /** Active output profile: "usb", "wired", "bt", or "spk" (priority in that order). */
    String activeProfile() { return profileFor(am); }

    static String profileFor(AudioManager am) {
        boolean wired = false, bt = false, usb = false;
        try {
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : outs) {
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                        wired = true; break;
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        bt = true; break;
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_USB_DEVICE:
                        usb = true; break;
                }
            }
        } catch (Throwable ignored) {}
        if (usb) return "usb";
        if (wired) return "wired";
        if (bt) return "bt";
        return "spk";
    }

    /** Human-readable name for a profile key. */
    static String profileLabel(String prof) {
        switch (prof) {
            case "usb":   return "USB-C HEADPHONES";
            case "wired": return "WIRED HEADPHONES";
            case "bt":    return "BLUETOOTH";
            default:      return "SPEAKER";
        }
    }

    private void registerDeviceCallback() {
        if (devCb != null) return;
        devCb = new AudioDeviceCallback() {
            public void onAudioDevicesAdded(AudioDeviceInfo[] a) { refresh(); }
            public void onAudioDevicesRemoved(AudioDeviceInfo[] r) { refresh(); }
        };
        am.registerAudioDeviceCallback(devCb, main);
    }

    private void unregisterDeviceCallback() {
        if (devCb != null) {
            try { am.unregisterAudioDeviceCallback(devCb); } catch (Throwable ignored) {}
            devCb = null;
        }
    }

    // ---- band info for the UI (probe a transient global instance if needed)

    int bandCount() { return numBands; }
    short minLevel() { return minLevel; }
    short maxLevel() { return maxLevel; }

    // -------- recommended defaults (millibels): fuller, clearer, louder

    static int defaultBandMb(String prof, int freqHz) {
        switch (prof) {
            case "spk": // small speaker: lots of help across the board
                if (freqHz < 120)  return 600;
                if (freqHz < 400)  return 250;
                if (freqHz < 1500) return 150;
                if (freqHz < 6000) return 350;
                return 250;
            case "bt":  // BT often dull/compressed: a touch more bass + air
                if (freqHz < 120)  return 500;
                if (freqHz < 400)  return 150;
                if (freqHz < 1500) return 0;
                if (freqHz < 6000) return 250;
                return 200;
            default:    // wired / usb: gentle, near-flat headphone curve
                if (freqHz < 120)  return 400;
                if (freqHz < 400)  return 100;
                if (freqHz < 1500) return 0;
                if (freqHz < 6000) return 200;
                return 100;
        }
    }

    static int defaultBass(String prof) {
        switch (prof) { case "spk": return 600; case "bt": return 400; default: return 300; }
    }

    static int defaultLoud(String prof) { // millibels of makeup gain
        switch (prof) { case "spk": return 700; case "bt": return 400; default: return 300; }
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
