# Arthas Workbench 发布说明

## 发布前检查

发布版本前，建议至少完成以下步骤：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew spotlessApply
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildPlugin -x buildSearchableOptions -x jarSearchableOptions
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew verifyPlugin
```

## 发布清单

- 更新 `gradle.properties` 中的 `pluginVersion`
- 更新 `CHANGELOG.md`
- 检查 `README.md`、截图、图标和文档是否需要同步
- 确认 `jifa/` submodule 已初始化并指向预期提交；如有本地 Jifa 补丁，需先在子模块仓库完成提交
- 确认插件元数据、仓库地址、vendor 信息无误
- 使用 JDK 21 显式执行 `verifyPlugin`，避免本地默认 `JAVA_HOME` 干扰 `instrumentCode`
- 确认构建产物可以在本地 IDEA 中安装

## GitHub Actions 相关

仓库已包含默认的构建、发布和 UI 测试工作流模板。实际发布前，请确认以下 secret 已准备好：

- `PUBLISH_TOKEN`
- `CERTIFICATE_CHAIN`
- `PRIVATE_KEY`
- `PRIVATE_KEY_PASSWORD`

如果暂时不发布到 JetBrains Marketplace，也可以只保留本地打包和 GitHub Release 产物分发。

## 发布后建议

- 在 Release Notes 中注明本次变更重点
- 如果有 UI 或交互变化，附上截图或录屏
- 如本次修复了常见问题，同步更新 `docs/TROUBLESHOOTING.md`
