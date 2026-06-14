package dev.pinkeys.navlock;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView rootView;
    private TextView audiofxStatus;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int PAD = 48;
    private static final String LINEAGE_AUDIOFX = "org.lineageos.audiofx";

    // Resolved at runtime from the Material theme + Monet dynamic palette.
    private int colAccent, colPrimary, colSecondary, colOk, colBad;

    /** A system Monet color (API 31+), falling back to a fixed color otherwise. */
    private int monet(int sysColorRes, int fallback) {
        if (Build.VERSION.SDK_INT >= 31) {
            try { return getColor(sysColorRes); } catch (Throwable ignored) {}
        }
        return fallback;
    }

    /** Resolve a theme color attribute (e.g. textColorPrimary) to an int. */
    private int themeAttrColor(int attr, int fallback) {
        TypedArray a = getTheme().obtainStyledAttributes(new int[]{attr});
        try { return a.getColor(0, fallback); } finally { a.recycle(); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Key2AccessibilityService.PREFS, MODE_PRIVATE);

        // Material You / Monet palette (with sane fallbacks pre-Android 12).
        colAccent = monet(android.R.color.system_accent1_600,
            themeAttrColor(android.R.attr.colorAccent, Color.parseColor("#1565C0")));
        colPrimary = themeAttrColor(android.R.attr.textColorPrimary, Color.BLACK);
        colSecondary = themeAttrColor(android.R.attr.textColorSecondary, Color.GRAY);
        colOk = Color.parseColor("#43A047");
        colBad = Color.parseColor("#E53935");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 80, 64, 64);

        TextView title = new TextView(this);
        title.setText("Key2 Tweaks");
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(colPrimary);
        root.addView(title);

        TextView legend = new TextView(this);
        legend.setText("Each feature is tagged below: [ROOT REQUIRED] needs root "
            + "(granted via APatch/Magisk); [NO ROOT NEEDED] works with just the "
            + "accessibility service. All features need the accessibility service enabled.");
        legend.setTextSize(12);
        legend.setTextColor(colSecondary);
        legend.setPadding(0, 12, 0, 0);
        root.addView(legend);

        // --- Feature toggles -------------------------------------------------
        root.addView(makeSwitch(
            "Keyboard Nav Lock",
            "Stops accidental Back / Home / Recents presses while the keyboard is "
                + "showing. Pick a mode with the two options below.",
            Key2AccessibilityService.KEY_NAV_LOCK, true, null));

        root.addView(makeSwitch(
            "   ↳ Double-tap Back (keep button)",
            "ON [NO ROOT NEEDED]: while typing, a single tap on Back is ignored — only "
                + "a double-tap fires it. (Only Back works this way; Android won't let "
                + "an app gate Home/Recents.) "
                + "OFF [ROOT REQUIRED]: all three buttons are fully disabled while typing.",
            Key2AccessibilityService.KEY_NAV_GESTURE, false, null));

        root.addView(makeSwitch(
            "Lockscreen PIN on Keyboard",
            "[NO ROOT NEEDED] Type your lockscreen PIN on the physical keyboard. "
                + "Digits map phone-dialpad style: W E R = 1 2 3, S D F = 4 5 6, "
                + "Z X C = 7 8 9, Q = 0. Enter confirms, Backspace deletes. "
                + "Uses the accessibility service only.",
            Key2AccessibilityService.KEY_PIN_INPUT, true, null));

        // --- Audio FX --------------------------------------------------------
        buildAudioSection(root);

        // --- Status ----------------------------------------------------------
        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTypeface(null, Typeface.BOLD);
        statusView.setPadding(0, 56, 0, 8);
        root.addView(statusView);

        Button accBtn = new Button(this);
        accBtn.setText("Open Accessibility Settings");
        accBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(accBtn);

        rootView = new TextView(this);
        rootView.setTextSize(15);
        rootView.setTypeface(null, Typeface.BOLD);
        rootView.setPadding(0, 40, 0, 8);
        root.addView(rootView);

        Button rootBtn = new Button(this);
        rootBtn.setText("Test Root Access");
        rootBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { testRoot(); }
        });
        root.addView(rootBtn);

        Button toggleBtn = new Button(this);
        toggleBtn.setText("Test: disable nav buttons for 3s [ROOT]");
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { testToggle(); }
        });
        root.addView(toggleBtn);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    /** Titled description block with a bound, persisted Switch (optional callback). */
    private View makeSwitch(String label, String desc, final String prefKey,
                            boolean def, final CompoundButton.OnCheckedChangeListener extra) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, PAD, 0, 0);

        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(18);
        sw.setTypeface(null, Typeface.BOLD);
        sw.setTextColor(colAccent);
        sw.setChecked(prefs.getBoolean(prefKey, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(prefKey, checked).apply();
                if (extra != null) extra.onCheckedChanged(b, checked);
            }
        });
        box.addView(sw);

        if (desc != null) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextSize(13);
            d.setTextColor(colSecondary);
            d.setPadding(0, 4, 0, 0);
            box.addView(d);
        }
        return box;
    }

    // ----------------------------------------------------------- Audio section

    private void buildAudioSection(LinearLayout root) {
        final LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        root.addView(makeSwitch(
            "Audio FX (EQ + Loudness)",
            "[NO ROOT NEEDED for the EQ] System-wide equalizer, bass boost and "
                + "loudness. Auto-switches between Speaker and Headphone tuning. "
                + "Needs the accessibility service running. NOTE: disabling the "
                + "conflicting LineageOS AudioFX (below) does require root.",
            AudioFx.KEY_ENABLED, false,
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    controls.setVisibility(checked ? View.VISIBLE : View.GONE);
                    // Our effects and LineageOS AudioFX fight over the same audio
                    // sessions; disable theirs automatically when ours turns on.
                    if (checked) setLineageAudioFx(false);
                }
            }));

        // Conflict control: LineageOS AudioFX must be off for our chain to work.
        audiofxStatus = new TextView(this);
        audiofxStatus.setTextSize(13);
        audiofxStatus.setPadding(0, 16, 0, 4);
        root.addView(audiofxStatus);

        Button afxBtn = new Button(this);
        afxBtn.setText("Disable / enable LineageOS AudioFX [ROOT]");
        afxBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() {
                        boolean disabled = isLineageAudioFxDisabled();
                        setLineageAudioFx(disabled); // flip: if disabled -> enable, else disable
                    }
                }).start();
            }
        });
        root.addView(afxBtn);

        // Probe the device EQ once (transient instance) to learn bands + range.
        final String prof = AudioFx.profileFor((AudioManager) getSystemService(AUDIO_SERVICE));
        Equalizer probe = null;
        int nBands = 0; short lo = -1500, hi = 1500;
        int[] centers = new int[0];
        try {
            probe = new Equalizer(0, 0);
            nBands = probe.getNumberOfBands();
            short[] range = probe.getBandLevelRange();
            lo = range[0]; hi = range[1];
            centers = new int[nBands];
            for (short b = 0; b < nBands; b++) centers[b] = probe.getCenterFreq(b) / 1000; // Hz
        } catch (Throwable t) {
            TextView err = new TextView(this);
            err.setText("Audio effects are not available on this ROM.");
            err.setTextColor(colBad);
            controls.addView(err);
        } finally {
            if (probe != null) try { probe.release(); } catch (Throwable ignored) {}
        }

        TextView profLabel = new TextView(this);
        profLabel.setText("Editing tuning for: " + AudioFx.profileLabel(prof)
            + "  (auto-selected by output)");
        profLabel.setTextSize(13);
        profLabel.setTypeface(null, Typeface.BOLD);
        profLabel.setTextColor(colAccent);
        profLabel.setPadding(0, 24, 0, 8);
        controls.addView(profLabel);

        // EQ band sliders.
        for (short b = 0; b < nBands; b++) {
            final short band = b;
            int freq = centers[b];
            String fl = freq >= 1000 ? (freq / 1000) + " kHz" : freq + " Hz";
            int def = AudioFx.defaultBandMb(prof, freq);
            int cur = prefs.getInt(AudioFx.kBand(prof, band), def);
            controls.addView(makeSlider("EQ " + fl, lo, hi, cur, 100, " dB", 100,
                AudioFx.kBand(prof, band)));
        }

        // BassBoost + Loudness.
        controls.addView(makeSlider("Bass Boost", 0, 1000,
            prefs.getInt(AudioFx.kBass(prof), AudioFx.defaultBass(prof)), 1, "", 10,
            AudioFx.kBass(prof)));
        controls.addView(makeSlider("Loudness (makeup gain)", 0, 2000,
            prefs.getInt(AudioFx.kLoud(prof), AudioFx.defaultLoud(prof)), 100, " dB", 100,
            AudioFx.kLoud(prof)));

        final int fBands = nBands;
        final int[] fCenters = centers;
        Button reset = new Button(this);
        reset.setText("Reset this profile to recommended");
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SharedPreferences.Editor e = prefs.edit();
                for (int b = 0; b < fBands; b++)
                    e.putInt(AudioFx.kBand(prof, b), AudioFx.defaultBandMb(prof, fCenters[b]));
                e.putInt(AudioFx.kBass(prof), AudioFx.defaultBass(prof));
                e.putInt(AudioFx.kLoud(prof), AudioFx.defaultLoud(prof));
                e.apply();
                Toast.makeText(MainActivity.this, "Reset — reopen to see sliders.",
                    Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        controls.addView(reset);

        controls.setVisibility(prefs.getBoolean(AudioFx.KEY_ENABLED, false) ? View.VISIBLE : View.GONE);
        root.addView(controls);
    }

    /**
     * A labelled SeekBar that persists an int pref.
     * @param divLabel divide the stored value by this for the displayed number
     * @param suffix   text appended to the displayed value
     * @param step     seekbar granularity (stored value increments by this)
     */
    private View makeSlider(final String label, final int min, final int max, int current,
                            final int divLabel, final String suffix, final int step,
                            final String prefKey) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 16, 0, 0);

        final TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(colSecondary);
        box.addView(tv);

        final SeekBar sb = new SeekBar(this);
        final int range = (max - min) / step;
        sb.setMax(range);
        sb.setProgress((AudioFx.clamp(current, min, max) - min) / step);
        box.addView(sb);

        tv.setText(fmt(label, AudioFx.clamp(current, min, max), divLabel, suffix));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int val = min + progress * step;
                tv.setText(fmt(label, val, divLabel, suffix));
                if (fromUser) prefs.edit().putInt(prefKey, val).apply();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        return box;
    }

    private static String fmt(String label, int val, int div, String suffix) {
        if (div == 1) return label + ": " + val + suffix;
        double d = val / (double) div;
        String num = (d == Math.rint(d)) ? String.valueOf((int) d) : String.format("%.1f", d);
        return label + ": " + (val > 0 ? "+" : "") + num + suffix;
    }

    // -------------------------------------------------- LineageOS AudioFX conflict

    private boolean isLineageAudioFxInstalled() {
        String out = runRootCapture("pm list packages");
        return out != null && out.contains(LINEAGE_AUDIOFX);
    }

    private boolean isLineageAudioFxDisabled() {
        String out = runRootCapture("pm list packages -d");
        return out != null && out.contains(LINEAGE_AUDIOFX);
    }

    /** @param enable true = pm enable, false = pm disable-user. Refreshes status. */
    private void setLineageAudioFx(final boolean enable) {
        new Thread(new Runnable() {
            public void run() {
                runRootCapture(enable
                    ? "pm enable " + LINEAGE_AUDIOFX
                    : "pm disable-user --user 0 " + LINEAGE_AUDIOFX);
                ui.post(new Runnable() { public void run() { refreshAudioFxConflictStatus(); } });
            }
        }).start();
    }

    private void refreshAudioFxConflictStatus() {
        if (audiofxStatus == null) return;
        audiofxStatus.setText("LineageOS AudioFX: checking…");
        audiofxStatus.setTextColor(colSecondary);
        new Thread(new Runnable() {
            public void run() {
                final boolean installed = isLineageAudioFxInstalled();
                final boolean disabled = isLineageAudioFxDisabled();
                ui.post(new Runnable() {
                    public void run() {
                        if (!installed) {
                            audiofxStatus.setText("LineageOS AudioFX: not present ✓");
                            audiofxStatus.setTextColor(colOk);
                        } else if (disabled) {
                            audiofxStatus.setText("LineageOS AudioFX: DISABLED ✓ (no conflict)");
                            audiofxStatus.setTextColor(colOk);
                        } else {
                            audiofxStatus.setText("⚠ LineageOS AudioFX is ENABLED — it conflicts. "
                                + "Tap below to disable it (or just turn Audio FX on).");
                            audiofxStatus.setTextColor(colBad);
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------- status etc.

    @Override
    protected void onResume() {
        super.onResume();
        if (isServiceEnabled()) {
            statusView.setText("✓ Accessibility service ENABLED");
            statusView.setTextColor(colOk);
        } else {
            statusView.setText("✗ Service disabled — tap below and enable \"Key2 Tweaks\".");
            statusView.setTextColor(colBad);
        }
        rootView.setText("Root: tap \"Test Root Access\" to check.");
        rootView.setTextColor(colSecondary);
        refreshAudioFxConflictStatus();
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String pkg = getPackageName();
        for (AccessibilityServiceInfo svc : services) {
            if (pkg.equals(svc.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    private void testRoot() {
        rootView.setText("Root: checking…");
        rootView.setTextColor(colSecondary);
        new Thread(new Runnable() {
            public void run() {
                final String out = runRootCapture("id");
                final boolean ok = out != null && out.contains("uid=0");
                ui.post(new Runnable() {
                    public void run() {
                        if (ok) {
                            rootView.setText("✓ Root granted (" + out.trim() + ")");
                            rootView.setTextColor(colOk);
                        } else {
                            rootView.setText("✗ Root denied / unavailable.\nGrant root to Key2 Tweaks in APatch.");
                            rootView.setTextColor(colBad);
                        }
                    }
                });
            }
        }).start();
    }

    private void testToggle() {
        Toast.makeText(this, "Disabling nav buttons for 3s…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                runRootCapture(navScript("0"));
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                runRootCapture(navScript("1"));
                ui.post(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "Nav buttons re-enabled.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private static String navScript(String value) {
        return "for d in /sys/class/input/event*; do "
            + "if [ \"$(cat \"$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then "
            + "echo " + value + " > \"$d/device/0dbutton\"; fi; done";
    }

    private static String runRootCapture(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (OutputStream os = p.getOutputStream()) { /* close stdin */ }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
