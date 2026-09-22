# AGENTS

## Project

ColorOS / realme UI 的「划卡（recents swipe）杀不杀」白名单 LSPosed 模块（Kotlin）。
让用户按应用选择「划卡保留进程」或「划卡强制结束进程」，覆盖系统内置白名单。
支持**应用分身（多开）单独设置**：本体与各分身各自独立。

- 逆向素材：`Athena_6.0.1_new.apk`（md5 `60944a5ffc5afb6d393a5ea52d77d90c`，
  与设备 `/system_ext/app/Athena/Athena.apk` 完全一致，`com.oplus.athena.*` 未混淆）
  - ⚠️ `Athena_6.0.1.apk` 是**另一个构建**（混淆版，md5 `01599ff8…`），结论不可混用，勿作素材
- 产物：Android LSPosed 模块（Kotlin），含 UI 可增删白名单；GitHub Actions 编译 APK

### 文档索引

| 文档 | 何时读 |
| --- | --- |
| [`docs/athena-reverse.md`](docs/athena-reverse.md) | 改 Hook 点、返回值语义、判定链之前必读 |
| [`docs/architecture.md`](docs/architecture.md) | 改模块结构、配置通道、分身支持之前必读 |
| [`docs/development.md`](docs/development.md) | 构建、安装、设备侧调试与验证时 |

## 当前状态

已完成并实机验证（2026-09-22）：

- 「划卡不杀」：`com.omarea.vtools` 划卡后卡片消失、进程保留
- 「划卡必杀」：`com.xunmeng.pinduoduo` 划卡瞬间全部进程结束，100 秒内无自启
- 4 个 Hook 点全部命中
- **分身**：拼多多两个分身（user 998 / 999）划卡时进程被精确结束，
  `am_kill` 的 userId 分别标记 998 / 999，未互相误伤；本体同样精确

待实施（方案已定，代码未改）：

- 配置通道从 LSPosed remote prefs 改为 App ContentProvider + 广播
- 名单加入 userId 维度，支持分身单独设置
- 两项**合并实施**（都要改 `Config.kt` / `ConfigBridge.kt` / UI / 通道格式）

未覆盖：系统应用分支（`I0()`）、最近任务锁定的卡片、
**同一 userId 下**同包多任务（athena `J0` 跳过）。

## Non-negotiables

| 规则 | enforcement |
| --- | --- |
| 必杀名单的杀进程路径**不改**：走 athena `utils.p.b` force-stop（Hook 4）。已实测有效；换成模块侧直调 `forceStopPackageAsUser` 属未验证路径，收益仅鲁棒性。 | 代码 |
| 配置通道**唯一**：App ContentProvider（`call("get")`）+ 配置广播。不得再引入 LSPosed `getRemotePreferences` / `XposedServiceHelper`。 | 代码 |
| 配置读取失败**必须**降级并打日志：沿用上次成功缓存，无缓存则空名单（全走系统默认）。禁止静默失败。 | 代码 |
| ContentProvider 与广播接收端**必须**校验来源 uid（`SYSTEM_UID` / 模块 App uid）。 | 代码 |
| 名单 key **必须**含 userId（`<pkg>#<userId>`），**不得硬编码 999** —— 分身 user 实测有 998、999 多个值。 | 代码 |
| Hook 侧日志**必须**带 userId（`athena swipe force kill: <pkg>#<userId>`），否则分身场景无法验证。 | 代码 |
| 分身 user 枚举**不得**假设数量或固定 id：按实际存在的 user 动态展开。 | 代码 |
| 旧名单迁移：`<pkg>` 展开为 `<pkg>#0` + 各实际存在的分身 userId（旧名单同时作用于本体与所有分身）。 | 代码 |
| **不介入** athena 的 `G0`/`J0`：实测 `J0` 已同时比较 `pkgName` 与 `userId`，同包名不同 user 互不影响。 | 代码 |
| 工作目录限定：只读写当前目录与 `~/tmp`。NEVER 修改系统分区文件。 | 提示词 |
| 不修改 athena 原始文件：两份 `Athena_*.apk` 只读；反编译产物放 `~/tmp`，原始 dex/资源不入 git。 | 提示词 |
| 逆向结论必须可溯源：标注类名、方法签名、jadx 出处（文件:行）。推断标 `[INFERENCE]`。 | 提示词 |
| 不假设系统行为：ColorOS 版本差异大，Hook 点必须有实机日志佐证，不能只凭静态阅读下结论。 | 提示词 |
| 验证驱动：所有 Hook 改动必须在真机日志有命中日志；无日志的「应该生效」不算完成。 | 提示词 |
| 不擅自扩展：不改未提及的功能，不顺手重构；发现副作用先报告。 | 提示词 |

## Language & Style

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin 2.2.21 |
| 构建 | AGP 8.10.1 / Gradle 8.13（wrapper）/ JDK 17 |
| SDK | `compileSdk 36`、`targetSdk 35`、`minSdk 26` |
| 框架 | libxposed 现代 API（`compileOnly io.github.libxposed:api:101.0.1`）；旧配置通道依赖的 `io.github.libxposed:service` **待移除** |
| UI | Material 3（`com.google.android.material:material:1.12.0`）+ DynamicColors |
| 签名 | 密钥**不入库**，存于 GitHub Secrets；本地/无密钥时自动退回 debug 签名 |
| CI | `.github/workflows/build.yml`，产物 artifact 名 `swipe-clean-release` |

`compileSdk 36` 是硬要求：libxposed 的 `service` / `interface` 制品声明 `minCompileSdk=36`。
Kotlin 必须 ≥ 2.2，否则在该 classpath 下会触发 FIR 内部崩溃（`FirIncompatibleClassTypeChecker`）。

## Operational Notes

- **本地不执行构建**：本机（Termux/aarch64）没有 Android SDK 与 `aapt2`，构建交给 CI。
- **CI 触发**：push 到 `main`，但 `**.md` 与 `docs/**` 的纯文档改动**不触发编译**。
- **改 Hook 后必须重启**：`su -c 'setprop ctl.restart zygote'`（约 1 分钟）让新代码注入
  system_server；只改 UI 不需要重启。
- **重装模块 APK 后**：当前配置通道会失效（LSPosed 每 uid 每轮开机只下发一次 binder），
  表现为「UI 里改有反馈、实际行为不变」，需完整重启设备。改造为新通道后此限制消失。
- **分身测试样本**：拼多多 `com.xunmeng.pinduoduo`，user 998 / 999 各一个分身，
  uid 分别为 `99810367` / `99910367`（本体 `10367`）。
- 日志位置、配置生效验证方法、分身验证方法、取回 CI 产物与安装步骤，见 `docs/development.md`。

## Communication

中文交流，术语保留英文。结论前置，事实优先，不客套。不确定推断标 `[INFERENCE]`。

## 文档同步

改代码前先判断本次改动是否让 `docs/*.md` 或本文件对应小节过时；**过时文档比没有文档更坏**。
判断不了时，在回答末尾列出「可能已过时」清单。

## 提交

只提供提交文案，用户手动 `git commit`。用户要求代提交时：

```
git commit --author="pi <pi@local>" --no-gpg-sign -m "<类型>: <文案>"
```

类型：`feat` / `fix` / `chore` / `docs` / `refactor`