# TVDeck Android

[简体中文](README.md) · [English](README_EN.md)

[返回项目总览](../README.md) · [连接电视](../docs/device-connection.md) · [Aerial 联动](../docs/aerial.md)

安卓手机／平板上的 TVDeck 客户端，包名 `com.tvdeck.client`，与电视端连接向导 `com.tvadb.onboarding` 分开安装。支持 Android 8.0（API 26）及以上。

## 已接入的功能

- 手动 ADB TCP 连接、Android 11+ 无线配对／TLS、NSD 服务发现、扫描 TVDeck／TVADB 连接二维码、保存多个设备、名称／分组／备注、收藏与最近连接；手动扫描当前私有 IPv4 网段的 5555 端口。
- 五个主页面：文件、应用、遥控、Shell、工具；霓虹配色、霓虹／珠白启动器图标及启动背景。
- ADB Sync 文件浏览、上传、下载、新建目录、重命名、批量选择和确认后删除；递归目录传输、SHA-256 校验与分块断点续传。上传／下载通过 Android 系统文件选择器授权，不申请整个存储权限。
- 用户／系统应用列表与搜索；通过现有电视 Helper 读取名称、图标和版本，Helper 不可用时仍可使用包名；手机端查看包详情、启动、强停、清缓存／清数据、卸载。
- 单 APK、XAPK/APKS/APKM 归档解析、ABI／密度／语言 split 选择、OBB 部署、APK 权限／组件／证书分析、安装历史，以及手动选择多个 split APK 的安装会话，失败时放弃安装会话并清理上传文件；单 APK 导出、base/split 完整 ZIP 导出。
- 方向键短按／长按、常用电视键、数字键。方向键复用持久 Helper，其他键走标准 ADB；UHID/uinput 触控板、双击、右／中键、按住拖动和滚轮；双指方向键、横向持续连发与触感反馈。
- 电视键盘读取与安全提交：仅操作支持无障碍编辑的焦点控件，保留 token／原文校验及密码框拒绝；双向持续同步，组合输入期间不发送，确认动作等待写入回执；控件切换或原文冲突后暂停，必须显式重新同步。
- 手动 H.264 MediaCodec 解码预览，失败时回退截图兼容模式；画面点击／滑动／长按、PNG 保存、15/30/60 秒无声录屏下载。
- Shell／预设命令、本次历史与显式命令收藏、支持搜索／Tag／PID／最低等级过滤、暂停和导出的实时 Logcat、网络诊断、设备信息、打开电视设置／开发者选项，以及确认后普通／Recovery／Bootloader 重启。

## 与 iOS 版的差异

已补齐上一版缺失的主要操作链路，并增加 Android 前台连接服务、桌面遥控 Widget、语言／深浅外观、自动连接、覆盖安装及应用图标加载设置。Widget 仅保存设备记录和失效可撤销的会话标识，不导出 ADB 身份。断开或删除设备后旧按钮失效；系统回收后的自动重连仅限 Widget 当前保存的目标。

仍有明确边界，不能视为全部逐项等价：

- 归档中出现多个独立 standalone APK 变体时拒绝自动猜选；需手动选取对应 APK。不解析 bundletool 的 `toc.pb` 设备定向元数据。
- APK 分析使用 Android 包解析器，展示包信息、SDK、权限、组件和签名证书 SHA-256；不提供完整签名方案审计。
- 应用详情、设备信息、网络诊断保留原始输出；未复刻 iOS 的全部卡片布局、遥控排序和主题选项。
- 上传续传重新选择同一源文件后恢复；下载校验完成后才写入系统文档。文档提供方写入失败仍可能留下不完整目标，需重试。远端需支持 `sha256sum`、`stat`、`head` 和 `dd`。
- 系统可能回收前台服务，服务不保证永久在线。断点缓存不包含 ADB 身份；卸载 App 会清除本地缓存与记录。

鼠标的可见性与输入权限依赖电视固件；已接入 Android 15 显示器关联，但无线配对、电视硬件解码、OEM 输入法、UHID/uinput、split／OBB、断网续传和 Widget 后台行为仍需真实设备验收。预览不会在进入页面或回到前台时自动启动。解码只丢弃完整已解码画面，不丢压缩参考帧。

## Aerial 联动

首页或工具页选择「扫码连接 Aerial」，扫描电视 Aerial 的手机管理二维码，在系统浏览器中打开官方管理页。可使用 Aerial 网页提供的桌面整理、壁纸和屏保管理功能；不需要先连接 ADB。

仅接受当前 Aerial 生成的私有 IPv4 地址、8790–8799 端口与 8 位令牌路径（Lite 在端口占用时会顺延）。手机和电视需在同一可信局域网，且 Aerial 正在运行。不会保存或记录令牌，也不会将管理端口当作 ADB 端口。连接 ADB 后自动检测 Aerial，工具页显示安装版本，未安装时可一键安装，已安装时可升级。首次安装可选 Standard 或 Guard，Android 4.x 自动选 Lite；升级保留当前类型。

安装按钮从官方 GitHub Release 获取最新稳定 APK，校验下载大小、SHA-256 和包名，重新读取电视版本以避免降级。覆盖安装保留数据，签名不兼容时显示失败，不自动卸载。安装或升级后按官方安装说明 Windows 第 3、6 步执行授权、启动和适用系统上的编译优化。单项授权失败或不支持会记录并继续；通知监听旧接口会保留原有服务。最终启动需收到 `Status: ok`，结果页显示每步命令、输出与退出码（单步显示最多 16384 个字符）。已安装时也可单独选择「授权并启动」，Standard 首次安装失败后可手动重试 Guard。

此流程不执行默认桌面、屏保、保持 ADB 常开、重启及完整接管验收步骤，也不会卸载应用。SDK 小于 14 时拒绝安装。GitHub API 或下载不可达时可以重试，安装状态检测不依赖互联网。

## 构建

### IntelliJ IDEA / Android Studio

打开本目录 `android-client`，按 Gradle 工程导入并完成同步，不要将 `app/src/main` 单独导入为 Java 模块。IDEA 需启用兼容的 Android 插件；未提供 Android 支持的版本请使用 Android Studio。

设置 Gradle JDK 为 JDK 21，并配置 Android SDK 35。同步后选择共享运行配置 `TVDeck Android`，选择模拟器或已授权的安卓手机／平板，点击 Run 即可构建、安装并启动客户端。配置位于 `.run/TVDeck Android.run.xml`，不固定设备或本机 SDK 路径。

### 命令行

需要 JDK 21、Android SDK 35 和网络（首次解析固定版本依赖）。在仓库根目录运行：

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk ./scripts/build-android-client.sh
```

输出 `dist/TVDeck-Android-debug.apk`。这是调试签名 APK，签名材料和构建产物均不提交。安装到真实设备前请自行确认目标；构建脚本不会安装或修改电视设置。

## 验证

```sh
cd android-client
./gradlew testDebugUnitTest
ANDROID_SERIAL=<emulator-id> ./gradlew connectedDebugAndroidTest
```

单元测试覆盖二维码可信边界、Shell 参数引用、Sync 报文长度与截断、Annex-B 分片、键盘组合输入／回执／冲突、归档路径与大小边界、畸形 Manifest、扫描地址边界。设备测试使用隔离的测试身份和本地模拟 ADB 服务器，验证身份重载、RSA AUTH 签名与 UTF-8 shell 往返、真实 APK Manifest 解析、设备记录和 Widget 会话失效；不会连接真实电视。测试通过不能替代真实电视兼容性测试。

## 身份与依赖

ADB 身份保存在 App 私有的 no-backup 目录，系统备份被关闭，不导出私钥、证书或配对码。配对码只用于当前配对请求。随源码引入的 ADB Java 层已移除 peer 信息日志，并修正了远端关闭时的缓冲读取，不记录键盘原文。

ADB/TLS 使用附带源码与局部修正的 LibADB Android 3.1.1（选择 Apache-2.0 许可，见 `adb-transport/README.md`）、Conscrypt 2.5.3、Spake2-Java 2.2.1（LGPL-3.0）；证书使用 Bouncy Castle 1.81，二维码使用 ZXing 3.5.3。完整来源与许可文本随 APK assets 提供，工具页可查看第三方声明。源码及固定版本 Gradle 配置支持重新构建和替换依赖。

## VPN 与局域网连接

使用电视的数字 IPv4 地址连接时，TVDeck 会查找 Wi-Fi／以太网的实际网段，将同网段私有地址的 ADB TCP、无线配对和后续 TLS 连接绑定到该物理网络。局域网扫描也使用物理网络；不会绑定整个 App，GitHub 下载继续遵循系统默认网络／VPN 路由。非同网段地址、远程 VPN 内设备和主机名保留系统路由。

系统 VPN 锁定或企业策略可能禁止局域网访问，此时需在允许的 VPN 配置中放行 TVDeck／局域网，App 不会修改 VPN 配置或规避系统限制。

## 后台连接、重连与遥控组件

连接操作开始时启动 `connectedDevice` 前台服务，通知栏可随时断开。服务每 15 秒检查一次是否需要探测，距离上次 ADB 活动满 60 秒才发送心跳。心跳的流打开和响应读取共用 8 秒预算；意外销毁服务不主动关闭 ADB，系统重启服务时仅恢复此前保存的目标。首次连接失败最多重试 3 次，断线后最多重新连接 3 次，全部失败返回设备页；手动断开后不重连。仅重试建立连接，安装、删除、Shell 和按键均不会自动重放。省电策略、Doze、网络隔离和系统回收仍可能中断后台执行。

桌面组件采用与 iOS 一致的左侧五向／OK、右侧返回／主页／电源和音量减／静音／音量加布局；增加高度后显示菜单、播放和信号源。组件跟随当前设备，冷启动恢复仍使用单次 8 秒连接预算，避免组件执行无限等待；旧连接标识不能控制切换后的电视。

Aerial 显示分阶段进度：下载按已接收字节推进，安装阶段显示不定进度，授权与启动按已完成命令推进。阶段切换会重置进度，不把下载完成视为安装成功。

预览保持 Surface 尺寸稳定，避免首帧回调调整 Surface 后将播放器关闭；每个 Surface 仅关闭自己创建的播放器。编码和解码使用同一套按 16 对齐的尺寸，结束读取后给首帧有限的解码时间，停止后忽略过期回调。视频无法解码时回退截图；本机测试不能替代真实电视／手机的黑屏和切页崩溃复测。

## 应用刷新与 Shell 布局

卸载成功后清除该包的名称／图标缓存并自动重新加载当前列表。名称和图标只更新当前页面实际存在的应用行，忽略其他筛选页和已卸载应用的缓存；旧页面／旧设备回调不能覆盖新列表。忙碌时的刷新请求延后执行，连续刷新只保留当前页面请求。

Shell 输出区默认占满输入栏之外的可用空间，新结果和重新进入页面时显示输出末尾。命令关键词默认折叠，可在输入栏「更多 → 命令关键词」切换；原有预设、历史和收藏保留。只有点击发送才执行命令。


## Release 构建

需要 JDK 21、Android SDK Platform 35；签名时还需 Build Tools 35.0.0。先设置本机 `JAVA_HOME`、`ANDROID_HOME`，在仓库根目录运行：

```sh
./scripts/build-android-client-release.sh
```

脚本运行单元测试和 Release APK 构建，默认输出：

```text
dist/TVDeck-Android-release-unsigned.apk
```

未签名 APK 不能直接安装。要生成可安装的发布包，提供以下环境变量，再运行相同脚本：

- `TVADB_KEYSTORE`：你自己的发布 keystore 文件路径。
- `TVADB_KEY_ALIAS`：发布密钥别名。
- `TVADB_STORE_PASSWORD`、`TVADB_KEY_PASSWORD`：通过本机安全方式注入的密码，不要写入仓库或脚本。
- `TVADB_BUILD_TOOLS`：可选，默认 `35.0.0`。

签名成功并校验后，输出 `dist/TVDeck-Android-release.apk`。持续升级必须保留相同包名和签名；请妥善备份密钥。脚本不生成密钥、不安装、不上传。


## 许可

本项目自有代码遵循 [MIT License](../LICENSE)，第三方依赖保留其原许可，见[第三方声明](../THIRD_PARTY_NOTICES.md)。
