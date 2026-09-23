# 架构

本文回答：这个模块由哪些部分组成、Hook 挂在哪、数据怎么流。

类型：architecture。相关：Hook 点改动的逆向依据见 [`references.md`](references.md)；
配置通道的读写契约见 [`api.md`](api.md)；名单数据结构见 [`data-model.md`](data-model.md)；
必杀路径为什么这样选见 [`decisions.md`](decisions.md)。

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
包名 `android` 不会注入）。作用域写错的排查见 `见 docs/runbook.md §1`。

### 装载顺序

`ModuleMain#onSystemServerStarting` 内依次安装（源码顺序即依赖顺序）：

| # | 调用 | 为什么在这个位置 |
| --- | --- | --- |
| 1 | `SwipeKillHooks.install(this, param.classLoader)` | 只依赖框架类，先装；判定路径会调 `ConfigBridge.modeOf` |
| 2 | `AppStartupHooks.install(this, param.classLoader)` | 必须在配置拉取之前就位，否则 ColorOS 会拦掉 provider 冷启动 |
| 3 | `AthenaHooks.install(this, param.classLoader)` + `AthenaHooks.installWhenReady(this)` | athena 的类由它自己的 APK 提供，此刻未必可见，故配兜底重试 |
| 4 | `ConfigBridge.install(this)` | 最后启动：注册广播接收器 + 首次拉取，失败按 1s×30 + 5s×60 重试 |

`onPackageLoaded("com.oplus.athena")` 是第 3 步的补挂点：athena 包在 system_server 内加载后，
用 `param.defaultClassLoader` 再 `AthenaHooks.install` 一次。

依赖方向单向：Hook 层（`SwipeKillHooks` / `AthenaHooks` / `AppStartupHooks`）→ `ConfigBridge`
→ `Config`（常量与 `key()`）；App 侧（`MainActivity` / `AppListAdapter` / `AppRepository` /
`ConfigStore` / `ConfigProvider`）与 Hook 层不共享运行时状态，只通过 ContentProvider + 广播
交换数据。`ModuleMain#systemContext()` 提供 system_server 的 `Context`，`ConfigBridge` 与
`AppStartupHooks` 共用。

## 判定链与 Hook 点

划卡后进程是否被杀由两条独立路径决定，任一放行都不够。完整逆向结论见
[`references.md`](references.md)，此处只列挂点（**本表是全仓库唯一出处**）。

| # | Hook 名 | 目标类#方法 | 作用 | 返回值语义（名单命中时） | 命中日志形状 |
| --- | --- | --- | --- | --- | --- |
| 1 | 框架侧判定实现 | `com.android.server.wm.OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)` | 真正的判定实现，覆盖直接调用者 | 返回 `1`（不杀）/ `3`（强杀） | `swipe-up keep: com.omarea.vtools#0`、`swipe-up force kill: com.xunmeng.pinduoduo#0` |
| 2 | 框架侧唯一调用点 | `com.android.server.wm.ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)` | 唯一调用点，保证拦到 | 同上 | 同上（两处共用 `swipe hooks installed: 2` 与 `filter query: <pkg>#<userId>`） |
| 3 | athena 保留判定 | `com.oplus.athena.common.parser.athena.FilterHelper#getStopTypeInner(ProcDetailInfo, r0.a)` | 覆盖内存清理、深度清理等其它调用方 | 返回 `0`（保留）/ `2`（强杀） | `athena keep: <pkg>#<userId>` / `athena force kill: <pkg>#<userId>`（装载 `athena hooks installed: 1`） |
| 4 | 划卡决策点 | `com.oplus.athena.systemservice.action.prockill.clear.v#D0(t, h, r0.a, ClearRecord, ProcDetailInfo)` | 不杀：跳过；必杀：调用 athena 自己的 force-stop 后跳过 | 不返回值，直接改写流程 | `athena swipe keep: com.xunmeng.pinduoduo#998`、`athena swipe force kill: <pkg>#<userId>`（装载 `athena swipe hooks installed: 1`） |
| 5 | 放行自身 provider 冷启动 | `com.android.server.am.OplusAppStartupManager#shouldPreventStartProvider(ProcessRecord, ContentProviderRecord, ApplicationInfo, String, int)` | 仅当目标包名 == 本模块且厂商原本要拦 → 返回 `false` | 先 `proceed()`，命中才改写为 `false` | `allowed provider start for io.github.lmq00.swipeclean (vendor block overridden)`（装载 `app startup hooks installed: 1`） |

未命中时一律 `chain.proceed()` 交回系统原逻辑。表中 `swipe-up keep: com.omarea.vtools#0`、
`swipe-up force kill: com.xunmeng.pinduoduo#0`、`athena swipe keep: com.xunmeng.pinduoduo#998`
为真机日志样本，其余以 `<pkg>#<userId>` 占位；`* hooks installed: N` 装载行取自完整重启后的实测输出。

框架侧返回值语义（`ActivityTaskSupervisor` 分支）：`0` = 杀但持有 foreground service 时保留、
`1` = 包级白名单不杀、`2` = 进程级白名单不杀、`3` = 强制杀（即使持有 foreground service）。
出处：`services.jar` → `com/android/server/wm/ActivityTaskSupervisor.java:1235-1253`。

Hook 3 挂在 `getStopTypeInner` 而非 `getStopType`，是因为两个重载都收敛到它，一处即可覆盖。

**四个点缺一不可**：路径 A 只负责移除任务，真正 force-stop 的是 athena 自己的清理动作；
Hook 1/2 返回 `3`、Hook 3 返回 `2` 之后，带常驻服务的应用仍会被 `D0` 内的闸门
（`t0()` 保护名单、`aVar.e()` / `O0()` 最近任务锁）与框架侧
`killProcessesForRemovedTask` 的 `setWaitingToKill` 拦下。

Hook 4 直接调用 athena 的 force-stop（为什么必须走这里见 `见 docs/decisions.md §1 必杀走 athena 自己的 force-stop`）：

```java
com.oplus.athena.systemservice.utils.p.b(ctx, pkg, userId, reason, type, a, b)
  -> p.c(...) -> j1.h.g(pkg, userId, 13, type + 2000, ...)
  -> OplusAthenaAmManager#forceStopWithReason（失败退回 forceStopPackageAsUser）
```

放在后台线程调用，避免在划卡流程里同步重入。跳过 `D0` 不影响卡片移除：
任务 id 在 `G0` 里就已登记进 `f1446s`，由 `e1` 末尾的 `E0()` 统一移除。

### 第 5 个 Hook：放行自身 provider 冷启动（配置通道的前提）

配置通道靠 Hook 主动 `contentResolver.call()` 拉取，而 ColorOS 的启动管控会拦掉第三方 App 的
provider 场景冷启动——**即使调用方是 system_server**（厂商日志与上游判定链见
`见 docs/references.md §8.2`）。

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

## 配置通道

### 数据流

```
App 侧:
  配置变更（UI）→ ConfigStore.setModes() → 写本地 SharedPreferences
                                        → sendBroadcast(ACTION_CONFIG_CHANGED)
Hook 侧（system_server, uid 1000）:
  启动 → 先读 /data/system/swipeclean_config.json（纯文件 I/O，不依赖 AMS/App）→ 内存缓存
       → 取 systemContext、注册广播接收器、contentResolver.call(content://<pkg>.config, "get")
         （带重试：1s × 30 + 5s × 60，约 5.5 分钟）
  收到广播        → 立即再拉一次（后台线程）
  判定路径（2 秒 TTL 过期）→ 后台线程再拉一次，本次判定仍用当前缓存
  拉取成功且内容变化 → 回写缓存文件
  本模块包名 → modeOf 直接返回 MODE_KEEP
```

- **Hook 侧缓存**（`/data/system/swipeclean_config.json`，system_server 可写）：
  最后一次成功拉取的结果。为什么需要：开机窗口内 ColorOS 会拦第三方 App 启动
  （`isPreventBootStartData` / `BOOT_PREVENT_START_APPLIST`），实测**完整重启后启动期 60 次
  拉取全部失败**，直到用户打开 App 才成功——期间名单为空、划卡走系统默认。有缓存后开机即可
  用上次名单，拉取退化为对账。闸门细节见 `见 docs/references.md §8`，取舍见
  `见 docs/decisions.md §4 开机先读 Hook 侧缓存`。
- **模块自身恒「不杀」**：`ConfigBridge.modeOf` 对本模块包名直接返回 `MODE_KEEP`，
  避免配置通道的一端被划卡/athena 内存清理杀掉（UI 里不列出本模块，无冲突）。
- **单一真相来源**：App 的 SharedPreferences（`/data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml`）。
  Hook 侧的内存与文件缓存都只是副本，每次拉取成功即被覆盖。
- **provider**：`ConfigProvider`，authority `io.github.lmq00.swipeclean.config`，`exported="true"`；
  内部校验 `Binder.getCallingUid() == 1000`（非 1000 记日志并返回 null）。
  Bundle 里传什么见 `见 docs/api.md §ConfigProvider 契约`，广播形态见 `见 docs/api.md §配置广播`。
- **旧名单迁移**：`ConfigProvider.call` 首次被调用时若发现旧格式元素，展开为 `<pkg>#0` +
  该包实际存在的分身 userId 后落盘；UI 侧 `load()` 也会做一次。编码细节见
  `见 docs/data-model.md §名单模型` 与 `见 docs/data-model.md §旧名单迁移规则`。
- **降级**：拉取失败沿用上次成功缓存并打日志；从未成功过时判定路径做一次同步补拉
  （按 `loadedAt` 限频），**绝不拿空名单静默判定**。
- **广播接收器**：`RECEIVER_EXPORTED`（system_server 必须收得到）。任意应用都能触发一次
  重新拉取，但不构成提权——拉取目标固定为本模块 App 的 provider，且 provider 只接受 uid 1000，
  最坏结果是多一次拉取。因此不加签名权限。

被排除的配置通道方案（直读 prefs、LSPosed 数据库中转、公共目录、旧 remote prefs 通道）
及其否决理由见 `见 docs/decisions.md §配置通道唯一`。

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
→ `ConfigBridge.install()` 改为带重试的注册+拉取：前 30 次 1 秒一次，之后 5 秒一次，共 90 次（约 5.5 分钟），直到「接收器注册成功 + 首次拉取成功」，
成功打 `config bridge ready (attempt=N)`，失败打 `config bridge not ready …`（不静默）。

**2. ColorOS 拦截 provider 冷启动**：见上「第 5 个 Hook」。没有它，拉取只在 App 进程恰好活着时
才能成功，重启后到用户打开 App 之前名单恒为空——正是本次要消灭的失效模式。

### 验收结果（2026-09-23，实机）

配置通道：

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 改配置后立即生效 | ✅ | UI 里把 `#998` 改成「默认」→ 1 秒内模块日志 `config loaded: keep=[…#999,…] kill=[…#0]` |
| 2 | 重装模块 APK 后仍生效 | ✅ | 重装 + 软重启后 `config bridge ready (attempt=13)`、`config loaded: …`，全程未打开 App |
| 3 | 重启后名单自动恢复 | ✅（软重启） | 同上；完整重启未单独复验（机制相同，配置源在 `/data` 持久区） |
| 4 | 拉取无感 | ✅ | 拉取后前台仍是用户应用，无 Activity、无通知；App 进程在后台被静默拉起 |

旧名单迁移（`<pkg>` → `<pkg>#0` + 各实际分身）在软重启后的首次拉取即完成：

```
config loaded: keep=[com.termux#0, com.omarea.vtools#0, github.tornaco.android.thanos.pro#0]
               kill=[com.xunmeng.pinduoduo#999, com.xunmeng.pinduoduo#0, com.xunmeng.pinduoduo#998]
```

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

各 Hook 从哪里取 userId、为什么不用 `UserHandle` 的访问器：`见 docs/references.md §7.2`。
架构侧只需知道一条：`J0` 同时比较 `pkgName` 与 `userId`，`Z0` 同样带 uid（判定链出处与行号见
`见 docs/references.md §7.3`），因此**同包名的不同 user
（本体 / 998 / 999）互不影响，不需要为了分身去改 `G0`/`J0`**。

实测印证：998 与 999 同时有任务时，划掉 998 的卡只杀 998，999 完好。

### UI 呈现（已实现）

- **图标**：分身行用 `LauncherActivityInfo#getBadgedIcon(density)`（公开 API，API 21+），
  ColorOS 在这里画的就是 launcher 里那个分身序号角标。本体行无角标。
  缓存键为 `<pkg>#<userId>`（`IconCache`），分身与本体图标不同。
- **序号**：`LauncherApps#getLauncherUserInfo(user).userSerialNumber` 按 serial 升序排名
  （API 35+，只做 `canAccessProfile` 校验）。实测 `ordinals={999=1, 998=2}`，与角标数字一致。
  取不到时退回显示真实 userId。
- **标题**：`AppEntry#title(context)` → `拼多多 · 分身2`；列表行与设置面板标题共用，两处一致。
- **缩进**：分身行左侧内缩 32dp，本体的 padding 在 `Holder` 构造时捕获后按 user 叠加。
- 分身标识放**行标题**而不是副标题：`item_app.xml` 的 `ellipsize=end` 会把副标题里的标识截掉。

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
3. userId 由 `ApplicationInfo.uid / PER_USER_RANGE` 反推（`UserHandle` 的访问器非公开 API，
   见 `见 docs/references.md §7.2`）。
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

名单元素的编码与旧名单迁移规则见 `见 docs/data-model.md §名单模型`、
`见 docs/data-model.md §旧名单迁移规则`。

## 关键文件与职责

| 路径 | 职责 |
| --- | --- |
| `.../Config.kt` | 常量（`PREFS`/`MODULE_PACKAGE`/`AUTHORITY`/`METHOD_GET`/`ACTION_CONFIG_CHANGED`/`KEY_KEEP`/`KEY_KILL`/`MODE_*`）、`key(pkg, userId)`、`isLegacy()` |
| `.../ConfigStore.kt` | UI 侧读写 prefs、`setModes` 批量落盘 + 发广播、`needsMigration()` / `migrate()` / `notifyChanged()`（旧名单展开）；已删除 `remote`/`attach`/`detach`/`push` |
| `.../ConfigProvider.kt` | Hook 读配置的入口：`call("get")` 返回 `Bundle`，校验 `Binder.getCallingUid() == 1000`，首次调用做旧名单迁移 |
| `.../AppRepository.kt` | `AppEntry`（含 `userId`/`key`/`title()`）、`DualApps`（`usersOf`）、`AppRepository.loadDualApps/iconOf/load`、`IconCache` |
| `.../AppListAdapter.kt` | 列表行渲染：标题 `entry.title(context)`、缩进 32dp、勾选框（批量模式）、图标 tag 去重 |
| `.../MainActivity.kt` | 搜索 / 系统应用开关 / 单条设置面板 / 长按批量选择（`applyBatch`）/ 返回键先退出选择 |
| `.../SwipeCleanApp.kt` | 仅 `DynamicColors`（不再绑定 LSPosed 服务） |
| `.../hook/ModuleMain.kt` | libxposed 入口：`onSystemServerStarting` 按上表顺序装载；`onPackageLoaded` 等 athena 包加载后补挂；`systemContext()` |
| `.../hook/ConfigBridge.kt` | 三条读取路径（开机读缓存 / 广播 / 2s TTL 惰性刷新）、启动期重试、`modeOf` 判定、`persist` 回写缓存 |
| `.../hook/SwipeKillHooks.kt` | 框架侧 2 处 Hook，反射取 `WindowProcessController.mInfo/mName/mUserId` |
| `.../hook/AthenaHooks.kt` | athena 侧 2 处 Hook，`FilterHelper` 取 `ProcDetailInfo.userId`；必杀调 athena force-stop |
| `.../hook/AppStartupHooks.kt` | 放行本模块 provider 冷启动（先 `proceed`，仅当厂商要拦且包名匹配才改写为 `false`） |
| `app/src/main/AndroidManifest.xml` | `QUERY_ALL_PACKAGES`、`MainActivity`、`ConfigProvider`（`authorities` 必须与 `Config.AUTHORITY` 逐字一致）；旧 `XposedService` provider 随依赖移除而消失 |
| `app/src/main/resources/META-INF/xposed/{module.prop,scope.list,java_init.list}` | libxposed 元数据；`scope.list` = `system`（进程名），`staticScope=false` |
| `app/src/main/res/values/strings.xml` | 全部文案，含 `dual_suffix` / `dual_ordinal` |
| `app/build.gradle.kts` | SDK 级别、版本号、签名（无密钥退回 debug）；依赖与版本见 `见 docs/tech-stack.md` |
| `.github/workflows/build.yml` | CI：push `main` 触发（`**.md`、`docs/**` 不触发）、`workflow_dispatch` 可手动跑、解码 Secrets 签名、上传 artifact `swipe-clean-release` |

## 常见改动 → 改哪里

| 想改什么 | 改哪个文件 / 符号 |
| --- | --- |
| 新增/调整划卡判定点 | `hook/SwipeKillHooks.kt`（框架侧 `TARGET_CLASSES`）、`hook/AthenaHooks.kt`（athena 侧）；两处都在 `hook/ModuleMain.kt` 注册 |
| 改名单元素格式或模式语义 | `Config.kt` 的 `key()` / `isLegacy()` / `MODE_*`；同步 `hook/ConfigBridge.kt#modeOf` 与 `ConfigStore`（编码规则见 `见 docs/data-model.md §名单模型`） |
| 改配置通道（读写协议） | `ConfigProvider.kt`（读端）+ `ConfigBridge.kt`（Hook 端）+ `AndroidManifest.xml` 的 provider 声明 + `Config.AUTHORITY`（契约见 `见 docs/api.md §ConfigProvider 契约`） |
| 改 UI 列表行（标题/副标题/缩进/图标） | `AppListAdapter.kt#Holder.bind`、`res/layout/item_app.xml`、`AppEntry.title()` |
| 改分身枚举 / 序号 / 图标 | `AppRepository.kt#loadDualApps`（`profiles`、`ordinals` 按 `userSerialNumber` 排名）、`iconOf` |
| 改批量操作 | `MainActivity.kt#applyBatch`、`#toggleSelectAll`、`ConfigStore.setModes` |
| 加/改文案 | `res/values/strings.xml` |
| 改版本号 | `app/build.gradle.kts`（`versionCode` 递增、`versionName` `X.Y`）；发版流程见 `见 docs/development.md §发布 Release` |
| 改作用域 / 模块元数据 | `META-INF/xposed/scope.list`、`module.prop`（作用域写错的症状见 `见 docs/runbook.md §1`） |
| 改 CI / 签名 | `.github/workflows/build.yml`、`app/build.gradle.kts` 的 `signingConfigs` |
| 改依赖 / SDK / Kotlin / AGP 版本 | `app/build.gradle.kts`、根 `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties`；先读 `见 docs/tech-stack.md` |

改 Hook 后必须重启 zygote 让新代码注入 system_server（只改 UI 不需要）：
`见 docs/development.md §设备侧维护`。

## 已知限制

- **系统应用不受名单控制**：`G0()` 把 `procDetailInfo.system == true` 的应用交给 `I0()` 分支，
  该分支不调用 `getStopType`。UI 默认不显示系统应用，与此一致。
- **最近任务里手动锁定过的卡片**由 `isRecentLockTask` 保护，本模块不覆盖。
- **同一 userId 下同包多任务**：`G0()` 中 `J0()` 在**同一 userId** 下该包还有其它任务时
  跳过整段（连 `D0` 都不进），此时划掉一张卡不会杀进程。`J0` 同时比较 `pkgName` 与
  `userId`（出处见 `见 docs/references.md §7.3`），因此**本体与分身互不影响**。属系统既有行为，本模块不介入。
- 「划卡不杀」名单同时会让该应用不被 athena 的后台内存清理回收（两者共用同一判定入口）。
- **无 launcher 入口的分身应用不会出现在列表里**：分身枚举走
  `LauncherApps#getActivityList`，只覆盖带 `MAIN`/`LAUNCHER` 入口的应用。
- **配置来源仍是 App**：`/data/system/swipeclean_config.json` 只是 Hook 侧副本。
  App 被卸载且缓存仍在时，模块会继续按最后一次名单执行（模块本身通常也会同时被卸载）。
- **配置拉取依赖第 5 个 Hook**：若 ColorOS 后续改类名/方法（`OplusAppStartupManager#shouldPreventStartProvider`），
  拉取会被厂商拦掉，表现为模块日志 `config bridge not ready after N attempts` 或
  `config pull failed: …`，此时按缓存里的最后一次名单执行（不会静默变成空名单）。
  排查步骤见 `见 docs/runbook.md §3`。