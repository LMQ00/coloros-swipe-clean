# AGENTS

## Project

ColorOS / realme UI 保后台研究 + LSPosed 模块。目标：让用户自由控制「划卡（recents swipe）能否杀死 App」的白名单。

- 逆向素材：`Athena_6.0.1_new.apk`（md5 `60944a5ffc5afb6d393a5ea52d77d90c`，
  与设备 `/system_ext/app/Athena/Athena.apk` 完全一致，`com.oplus.athena.*` 未混淆）
  - ⚠️ `Athena_6.0.1.apk` 是**另一个构建**（混淆版，md5 `01599ff8…`），结论不可混用，勿作素材
- 产物：Android LSPosed 模块（Kotlin），含 UI 可增删白名单；GitHub Actions 编译 APK
- 逆向结论：[`doc/athena-reverse.md`](doc/athena-reverse.md)（判定链、Hook 点、返回值语义、失效风险、实机验证记录）

## 当前状态

功能已实机验证通过（详见 `doc/athena-reverse.md` §8）：

- 「划卡不杀」：`com.omarea.vtools` 划卡后卡片消失、进程保留
- 「划卡必杀」：`com.tencent.mm` 划卡瞬间结束，3 秒后由系统重新拉起
- 配置通道：UI 改动实时推送到框架侧（`module_configs` 表）

未覆盖：系统应用分支（`I0()`）、最近任务锁定的卡片。见 `doc/athena-reverse.md` §7。

## 非协商规则

- **工作目录限定**：只读写当前目录与 `~/tmp`。NEVER 触碰其他路径，NEVER 修改系统分区文件（模块以运行时 Hook 生效为主）。
- **不修改 athena 原始文件**：两份 `Athena_*.apk` 只读；反编译产物放 `~/tmp`，原始 dex/资源不入 git。
- **逆向结论必须可溯源**：每个结论标注类名、方法签名、jadx 出处（文件:行）。推断标 `[INFERENCE]`。
- **不假设系统行为**：ColorOS 版本差异大，Hook 点必须有实机日志佐证，不能只凭静态阅读下结论。
- **验证驱动**：所有 Hook 改动必须在真机日志有命中日志；无日志的「应该生效」不算完成。
- **不擅自扩展**：不改未提及的功能，不顺手重构；发现副作用先报告。

## 技术栈与构建

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin 2.2.21 |
| 构建 | AGP 8.10.1 / Gradle 8.13（wrapper）/ JDK 17 |
| SDK | `compileSdk 36`、`targetSdk 35`、`minSdk 26` |
| 框架 | libxposed 现代 API（`compileOnly io.github.libxposed:api:101.0.1` + `implementation io.github.libxposed:service:101.0.0`） |
| UI | Material 3（`com.google.android.material:material:1.12.0`）+ DynamicColors |
| 签名 | `keystore/swipeclean.jks`（alias / storepass / keypass 均为 `swipeclean`），签名固定 → 新构建可直接覆盖安装 |
| CI | `.github/workflows/build.yml`，产物 artifact 名 `swipe-clean-release` |

`compileSdk 36` 是硬要求：libxposed 的 `service` / `interface` 制品声明 `minCompileSdk=36`。
Kotlin 必须 ≥ 2.2，否则在该 classpath 下会触发 FIR 内部崩溃（`FirIncompatibleClassTypeChecker`）。

**本地不执行构建**：本机（Termux/aarch64）没有 Android SDK 与 `aapt2`，构建交给 CI。

```bash
# 取回 CI 产物并安装
gh run list --limit 3
gh run download <run-id> -R LMQ00/coloros-swipe-clean -D ~/tmp/apk
su -c 'cp ~/tmp/apk/swipe-clean-release/app-release.apk /data/local/tmp/swipeclean.apk'
su -c 'pm install -r /data/local/tmp/swipeclean.apk'
```

## 目录结构

```
AGENTS.md                   本文件
README.md                   面向使用者的说明
Athena_6.0.1_new.apk        逆向素材（只读，对应设备实际版本）
Athena_6.0.1.apk            另一构建（混淆版，勿用）
app/src/main/java/...       UI（MainActivity / AppListAdapter / Config / AppRepository）
app/src/main/java/.../hook/ Hook 实现（ModuleMain / SwipeKillHooks / AthenaHooks / ConfigBridge）
app/src/main/res/           布局与字符串
app/src/main/resources/META-INF/xposed/   module.prop / java_init.list / scope.list
keystore/swipeclean.jks     固定签名
doc/athena-reverse.md       逆向结论（接手先读这个）
.github/workflows/build.yml CI
```

## 设备侧维护（关键）

设备：realme UI / ColorOS 16（Android 16），已 root（KernelSU），LSPosed `v2.1.1-it`。

- **作用域必须是进程名 `system`**，不是包名 `android`。两者在 LSPosed 界面里都显示成「Android 系统」，
  写错会表现为「模块已启用但完全没有日志」。
- `module.prop` 的 `staticScope=false`：作用域由用户在 LSPosed 里选，`scope.list` 只作推荐预勾选。
- **配置通道**：Hook 侧 `getRemotePreferences()` 读的是**框架侧**存储（LSPosed 数据库
  `modules_config.db` 的 `module_configs` 表），不是模块 App 自己的 SharedPreferences。
  写入必须经 libxposed service（`XposedProvider` → `XposedService`），且 **App 必须至少启动过一次**
  （框架只在模块 App 进程启动时下发 binder）。详见 `doc/athena-reverse.md` §6。
- **重装 APK 后**：LSPosed 配置库里记录的 `apk_path` 会失效（安装路径含随机目录）。
  本机未安装 LSPosed 管理器，需手动同步：

  ```bash
  su -c 'cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/'
  # 用 python sqlite3 打开（会自动合并 -wal），更新 modules.apk_path，再写回并删除 -wal/-shm
  ```

- **改 Hook 后必须重启**：`su -c 'setprop ctl.restart zygote'` 软重启（约 1 分钟）即可让新代码注入
  system_server；只改 UI 不需要重启。
- **日志位置**：
  - 模块内 `module.log(...)` → `/data/adb/lspd/log/modules_*.log`
  - App 内 `Log.*`（tag `SwipeClean`）→ `logcat`
  - `su -c 'grep -a SwipeClean /data/adb/lspd/log/modules_$(ls -t /data/adb/lspd/log | grep modules | head -1)'`

## Hook 点速查

| # | 类 | 方法 | 名单命中 |
| --- | --- | --- | --- |
| 1 | `com.android.server.wm.OplusAthenaManager` | `getRemoveTaskFilterType` | `1` / `3` |
| 2 | `com.android.server.wm.ActivityTaskSupervisorExtImpl` | 同上 | `1` / `3` |
| 3 | `com.oplus.athena.common.parser.athena.FilterHelper` | `getStopTypeInner` | `0` / `2` |
| 4 | `com.oplus.athena.systemservice.action.prockill.clear.v` | `D0` | 跳过 / 调用 athena 的 force-stop |

4 个点缺一不可，原因（框架与 athena 各自的闸门）见 `doc/athena-reverse.md` §5.1。

## 逆向工作流

1. `jadx -d ~/tmp/athena_new Athena_6.0.1_new.apk` 反编译 athena
2. 框架侧：`unzip -p /system/framework/services.jar classes*.dex`、`oplus-services.jar` 取 dex，
   `jadx --single-class <类名>` 按需反编译
3. 先定位入口字符串（`REQUEST_CLEAR_SPEC_APP`、`swipe`、`clear`），再顺调用链找到**单点判定**
4. 结论写入 `doc/athena-reverse.md`，附类名/方法签名/行号；实机验证结果也写进去

## 提交

只提供提交文案，用户手动 `git commit`。用户要求代提交时：

```
git commit --author="pi <pi@local>" --no-gpg-sign -m "<类型>: <文案>"
```

类型：`feat` / `fix` / `chore` / `docs` / `refactor`

## 沟通

中文交流，术语保留英文。结论前置，事实优先，不客套。不确定推断标 `[INFERENCE]`。