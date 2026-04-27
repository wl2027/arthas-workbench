# Arthas Workbench 使用说明

## 适用场景

Arthas Workbench 面向本地开发与调试场景，适合下面几类使用方式：

- 在 IDEA 内直接发现并 Attach 当前 Run/Debug 的 Java 进程
- Attach 非 IDEA 启动的本地 JVM
- 在 IDE 内维护多个 Arthas 会话的 Terminal / Log
- 用浏览器打开目标会话的 Arthas Web UI
- 右键分析 `.jfr`、`.hprof`、GC 日志和 thread dump 文件
- 把多个会话统一暴露为一个稳定的 MCP Gateway

## 安装插件

### 本地打包

```bash
cd /path/to/arthas-workbench
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
```

默认产物：

`build/distributions/*.zip`

### 在 IDEA 中安装

1. 打开 `Settings -> Plugins`
2. 点击右上角齿轮按钮
3. 选择 `Install Plugin from Disk...`
4. 选择刚刚生成的 zip 包
5. 重启 IDEA

## 第一次使用

### 1. 打开设置页

路径：

`Settings -> Tools -> Arthas Workbench`

建议先确认 5 项：

- `包来源`
- `端口分配策略`
- `Agent MCP 密码`
- `MCP Gateway`
- `Offline Helper Path`

### 2. 选择包来源

当前支持 5 种来源：

- `官方最新版本`
- `官方指定版本`
- `自定义远程 Zip`
- `本地 arthas-bin.zip 文件`
- `本地 arthas-bin 目录`

默认缓存目录：

`~/.arthas-workbench-plugin/packages`

## 开启 Arthas

### 1. 打开 Workbench

在 IDEA 右侧打开 `Arthas Workbench` Tool Window。

你会看到两个分页：

- `IDEA`
- `本地`

其中 `IDEA` 分页会优先展示当前 Run/Debug 启动的 Java 进程。

### 2. 选择目标 JVM

可用方式：

- 单击列表项后，使用底部按钮
- 右键列表项，使用上下文菜单

### 3. 开启 Arthas

点击 `开启 Arthas` 后，插件会自动完成：

- 解析 Arthas 包
- 选择 Attach Java
- 分配 HTTP / Telnet 端口
- 按设置生成或读取 `Agent MCP 密码`

Attach 成功后：

- 当前进程状态会变成 `已附加`
- 可以打开会话
- 可以打开 Web UI
- 可以复制统一 MCP 配置

## 使用会话窗口

`Arthas Sessions` 位于左下方，用于统一管理多个会话。

### Terminal

- 通过 `JediTerm + Telnet` 连接 Arthas Terminal
- 可以直接输入命令

### Log

- 记录当前会话的 Attach 日志和运行日志
- 适合排查 Attach 失败、端口冲突、包路径等问题

## 使用 Jifa 分析文件

当前插件统一通过 `Open in Jifa Web` 打开 Jifa 分析。

### 浏览器版 Jifa Web

适用文件：

- `.jfr`
- `.hprof`
- `.phd`
- GC 日志
- thread dump

使用方式：

1. 在 IDEA 的项目视图或编辑器标签页中右键目标文件
2. 选择 `Open in Jifa Web`
3. 插件会启动或复用本地 Jifa 服务，并在浏览器打开对应分析页

### helper 自动下载与离线配置

默认情况下，插件会在第一次真正使用 Jifa 时自动下载 helper jar，缓存到：

`~/.arthas-workbench-plugin/jifa/runtime/<version>/arthas-jifa-server-helper.jar`

如果你处于离线环境，可以在设置页配置 `Offline Helper Path`：

- 直接配置 jar 路径
- 或配置一个包含 `arthas-jifa-server-helper.jar` / `jifa.jar` 的目录

插件会优先使用这个本地 helper，不再先走自动下载。

### 文件托管范围

浏览器版会自动扫描当前已打开项目中的 `arthas-output` 目录，并把用户主动右键打开的任意可分析本地文件一并纳入托管索引。统一缓存位于：

`~/.arthas-workbench-plugin/jifa`

设置页中可以查看并清理：

- `storage`
- `meta`
- `logs`
- `runtime`

## 使用 MCP Gateway

### 统一入口

插件会暴露固定的 Gateway MCP 地址：

`http://127.0.0.1:<gateway-port>/gateway/mcp`

### 推荐使用方式

1. 在 Settings 中配置 `MCP Gateway` 认证策略
2. 在 Workbench 点击 `复制 MCP`
3. 将复制出的配置粘贴到 AI 客户端或 IDE 助手中

## 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [JIFA.md](JIFA.md)
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- [DEVELOPMENT.md](DEVELOPMENT.md)
