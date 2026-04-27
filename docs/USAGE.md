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
git submodule update --init --recursive
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin -x buildSearchableOptions -x jarSearchableOptions
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

建议先确认 4 项：

- `包来源`
- `端口分配策略`
- `Agent MCP 密码`
- `MCP Gateway`

### 2. 选择包来源

当前支持 5 种来源：

- `官方最新版本`
- `官方指定版本`
- `自定义远程 Zip`
- `本地 arthas-bin.zip 文件`
- `本地 arthas-bin 目录`

对应输入规则：

- 选 `官方最新版本` 时，“版本 / 地址 / 路径”会直接显示官方下载链接，且不可编辑
- 选 `官方指定版本` 时，填写版本号，例如 `4.1.8`
- 选 `自定义远程 Zip` 时，填写可直接下载的 `arthas-bin.zip` 地址
- 选 `本地 arthas-bin.zip 文件` 时，点击输入框直接选文件
- 选 `本地 arthas-bin 目录` 时，点击输入框直接选目录

### 3. 更新官方最新版包

如果你当前选择的是 `官方最新版本`，设置页会出现 `更新官方最新版包` 按钮。

它的作用是：

- 强制重新下载最新版 Arthas 包
- 覆盖插件缓存目录下的旧缓存
- 适合官方最新包已有更新，但你不想等待下次 Attach 时再刷新

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
- 命令补全依赖 Arthas 原生命令补全

### Log

- 记录当前会话的 Attach 日志和运行日志
- 适合排查 Attach 失败、端口冲突、包路径等问题

### 多会话

- 每个会话对应一个 tab
- 同时支持多个 JVM 会话并存
- 运行中的会话不允许误关闭
- 停止或失败后的 tab 可以手动关闭

## 打开 Web UI

插件不再内嵌 Web UI，而是直接调用默认浏览器打开。

入口有两种：

- Workbench 底部动作
- 右键菜单中的 `打开 Web UI`

当前默认是本地访问，因此更适合本地开发调试。

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

浏览器版会自动扫描当前已打开项目中的 `arthas-output` 目录，并把用户主动右键打开的任意可分析本地文件一并纳入托管索引。统一缓存位于：

`~/.arthas-workbench-plugin/jifa`

设置页中可以查看并清理：

- `storage`
- `meta`
- `logs`

## 使用 MCP Gateway

### 统一入口

插件会暴露固定的 Gateway MCP 地址：

`http://127.0.0.1:<gateway-port>/gateway/mcp`

这比直接使用某个会话的 MCP 地址更稳定，因为会话地址会随着 Attach 变化。

### 推荐使用方式

1. 在 Settings 中配置 `MCP Gateway` 认证策略
2. 在 Workbench 点击 `复制 MCP`
3. 将复制出的配置粘贴到 AI 客户端或 IDE 助手中

### `Agent MCP 密码` 和 `Gateway Token` 的区别

- `Agent MCP 密码`
  只用于插件访问 agent 侧 MCP
- `Gateway Token`
  只用于外部客户端访问插件内置 Gateway

它们不是一套密码，也不会互相替代。

## 常用操作建议

### 本地开发最推荐的路径

1. 用 IDEA 运行目标 Java 程序
2. 在 `IDEA` 分页里选中进程
3. 点击 `开启 Arthas`
4. 自动打开或手动打开会话
5. 需要图形界面时，再打开浏览器版 Web UI

### 想要更稳定的 MCP 接入

1. 不要直接保存单会话 MCP 地址
2. 统一使用 Gateway MCP
3. 如果需要长期复用，优先给 Gateway 设置固定 Token

## 常见问题

### 为什么 Terminal / Web UI 不需要输入 `Agent MCP 密码`

因为当前密码主要用于 agent MCP，不是 arthas 的 auth 密码。

### Attach 失败应该先看哪里

优先看：

- 当前会话的 `Log`
- `docs/TROUBLESHOOTING.md`

## 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [JIFA.md](JIFA.md)
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- [DEVELOPMENT.md](DEVELOPMENT.md)
