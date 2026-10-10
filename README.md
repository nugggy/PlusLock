# PlusLock

Good Lock style customisation for OnePlus phones, built for the OnePlus 15 (OxygenOS 16, Android 16). No root needed.

Samsung's Good Lock gets deep access to One UI that no third-party app can have, so PlusLock does everything Android allows an ordinary app to do. It also adds a few features Good Lock doesn't have.

## Features

| PlusLock | Good Lock equivalent | What it does |
|---|---|---|
| Gestures | One Hand Operation+ | Handles on the left and right edges. Each side has five gestures (swipe in, in and up, in and down, long swipe, swipe and hold). Each gesture can run back, home, recents, previous app, screenshot, lock, torch, media controls, open any app, and more. Also back tap, shake for the torch, double-tap the status bar to lock, and hold volume to skip tracks. |
| Edge lighting | Edge Lighting+ | The screen edge, the top edge or the camera glows when a notification arrives. Five effects, each app's own colour, and per-person effects. The preview bubble can be tapped to open, swiped away or used to reply, and groups bursts from one chat. Quiet hours and an unread reminder. |
| Notification vault | NotiStar | Searchable notification history, kept on the phone only. |
| App profiles | Sound Assistant, Display Assistant | Per-app media volume with volume memory, keep the screen on, rotation lock, text size, keyboard and Do Not Disturb. Fingerprint lock and daily time limits. You can also switch edge gestures or lighting off per app. |
| Control deck and tiles | QuickStar, Sound Assistant | A floating mixer with separate volume sliders, sound mode, torch brightness and caffeine. Six Quick Settings tiles. |
| Living sky wallpaper | Wonderland | A live wallpaper that changes with the time of day (or your real sunrise and sunset), shows rain, snow and cloud, and tilts with the phone. |
| Look and feel | LockStar, QuickStar | A custom lock screen clock, network speed beside the camera, and status bar icon hiding. |

Extras Good Lock doesn't have:

- **Camera ring.** Battery level drawn around the punch-hole camera. It spins while charging.
- **Unsent message catcher.** When a chat app pulls back a message it already showed you, the vault keeps it and tells you.
- **Haptic signatures.** Each app gets its own vibration pattern, including Morse code of the app's first letter.
- **Read-aloud.** Speaks new messages from the apps you choose, through headphones only if you prefer.
- **Doomscroll breaker.** After a time limit you set in an app, the screen dims for a short breathing pause. Then you choose to leave or have 5 more minutes.
- **Charge guard.** A nudge to unplug at 80% (or any level you set), a bedtime reminder to plug in, time to full beside the camera, and a green glow when you plug in.
- **Keyword alerts.** An SOS buzz when a message contains a word you choose, even in Do Not Disturb.
- **Work-hours hold.** Chosen apps stay quiet during set hours, then their notifications come back.

PlusLock can't change these, because Android reserves them for the phone maker: the lock screen layout (PlusLock's clock is drawn on top of it), the always-on display, the navigation bar, the Quick Settings panel colours and the system theme.

### ADB extras

A few features need a permission that Android only grants over USB, once: keyboard per app, status bar icons and clock seconds, and one-handed mode. PlusLock's ADB extras screen shows the command, which is:

```
adb shell pm grant io.github.nugggy.pluslock android.permission.WRITE_SECURE_SETTINGS
```

## Install

1. On the phone, download [PlusLock.apk](https://github.com/nugggy/PlusLock/releases/latest/download/PlusLock.apk) and open it.
2. Open PlusLock and work through the **Finish setting up** card.
3. If the accessibility or notification switch is greyed out, go to **App info**, open the three-dot menu and choose **Allow restricted settings**. Android does this for every app installed from a file.

## Updates

PlusLock checks this repository's releases in the background (every 6 hours by default) and installs new versions itself.

- **Stable channel:** normal releases only.
- **Beta channel:** pre-releases as well.

Android asks you to confirm the first update, because a file manager installed the app. After that PlusLock is its own installer, so updates go in without a prompt. Each download is checked against the published SHA-256 checksum and must be signed with the same key as the installed app.

## Publishing a release

Releases are built and signed on the maintainer's PC, so the signing key never leaves that machine. You need `keystore.properties` (git-ignored) pointing at the key, and the [GitHub CLI](https://cli.github.com) signed in with `gh auth login`.

```powershell
.\scripts\release.ps1 0.2.0          # stable
.\scripts\release.ps1 0.3.0-beta.1   # beta
```

The script builds and signs the APK, pushes a `v` tag, and uploads `PlusLock.apk` with its checksum to GitHub Releases. Versions containing `-` are marked as pre-releases.

The newest stable build is always at https://github.com/nugggy/PlusLock/releases/latest/download/PlusLock.apk

Version numbers must keep going up. Minor and patch numbers stay under 100.

## Building locally

You need JDK 17 or later and the Android SDK (platform 36).

```powershell
.\gradlew assembleDebug     # installs alongside the release build as "io.github.nugggy.pluslock.debug"
.\gradlew assembleRelease   # needs keystore.properties (git-ignored) pointing at the signing key
```

## Privacy

PlusLock has no analytics and no accounts. It makes two kinds of network request: the update check to GitHub's public API, and, only if you turn on real weather for the wallpaper, a request to Open-Meteo about once an hour with your location rounded to roughly 1 km. The accessibility service listens only for which app is in front. It never reads what's on the screen. If you turn on volume-button track skipping, it also sees volume key presses, and nothing else.
