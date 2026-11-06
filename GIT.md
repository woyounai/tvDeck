# Git 工作约定

本目录保留从 https://github.com/wy164519317/tvadb 克隆的 Git 历史和 origin。当前提交与修改以 git log 和 git status 为准；移动目录不等于提交或推送，也没有新建远程 fork。

## 日常流程

```sh
git status --short
git diff
# 如需隔离开发，在审阅现有修改后创建分支：
git switch -c feature/remote-keyboard
./tests/run-remote-text-tests.sh
git diff --check
```

按功能选择文件暂存并查看 git diff --cached 后再提交；避免 git add . 将无关文件一起加入。提交说明应描述行为变化和原因。推送到远程前确认目标账号、仓库与用户授权。

## 仓库内容

提交源码、项目配置、测试和设计文档。忽略构建目录、编辑器个人状态、本地智能体 scratch 目录、设备日志及签名凭据。仓库现有 Vendor 压缩包和资源为已有项目依赖，不做批量删除。

`.gitattributes` 统一文本为 LF，标记二进制资源；不提交个人全局 Git 配置，不自动安装 hooks。首次引入行尾设置后不执行无关的全仓库重格式化。

## 子模块

Vendor/adb-mobile 已在 .gitmodules 声明。常规构建使用仓库提供的 arm64 预编译库；只有需要修改底层 ADB 时才按上游说明初始化和构建子模块。升级需同时记录子模块提交、构建方式和验证结果。
