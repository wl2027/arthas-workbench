# Arthas Workbench 发布说明

## 发布前检查

发布版本前，建议至少完成以下步骤：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew spotlessApply
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPlugin
```

## 发布清单

- 更新 `gradle.properties` 中的 `pluginVersion`
- 更新 `CHANGELOG.md`
- 检查 `README.md`、截图、图标和文档是否需要同步
- 确认插件元数据、仓库地址、vendor 信息无误
- 使用 JDK 21 显式执行 `verifyPlugin`，避免本地默认 `JAVA_HOME` 干扰 `instrumentCode`
- 确认构建产物可以在本地 IDEA 中安装

## 插件发布产物

插件包：

- `build/distributions/arthas-workbench-<version>.zip`

这个 zip 可以直接在 IDEA 中通过 `Install Plugin from Disk...` 安装。

## Jifa helper 发布产物

Jifa helper 不再内置到插件 zip 中，而是单独作为 Release 资产发布，供插件运行时下载。

构建步骤：

1. 初始化子模块：

```bash
git submodule update --init --recursive
```

2. 构建 helper：

```bash
cd jifa
./gradlew :server:bootJar --no-daemon
```

3. 产物路径：

`jifa/server/build/libs/jifa.jar`

4. 上传到主仓 release 时重命名为：

`arthas-jifa-server-helper.jar`

5. 与插件包一起上传到：

[wl2027/arthas-workbench Releases](https://github.com/wl2027/arthas-workbench/releases)

插件默认下载地址固定为：

`https://github.com/wl2027/arthas-workbench/releases/latest/download/arthas-jifa-server-helper.jar`

因此 latest release 中必须存在这个同名资产。

## 发布后建议

- 在 Release Notes 中注明本次变更重点
- 如果有 UI 或交互变化，附上截图或录屏
- 如本次修复了常见问题，同步更新 `docs/TROUBLESHOOTING.md`
