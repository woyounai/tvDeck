# Project Structure

[简体中文](project-structure.md) · [English](project-structure.en.md)

[Project overview](../README_EN.md)

```text
iosadb.xcodeproj/              Xcode project, shared scheme, and build settings
iosadb/
├── IOSADBApp.swift            App entry point and language environment
├── Core/
│   ├── ADBMobileClient.swift  Swift interface to the AOSP ADB static library
│   ├── ADBCommandService.swift
│   │                          App, Shell, screenshot, recording, and mouse commands
│   ├── ADBSyncService.swift   Browsing, Push/Pull, chunks, and transfer resumption
│   ├── DeviceDiscovery.swift  Bonjour and current-subnet discovery
│   ├── APKInspector.swift     APK Manifest, signature, permission, and component inspection
│   └── ...                    ADB protocol, keys, archives, and recording recovery
├── Models/                    Devices, files, apps, installation history, and global state
├── UI/                        Home, files, apps, remote, Shell, tools, and settings
├── Resources/
│   ├── Assets.xcassets/       App Icon, Accent Color, and image assets
│   ├── AndroidUInput/         arm64/armv7/x86/x86_64 UHID/uinput mouse helpers
│   └── TVADBIconHelper.dex    Android PackageManager icon helper
├── en.lproj/                  English localization
├── zh-Hans.lproj/             Simplified Chinese localization
└── Info.plist                 Permissions, document types, Bonjour, and scene settings

android-client/                Android phone / tablet ADB client
android-onboarding/            TV connection companion APK
android-helper/                Source for the install-free DEX icon helper
android-uinput-helper/         Source for the libc-free Android UHID/uinput helper
Vendor/adb-mobile/             AOSP ADB source, libraries, headers, and licenses
Vendor/adb-prebuilt/           Compressed device arm64 and simulator arm64/x86_64 ADB libraries
Vendor/adb-public/             Stable public ADB header required by the app target
patches/                       iOS compatibility patches and checksums for the prebuilt ADB libraries
scripts/                       Helper, simulator, and unsigned-IPA build scripts
docs/images/                   Current README screenshots
dist/                          Locally generated IPA and Android APK files
README.md / README_EN.md       Chinese and English documentation
LICENSE                        MIT License
```
