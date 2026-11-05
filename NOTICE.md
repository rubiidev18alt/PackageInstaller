# Third-party notices

Shell Installer is GPL-3.0-or-later. It includes or depends on the following third-party components.

## Vendored in the `:adb` module

### libadb-android
- Source: https://github.com/MuntashirAkon/libadb-android (tag 3.1.1, `master` at time of vendoring)
- Copyright 2021 Muntashir Al-Islam
- License: dual GPL-3.0-or-later OR Apache-2.0. This project uses it under **Apache-2.0**.
- Files: `adb/src/main/java/io/github/muntashirakon/adb/**` (unmodified).
- License texts: `adb/COPYING`, `adb/LICENSES/Apache-2.0`, `adb/LICENSES/GPL-3.0-or-later`.

### spake2-java (pure-Java variant)
- Source: https://github.com/MuntashirAkon/spake2-java (`java` module)
- Copyright 2021 Muntashir Al-Islam
- License: **LGPL-3.0** (see `adb/LICENSES/LGPL-3.0-spake2`).
- Files: `adb/src/main/java/io/github/muntashirakon/crypto/**`.
- Modification: removed `System.out` debug statements that printed intermediate key material
  (`Spake2Context.java`, `ed25519/GroupElement.java`). The pure-Java `Spake2Context` is API-compatible
  with the native `spake2-android` variant that libadb normally depends on, so no native build is
  required.

## Runtime dependencies

- **Shizuku API / provider / aidl** (`dev.rikka.shizuku:*:13.1.5`) — Apache-2.0, RikkaApps.
  https://github.com/RikkaApps/Shizuku-API
- **Bouncy Castle** (`org.bouncycastle:bcprov-jdk15to18`, `bcpkix-jdk15to18`) — MIT-style license.
  https://www.bouncycastle.org/licence.html
- **Conscrypt** (`org.conscrypt:conscrypt-android:2.5.3`) — Apache-2.0, Google.
  https://github.com/google/conscrypt
- **AndroidX** (core, appcompat, activity, lifecycle) — Apache-2.0, The Android Open Source Project.
- **Material Components for Android** — Apache-2.0, Google.
- **kotlinx.coroutines** — Apache-2.0, JetBrains.
- **Gradle wrapper** (`gradle/wrapper/gradle-wrapper.jar`) — Apache-2.0, Gradle Inc.
