# Shell Installer

A package-installer alternative for Android that installs APKs with **shell-level privileges** using
**[Shizuku](https://shizuku.rikka.app/)**, **on-device Wireless ADB**, or **root** — no PC required.

Because installs run as the shell user (exactly like `adb install`), they go through `pm` directly
instead of the standard on-device installer. That means:

- **No "install unknown apps" permission** is needed for this app.
- Installs are **not subject to the on-device "advanced sideloading flow"** (the reboot / waiting
  period Android applies to unverified apps installed through the normal PackageInstaller UI).
  Google's own guidance keeps `adb` installs available for developers and power users, and Shizuku /
  ADB command-line installs are unaffected by that flow.
- Works as a drop-in **"Open with / Share → install"** handler for APK files and split bundles.

> **Important:** This app uses privileges *you* grant it (Shizuku authorization, ADB pairing, or root).
> It does **not** defeat device-owner / MDM / enterprise policies such as `DISALLOW_INSTALL_APPS`,
> and it cannot do anything while debugging access is disabled. It is a power-user tool for your own
> device. Only install APKs you trust.

---

## Features

- **Three backends**
  - **Shizuku** (recommended) — start Shizuku via Wireless debugging, grant access, done.
  - **Wireless ADB (on-device)** — pair with a pairing code and connect, entirely on the phone
    (Android 11+). Includes mDNS auto-detection of the pairing / connection ports.
  - **Root (`su`)** — for rooted devices.
- **Installer alternative** — registers for `VIEW` / `INSTALL_PACKAGE` on
  `application/vnd.android.package-archive`, plus `SEND` / `SEND_MULTIPLE`, so any file manager or
  browser can hand APKs to it.
- **Split bundles** — installs `.apk`, `.apks`, `.xapk`, `.apkm`, and `.zip` bundles, or multiple
  selected split APKs, using a proper `pm install-create / install-write / install-commit` session.
- **App preview** — icon, label, package name, version, size, min/target SDK, and an
  update / downgrade / same-version comparison against what's installed.
- **Options** — allow downgrade (`-d`), grant all runtime permissions (`-g`), and an automatic retry
  with `--bypass-low-target-sdk-block` for legacy apps blocked on Android 14+.
- **Open / uninstall** the app after installing, with a live install log.
- Material 3 UI with dynamic color, light/dark themes.

## Requirements

- Android 8.0+ (API 26). Wireless ADB pairing requires Android 11+ (API 30).
- One of: Shizuku running, Wireless debugging enabled, or root.

## Getting the app

- **Releases:** download `ShellInstaller.apk` from the repository's
  [Releases](https://github.com/rubiidev18alt/PackageInstaller/releases) page (the `nightly`
  prerelease is rebuilt automatically on every push).
- **CI artifact:** each push also uploads a `ShellInstaller-apk` artifact on the
  [Actions](https://github.com/rubiidev18alt/PackageInstaller/actions) tab.
- **Build it yourself:** see [Building](#building).

Install the APK once (you can use the system installer for this first step), then set up a backend
below. After that, all further installs go through Shell Installer.

## Setup

### Option A — Shizuku (recommended, no root)

1. Install [Shizuku](https://shizuku.rikka.app/) (Play Store or GitHub).
2. Open Shizuku and start it via **Wireless debugging** (Android 11+) — follow its in-app guide.
   This only needs to be redone after a reboot.
3. In Shell Installer, tap **Grant access** on the Shizuku card and accept the Shizuku prompt.
4. The card should read **Ready (shell)**. You're done.

### Option B — Wireless ADB, fully on-device (Android 11+)

1. Enable **Developer options → Wireless debugging**.
2. In Wireless debugging, tap **Pair device with pairing code**. A dialog shows a **pairing port**
   and a **6-digit code**. Keep this dialog open and put Shell Installer in **split-screen** next to
   it (or use **Auto-detect ports**).
3. In Shell Installer's *Wireless ADB* card, enter the pairing port and code, then tap **Pair**.
4. After pairing succeeds, enter the **connection port** (shown on the main Wireless debugging
   screen, *not* the pairing dialog) and tap **Connect**. The card should read **Connected**.

Pairing is one-time; the app stores an ADB host key and reconnects to the saved port automatically.
If the port changes (toggling Wireless debugging assigns a new one), use **Auto-detect ports** or
re-enter it. If you revoke USB/wireless debugging authorizations, pair again.

### Option C — Root

Tap **Check root** on the Root card and approve the `su` prompt. If it reads **Available**, it will be
used automatically (or select it explicitly under *Preferred backend*).

## Usage

- **From a file manager / browser:** open or share an `.apk` (or `.apks` / `.xapk` / `.apkm`) and
  choose **Install with Shell Installer**.
- **From the app:** tap **Choose APK or bundle**, select one or more files, review the app info, and
  tap **Install**.
- After a successful install you can **Open app** or **Done**. If a version is already installed you
  can also **Uninstall existing app** (useful for signature-mismatch updates).

### Install options

| Option | Effect |
| --- | --- |
| Allow downgrade | Adds `pm -d`; install an older version over a newer one. |
| Grant all runtime permissions | Adds `pm -g`; skip first-launch permission prompts. |
| Bypass low-target-SDK block | On Android 14+, automatically retries legacy apps with `--bypass-low-target-sdk-block`. |
| Preferred backend | Auto (Shizuku → ADB → Root), or force a specific backend. |

## How it works

The app never calls the public `PackageInstaller` session API as itself. Instead it runs the `pm`
command as a privileged user:

- **Shizuku:** talks to the Shizuku service binder (`IShizukuService.newProcess`) to spawn `pm` as
  shell/root and streams APK bytes into `pm install-write … -`.
- **Wireless ADB:** implements the ADB client protocol (vendored
  [libadb-android](https://github.com/MuntashirAkon/libadb-android)). Pairing uses TLS 1.3 + SPAKE2;
  APK bytes are pushed with the binary-safe `sync:` service to `/data/local/tmp`, then installed with
  `pm install-write <path>` — the same sequence as `adb install`.
- **Root:** runs the same `pm` commands through `su -c`.

A single install session is created with `pm install-create -r -i <this app> --user current`, each
split is written with `pm install-write -S <size> <session> <name>`, and the session is finalized with
`pm install-commit`. Failures are parsed and explained, and abandoned sessions are cleaned up.

## Limitations & notes

- **Device policies win.** If a device owner / MDM sets `DISALLOW_INSTALL_APPS` or similar user
  restrictions, `pm` will refuse the install (`INSTALL_FAILED_USER_RESTRICTED`). This app does not
  override those controls.
- **OBB / extra data** inside XAPK bundles is not copied; only the APK splits are installed.
- **Wireless ADB pairing** uses the bundled Conscrypt TLS provider. On devices running 16 KB memory
  pages, the bundled native library may fail to load; use the Shizuku or root backend there.
- The first install of *this* app still uses the normal system installer (a chicken-and-egg step).
- Keep APKs from untrusted sources in mind: shell installs skip some of the normal user-facing
  warnings, so only install what you trust.

## Building

Requirements: JDK 17, Android SDK (API 35). The Gradle wrapper fetches Gradle 8.9 automatically.

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Only Google Maven and Maven Central are needed — the ADB + SPAKE2 libraries are vendored in the
`:adb` module, so there is no JitPack dependency.

CI (`.github/workflows/build.yml`) builds the APK on every push and publishes it as an artifact and a
rolling `nightly` prerelease.

## Credits & licenses

This project is licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)). It builds on:

- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) — Apache-2.0 (RikkaApps).
- [libadb-android](https://github.com/MuntashirAkon/libadb-android) — Apache-2.0 / GPL-3.0 (Muntashir
  Al-Islam), vendored in `:adb`.
- [spake2-java](https://github.com/MuntashirAkon/spake2-java) — LGPL-3.0 (Muntashir Al-Islam), the
  pure-Java variant vendored in `:adb` (debug logging of key material removed).
- [Bouncy Castle](https://www.bouncycastle.org/) — MIT-style license.
- [Conscrypt](https://github.com/google/conscrypt) — Apache-2.0.
- AndroidX & Material Components — Apache-2.0.

See [NOTICE.md](NOTICE.md) and `adb/LICENSES/` for details.
