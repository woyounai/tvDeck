# TVDeck Connect（电视端）

[简体中文](README.md) · [English](README_EN.md)

[返回项目总览](../README.md) · [连接电视](../docs/device-connection.md)

TVDeck Connect 是安装在电视或盒子上的可选连接向导。最低 Android 6.0（API 23），包名 `com.tvadb.onboarding`，与手机端客户端分开安装。

## 已实现功能

- 展示开发者选项和 ADB 调试的开启指引。
- 每 2 秒检测电视 Wi-Fi／以太网 IPv4 与本机已开启的传统 ADB TCP 端口。
- 就绪后显示 `tvadb://connect?host=<IPv4>&port=<port>` 二维码，供 iOS 或 Android 客户端扫描。
- 提供适合电视的横屏界面、启动器图标和启动背景。

不读取配对码、ADB 身份、私钥，也不自行修改调试设置。二维码不是授权凭据，首次连接仍需在电视确认 ADB 授权。不安装此向导，也能从手机客户端手动连接电视。

## 使用方式

1. 在电视上开启开发者选项及 ADB 调试。
2. 若电视未提供传统网络调试入口，可从已授权电脑执行 `adb tcpip 5555`。
3. 打开向导，等待地址和传统 TCP 端口检测就绪，再用手机客户端扫码。

Android 11+ 无线调试需要先在手机客户端完成配对，再连接其连接端口；本向导不代替 TLS 配对，也不读取配对码。详见[公共连接指南](../docs/device-connection.md)。

## 构建

需要 JDK 21、Android SDK Platform 35；工程使用 Gradle 8.14、Android Gradle Plugin 8.13.2。以下命令均从仓库根目录执行。

调试 APK：

```sh
cd android-onboarding
./gradlew assembleDebug
```

输出 `android-onboarding/app/build/outputs/apk/debug/app-debug.apk`（相对仓库根目录），使用调试签名。

Release APK：

```sh
./scripts/build-android-tv-release.sh
```

设置本机 `JAVA_HOME`、`ANDROID_HOME`。默认输出 `dist/TVDeck-Connect-release-unsigned.apk`，不能直接安装；发布签名变量见 [Android 客户端的 Release 构建](../android-client/README.md#release-构建)。配置签名后输出 `dist/TVDeck-Connect-release.apk`。升级必须沿用相同包名与签名。脚本不安装 APK 或更改电视设置。工程当前 targetSdk 为 28。

## 验证与许可

Android 9 Sony BRAVIA 的完整扫码连接流程仍需真机验收。电视是否开放 ADB 及其授权策略由固件决定。

自有代码采用 [MIT License](../LICENSE)。界面使用 Android SDK 和随源码附带的 MIT QR 编码器，保留原作者声明，见[第三方说明](../THIRD_PARTY_NOTICES.md)。
