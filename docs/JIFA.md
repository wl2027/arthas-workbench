# Arthas Workbench 中的 Jifa 集成说明

## 当前方案

当前插件只保留一条 Jifa 分析链路：

- 浏览器版 `Open in Jifa Web`

适用文件：

- `.jfr`
- `.hprof`
- `.phd`
- GC 日志
- thread dump

IDEA 内嵌 JFR 入口已经下线，JFR 统一走浏览器版 Jifa Web。

## 代码组织

- `jifa/`
  作为 `git submodule` 挂载的 Jifa 源码，默认指向 `https://github.com/wl2027/jifa.git` 的 `arthas-workbench` 分支
- `jifa-bridge/`
  组合构建桥接层，把 Jifa 的分析模块映射成当前插件可直接依赖的 Gradle 模块
- `src/main/java/com/alibaba/arthas/idea/workbench/service/JifaWebRuntimeService.java`
  管理本地 Jifa Web 服务、文件同步、缓存和托管索引
- `src/main/java/com/alibaba/arthas/idea/workbench/action/OpenInJifaWebAction.java`
  提供右键入口

## 为什么继续使用 Submodule

当前主仓不再 vendored 整份 Jifa 源码，而是保留子模块形式，原因是：

- Jifa 本身是独立项目，单独维护历史更清晰
- 主仓只需要记录一个明确的 Jifa 提交指针
- 别人拉取主仓后，通过 `git submodule update --init --recursive` 就能复现依赖版本

当前确实存在一处插件侧需要的 Jifa 补丁：

- `jifa/server/src/main/java/org/eclipse/jifa/server/configurer/HttpConfigurer.java`
  增加 `/jfr-file-analysis/*` 的前端路由转发，保证浏览器直接打开 JFR 分析深链接时不会 404

这个补丁属于浏览器版 JFR 直达能力的一部分，不再和 IDEA 内嵌 JFR 有关系。

## 构建链路

根项目通过 `settings.gradle.kts` 中的 `includeBuild("jifa-bridge")` 接入 Jifa 分析模块。

同时，`build.gradle.kts` 会在需要时调用子模块自己的 `gradlew` 构建：

- `jifa/server:bootJar`

生成后，主仓会把产物复制到：

- `build/generated/jifa-helper/arthas-jifa-server-helper.jar`

这样 `runIde`、`prepareSandbox` 和 `buildPlugin` 都能把本地 Jifa helper server 一起带入插件沙箱。

## 缓存与托管目录

Jifa 浏览器版统一使用：

- `~/.arthas-workbench-plugin/jifa/storage`
- `~/.arthas-workbench-plugin/jifa/meta`
- `~/.arthas-workbench-plugin/jifa/logs`

职责约定：

- `storage`
  Jifa 服务端实际存储、上传文件和分析数据
- `meta`
  服务状态和导入索引
- `logs`
  helper server 运行日志

服务、缓存和索引都是全局共享的：

- 同一台机器上的 IDEA 插件实例共用一套目录
- 本地只维护一个健康的 Jifa helper server
- 打开新项目时会增量扫描当前所有已打开项目下的 `arthas-output`

## 文件托管规则

托管来源分两类：

1. 当前 IDEA 已打开项目下自动扫描到的 `arthas-output` 文件
2. 用户主动右键 `Open in Jifa Web` 的任意可分析本地文件

第二类文件即使不在 `arthas-output` 目录下，也会写入托管索引。后续：

- 文件未变化时会复用已有远端记录
- 文件更新后会重新上传
- 文件被删除后会自动从索引和 Jifa 远端存储中清理

这意味着它的行为等价于“被上传到本地 Jifa 并持续管理”，而不是一次性临时打开。

## 初始化与验证

首次拉取主仓后，先执行：

```bash
git submodule update --init --recursive
```

推荐验证步骤：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin -x buildSearchableOptions -x jarSearchableOptions
```
