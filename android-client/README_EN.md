# TVDeck Android

[简体中文](README.md) · [English](README_EN.md)

[Project overview](../README_EN.md) · [Connect a TV](../docs/device-connection.en.md) · [Aerial](../docs/aerial.en.md)

TVDeck Android is an independent native Android ADB client. It manages a TV directly over a trusted LAN, without an iPhone or relay computer. Application ID: `com.tvdeck.client`; minimum OS: Android 8.0 (API 26).

## Implemented Android features

| Module | Current capabilities |
| --- | --- |
| Devices and connections | ADB TCP/RSA, Android 11+ pairing/TLS, NSD discovery, manual subnet scanning and connection QR scanning; saved devices, favorites, groups, notes and recent connections |
| Files and apps | Browsing, recursive transfers, verification and resume; app names/icons, search, launch, force stop, clear cache/data, uninstall and base/split export |
| Installation and analysis | APK, APKS, APKM and XAPK installation, split selection, OBB deployment, installation history, package/SDK/permission/component and certificate analysis |
| Remote and input | Short/held directional keys, number/volume keys; UHID/uinput mouse, drag, double/right/middle click, wheel, two-finger directions and haptics |
| Keyboard sync | UTF-8 focused-editor synchronization, composition handling, confirmation acknowledgments, password rejection and edit-conflict protection |
| Screen and tools | Manually started H.264 / MediaCodec preview, screenshot fallback, saved screenshots, silent 15/30/60-second recordings, Shell presets/favorites, live Logcat and device information |
| Android integration | System document picker, foreground connection service, home-screen remote widget, Chinese/English, light/dark appearance, idle heartbeat and bounded reconnect |
| Aerial and usage notice | Management QR scanning, install/upgrade, Guard retry, user-approved authorization and launch; versioned usage consent |

## Android requirements and debug build

Requires JDK 21 and Android SDK Platform 35. Open `android-client/` in Android Studio or an Android-capable IntelliJ IDEA, or set `JAVA_HOME` and `ANDROID_HOME` and run from the repository root:

```sh
./scripts/build-android-client.sh
```

Output: `dist/TVDeck-Android-debug.apk`, for debugging only.

## Android Release builds

Requires JDK 21 and Android SDK Platform 35; signing also requires Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run from the repository root:

```sh
./scripts/build-android-client-release.sh
```

The script runs unit tests and Release APK assembly. Default output:

```text
dist/TVDeck-Android-release-unsigned.apk
```

Unsigned APKs cannot be installed directly. To produce signed APKs, supply these environment variables and run the same scripts:

- `TVADB_KEYSTORE`: your release keystore path.
- `TVADB_KEY_ALIAS`: release key alias.
- `TVADB_STORE_PASSWORD`, `TVADB_KEY_PASSWORD`: inject securely; never commit passwords.
- `TVADB_BUILD_TOOLS`: optional; defaults to `35.0.0`.

Set `TVADB_VERSION_NAME` and `TVADB_VERSION_CODE` to override the APK version name and code. Increase the code for upgrades. Without these variables, project defaults apply.

Verified signed output: `dist/TVDeck-Android-release.apk`. Keep the same application ID and signing key for updates; back up your key securely. Scripts do not create keys, install or upload.

## Android differences and validation limits

Android does not provide a full APK signing-scheme audit or parse bundletool `toc.pb` targeting metadata. Multiple standalone APK variants require manual selection. Interface, remote ordering and theme options also differ from iOS.

Resuming an upload requires selecting the same source file again. Android can reclaim the foreground service; permanent connectivity is not guaranteed. Pairing, OEM input methods, UHID/uinput, video decoding and widget background behavior still require real-device validation.

## Tests

```sh
cd android-client
./gradlew testDebugUnitTest
ANDROID_SERIAL=<emulator-id> ./gradlew connectedDebugAndroidTest
```

Device tests use a local test ADB server and isolated identity; they do not connect to a real TV. Real-device compatibility still needs validation.

## Identity and licenses

ADB identities stay in private no-backup storage; private keys, certificates and pairing codes are not exported. Project-owned source uses the [MIT License](../LICENSE). Dependencies retain their own licenses; see [third-party notices](../THIRD_PARTY_NOTICES.md) and bundled assets.
