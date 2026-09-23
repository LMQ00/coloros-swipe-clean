# 关键决策与被否决的方案

**本文回答——关键决策与被否决的方案，以及为什么。**

每条格式：**决策** / **理由** / **被否决的替代方案** / **证据来源**。
想推翻其中任何一条之前，先读对应条目的「被否决的替代方案」。

## 1. 必杀走 athena 自己的 force-stop（`utils.p.b`）

- **决策**：必杀名单命中时，Hook 4（`clear.v#D0`）跳过原逻辑，直接反射调用
  `com.oplus.athena.systemservice.utils.p.b(Context, String pkg, int userId, int, int, String, String)`，
  放在后台 daemon 线程（避免在划卡流程里同步重入）。
- **理由**：只让 `getStopType` 返回 `2` 不够——`D0` 内还有两道闸门（`t0()` 的保护名单、
  `aVar.e()` / `O0()` 的最近任务锁，实测微信会被拦掉），且框架侧 `killProcessesForRemovedTask`
  对「有 started service / 有 receiver / 非后台态」的进程只 `setWaitingToKill` 而不立即杀。
  走 athena 自己的 force-stop 才与系统「清理」语义一致：
  `utils.p.b → p.c → j1.h.g → OplusAthenaAmManager#forceStopWithReason`（失败再退回 `forceStopPackageAsUser`）。
  跳过 `D0` 不影响卡片移除：任务 id 在 `G0` 里就已登记进 `f1446s`，由 `e1` 末尾的 `E0()` 统一移除。
- **被否决**：模块侧直调 `ActivityManager#forceStopPackageAsUser`——未验证路径，绕开 athena 的清理语义，
  与已实测有效的现状相比是净风险。
- **证据来源**：`hook/AthenaHooks.kt`（`FORCE_STOP_HELPER`、`forceStopAsync`）；
  实测拼多多 3 个进程全灭（含 `:titan`、`:sandboxed_process0`），`am_kill` reason
  `stop <pkg> due to o-stop(0)`，100 秒内无 `am_proc_start`。判定链见 `见 docs/architecture.md §判定链与 Hook 点`。

## 2. 配置通道唯一：App ContentProvider + 配置广播

- **决策**：Hook 只经 `contentResolver.call(content://io.github.lmq00.swipeclean.config, "get")` 读模块 App 的
  `ConfigProvider`，配合 `ACTION_CONFIG_CHANGED` 广播 + 2 秒 TTL 惰性刷新。
- **理由**：`call()` 只拉起本 App 的**进程**——不创建 Activity、不切前台、无通知，且不依赖
  「框架每 uid 每轮开机只下发一次 binder」，重装 APK 后不会静默失效。契约见 `见 docs/api.md §ConfigProvider 契约`。
- **被否决**：
  - **LSPosed remote prefs**（`XposedInterface#getRemotePreferences` + `XposedServiceHelper`）：
    读的是框架侧存储（LSPosed `modules_config.db` 的 `module_configs` 表），不是模块 App 自己的 prefs；
    写入必须走 libxposed 服务通道，而该通道每 uid 每轮开机只下发一次 binder。
    实锤（2026-09-22）：**重装 APK 后通道静默失效**——uid 不变，LSPosed 认为「已发过」不再下发，
    `ConfigStore.push()` 首行 `val target = remote ?: return` 静默返回，本地写成功、框架侧永远不变且无任何日志。
    另两个坑：App 未注册过该 group 时 Hook 读到空集（全部按「默认」放行）；group 注册绑定 uid 集合，需重启设备才恢复。
  - **已移除的 `io.github.libxposed:service` 旧通道**：代码侧已全删（`XposedServiceHelper` / `XposedProvider` /
    `getRemotePreferences` / `ConfigStore` 的 `remote`·`attach`·`detach`·`push`），manifest 里的
    `XposedProvider`（authorities `${applicationId}.XposedService`）随依赖一并消失。
  - **Hook 直接读 App 的 SharedPreferences 文件**：system_server（uid 1000）读不了 `/data/data/<pkg>/`
    （父目录 0700，无 `CAP_DAC_OVERRIDE`）。
  - **经 LSPosed 数据库中转**：`/data/adb` 是 `0700 root:root`，uid 1000 无法穿越。
  - **App 写公共目录供 Hook 读**：`/data/local/tmp` 是 `0771 shell:shell`，普通 App 只能穿过、不能建文件；
    `/sdcard` 受 FUSE 与分区隔离限制。
- **证据来源**：`ConfigProvider.kt`、`hook/ConfigBridge.kt`、`AndroidManifest.xml`；
  配置通道历史（已被取代）见 `见 docs/references.md §6. 本模块的挂点与配置通道`。

## 3. 模块自身 App 恒「不杀」

- **决策**：`ConfigBridge.modeOf` 在一切刷新逻辑之前，对 `pkg == Config.MODULE_PACKAGE` 直接返回 `MODE_KEEP`。
- **理由**：模块 App 是配置通道的一端，被划卡或 athena 的后台内存清理杀掉只会带来无谓的冷启动
  （开机窗口内还可能被 ROM 拦住，见第 4 条）。UI 里不列出本模块（`AppRepository.load` 跳过 `context.packageName`），
  用户无从冲突。
- **被否决**：把本模块当普通应用交给名单控制——配置源被清掉后，后续拉取只能靠重新冷启动，得不偿失。
- **证据来源**：`hook/ConfigBridge.kt#modeOf`；实测划两次模块卡片均 `swipe-up keep: io.github.lmq00.swipeclean#0`，
  pid 8735 存活、未重启。

## 4. 开机先读 Hook 侧缓存，而不是等闸门放行

- **决策**：`ConfigBridge.install()` 第一步 `restoreFromCache`——纯文件 I/O 读
  `/data/system/swipeclean_config.json`，不依赖 AMS、不依赖 App 能否被拉起；拉取退化为对账。
- **理由**：ColorOS 在开机窗口内会拦第三方 App 启动，闸门是
  `OplusAppStartupManager#isPreventBootStartData()`（`OplusAppStartupManager.java:4082`，默认 `preventDuration = 30s`，
  名单 `BOOT_PREVENT_START_APPLIST`），**不在第 5 个 Hook 的覆盖范围**。实测完整重启后启动期拉取全部失败，
  直到用户打开一次 App 才成功——**不是「等固定时长就放行」**（本会话曾误判为「约 5 分钟」，实为用户恰好那时打开了 App）。
  没有缓存就会出现「重启后到用户打开 App 之前名单恒为空」的失效模式。
- **被否决**：
  - **等闸门放行 / 等固定时长**：没有可依赖的时长保证（实测反例见上）。
  - **模块 App 开机自动启动**：ColorOS 开机闸门的实际调用点尚未定位 `[待确认]`。
  - **Hook 覆盖开机闸门**：闸门不在 `shouldPreventStartProvider` 路径上，第 5 个 Hook 只放行 provider 场景冷启动。
- **证据来源**：`hook/ConfigBridge.kt`（`CACHE_FILE`、`restoreFromCache`）；实测完整重启后 **1 秒**
  （`12:28:02.754`）出现 `config restored from cache: keep=[…] kill=[…]`，早于任何拉取。

## 5. 名单 key 必须带 userId

- **决策**：key 一律为 `<pkg>#<userId>`（`Config.key(pkg, userId)`）；分身 user 按实际存在的 user 动态展开。
- **理由**：应用分身是独立 user，**包名与本体完全相同**，只有 uid 不同
  （实测 `10367` / `99810367` / `99910367`，`99810367 = 10367 + 998 × 100000`），不带 userId 无法区分本体与分身。
- **被否决**：
  - **纯包名 key**：无法区分本体与分身，旧名单正是这样（升级行为：作用于本体 + 所有分身，见 `见 docs/data-model.md §旧名单迁移规则`）。
  - **硬编码 999**：`MultiAppConstants.java:44` 的 `USER_ID_MULTI_APP = 999` 只是**首个**分身的 id；
    实测同时存在 998 与 999，**userId 不固定**。`/data/oplus/os/multiapp/sys_created_multi_app_config.xml`
    恒为 `<config version="2" />`，不记录已创建的分身，也不是可用的枚举来源。
- **证据来源**：`Config.kt#key`；`pm list packages -U --user 0/998/999` 的 uid 输出；
  `OplusMultiApp.apk` 全库 grep 无任何包名改写逻辑。枚举方式见 `见 docs/data-model.md §分身 user 枚举方式`。

## 6. 不介入 athena 的 `G0` / `J0`

- **决策**：模块不 Hook `clear.v` 的 `G0` / `J0`。
- **理由**：`J0` 已同时比较 `pkgName` 与 `userId`，`Z0` 同样带 uid——**同包名的不同 user 互不影响**，
  不需要为了分身去改 `G0`/`J0`（判定链出处与行号见 `见 docs/references.md §7.3`）。
  实测 998 与 999 同时有任务时，划掉 998 的卡只杀 998，999 完好。
- **被否决**：为分身场景改写 `G0`/`J0`——属系统既有行为，改动无收益且引入未验证路径。
- **证据来源**：`hook/AthenaHooks.kt` 类注释（含 `v.java:134-190` 调用链）；分身实测见
  `见 docs/architecture.md §应用分身（多开）`。

## 7. 编译期只用公开 API

- **决策**：跨进程与框架交互的取值只用公开 API：`Bundle#putStringArray/getStringArray`、
  `uid / PER_USER_RANGE`（`100_000`）换算 userId、`SYSTEM_UID` 写死字面量 `1000`。
- **理由**：CI 的 `android-36/android.jar` 里**没有**这些系统 API，`javap` 实测确认——已被 CI 打回三次。
- **被否决**：
  - `Bundle#putStringSet/getStringSet` → 用 `putStringArray/getStringArray`。
  - `UserHandle#of/getUserId/myUserId/getIdentifier` → 用 `uid / PER_USER_RANGE`；需要 `UserHandle` 实例时
    只能用 `LauncherApps#getProfiles()` 给的。
  - `Process#SYSTEM_UID` → 写死字面量 `1000`。
  - 另两个类型陷阱：`LauncherUserInfo#getUserSerialNumber()` 返回 `Int` 不是 Long（比较前 `?.toLong()`）；
    `runCatching{…}.getOrDefault(-1L)` 会被推成 `Nothing`（用显式类型或 `getOrNull()`）。
  - 已验证公开可用，不受此限：`LauncherActivityInfo#getBadgedIcon(int)`、
    `LauncherApps#getProfiles/getActivityList/getLauncherUserInfo`、`UserManager#getUserProfiles`、
    `SharedPreferences#getStringSet`。
- **证据来源**：`ConfigProvider.kt`（`SYSTEM_UID` 注释）、`AppRepository.kt`（`PER_USER_RANGE` 注释）；
  完整清单与症状见 `见 docs/runbook.md §8. 编译期被 CI 打回`。

## 8. 本地不构建，交给 CI

- **决策**：本机不执行任何构建/测试，构建与产物一律走 GitHub Actions（`.github/workflows/build.yml`）。
- **理由**：本机是 Termux/aarch64，没有 Android SDK、没有 `aapt2`，无法产出 APK；CI 侧 `compileSdk 36` 是硬要求。
- **被否决**：在 Termux 里装 SDK 自建——不可行（无 `aapt2`），且会与 CI 产物不一致。
- **证据来源**：`.github/workflows/build.yml`；CI run `35820482374`（HEAD `5562ef9`）`conclusion=success`。
  构建与发版步骤见 `见 docs/development.md §构建`、`见 docs/development.md §发布 Release`；验证步骤见 `见 docs/testing.md §3 六步可复现验证`。