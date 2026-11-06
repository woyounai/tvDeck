# TVDeck Connect (TV companion)

[简体中文](README.md) · [English](README_EN.md)

[Project overview](../README_EN.md) · [Connect a TV](../docs/device-connection.en.md)

TVDeck Connect is an optional setup companion installed on an Android TV or box. Minimum OS: Android 6.0 (API 23); application ID: `com.tvadb.onboarding`. It is separate from the mobile clients.

## Implemented features

- Explains how to enable Developer options and ADB.
- Checks Wi-Fi/Ethernet IPv4 and enabled classic ADB TCP ports every two seconds.
- Displays a `tvadb://connect?host=<IPv4>&port=<port>` QR code for either mobile client.
- Provides a landscape TV interface, launcher icon and launch background.

It does not read pairing codes, ADB identities or private keys, or change debugging settings. A connection QR is not authorization: accept the first-time ADB prompt on the TV. Manual mobile-client connection works without this companion.

## Usage

1. Enable Developer options and ADB debugging on the TV.
2. If the TV provides no classic network-debugging control, use an authorized computer to run `adb tcpip 5555`.
3. Open the companion, wait for its address/port check, then scan with a mobile client.

Android 11+ wireless debugging requires pairing in the mobile client before connecting to the connection port. This companion cannot replace TLS pairing or read pairing codes. See the [shared connection guide](../docs/device-connection.en.md).

## Build

Requires JDK 21 and Android SDK Platform 35. The project uses Gradle 8.14 and Android Gradle Plugin 8.13.2. Start each example from the repository root.

Debug APK:

```sh
cd android-onboarding
./gradlew assembleDebug
```

Output: `android-onboarding/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root, signed with a debug key.

Release APK:

```sh
./scripts/build-android-tv-release.sh
```

Set `JAVA_HOME` and `ANDROID_HOME`. Default output: `dist/TVDeck-Connect-release-unsigned.apk`, which cannot be installed directly. See [Android client release signing](../android-client/README_EN.md#android-release-builds) for signing variables. With signing configured, output is `dist/TVDeck-Connect-release.apk`. Keep the same application ID and signing key for updates. The script does not install or change TV settings. Current target SDK: 28.

## Validation and licensing

The complete Android 9 Sony BRAVIA QR connection flow still requires real-device validation. ADB availability and authorization depend on TV firmware.

Project-owned code uses the [MIT License](../LICENSE). The interface uses the Android SDK and bundled MIT QR encoder, retaining upstream notices. See [third-party notices](../THIRD_PARTY_NOTICES.md).
