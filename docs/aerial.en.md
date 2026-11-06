# Aerial launcher integration

[简体中文](aerial.md) · [English](aerial.en.md)

[Project overview](../README_EN.md)

Choose **Scan to open Aerial** on the home screen or Tools tab in either client. Scan the phone management QR code displayed by Aerial on the TV. iOS opens its management page in Safari inside the app; Android uses the system browser. Aerial's own page provides desktop, wallpaper and screensaver management without an ADB connection. Both devices must use the same trusted LAN, and Aerial must be running. Management codes are validated separately from ADB connection and pairing codes; management tokens are not saved.

After ADB connects, TVDeck detects Aerial's installation and shows Install or Upgrade in Tools. It downloads the latest official stable release, verifies SHA-256, size, package identity and version code, and preserves the installed Standard/Guard/Lite edition. Updates do not downgrade or uninstall. Installation then runs authorization and launch steps 3 and 6 of the official Windows guide and shows per-step results. It does not explicitly configure default Home, screensaver takeover or persistent ADB. New installations offer Standard/Guard, with Lite selected automatically for older Android versions.

On a phone, join the TV's Wi-Fi, enable TV ADB, connect in TVDeck and accept the TV's authorization prompt. Install Aerial in Tools, with an explicit Guard retry if Standard fails. The installed app can also use **Authorize and start** without downloading again. Unsupported permission commands are reported and skipped, existing notification listeners are preserved, and launch success requires `Status: ok`. This flow does not claim full desktop takeover verification.
