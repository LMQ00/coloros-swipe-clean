# AGENTS

## Project

ColorOS/realme UI 保后台研究 + LSPosed 模块。目标：让用户自由控制「划卡（recents swipe）能否杀死 App」的白名单。

- 输入素材：`Athena_6.0.1.apk`（ColorOS 后台管理核心组件，逆向对象）
- 产物：Android LSPosed 模块（Kotlin），含 UI 可增删白名单；GitHub Actions 编译 APK
- 文档：`doc/` 存放 athena 逆向结论与开发文档

## 非协商规则

- **工作目录限定**：只读写当前目录与 `~/tmp`。NEVER 触碰其他路径，NEVER 修改系统分区文件（模块以运行时 Hook 生效为主）。
- **不修改 athena 原始文件**：`Athena_6.0.1.apk` 只读；反编译产物放 `~/tmp` 或 `doc/`，原始 dex/资源不入 git。
- **逆向结论必须可溯源**：每个结论标注类名、方法签名、smali/jadx 出处（文件:行）。推断标 `[INFERENCE]`。
- **不假设系统行为**：ColorOS 版本差异大，Hook 点必须有实机日志（LSPosed logcat）佐证，不能只凭静态阅读下结论。
- **验证驱动**：所有 Hook 改动必须在真机 logcat 有命中日志；无日志的「应该生效」不算完成。
- **不擅自扩展**：不改未提及的功能，不顺手重构；发现副作用先报告。

## 技术栈

- 语言：Kotlin（模块）+ Gradle（Kotlin DSL）
- 框架：LSPosed（libxposed API，`de.robv.android.xposed` / `io.github.libxposed`）
- 最低适配：Android 12+ (ColorOS 12+)，模块 `minSdk 26`
- 构建：GitHub Actions（ubuntu-latest，JDK 17，`./gradlew assembleRelease`）
- 本地无 Gradle/Android SDK，本地不执行构建；构建交给 CI。

## 目录结构

```
AGENTS.md               本文件
Athena_6.0.1.apk        逆向素材（只读，不入文档结论以外用途）
app/                    LSPosed 模块工程
  src/main/java/...     模块与 Hook 代码
  src/main/res/         UI 布局、字符串
doc/                    逆向笔记与开发文档
.github/workflows/      CI 编译
```

## 逆向工作流

1. `jadx -d ~/tmp/athena Athena_6.0.1.apk` 反编译
2. 先在 `AndroidManifest.xml` / 资源里定位「白名单」相关字符串（`whitelist`、`保护`、`keepAlive`、`kill`、`swipe`）
3. 用 `grep` 追踪字符串到类/方法，确认判定逻辑是否单点、是否可 Hook
4. 结论写入 `doc/athena-reverse.md`，附类名/方法签名/行号

## 提交

只提供提交文案，用户手动 `git commit`。用户要求代提交时：

```
git commit --author="pi <pi@local>" --no-gpg-sign -m "<类型>: <文案>"
```

类型：`feat` / `fix` / `chore` / `docs` / `refactor`

## 沟通

中文交流，术语保留英文。结论前置，事实优先，不客套。不确定推断标 `[INFERENCE]`。
