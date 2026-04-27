# Arthas Workbench 开发说明

## 环境要求

- JDK：`21`
- IDE：`IntelliJ IDEA Community 2025.1+`
- 构建工具：`Gradle 9.4.1`

## 常用命令

### 格式化

```bash
cd /path/to/arthas-workbench
./gradlew spotlessApply
./gradlew spotlessCheck
```

### 本地启动插件沙箱

```bash
cd /path/to/arthas-workbench
./gradlew runIde
```

### 测试

```bash
cd /path/to/arthas-workbench
./gradlew test
```

### 打包

```bash
cd /path/to/arthas-workbench
./gradlew buildPlugin
```

## Gradle 生命周期约定

- `runIde` 与所有 `runIde*` 任务：
  先执行 `spotlessApply`
- `build`、`test`、`buildPlugin`、`publishPlugin`：
  先执行 `spotlessCheck`

## 目录结构

### 主代码

- `src/main/java/com/alibaba/arthas/idea/workbench/model`
- `src/main/java/com/alibaba/arthas/idea/workbench/service`
- `src/main/java/com/alibaba/arthas/idea/workbench/service/attach`
- `src/main/java/com/alibaba/arthas/idea/workbench/settings`
- `src/main/java/com/alibaba/arthas/idea/workbench/ui`
- `src/main/java/com/alibaba/arthas/idea/workbench/util`

### 资源

- `src/main/resources/META-INF`
- `src/main/resources/messages`
- `src/main/resources/icons`

### 测试

- `src/test/java/com/alibaba/arthas/idea/workbench/...`

## Jifa 集成开发

插件主构建已经不再依赖 Jifa submodule，也不会在 `buildPlugin` 或 `runIde` 时自动把 helper 打进插件包。

普通插件开发直接执行：

```bash
./gradlew test
./gradlew buildPlugin
```

如果你需要修改 helper 或更新默认下载产物，再额外初始化 Jifa 子模块：

```bash
git submodule update --init --recursive
cd jifa
./gradlew :server:bootJar --no-daemon
```

构建产物位于：

`jifa/server/build/libs/jifa.jar`

运行时如果这个文件存在，插件会优先使用它；否则会按正常逻辑自动下载 helper。

## 产物与缓存

### 插件产物

默认输出：

`build/distributions/*.zip`

### Arthas 包缓存

默认缓存目录：

`~/.arthas-workbench-plugin/packages`

### Jifa 缓存

- 浏览器版 Jifa Web：`~/.arthas-workbench-plugin/jifa`

其中：

- `storage`：分析数据
- `meta`：导入索引和服务状态
- `logs`：helper 日志
- `runtime`：自动下载的 helper jar

## 推荐提交流程

```bash
cd /path/to/arthas-workbench
./gradlew spotlessApply
./gradlew test
./gradlew buildPlugin
```

如果这 3 步都通过，再准备提交 PR。
