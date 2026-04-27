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

IDEA 内嵌 JFR/Jifa 分析入口已经下线，所有文件统一走浏览器版 Jifa Web。

## 代码组织

- `src/main/java/com/alibaba/arthas/idea/workbench/service/JifaWebRuntimeService.java`
  负责 helper 解析、Jifa 本地服务启动、文件同步、缓存管理和导入索引
- `src/main/java/com/alibaba/arthas/idea/workbench/analysis/JifaAnalysisFacade.java`
  负责识别文件类型，决定目标文件能否交给 Jifa Web
- `src/main/java/com/alibaba/arthas/idea/workbench/action/OpenInJifaWebAction.java`
  提供项目视图和编辑器中的右键入口
- `jifa/`
  仅作为 helper 发布和上游补丁维护使用的可选 submodule，不再参与主插件构建依赖

## helper 解析优先级

插件在真正打开 Jifa 分析页时才解析 helper jar，优先级如下：

1. JVM 参数 `-Darthas.workbench.jifa.helper.path=...`
2. Settings 中配置的 `Offline Helper Path`
3. 插件目录 `lib/arthas-jifa-server-helper.jar`
4. 工作区中手工构建出来的 `jifa/server/build/libs/jifa.jar`
5. 自动下载并缓存到 `~/.arthas-workbench-plugin/jifa/runtime/<version>/arthas-jifa-server-helper.jar`

其中离线路径支持两种形式：

- 指向一个具体 jar 文件
- 指向一个目录，目录里包含 `arthas-jifa-server-helper.jar` 或 `jifa.jar`

这套设计的目标是：

- 插件包本身保持很小
- 在线环境首次使用即可自动准备 helper
- 离线环境可以通过设置页显式指向本地 helper

## 缓存与托管目录

Jifa 浏览器版统一使用：

- `~/.arthas-workbench-plugin/jifa/storage`
- `~/.arthas-workbench-plugin/jifa/meta`
- `~/.arthas-workbench-plugin/jifa/logs`
- `~/.arthas-workbench-plugin/jifa/runtime`

职责约定：

- `storage`
  Jifa 服务端实际存储、上传文件和分析数据
- `meta`
  服务状态和导入索引
- `logs`
  helper server 运行日志
- `runtime`
  自动下载得到的 helper jar

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

## helper 构建与发布

如果你需要更新默认自动下载的 helper jar，请按下面的流程操作：

1. 初始化子模块：

```bash
git submodule update --init --recursive
```

2. 构建 helper：

```bash
cd jifa
./gradlew :server:bootJar --no-daemon
```

3. 构建产物位于：

`jifa/server/build/libs/jifa.jar`

4. 上传到主仓 `arthas-workbench` 的 GitHub Release 时，重命名为：

`arthas-jifa-server-helper.jar`

5. 插件默认下载地址为：

`https://github.com/wl2027/arthas-workbench/releases/latest/download/arthas-jifa-server-helper.jar`

因此 latest release 中需要存在这个同名资产。

## 初始化与验证

插件主构建不再要求先拉 Jifa submodule。常规验证步骤：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
```
