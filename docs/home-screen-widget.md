# 桌面电视遥控 Widget

## 使用

需要 iOS 17 或更新版本。安装新版本后，在 TVADB 中连接并授权电视，返回桌面，长按空白处 → 编辑／添加小组件 → TVADB → 电视遥控器。支持中号与大号；可添加多个相同组件，均跟随 App 当前连接的电视。

中号包含五向／OK、返回、主页、电源、音量加减和静音；大号再提供菜单、播放／暂停及信号源。右上角打开 App 遥控页。按键为单次点击，WidgetKit 不提供与 App 方向键相同的长按连续加速。

组件显示的是上次成功连接记录，不是实时在线监测。手动断开／切换设备会清除旧连接并刷新；旧画面上的过期按钮会被拒绝，不能控制下一台电视。若 App 被系统回收，点击可尝试恢复同一台已保存电视，连接授权等待限制为 8 秒；失败后打开 App 重新连接。电视关机或网络断开时不保证可控制或唤醒。

## 架构

TVADBWidget.appex 只绘制界面和读取 App Group 中的 WidgetRemoteState（名称、设备 ID、连接随机 ID、连接时间和最近操作结果）。不共享 ADB 私钥、配对码、文件目录或电视输入文本，也不在时间线中扫描／联网。

TVRemoteWidgetIntent 同时编译到主 App 与扩展，使用 LiveActivityIntent 将执行路由到主 App 进程，不启动 Live Activity。主 App 使用单一 TVADBAppRuntime.model、原 ADB 身份与 ADBCommandService；冷启动仅恢复指定 ID 的设备，不选择其他最近设备。该方式依据 [Apple 的交互式 Widget 文档](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities)。

每次连接轮换 connectionID；执行检查 ID 和命令白名单，串行接受按键，拒绝忙碌期间积压。不会自动重试已发送但缺失回执的按键，避免双击。连接时绕过文件列表加载；主 App 前台使用的默认连接流程保留。

## 签名和分发

主 App 与 TVADBWidget 扩展必须使用同一个团队，两个 App ID 都启用 App Groups，并包含同一个共享组。工程默认 TVADB_APP_GROUP = group.com.tvadb.ios；fork／自签时在项目级修改为自己团队注册的组，并同步使用对应的两个描述文件。扩展 Bundle ID 默认 com.tvadb.ios.RemoteWidget，必须以主 App Bundle ID 为前缀。无签名构建只能验证编译，不能验证设备上的共享容器权限。

Widget 已作为主 App 目标依赖嵌入 PlugIns/TVADBWidget.appex。导出／重签 IPA 时必须保留并正确重签这个扩展，以及双方 App Groups entitlement；只给主 App 签名不能使用 Widget。

## 验证

运行 tests/run-widget-tests.sh 验证共享状态、Android keycode 映射、未知命令拒绝、设备切换、重连旧时间线失效、手动断开和损坏数据处理。既有同步／方向测试用于回归。主 App + Widget arm64 模拟器无签名构建用于确认扩展打包和双目标编译。

尚未在 iPhone 上验证桌面排版、连续点击、后台唤起／系统回收后恢复、本地网络授权、真实电视按键响应与签名共享容器。Widget 刷新时机由 iOS 调度，旧画面可能短暂保留，但连接随机 ID 的执行校验不依赖画面刷新。

## Android 对齐布局

Android 组件采用相同的左侧五向盘、右侧两排快捷键；高度达到 220dp 时增加菜单／播放／信号源第三排。标题和右上角入口打开 App 遥控页，未连接时显示连接提示。大小变化重新绘制，按键仍通过连接标识和白名单校验，不共享 ADB 身份。
