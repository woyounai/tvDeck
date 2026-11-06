# 工程结构

[简体中文](project-structure.md) · [English](project-structure.en.md)

[返回项目总览](../README.md)

```text
iosadb.xcodeproj/              Xcode 工程、共享 Scheme 与构建配置
iosadb/
├── IOSADBApp.swift            App 入口与语言环境
├── Core/
│   ├── ADBMobileClient.swift  AOSP ADB 静态库的 Swift 接口
│   ├── ADBCommandService.swift
│   │                          应用、Shell、截图、录屏与虚拟鼠标命令
│   ├── ADBSyncService.swift   文件浏览、Push/Pull、分块与续传
│   ├── DeviceDiscovery.swift  Bonjour 与当前网段扫描
│   ├── APKInspector.swift     APK Manifest、签名、权限与组件解析
│   └── ...                    ADB 协议、密钥、压缩包及录屏恢复
├── Models/                    设备、文件、应用、安装历史和全局状态
├── UI/                        首页、文件、应用、遥控、Shell、工具与设置
├── Resources/
│   ├── Assets.xcassets/       App Icon、Accent Color 与图像资源
│   ├── AndroidUInput/         arm64/armv7/x86/x86_64 UHID/uinput 鼠标助手
│   └── TVADBIconHelper.dex    Android PackageManager 图标助手
├── en.lproj/                  English 本地化
├── zh-Hans.lproj/             简体中文本地化
└── Info.plist                 权限、文件类型、Bonjour 与场景配置

android-client/                Android 手机／平板 ADB 客户端
android-onboarding/            电视端连接向导 APK
android-helper/                安装免 DEX 图标助手源码
android-uinput-helper/         libc-free Android UHID/uinput 助手源码
Vendor/adb-mobile/             AOSP ADB 源码、静态库、头文件与许可
Vendor/adb-prebuilt/           GitHub 可直接分发的 arm64 ADB 压缩静态库
Vendor/adb-public/             App 编译所需的稳定 ADB 公共头文件
patches/                       预编译 ADB 库对应的 iOS 兼容补丁与校验值
scripts/                       Helper、模拟器与无签名 IPA 构建脚本
docs/images/                   README 最新页面截图
dist/                          本地生成的 IPA 与 Android APK
README.md / README_EN.md       中英文文档
LICENSE                        MIT 开源协议
```
