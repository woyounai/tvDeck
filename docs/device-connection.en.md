# Connect to Android TV

[简体中文](device-connection.md) · [English](device-connection.en.md)

[Project overview](../README_EN.md)

### Legacy ADB TCP

Enable TCP mode from a computer or through a capability provided by the TV:

```bash
adb tcpip 5555
adb shell ip addr show wlan0
```

Both mobile clients support manual IP/port entry and user-triggered LAN discovery. iOS scans the current IPv4 `/24`; Android scans the current private IPv4 subnet. Classic-port scanning uses 5555 and starts only when requested by the user.

### Android 11+ Wireless Pairing

1. On the TV, open **Developer options → Wireless debugging → Pair device with pairing code**.
2. On TVADB's device screen, choose the automatically discovered device or tap `+` to start manual wireless pairing.
3. Enter the pairing IP address, pairing port, and six-digit pairing code shown by the TV.
4. Enter the connection port shown on the main Wireless debugging screen and connect.

The pairing port and connection port are usually different, and they may change after Wireless debugging is turned off and on. After successful pairing, TVADB stores its ADB identity and TLS certificate in the app sandbox.
