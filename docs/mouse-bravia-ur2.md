# Sony BRAVIA 虚拟鼠标实测

测试日期：2026-10-29。

## 环境

Sony BRAVIA 4K UR2，Android 9 / SDK 28，armeabi-v7a。ADB shell 包含 uhid 组；SELinux Enforcing，/dev/uhid 对该组可读写。没有使用 root，也没有修改系统设备节点权限。

## 实测

运行仓库现有 Resources/AndroidUInput/TVADBVirtualMouse-armv7.bin，未修改助手二进制。

- 收到 TVADB_UHID_READY，设备成功注册为 TVADB Virtual Mouse。
- Android 识别为外部 USB 总线鼠标（bus 0x0003），输入源 0x00002002，Cursor Input Mapper 为 pointer，HasAssociatedDisplay 为 true。
- 使用双向 `adb shell -T` 传递助手 M dx dy 协议。getevent 从新建虚拟设备节点捕获了 REL_X/REL_Y 和 SYN_REPORT，正负方向都有。
- 用户现场确认：电视箭头出现并移动。
- 本次仅测试移动，没有发送点击、拖动或滚轮；没有验证 iPhone App 到 FIFO/持久写入通道的完整链路。
- 测试结束发送 Q，助手正常退出（状态 0）；临时程序文件已删除，未遗留测试助手进程。

## 测试方式修正

最初错误地使用 CLI `adb exec-out` 驱动交互式助手。该方式没有将本次客户端标准输入送入助手，注册成功却没有移动事件，不能据此判断 TV 不支持虚拟鼠标。改用双向 shell 后，实际事件和可见移动均验证成功。App 内的原始双向 ADB 服务流与 CLI exec-out 的单向测试方式不同。

## 后续排查

若手机 App 中仍无可见箭头，优先检查 armv7 资源选择、FIFO 写入、持久控制流，以及切换页面时是否提前停止鼠标。不要把本机已验证可用的 UHID 权限误判为需要 root。

## App 启动失败的根因与修复

进一步复现 App 原始启动脚本时，电视明确返回 `mkfifo: ... Permission denied`。原先 App 依赖 /data/local/tmp 下的 FIFO 连接后台助手，即使 UHID 可用也会在此前置步骤失败。这与点击位置提示无关；开启 show_touches 只会显示点击圆点。

捆绑 UHID/uinput 助手现改为由双向 ADB exec 服务直接托管，读取 READY 回执并检查 InputReader 注册后写入移动报告，不再要求创建 FIFO 或运行 nohup/tail 保活进程。关闭流时请求 Q；直连失败不会再写入不存在的 FIFO。AOSP 系统 uinput 工具的旧兼容分支仍保留。

鼠标使用提醒中新增重新启用虚拟鼠标入口，允许清除当前连接内缓存的失败状态后重试。原始助手的直连移动已在这台电视验证；修改后的 iPhone App 仍需要重新构建安装后验证完整 UI 通道。

## 鼠标底部按钮与事件测试

底部居中、拖动和更多从触控板手势叠层中拆出，放到独立工具栏；悬浮入口移入保留空间的底部区域，避免覆盖工具栏。更多改成独立 sheet，关闭后再执行选择，不再依赖 UIKit Menu 源视图动画。双击在释放拖动后仍会执行双击。

原先居中仅重置手机坐标，服务层坐标恰好为中心时不会发送任何移动。新增 H x y 校准协议：释放按键、归零、等待速度历史失效，再以低速小步移动；读取电视指针速度比例，避免快速大位移触发加速。校准期间禁用触控板，成功收到 TVADB_MOUSE_CENTERED 后才同步手机中心坐标。此过程可能耗时数秒。

实际使用新 armv7 助手在电视主页测试，收到居中回执；从虚拟输入节点捕获左键 DOWN → REL_X → UP、右键 DOWN/UP、中键 DOWN/UP、正反 REL_WHEEL 与 SYN_REPORT，全部通过。此检查证明电视收到相应鼠标协议，并非 iPhone 按钮触摸或居中像素精度的端到端验收。临时助手已退出并删除。
