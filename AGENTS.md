# 智能体开发指南

## 项目范围与阅读顺序

TVADB / TVDeck 包含三个独立客户端：

| 组件 | 目录 | 技术与用途 |
| --- | --- | --- |
| iOS 客户端 | `iosadb/`、`iosadb.xcodeproj/` | iOS 17+，SwiftUI，通过嵌入式 AOSP ADB 管理电视 |
| Android 客户端 | `android-client/` | Android 8.0+，Java 原生界面，通过 LibADB 管理电视 |
| 电视连接向导 | `android-onboarding/` | Android 6.0+，显示调试指引和传统 ADB TCP 连接二维码 |

电视向导不是 ADB 服务端，不会自动开启调试或完成无线配对。`android-helper/` 的 DEX 与 `android-uinput-helper/` 的原生助手由手机客户端临时部署到电视，与向导 APK 是不同组件。

先读 [README.md](README.md)、[DESIGN.md](DESIGN.md)、[GIT.md](GIT.md)，再读目标端说明和实际调用链：

- iOS：[docs/ios.md](docs/ios.md)。
- Android：[android-client/README.md](android-client/README.md)。
- 电视向导：[android-onboarding/README.md](android-onboarding/README.md)。
- 输入：[docs/remote-keyboard.md](docs/remote-keyboard.md)；预览：[docs/live-preview.md](docs/live-preview.md)。
- Widget：[docs/home-screen-widget.md](docs/home-screen-widget.md)；网络：[docs/lan-scan-environment.md](docs/lan-scan-environment.md)。
- Aerial：[docs/aerial.md](docs/aerial.md)；鼠标兼容记录：[docs/mouse-bravia-ur2.md](docs/mouse-bravia-ur2.md)。

专题文档中的历史实测不是当前任务的验证结果；行为与构建参数以当前代码为准。

## 工作区与修改范围

- 开始前检查 `git status --short` 和相关 diff。保留用户未提交改动，不擅自重置、清理、覆盖或暂存无关文件。
- 先定位调用方、共享实现、资源和测试，再修改根因。不要为单端改动重构整个项目。
- 不擅自更新 Vendor 二进制、Gradle 依赖或子模块版本。常规 iOS 构建使用已有预编译库，不必初始化子模块。
- 自有源码遵循 MIT；保留第三方版权与许可。依赖变动同步 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及 Android 随包许可。
- 不提交绝对本机路径、ADB 身份、keystore、密码、签名配置、IDE 个人状态和构建产物。不要输出 Secrets 或把密码放进命令参数。
- 提交、推送、发布、密钥上传、真机安装及设备设置变更按用户明确授权范围执行；不要把本地构建当成发布授权。远程和分支以当前 Git 状态为准，不根据旧文档猜测，也不默认强推或扁平化历史。

## 分层与并发

### iOS

- SwiftUI 页面负责显示与交互；共享 UI 状态和操作生命周期由 `AppModel` 管理。
- 设备命令放在 `ADBCommandService`；文件传输复用 `ADBSyncService.swift` 中的 `ADBFileService`；连接与持久服务由 `ADBMobileClient` 管理。
- `ADBMobileShim.*` 桥接嵌入式原生 ADB。另有 Swift `ADBClient` 实现，修改前确认实际调用链，不能按文件名替换生产传输。
- 遵循 `@MainActor` 和 actor 隔离；阻塞 I/O、解析与解码不能搬到主线程。异步结果须校验设备、请求或会话标识，取消后不得回填旧页面。
- 新 Swift 文件需手动加入 `iosadb.xcodeproj/project.pbxproj` 的引用与对应 Sources；工程未使用自动同步目录。

### Android

- `DeckApplication` 持有共享 `DeckModel`。`MainActivity` 处理界面，设备操作优先集中到 Model 或现有职责类，避免继续扩散 Shell 拼接。
- `DeckModel` 的普通操作、输入、预览使用独立执行器；UI 回调通过主线程 Handler。不要在主线程执行网络、文件传输或等待 Helper 回执。
- 连接层为 `AdbIdentity` 和本地 `adb-transport`；文件协议在 `AdbFiles`，递归/续传在 `Transfers`，安装归档在 `InstallBundle`。
- 保留连接 generation、设备 token、取消与关闭流的检查。`ConnectionService` 和 Widget 必须复用共享 Model，不能私建第二套身份或连接管理器。

## 输入、预览与连接约束

- 扫描和屏幕预览由用户手动启动。进入页面、回到前台或切换语言不能自动触发；自动重连与扫描/预览是不同功能。
- 仅重试连接，不重放安装、删除、Shell、按键或已发送但回执丢失的命令。长按与双指连发不得无界积压；取消、断连、切页和后台时停止后续输入。
- 双指方向键与单指鼠标互斥，不能在手势切换时补发单击或跳动。保留横向持续方向输入、触感反馈和触控板下方提示。
- 新同步键盘仅在弹框打开时建立 TextSync 自动化会话，关闭时关闭流。UTF-8 写入必须校验焦点 token、预期原文与回执；密码框和不支持编辑的控件拒绝，不回退退格重输。组词期间不发送中间文本，冲突后保留草稿并等待用户重新同步。
- 旧 `input text` 仅支持 ASCII，不得当作 UTF-8 同步键盘替代路径。不要记录输入原文、配对码、私钥或证书内容。
- 实时预览保留有界缓存、手动会话和截图回退。只能丢弃已解码的完整画面，不能随意丢 H.264 参考帧或按网络分片猜帧边界。
- Widget 只共享显示信息、设备标识和可失效的会话标识，不向 App Group 或 Widget 状态导出 ADB 身份；旧按钮不得控制切换后的设备。
- 新增设备 Shell 参数复用现有引用函数，不直接插入路径、包名或外部输入。用户主动提交的 Shell 原文与程序生成命令的参数处理要区分。预设和关键词只填入输入框，不自动执行。

## 跨端、资源与本地化

- 新增或修改可见字符串同时维护该端中英文：iOS 为 `en.lproj` / `zh-Hans.lproj`，Android 为 `values` / `values-zh-rCN`；电视向导也维护对应资源。iOS 复用 `L10n`。
- 公共功能改动检查两端调用链；不要声称未实现的平台已经等价。根 README 保持总览，详细功能和构建步骤放三端各自文档，中英文同步。
- 修改电视 Helper 源码或协议时检查 iOS `Resources/` 与 Android `assets/`。部分构建脚本只更新 iOS 资源，必须核对输出并同步需要的另一端；不要只改源码或随意复制不匹配的二进制。
- Aerial 两端同时维护：工具页紧凑状态行、独立详情页隐藏底部 Tab，按状态、基础地址、错误/兼容提示和操作顺序展示；共同支持安装/升级、Guard 重试、授权并启动、刷新和扫码。
- Aerial 基础地址仅显示 `http://<电视 IP>:8790` 并明确标注。完整管理地址只能读取电视实际二维码，不猜测、不持久化、不记录动态令牌。
- Aerial 下载与授权须保留官方来源、大小/SHA-256/包名/版本校验及每次操作确认。确认绑定当前设备和版本类型，切换设备或类型后失效；取消不下载、不安装、不授权。
- 使用须知保持双端版本、地区规则与文案一致。地区只读系统地区，不提供手动切换；未同意时禁止连接、扫描、设备命令和 Widget 操作。不要重新加入已移除的个人署名、邮箱或联系入口。

## 验证与交付

只运行与改动相关的检查。文档或低影响修改不用全量构建；行为变化补充能覆盖实际边界的测试。以下命令默认从仓库根目录执行。

| 改动范围 | 对应检查 |
| --- | --- |
| 旧文本输入、方向键 | `./tests/run-remote-text-tests.sh`、`./tests/run-directional-key-tests.sh` |
| 同步键盘、帧调度、心跳 | `./tests/run-sync-preview-tests.sh` |
| Widget | `./tests/run-widget-tests.sh`，并构建 iOS 主 App 验证扩展嵌入 |
| 设备二维码、扫描环境 | `./tests/run-qr-endpoint-tests.sh`、`./tests/run-lan-tests.sh` |
| Aerial | `./tests/run-aerial-qr-tests.sh`、`./tests/run-aerial-integration-tests.sh` |
| 使用须知 | `./tests/run-usage-consent-tests.sh`、`python3 tests/test_usage_notice_resources.py` |
| Java Helper | 按需运行 `tests/test_remote_keys.py`、`tests/test_text_sync_helper.py`、`tests/test_video_stream_helper.py`；先读脚本所需 JDK / Android SDK 环境变量 |
| H.264 实际编解码 | `./tests/run-h264-roundtrip-tests.sh`；环境不支持时如实报告跳过 |
| ADB 预编译库 | `python3 tests/test_adb_prebuilt.py`；模拟器运行另见 `tests/run-adb-simulator-smoke.sh` |
| Android Release / 签名工具 | `python3 tests/test_android_release_scripts.py`、`python3 tests/test_github_android_signing.py` |

完整 iOS 构建需要 macOS 与完整 Xcode：

```sh
xcodebuild -project iosadb.xcodeproj -scheme TVADB -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build
```

Android 需要 JDK 21、Android SDK Platform 35，使用工程内 Gradle Wrapper：

```sh
(cd android-client && ./gradlew :app:testDebugUnitTest :app:assembleDebug)
(cd android-onboarding && ./gradlew :app:assembleDebug)
```

设备测试另运行 `:app:connectedDebugAndroidTest`，需可用且获授权的模拟器/设备；编译测试 APK 不代表设备测试已执行。Release 入口为 `scripts/build-android-client-release.sh` 与 `scripts/build-android-tv-release.sh`；默认未签名，签名变量见各端文档。

提交前执行 `git diff --check`，逐项审阅暂存文件。交付说明修改、验证结果和未验证事项；构建通过不等于真机兼容、设备签名或端到端性能验证通过。
