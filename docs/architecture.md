# 架构

## 组成

```
app/src/main/java/io/github/lmq00/swipeclean/
├── Config.kt              共享常量与配置模型（App 与 Hook 共用）
├── ConfigStore.kt         配置读写（本地落盘 + 发配置广播）
├── ConfigProvider.kt      配置读取入口（ContentProvider，Hook 跨进程 call("get")）
├── AppRepository.kt       应用列表加载（含分身）
├── AppListAdapter.kt      列表、展开与批量选择
├── MainActivity.kt        UI
├── SwipeCleanApp.kt       Application 入口
└── hook/
    ├── ModuleMain.kt      libxposed 入口（见 META-INF/xposed/java_init.list）
    ├── ConfigBridge.kt    Hook 侧配置读取与缓存
    ├── SwipeKillHooks.kt  框架侧 Hook（路径 A）
    ├── AthenaHooks.kt     athena 侧 Hook（路径 B）
    └── AppStartupHooks.kt 放行本模块自身 provider 冷启动（配置通道的前提）
```

模块以 `com.oplus.athena` 的 `systemservice`（`android:process="system"`）与 system_server
同进程运行，因此 **LSPosed 作用域必须选进程名 `system`**（界面里显示为「Android 系统」，
包名 `android` 不会注入）。

## 判定链与 Hook 点

划卡后进程是否被杀由两条独立路径决定，任一放行都不够。完整逆向结论见
[`athena-reverse.md`](athena-reverse.md)，此处只列挂点。

| # | 类 | 方法 | 名单命中时 |
| --- | --- | --- | --- |
| 1 | `com.android.server.wm.OplusAthenaManager` | `getRemoveTaskFilterType(WindowProcessController)` | 返回 `1`（不杀）/ `3`（强杀） |
| 2 | `com.android.server.wm.ActivityTaskSupervisorExtImpl` | 同上 | 同上 |
| 3 | `com.oplus.athena.common.parser.athena.FilterHelper` | `getStopTypeInner(ProcDetailInfo, r0.a)` | 返回 `0`（保留）/ `2`（强杀） |
| 4 | `com.oplus.athena.systemservice.action.prockill.clear.v` | `D0(t, h, r0.a, ClearRecord, ProcDetailInfo)` | 不杀：跳过；必杀：调用 athena 自己的 force-stop 后跳过 |

四个点缺一不可：路径 A 只负责移除任务，真正 force-stop 的是 athena 自己的清理动作；
Hook 1/2 返回 `3`、Hook 3 返回 `2` 之后，带常驻服务的应用仍会被 `D0` 内的闸门
（`t0()` 保护名单、`aVar.e()` / `O0()` 最近任务锁）与框架侧
`killProcessesForRemovedTask` 的 `setWaitingToKill` 拦下。

Hook 4 直接调用 athena 的 force-stop：

```java
com.oplus.athena.systemservice.utils.p.b(ctx, pkg, userId, reason, type, a, b)
  -> p.c(...) -> j1.h.g(pkg, userId, 13, type + 2000, ...)
  -> OplusAthenaAmManager#forceStopWithReason（失败退回 forceStopPackageAsUser）
```

放在后台线程调用，避免在划卡流程里同步重入。跳过 `D0` 不影响卡片移除：
任务 id 在 `G0` 里就已登记进 `f1446s`，由 `e1` 末尾的 `E0()` 统一移除。

### 第 5 个 Hook：放行自身 provider 冷启动（配置通道的前提）

`com.android.server.am.OplusAppStartupManager#shouldPreventStartProvider(ProcessRecord, ContentProviderRecord, ApplicationInfo, String, int)`
（`oplus-services.jar`，`@Override // IOplusAppStartupManager`）。

配置通道靠 Hook 主动 `contentResolver.call()` 拉取，而 ColorOS 的启动管控会拦掉第三方 App 的
provider 场景冷启动——**即使调用方是 system_server**：

```
W/OplusAppStartupManager: prevent start io.github.lmq00.swipeclean,
  cmp ComponentInfo{…/ConfigProvider} by contentprovider android callingUid 1000, scenePriority = 0
E/ActivityThread: Failed to find provider info for io.github.lmq00.swipeclean.config
```

判定链（`OplusAppStartupManager.java`，jadx 自 `/system/framework/oplus-services.jar`）：

```
AMS → shouldPreventStartProvider(proc, providerRecord, appInfo, callingPackage, callingUid)  // 2062 ← 挂这里
       -> validStartupWithRestrict(providerRecord, null, 0, null, "provider")                 // 2068
       -> handleStartProvider(providerRecord, proc)                                           // 2070
            （callerApp.uid <= 10000 时直接放行）
       -> !isAllowStartFromProvider(proc, providerRecord, appInfo, …)                          // 2080
            - isRootOrShell(callingUid)                                                       // 2261（uid 1000 不算）
            - isDefaultAllowStart(appInfo) || isInLruProcessesLocked(appInfo.uid)             // 2325
            - inProtectWhiteList(pkg)                                                         // 2335
            - isAllowAssociateByList(64, …)                                                   // 2352
            - 全不满足 → 2380 打上面那条日志并 return false（= 不允许启动）
```

本 Hook 的行为：**先执行原方法，仅当原结果为 true（要拦）且目标包名就是本模块自己时返回 false**。

- 目标包名取自 `ApplicationInfo#packageName`（即厂商日志里的 calledPackageName），
  取不到回退 `ContentProviderRecord#getComponentName()`。
- 包名不匹配时一律 `proceed()`，其它应用的启动策略完全不受影响。
- ROM 将来自己放行时（例如用户手动把本 App 加进自启动白名单），本 Hook 自动退化为空操作。
- 实际覆盖厂商拦截时打 `allowed provider start for io.github.lmq00.swipeclean (vendor block overridden)`。

`OplusAppStartupManagerExtImpl` 只做配置场景转发（`notifyConfigExSceneUpdate`），没有 provider 闸门。

### 实测结论（2026-09-22）

- 必杀名单命中后，划卡瞬间该包全部进程结束（含 `:titan`、`:support`、`:sandboxed_process0`），
  `am_kill` reason 为 `stop <pkg> due to o-stop(0)`。
- 100 秒内无任何 `am_proc_start`，即 **force-stop 后不会被自动拉起**。
- 「拼多多无法被杀」的原始观察，根因是配置未生效（见下），不是 force-stop 不够强。

## 应用分身（多开）

### 机制（实机验证，2026-09-22）

- ColorOS 分身 = **独立 user**，类型 `MultiApp`，`parentId=0`，且**可以有多个**：

  ```
  UserInfo{998:MultiApp:4001010} serialNo=11 isPrimary=false parentId=0
  UserInfo{999:MultiApp:4001010} serialNo=10 isPrimary=false parentId=0
  ```

- **分身包名与本体完全相同，仅 uid 不同**：

  ```
  pm list packages -U --user 0    → package:com.xunmeng.pinduoduo uid:10367
  pm list packages -U --user 998  → package:com.xunmeng.pinduoduo uid:99810367
  pm list packages -U --user 999  → package:com.xunmeng.pinduoduo uid:99910367
  ```

  `99810367 = 10367 + 998 × 100000`，符合 Android 多用户 uid 编码。
  `OplusMultiApp.apk` 全库 grep 无任何包名改写逻辑。

- `MultiAppConstants.java:44` → `USER_ID_MULTI_APP = 999` 只是**首个**分身的 id，
  **userId 不固定** → 名单 key 不得硬编码 999。
- `/data/oplus/os/multiapp/sys_created_multi_app_config.xml` 恒为 `<config version="2" />`，
  **不记录已创建的分身**，不是可用的枚举来源。

### 判定链取 userId

| Hook | userId 来源 | 说明 |
| --- | --- | --- |
| 3 / 4 | `ProcDetailInfo.userId` | `public int` 字段，与 `uid`、`realUid` 并列 |
| 1 / 2 | `WindowProcessController.mUserId` | 实测字段（`WindowProcessController.java:122`）；取不到回退 `mInfo.uid / PER_USER_RANGE` |

`UserHandle` 的 `of()` / `getUserId()` / `myUserId()` / `getIdentifier()` **都不是公开 API**
（CI 侧 `android-36/android.jar` 用 `javap` 实测确认，对照 `SharedPreferences#getStringSet` 在），
因此 userId 一律用 `uid / 100000`（`PER_USER_RANGE`）换算，不引用 `UserHandle` 的访问器。

### `G0` 的闸门是 user 感知的

`J0`（`clear.v` / `v.java:245`）同时比较 `pkgName` 与 `userId`：

```java
if (procDetailInfo.pkgName.equals(intent.getComponent().getPackageName())
    && recentTaskInfo.userId == procDetailInfo.userId      // ← 同时比较 userId
    && !T0(procDetailInfo, recentTaskInfo)) {
    return true;                                            // 跳过整段
}
```

`Z0` 用 `t0.o.b(pkgName, uid)` 同样带 uid。**因此同包名的不同 user（本体 / 998 / 999）
互不影响，不需要为了分身去改 `G0`/`J0`。**

实测印证：998 与 999 同时有任务时，划掉 998 的卡只杀 998，999 完好。

### 现状行为（已实施）

名单元素为 `<pkg>#<userId>`，本体与各分身各自独立设置。实测（2026-09-23，配置为
本体=必杀、998/999=不杀）：

- 划本体卡 → `am_kill [0,…]` ×3（`com.xunmeng.pinduoduo` / `:titan` / `:sandboxed_process0`）
- 划 998 卡 → `athena swipe keep: com.xunmeng.pinduoduo#998`，`u998_a367` 三个进程存活
- 划 999 卡 → 同上，`u999_a367` 存活

### UI 枚举分身（已实现）

`AppRepository.loadDualApps()`：

1. `LauncherApps#getProfiles()` → 当前 user 所属 profile group 的全部 user
   （`UserManagerService.getProfileIds(自己, true)`，只在 `userId != callingUserId` 时校验权限）。
2. 对每个 profile 调 `LauncherApps#getActivityList(null, profile)` → 该 user 下带 launcher 入口的应用。
3. userId 由 `ApplicationInfo.uid / PER_USER_RANGE` 反推（`UserHandle` 的访问器非公开 API，见上）。
4. 跳过当前 user 自己（`Process.myUid() / PER_USER_RANGE`）。

实测模块日志：`dual users=[998, 999] packages={998=…, 999=…}`。

**局限**：只覆盖带 launcher 入口（`MAIN`/`LAUNCHER`）的应用；无入口的分身应用不会列出。

依据（设备实测 + 框架代码）：分身 user 与当前 user **同 profile group**（`parentId=0`），
`filterAppAccess` 对同 profile group 的跨 user 查询**不过滤**；
`LauncherAppsService#getLauncherActivities` → `canAccessProfile` → `isProfileAccessible`
对同 profileGroupId 的已启用 user 返回 true。Termux（`uid=10366 u0_a366`，**无 su**）实测：

```
cmd package list packages -U --user 998  → package:com.xunmeng.pinduoduo uid:99810367
```

uid 前缀 998 证明查询真的落在目标 user，不是回退到 0。

### 名单格式与迁移

- key 格式：`<pkg>#<userId>`，仍是 `StringSet`（只改元素编码，结构不变）。
- 迁移：读到不含 `#` 的旧元素 → 展开为 `<pkg>#0` + 各**实际存在**的分身 userId
  （旧名单同时作用于本体与所有分身）。
- Hook 侧日志必须带 userId（`athena swipe force kill: <pkg>#<userId>`），
  否则分身场景无法验证。

## 配置通道

### 实现：App ContentProvider + 广播

```
App 侧:
  配置变更（UI）→ ConfigStore.setModes() → 写本地 SharedPreferences
                                        → sendBroadcast(ACTION_CONFIG_CHANGED)
Hook 侧（system_server）:
  system_server 启动 → ConfigBridge.install()：取 systemContext、注册广播接收器、
                       contentResolver.call(content://<pkg>.config, "get") 拉一次 → 内存缓存
  收到广播        → 立即再拉一次（后台线程）
  判定路径（2 秒 TTL 过期）→ 后台线程再拉一次，本次判定仍用当前缓存
```

- **单一真相来源**：App 的 SharedPreferences（`/data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml`）。
  Hook 侧只是内存缓存，不产生副本分叉。
- **provider**：`ConfigProvider`，authority `io.github.lmq00.swipeclean.config`，`exported="true"`；
  内部校验 `Binder.getCallingUid() == 1000`（非 1000 记日志并返回 null）。
- **旧名单迁移**：`ConfigProvider.call` 首次被调用时若发现旧格式元素（不含 `#`），
  展开为 `<pkg>#0` + 该包实际存在的分身 userId 后落盘；UI 侧 `load()` 也会做一次。
- **降级**：拉取失败沿用上次成功缓存并打日志；从未成功过时判定路径做一次同步补拉
  （按 `loadedAt` 限频），**绝不拿空名单静默判定**。
- **广播接收器**：`RECEIVER_EXPORTED`（system_server 必须收得到）。任意应用都能触发一次
  重新拉取，但不构成提权——拉取目标固定为本模块 App 的 provider，且 provider 只接受 uid 1000，
  最坏结果是多一次拉取。因此不加签名权限。

### 实测踩到的两个坑（都已复现并修）

**1. AMS 未就绪**：`onSystemServerStarting` 早于 AMS 初始化，此时 `ActivityThread#mgr`
（IActivityManager）为 null，注册接收器与拉取 provider 都会 NPE：

```
config receiver register failed: NPE at ContextImpl.registerReceiverInternal
  → IActivityManager.registerReceiverWithFeature on null
config pull failed: NPE at ActivityThread.acquireProvider
  → IActivityManager.getContentProvider on null
```

实测时间线：本回调 22:51:03.958 失败，AMS 就绪约 22:51:04.3。
→ `ConfigBridge.install()` 改为 1 秒间隔、上限 60 次的重试，直到「接收器注册成功 + 首次拉取成功」，
成功打 `config bridge ready (attempt=N)`，失败打 `config bridge not ready …`（不静默）。

**2. ColorOS 拦截 provider 冷启动**：见上「第 5 个 Hook」。没有它，拉取只在 App 进程恰好活着时
才能成功，重启后到用户打开 App 之前名单恒为空——正是本次要消灭的失效模式。

### 被排除的方案

| 方案 | 为什么不行 |
| --- | --- |
| Hook 直接读 App 的 SharedPreferences 文件 | system_server（uid 1000）读不了 `/data/data/<pkg>/`（父目录 0700，无 `CAP_DAC_OVERRIDE`） |
| 经 LSPosed 数据库中转 | `/data/adb` 是 `0700 root:root`，uid 1000 无法穿越 |
| App 写公共目录供 Hook 读 | `/data/local/tmp` 是 `0771 shell:shell`，普通 App 只能穿过、不能建文件；`/sdcard` 受 FUSE 与分区隔离限制 |
| 保留旧 LSPosed remote prefs 通道 | 见 [`athena-reverse.md`](athena-reverse.md) §6：每 uid 每轮开机只下发一次 binder，重装 APK 后静默失效 |

### 实施结果（2026-09-23）

| 文件 | 最终形态 |
| --- | --- |
| `ConfigProvider.kt` | ContentProvider，authority `io.github.lmq00.swipeclean.config`，`call("get")` 返回 `Bundle`（keep/kill 以 **StringArray** 传递），校验 calling uid == 1000，首次调用时做旧名单迁移 |
| `Config.kt` | `PREFS` / `MODULE_PACKAGE` / `AUTHORITY` / `METHOD_GET` / `ACTION_CONFIG_CHANGED` + `key(pkg, userId)` / `isLegacy()`；`ConfigStore` 删除 `remote` / `attach` / `detach` / `push`，新增 `needsMigration()` / `migrate()` / `notifyChanged()` |
| `SwipeCleanApp.kt` | 只保留 `DynamicColors`（不再绑定 LSPosed 服务） |
| `AndroidManifest.xml` | 声明 `ConfigProvider`（`exported="true"`）；`service` 依赖移除后旧 `XposedService` provider 一并消失 |
| `build.gradle.kts` | 移除 `io.github.libxposed:service`；versionCode 3 / versionName 1.2 |
| `hook/ConfigBridge.kt` | `contentResolver.call()` 拉取 + 2 秒 TTL 惰性刷新 + 广播即时刷新 + 启动期重试；按 `<pkg>#<userId>` 匹配 |
| `hook/ModuleMain.kt` | `onSystemServerStarting` 中 `ConfigBridge.install()` 与 `AppStartupHooks.install()`；共享 `systemContext()` |
| `hook/SwipeKillHooks.kt` | userId 取自 `mUserId`（回退 `uid / PER_USER_RANGE`）；判定与日志带 userId |
| `hook/AthenaHooks.kt` | userId 用 `ProcDetailInfo.userId`；日志带 userId |
| `hook/AppStartupHooks.kt` | **新增**，见「第 5 个 Hook」 |
| `AppRepository.kt` | `AppEntry` 加 `userId` / `key`；新增 `loadDualApps()`；`load()` 为每个分身展开一行 |
| `AppListAdapter.kt` / `MainActivity.kt` | 分身子项缩进显示，**userId 放在行标题**（`拼多多 · #998`）；选择与写入一律按 `entry.key` |

> Bundle 用 `putStringArray` / `getStringArray` 而非 `putStringSet` / `getStringSet`：
> 后两者不是公开 API，CI 编译期不可见（`javap` 实测）。
> 行标题而非副标题放 userId：`item_app.xml` 的 `ellipsize=end` 会把副标题里的 `· #998` 截掉。

### 验收结果（2026-09-23，实机）

配置通道：

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 改配置后立即生效 | ✅ | UI 里把 `#998` 改成「默认」→ 1 秒内模块日志 `config loaded: keep=[…#999,…] kill=[…#0]` |
| 2 | 重装模块 APK 后仍生效 | ✅ | 重装 + 软重启后 `config bridge ready (attempt=13)`、`config loaded: …`，全程未打开 App |
| 3 | 重启后名单自动恢复 | ✅（软重启） | 同上；完整重启未单独复验（机制相同，配置源在 `/data` 持久区） |
| 4 | 拉取无感 | ✅ | 拉取后前台仍是用户应用，无 Activity、无通知；App 进程在后台被静默拉起 |

分身：

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 5 | 本体必杀 + 分身不杀 | ✅ | 划本体卡 → `am_kill [0,…]` ×3；`u998_a367` 三进程存活 |
| 6 | 两个分身各自独立 | ✅ | `athena swipe keep: …#998` / `…#999`，两者均无 `am_kill` |
| 7 | 日志带 userId | ✅ | `athena swipe force kill: com.xunmeng.pinduoduo#0`、`…keep: …#998` |
| 8 | UI 展开分身子项 | ✅ | 列表 3 行：`拼多多`（必杀）/ `拼多多 · #998`（不杀）/ `拼多多 · #999`（不杀），各自独立 |

旧名单迁移（`<pkg>` → `<pkg>#0` + 各实际分身）在软重启后的首次拉取即完成：

```
config loaded: keep=[com.termux#0, com.omarea.vtools#0, github.tornaco.android.thanos.pro#0]
               kill=[com.xunmeng.pinduoduo#999, com.xunmeng.pinduoduo#0, com.xunmeng.pinduoduo#998]
```

## 已知限制

- **系统应用不受名单控制**：`G0()` 把 `procDetailInfo.system == true` 的应用交给 `I0()` 分支，
  该分支不调用 `getStopType`。UI 默认不显示系统应用，与此一致。
- **最近任务里手动锁定过的卡片**由 `isRecentLockTask` 保护，本模块不覆盖。
- **同一 userId 下同包多任务**：`G0()` 中 `J0()` 在**同一 userId** 下该包还有其它任务时
  跳过整段（连 `D0` 都不进），此时划掉一张卡不会杀进程。`J0` 同时比较 `pkgName` 与
  `userId`（`v.java:245`），因此**本体与分身互不影响**。属系统既有行为，本模块不介入。
- 「划卡不杀」名单同时会让该应用不被 athena 的后台内存清理回收（两者共用同一判定入口）。
- **无 launcher 入口的分身应用不会出现在列表里**：分身枚举走
  `LauncherApps#getActivityList`，只覆盖带 `MAIN`/`LAUNCHER` 入口的应用。
- **配置拉取依赖第 5 个 Hook**：若 ColorOS 后续改类名/方法（`OplusAppStartupManager#shouldPreventStartProvider`），
  拉取会被厂商拦掉，表现为模块日志 `config bridge not ready after N attempts`，
  名单停在最后一次成功拉取的值（不会静默变成空名单）。