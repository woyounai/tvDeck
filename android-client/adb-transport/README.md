# Patched LibADB Android Java transport

Source: https://github.com/MuntashirAkon/libadb-android/tree/3.1.1/libadb/src/main/java
Version: 3.1.1. Copyright Muntashir Al-Islam and upstream contributors. TVDeck uses the Apache-2.0 option of the original dual license; COPYING and LICENSES retain the original terms.

Local changes:
- AdbStream drains queued and buffered data before reporting peer EOF, wakes readers at peer EOF, and rejects writes after peer close.
- PairingConnectionCtx does not log peer information and safely closes partially initialized connections after routing/TLS failures.
- The manager supplies a per-target SocketFactory to both ADB and wireless pairing. TLS wraps the same routed TCP socket. Default behavior remains unchanged when no factory override is supplied.
- Build targets Android API 26+ and uses Bouncy Castle 1.81 with the same package API.

This source module replaces the published LibADB AAR so the same EOF and logging behavior applies to Shell, Sync, preview and helper channels. No ADB private key, certificate or pairing code is bundled.
