# 开发与交付

开发环境和功能说明见 README.md，架构见 DESIGN.md，智能体规则见 AGENTS.md，版本管理见 GIT.md。

1. 先检查工作区修改，确认本次任务范围。
2. 按现有 SwiftUI / AppModel / command service / client 分层实现，更新中英文文案。
3. 执行相关逻辑测试与 arm64 模拟器构建；命令见 AGENTS.md。
4. 在变更说明中区分主机测试、构建、模拟器运行、iPhone 和电视真机测试。
5. 对未验证功能写清限制；报告 OEM 兼容问题时只附脱敏型号、Android 版本和必要日志。

当前键盘测试为独立 Swift 测试脚本，不是 Xcode XCTest target。不要把空的 Xcode TestAction 当作已有端到端测试。
