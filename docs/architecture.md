# 架构

## 组成

```
app/src/main/java/io/github/lmq00/swipeclean/
├── Config.kt              共享常量与配置模型（App 与 Hook 共用）
├── ConfigStore.kt         配置读写（本地 + 同步到 Hook 侧）
├── ConfigProvider.kt      配置读取入口（ContentProvider，待实施）
├── AppRepository.kt       应用列表加载（含分身）
├── AppListAdapter.kt      列表、展开与批量选择
├── MainActivity.kt        UI
├── SwipeCleanApp.kt       Application 入口
└── hook/
    ├── ModuleMain.kt      libxposed 入口（见 META-INF/xposed/java_init.list）
    ├── ConfigBridge.kt    Hook 侧配置读取与缓存
    ├── SwipeKillHooks.kt  框架侧 Hook（路径 A）
    └── AthenaHooks.kt     athena 侧 Hook（路径 B）
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
| 1 / 2 | `WindowProcessController.mInfo.uid` → `UserHandle.getUserId(uid)` | `ApplicationInfo.uid` 是 public 字段 |

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

### 现状行为（改造前）

名单是包级 `StringSet` → 一条记录同时命中本体与所有分身。实测划任一张卡，
`am_kill` 的 userId 与所划的 user 一致（998 / 999 / 0 均精确），但**设置无法区分**。

### UI 枚举分身

模块 App 是普通应用（无特权），但分身 user 与当前 user **同 profile group**（`parentId=0`），
Android 的 `filterAppAccess` 对同 profile group 的跨 user 查询**不过滤**。实测
（Termux，`uid=10366 u0_a366`，`untrusted_app_27`，**无 su**）：

```
cmd package list packages -U --user 998  → package:com.xunmeng.pinduoduo uid:99810367
```

uid 前缀 998 证明查询真的落在目标 user，不是回退到 0。

候选 API：`LauncherApps`（public API，首选）→ 回退反射
`PackageManager.getInstalledApplicationsAsUser`（`@SystemApi`，受 hidden API 限制影响）。
**需真机代码验证**（`cmd` 是 native 进程，与 app 环境的 hidden API 限制不同）。

### 名单格式与迁移

- key 格式：`<pkg>#<userId>`，仍是 `StringSet`（只改元素编码，结构不变）。
- 迁移：读到不含 `#` 的旧元素 → 展开为 `<pkg>#0` + 各**实际存在**的分身 userId
  （旧名单同时作用于本体与所有分身）。
- Hook 侧日志必须带 userId（`athena swipe force kill: <pkg>#<userId>`），
  否则分身场景无法验证。

## 配置通道

### 现状：LSPosed remote prefs（将被替换）

```
App: XposedServiceHelper.registerListener → 框架下发 IXposedService binder
     → service.getRemotePreferences("config").edit()...commit()
Hook: module.getRemotePreferences("config")
```

数据落在 LSPosed 的 `modules_config.db` → `module_configs` 表，Hook 读的是**同一份**。
App 本地另有 SharedPreferences 副本，靠 App 推送保持同步。

**失效模式（已实锤）**：

1. LSPosed 在模块 App 进程启动时调用其 `XposedProvider` 下发 binder，**每个 uid 每轮开机只下发一次**。
2. 重装模块 APK 后 uid 不变，LSPosed 认为「已发过」，**不再下发**。
3. `ConfigStore.push()` 首行 `val target = remote ?: return` —— `remote == null` 时静默返回，
   本地写成功、框架侧永远不变，**无任何日志**。
4. 结果：UI 里改配置有反馈，实际行为不变，直到完整重启设备。

时间证据（2026-09-22）：

```
App config.xml            mtime = 20:43:58    ← 用户设置必杀
modules_config.db-wal     mtime = 20:52:28    ← 框架侧才被写入（重启后 App 启动那一刻）
```

软重启 zygote **不足以恢复**，必须完整重启设备。

### 目标：App ContentProvider + 广播

```
Hook 侧（system_server）:
  system_server 启动 → ContentResolver.call(content://<pkg>.config, "get") 拉一次
                      → 内存缓存
  收到 CONFIG_CHANGED 广播（带完整名单）→ 直接更新缓存，不再回调 provider

App 侧:
  配置变更 / App 启动 → 写本地 SharedPreferences + sendBroadcast(CONFIG_CHANGED)
```

设计要点：

- **单一真相来源**：只有 App 的 SharedPreferences 一份，不存在副本分叉。
- **避免循环**：广播携带完整名单，Hook 收到后直接用广播数据更新缓存，不回调 provider。
  provider 仅在 system_server 启动时调用一次。
- **后台启动无界面**：`ContentResolver.call()` 只启动 App 进程
  （`Application.onCreate` → provider `onCreate`），不创建 Activity，无前台切换、无通知。
- **降级**：拉取/广播失败沿用上次缓存；从未成功过则视为空名单（全走系统默认），
  失败写模块日志。
- **权限**：provider `exported="true"`（Hook 跨 uid 调用），内部校验
  `Binder.getCallingUid() == Process.SYSTEM_UID`；广播接收端校验发送方 uid。
- **取 Context**：Hook 侧用 `ActivityThread.currentActivityThread().getSystemContext()`
  （`AthenaHooks.resolveForceStop` 已有同样用法）。

### 改造实现路径

配置通道改造与分身改造**合并实施**（两者都改 `Config.kt` / `ConfigBridge.kt` / UI / 通道格式）。

| 文件 | 改动 |
| --- | --- |
| `ConfigProvider.kt` | **新增**。ContentProvider，authority `<applicationId>.config`，`call("get")` 返回 Bundle（keep/kill），校验 calling uid |
| `Config.kt` | 移除旧通道专用的 `GROUP`；新增 authority / 广播 action / Bundle key；新增 `<pkg>#<userId>` 编解码；保留 `KEY_KEEP` / `KEY_KILL` |
| `ConfigStore.kt` | 删除 `remote` / `attach` / `detach` / `push`；`setModes` 改为「写本地 + 发配置广播」；读取时做旧格式迁移 |
| `SwipeCleanApp.kt` | 删除 `XposedServiceHelper.registerListener`；改为启动时发一次配置广播 |
| `AndroidManifest.xml` | 声明 `ConfigProvider`（`exported="true"`） |
| `build.gradle.kts` | 移除 `implementation("io.github.libxposed:service:101.0.0")` |
| `hook/ConfigBridge.kt` | 重写：改用 `contentResolver.call()` 拉取 + 内存缓存 + 广播入口；按 `<pkg>#<userId>` 匹配 |
| `hook/ModuleMain.kt` | `onSystemServerStarting` 中初始化 ConfigBridge（取 systemContext、注册接收器、首次拉取） |
| `hook/SwipeKillHooks.kt` | 包名匹配改为 `<pkg>#<userId>`；userId 由 `mInfo.uid` 经 `UserHandle.getUserId()` 取得 |
| `hook/AthenaHooks.kt` | 同上；userId 用已有的 `ProcDetailInfo.userId`；日志带 userId |
| `AppRepository.kt` | 枚举分身 user 及其已安装应用（`LauncherApps` → 回退反射） |
| `AppListAdapter.kt` / `MainActivity.kt` / `item_app.xml` | 本体条目下展开分身子项；每个子项独立设置 |

移除 `service` 依赖后，其 manifest 合并的 `XposedProvider` 声明一并消失——
这是「旧通道彻底移除」的预期结果。

### 验收

配置通道：

1. 改配置后**立即生效**（不重启）
2. **重装模块 APK 后仍生效**（旧通道正是在这里失效）
3. 重启后名单**自动恢复**
4. 拉取时**无感**：无界面、无通知

分身：

5. 本体设「必杀」、分身设「不杀」→ 划本体卡杀 uid 尾号 `0` 的进程，划分身卡进程保留
6. 两个分身设不同模式 → 各自生效，互不影响
7. 模块日志能直接看出命中的 userId（`athena swipe force kill: <pkg>#<userId>`）

## 已知限制

- **系统应用不受名单控制**：`G0()` 把 `procDetailInfo.system == true` 的应用交给 `I0()` 分支，
  该分支不调用 `getStopType`。UI 默认不显示系统应用，与此一致。
- **最近任务里手动锁定过的卡片**由 `isRecentLockTask` 保护，本模块不覆盖。
- **同一 userId 下同包多任务**：`G0()` 中 `J0()` 在**同一 userId** 下该包还有其它任务时
  跳过整段（连 `D0` 都不进），此时划掉一张卡不会杀进程。`J0` 同时比较 `pkgName` 与
  `userId`（`v.java:245`），因此**本体与分身互不影响**。属系统既有行为，本模块不介入。
- 「划卡不杀」名单同时会让该应用不被 athena 的后台内存清理回收（两者共用同一判定入口）。