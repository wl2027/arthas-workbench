<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# arthas-workbench Changelog

## [0.0.5] - 2026-04-28
### Added
- Settings 页面新增 `Offline Helper Path`，支持为 Jifa Web 配置本地 helper jar 路径或 helper 目录，满足离线环境使用
- Jifa 运行时缓存摘要新增 `runtime` 维度，便于定位已下载的 helper 版本和占用空间

### Changed
- 插件包不再内置 Jifa helper，改为在首次打开 Jifa Web 分析时按需自动下载并缓存
- 插件主构建不再依赖 Jifa submodule；常规 `buildPlugin` / `runIde` / `test` 流程无需先初始化子模块
- README 与 `docs/*` 同步更新为“运行时下载 helper + 可选离线路径”方案，并补充 helper 单独发布步骤

## [0.0.4] - 2026-04-27
### Added
- 集成 Jifa Web 文件分析入口，支持从 IDEA 右键打开 `.jfr`、`.hprof`、GC 日志和 thread dump
- Settings 页面新增 Jifa Web 缓存概览、目录打开与清理操作

### Changed
- 将 Jifa 依赖形态调整为 `git submodule` + `jifa-bridge`，并改为调用子模块自己的 `gradlew` 构建 helper server
- 构建流程新增本地 Jifa helper server 打包与沙箱准备步骤
- 下线 IDEA 内嵌 JFR 入口，Jifa 文件分析统一走浏览器版 `Open in Jifa Web`
- 浏览器版 Jifa Web 现在会持续托管用户主动打开的任意可分析本地文件，不再局限于 `arthas-output`
- README 与 `docs/*` 同步补充 Jifa 集成、缓存和发布约定说明

### Fixed
- 修复浏览器版 JFR 深链接缺少 `/jfr-file-analysis/*` 路由转发的问题

## [0.0.3] - 2026-03-25
### Changed
- 将插件内原本面向会话终端的 `Arthas Console` 概念统一更名为 `Arthas Terminal`
- 同步更新会话 UI、设置项、资源文案、README 与 `docs/*` 中的 `Terminal / Log` 表述
- 移除 `ArthasWorkbenchSettingsService` 与设置面板中遗留的 `autoConnectConsole` 配置字段，统一使用 `autoOpenTerminal`

### Fixed
- 调整相关测试断言，避免因为默认资源语言差异导致测试结果不稳定

## [0.0.2] - 2026-03-24
### Fixed
- 移除 `ArthasTelnetTtyConnector` 对 `Questioner` 和 `TtyConnector.init(Questioner)` 的覆写，消除 `verifyPlugin` 中的 scheduled for removal API 告警
- 显式实现 `TtyConnector.resize(TermSize)`，避免 Telnet 终端继续依赖旧的默认分支
- 明确使用 JDK 21 运行 `verifyPlugin`，规避本地异常 `JAVA_HOME` 带来的 `instrumentCode` 失败问题

## [0.0.1] - 2026-03-23
### Added
- 支持自动发现并 Attach IDEA Run/Debug 启动的 Java 进程
- 基于 Workbench + Sessions Tool Window 的双窗口 UI 结构
- Settings 页面统一管理包来源、端口、Arthas 密码、Gateway 与打开行为
- `PackageSource` 统一抽象 5 种 Arthas 包来源
- `AttachStrategy` 抽象与 `Arthas Boot` 实现
- 使用 JediTerm 直连 Arthas Terminal 的真实终端会话
- MCP Gateway 聚合多个会话并提供稳定入口
- Spotless 格式化链路，接入 `runIde` / `build` / `buildPlugin` / `test` / `publishPlugin`
