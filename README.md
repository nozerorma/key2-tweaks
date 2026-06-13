# Key2 Tweaks

A small utility app for the **BlackBerry KEY2** running **LineageOS 22.x (Android 15)**, bundling three quality-of-life fixes into one app driven by a single accessibility service.

> Built and tested on a KEY2 (`bbf100`) with LineageOS 22.2 and **APatch** root. It targets this specific hardware (sdm660, synaptics touch digitizer, TFA9891 speaker amp) and is unlikely to work unchanged on other devices.

> ⚠️ **Disclaimer:** This app was "vibecoded" — essentially all of the code was written by **Claude Opus 4.8** through conversational prompting. The app has been **tested on-device and works**, but the **code has not been reviewed or audited**. It also runs operations as **root** (writing kernel sysfs nodes, toggling system packages). Use at your own risk; review the source before installing if that matters to you.

## Features

| Feature | What it does | Root? |
|---|---|---|
| **Keyboard Nav Lock** | Disables the capacitive Back / Home / Recents buttons *while the keyboard is showing*, so you don't trigger them by accident when reaching for the top keyboard row. | **Required** |
| **Lockscreen PIN on Keyboard** | Type your lockscreen PIN on the physical keyboard. Digits map phone-dialpad style: `W E R = 1 2 3`, `S D F = 4 5 6`, `Z X C = 7 8 9`, `Q = 0`. Enter confirms, Backspace deletes. | Not needed |
| **Audio FX** | System-wide Equalizer + Bass Boost + Loudness with separate **Speaker / Headphone** profiles (auto-switched by output). | Not needed for the EQ¹ |

¹ Disabling the conflicting **LineageOS AudioFX** (see below) requires root; a button is provided.

Each feature has its own persisted on/off toggle. All of them require the app's **accessibility service** to be enabled.

## How it works (the interesting bits)

- **Nav Lock** — the KEY2's capacitive nav buttons are *key events* (`KEY_BACK` / `KEY_HOMEPAGE` / `KEY_APPSELECT`) emitted by the `synaptics_dsx_2` touch digitizer. They share a master enable at the root-only sysfs node `/sys/class/input/eventN/device/0dbutton` (`1` = on, `0` = off), resolved by device name so it survives reboots. The service flips it via `su` whenever an IME (keyboard) window appears/disappears.
- **Audio FX** — Like LineageOS AudioFX, the app attaches `Equalizer` / `BassBoost` / `LoudnessEnhancer` to **each media app's own audio session** (via the `OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` broadcasts). The effects are hosted in the long-lived accessibility process, so no foreground-service notification is needed.
- **LineageOS AudioFX conflict** — two effect engines on the same sessions cancel/muddy each other, so `org.lineageos.audiofx` must be disabled. The app shows its status and can disable/enable it (root).
- **Speaker loudness ceiling** — the loudspeaker is a protected **TFA9891 smart amp** whose tuning is baked into a CRC-protected firmware container, already using a loud profile, with the volume curve already at 0 dB at max. The software `LoudnessEnhancer` drives it to its safe limit; pushing the hardware further risks speaker damage, so it's intentionally not done.

## Requirements

- BlackBerry KEY2 on LineageOS Android 15 or 16 (untested).
- Root (APatch or Magisk) for Nav Lock and the AudioFX-disable button.
- Android SDK command-line tools for building (build-tools 35.0.0, platform android-34).

## Build

No Gradle — a raw `aapt2` / `d8` / `zipalign` / `apksigner` pipeline:

```bash
./build.sh        # outputs key2tweaks.apk
```

Edit the `SDK`, `BT`, `PLATFORM` and `KS` (keystore) paths at the top of `build.sh` for your environment. The signing keystore is **not** included in this repo — supply your own.

## Install & enable

```bash
adb install -r key2tweaks.apk
```

Then open the app and:
1. **Open Accessibility Settings** → enable **Key2 Tweaks**. (Reinstalling resets this — re-enable after each install.)
2. **Test Root Access** → grant root in APatch when prompted (needed for Nav Lock).
3. Toggle the features you want.

## License

[MIT](LICENSE)
