# 连接 Android TV

[简体中文](device-connection.md) · [English](device-connection.en.md)

[返回项目总览](../README.md)

### 传统 ADB TCP

先通过电脑或电视系统能力启用 TCP 模式：

```bash
adb tcpip 5555
adb shell ip addr show wlan0
```

两端客户端均可手动输入电视 IP 和端口，也可在设备页主动扫描局域网。iOS 扫描当前 IPv4 `/24` 网段，Android 扫描当前私有 IPv4 网段；传统端口扫描使用 5555。扫描需由用户触发。

### Android 11+ 无线配对

1. 在电视上进入“开发者选项 → 无线调试 → 使用配对码配对设备”。
2. 在 TVADB 设备页选择自动发现的设备，或点击 `+` 手动无线配对。
3. 输入电视显示的配对 IP、配对端口和 6 位配对码。
4. 输入无线调试主页显示的连接端口并开始连接。

配对端口与连接端口通常不同，重新开关无线调试后也可能变化。成功配对后，TVADB 会把 ADB 身份和 TLS 证书保存在 App 沙盒中。
