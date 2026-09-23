# 上游行为参考（athena / ColorOS）

本文回答——上游（athena / ColorOS）的真实行为是什么、结论从哪来。

本文只写上游判定链与实机观察；本模块自己的挂点、实现与契约见文末「挂点与配置通道」的指针。

## 1. 素材与复现

| 项 | 值 |
| --- | --- |
| APK | `Athena_6.0.1_new.apk`（设备路径 `/system_ext/app/Athena/Athena.apk`） |
| md5 | `60944a5ffc5afb6d393a5ea52d77d90c` |
| versionCode / versionName | `601` / `6.0.1` |
| 构建提交 / 构建日期 | `62c260e` / `260724` |
| 框架侧素材 | `/system/framework/services.jar`、`/system/framework/oplus-services.jar` |
| 设备 | realme UI / ColorOS（Android 16），LSPosed v2.1.1 |

该 APK 的 `com.oplus.athena.*` 包**未被混淆**，类名/方法名可直接引用。
（例外：划卡动作类 `...prockill.clear.v` 是混淆名，见 §9 第 2 条。）

复现命令：

```bash
jadx -d ~/tmp/athena_new Athena_6.0.1_new.apk
unzip -p /system/framework/oplus-services.jar classes3.dex > ~/tmp/oplus.dex
```

## 2. 结论：划卡杀不杀由两条独立路径决定

任何一条放行都不够：

```
路径 A（框架）：ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)
  -> ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)
    -> OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)

路径 B（athena）：com.oplus.recents --REQUEST_CLEAR_SPEC_APP(13)-->
  com.oplus.athena.systemservice.action.prockill.clear.v (SwipeUpClearAction)
    -> e1(Bundle) -> G0(String,...) -> D0(...)      ← 划卡决策点（v.java:97）
         int stopType = FilterHelper.getInstance().getStopType(procDetailInfo, aVar);
         stopType == 2 -> com.oplus.athena.systemservice.utils.p.b(...) -> 强制结束进程
    -> E0()                                         ← 统一移除任务卡片（与杀不杀无关）
```

实机验证过：**只挂路径 A 时，`getRemoveTaskFilterType` 已返回「不杀」，进程仍被 athena 杀掉**。
两条路径的返回值语义：

| 路径 | 方法 | 保留 | 强杀 |
| --- | --- | --- | --- |
| A | `getRemoveTaskFilterType` | `1`（包级）/ `2`（进程级） | `3`；`0` = 杀（有前台服务则保留） |
| B | `FilterHelper#getStopTypeInner` | `0` | `2` |

出处：`services.jar` → `com/android/server/wm/ActivityTaskSupervisor.java:1235-1253`；
`Athena` → `.../prockill/clear/v.java:97-120`。

## 3. athena 概览

| 项 | 值 | 出处 |
| --- | --- | --- |
| 包名 | `com.oplus.athena` | `AndroidManifest.xml:package` |
| 共享 uid | `android.uid.system` | `AndroidManifest.xml:android:sharedUserId` |
| `coreApp` | `true` | `AndroidManifest.xml:coreApp` |
| 常驻 | `android:persistent="true"` | `AndroidManifest.xml:application` |
| 系统服务进程 | `com.oplus.athena.systemservice.OplusAthenaSystemService`，`android:process="system"` | `AndroidManifest.xml:86-89` |

`android:process="system"` 表示 athena 的系统服务与 system_server **同进程**；athena 的类由它自己的
APK 提供，需等 `onPackageLoaded("com.oplus.athena")` 拿到对应 ClassLoader。

## 4. 路径 B：athena 的划卡清理动作

最近任务（`com.oplus.recents`）划掉一张卡后，向 athena 发起
`oplus.intent.action.REQUEST_CLEAR_SPEC_APP`（`type=13`）：

```
com.oplus.athena.systemservice.action.prockill.clear.d#G0(Bundle)          // ClearSpecAppAction
  d.java:438  i3 == 13 && caller == "com.oplus.recents"
  -> v.A0().e1(bundle)                                                     // SwipeUpClearAction
     v.java:581  e1(Bundle)：逐条 F("swipeup_forcestop_clear", 40) -> G0(...)
       -> G0(String,...)   v.java:134-190
          - Z0(): 正在通话 -> 跳过（t0.o.b(pkgName, uid)，带 uid）
          - J0(): 同一 userId 下该包还有其它任务 -> 跳过（v.java:245，同时比较 pkgName 与 userId）
          - 系统应用走 I0()，普通应用走 D0()
       -> D0(...)          v.java:97-120
          int stopType = FilterHelper.getInstance().getStopType(procDetailInfo, aVar);
          if (stopType == 2 && ...) { ... utils.p.b(...); return; }        // 强杀
          if (stopType != 0) { ... 音频/PiP/可见窗口/连续划卡 再判断 ... }   // 0 时完全不动
```

`clear type` 编号 40 的映射表见 `d.java:137`：`arrayMap.put("com.oplus.recents", 40)`；
`SwipeUpClearAction` 自身也以 `F("swipeup_forcestop_clear", 40)` 设定，`r0.a#b()` 即返回该值。

强制结束进程的落点（`com/oplus/athena/systemservice/utils/p.java:84`）：

```java
public static void b(Context ctx, String pkg, int userId, int reason, int type, String a, String b) {
    c(ctx, pkg, userId, reason, type, a, b, false);
}
public static void c(..., boolean z) {
    ...
    j1.h.g(pkg, userId, 13, type + 2000, "o-stop(" + type + ")", null);   // force-stop
    p0.b.s("OplusClearSystemService", "F [" + userId + "," + pkg + ", reason: ...]");
}
```

实机日志里能同时看到 `Athena : SwipeUpClearAction: remove task, taskId: N`（移除任务）
与 `OplusClearSystemService: F [0, <pkg>, reason: ...]`（强制结束）。

**卡片移除与杀不杀无关**：任务 id 在 `G0` 里（`v.java:501`）就已登记进 `f1446s`，
由 `e1` 末尾的 `E0()`（`v.java:121-125` -> `F0` -> `utils.p.j` -> `z0.l.n` = `removeTask`）统一移除。
实测确认：即使跳过 `D0`，卡片照常消失。

### 4.1 `getStopType` 的返回值语义（各调用方一致）

`FilterHelper#getStopType(ProcDetailInfo, r0.a)`（`FilterHelper.java:2062`）与
`getStopType(ProcDetailInfo, r0.a, List)`（`:2896`）都走 `getStopTypeInner`（`:2066`）：

```java
public int getStopType(ProcDetailInfo p, r0.a a) { return getStopType(p, a, null); }
public int getStopType(ProcDetailInfo p, r0.a a, List<String> l) {
    int inner = getStopTypeInner(p, a);
    if (inner != 2 || l == null || !l.contains(p.pkgName)) return inner;
    return 1;                      // 把「强杀」纠正为「保留」
}
```

`getStopTypeInner` 的分支：`persist` -> `1`；`isForceStopApp` -> `2`；
`aVar.a()/c()` 非 2 时直接返回其值；系统资源控制/保护名单命中 -> `0`/`1`。

各调用方对返回值的处理（决定「保留」应返回哪个值）：

| 调用方 | 处理 |
| --- | --- |
| `clear/v.java:98`（划卡） | `2` -> force-stop；`0` -> 什么都不做；其它 -> 再判断音频/PiP/可见窗口 |
| `clear/b.java:487`（通用清理） | `f(...)`：`2` 且 `z2` -> 降级为 `1`；`s0()` 中 `stopType != 2` 即跳过 force-stop |
| `clear/l.java:221`（深度清理） | `== 0` -> 保留（`U(..., 6)`）；否则走 kill 分支 |
| `clear/i.java:289`（内存超标） | `== 0` -> 保留；`2`/`4` -> kill |

因此名单内应用统一返回 **`0`（保留）**，对「必杀」名单返回 **`2`**。

### 4.2 `stopType == 2` 之后仍有两处闸门

实测：路径 A 返回 `3`、路径 B 的 `getStopTypeInner` 返回 `2` 之后，**带常驻服务的应用（微信）
依然不会被杀**。两处闸门：

- 框架侧 `ActivityManagerService#killProcessesForRemovedTask`：

  ```java
  if (wpc.hasRecentTasks()) { log "skip ... because hasRecentTasks"; }
  else {
      ProcessRecord pr = wpc.mOwner;
      if (ActivityManager.isProcStateBackground(pr.mState.getSetProcState())
              && pr.mReceivers.numberOfCurReceivers() == 0
              && !pr.mState.hasStartedServices()) {
          pr.killLocked("remove task", 10, 22, true);
      } else {
          pr.setWaitingToKill("remove task");   // 只标记，等它自己变后台
      }
  }
  ```

- athena 侧 `D0` 自身：`stopType == 2` 之后还有
  `!t0(tVar, hVar, proc, false)`（`b.t0` -> `h.x` 的名单 + `t.d`）与
  `aVar.e(pkg) || !O0(proc)`（`e` = `FilterHelper.getBlackList().contains`，`O0` = 最近任务锁）。

因此强杀必须落在划卡决策点自身，走 athena 自己的 force-stop（`utils.p.b`）；
为什么必须这么做见 docs/decisions.md，模块侧怎么调用见 docs/architecture.md。

## 5. 路径 A：框架侧判定

### 5.1 判定实现

`oplus-services.jar` → `com/android/server/wm/OplusAthenaManager.java:110-164`：

```java
public int getRemoveTaskFilterType(WindowProcessController proc) {
    ...
    ArrayList<String> stageProtectList = OplusListManager.getInstance().getStageProtectList();
    if (stageProtectList.contains(proc.mInfo.packageName))            return 1;   // 不杀
    ArrayList<String> filterList =
            OplusListManager.getInstance().getRemoveTaskFilterPkgList(mContext);
    if (filterList.contains(proc.mInfo.packageName))                  return 1;   // 不杀
    ArrayList<String> proFilterList =
            OplusListManager.getInstance().getRemoveTaskFilterProcessList(mContext);
    if (proFilterList.contains(proc.mName))                           return 2;   // 不杀
    if (isOneKeyClear && isOneKeyProtectPkg(proc.mInfo.packageName))  return 1;
    if (isBackupPackage(proc.mInfo.packageName))                      return 1;
    if (OplusAthenaAmManager.getInstance()
            .isAuthScopeProtectProc(proc.mInfo.packageName, proc.mName)) return 2;
    ...
    if (proc.hasForegroundServices() && REMOVE_TASK_NOT_SKIP_LIST.contains(pkg)) return 3;
    return 0;
}
```

调用方 `ActivityTaskSupervisor#killTaskProcessesIfPossible`（`ActivityTaskSupervisor.java:1216-1253`）：

```java
int filterType = this.mActivityTaskSupervisorExt.getRemoveTaskFilterType(proc);
if (filterType == 1) return;                      // 不杀
if (filterType != 2) {
    if (filterType == 3) procsToKill.add(proc);   // 强制杀
    else { if (proc.hasForegroundServices()) return; procsToKill.add(proc); }
}
```

### 5.2 系统自带白名单来源

`remove_task_filter_pkg` / `remove_task_filter_proc` 由 athena 解析配置后推送给框架：

- 解析：`com.oplus.athena.common.parser.athena.g5#parseRemoveTaskFilterPkgNew` /
  `parseRemoveTaskFilterProcNew`（标签 `RemoveTaskFilterPkgNew` /
  `RemoveTaskFilterProcessNew`，来源 `assets/sys_system_config_list.xml`）。
- 落库：`FilterHelper#addRemoveTaskFilterPkg` / `addRemoveTaskFilterProc`（`FilterHelper.java:1476+`）。
- 推送：`FilterHelper#updateConfigToAms()`（`FilterHelper.java:2731-2743`）→
  `updateConfigToAms("remove_task_filter_pkg", list)`（`FilterHelper.java:2970`）→
  `OplusCommonConfig.putConfigInfoAsUser`。

> `getRemoveTaskFilterPkgList()` / `getRemoveTaskFilterProcList()` 在 athena 内部**没有**业务调用者，
> 只有 dump 打印；真实消费者是框架 `OplusListManager`。

### 5.3 另一条相关机制：最近任务锁定

`OplusAthenaManager#isRecentLockTask(Task)`（`OplusAthenaManager.java:378-392`）读取
`OplusListManagerImpl#getRecentLockListWithUserIdAsUser`（对应 athena 的 `recent_lock_list`，
由 `FilterHelper#isRecentLockApp` 暴露给 `PermStatusProvider`）。
调用点在 `com.android.server.am.ActivityManagerService`。
这是「最近任务卡片上的锁」特性，与 §5.2 的白名单相互独立；用户手动锁定过的卡片
不受任何名单控制（见 §9 第 7 条）。

## 6. 本模块的挂点与配置通道

- 本模块的 5 个 Hook 点见 docs/architecture.md。
- 模块 ↔ 框架的配置通道（历史，已被取代）：该通道已废弃，现行契约见 docs/api.md。

## 7. 应用分身（MultiApp）的真实形态

### 7.1 机制（实机验证，2026-09-22）

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

### 7.2 判定链取 userId

| 位置 | userId 来源 | 说明 |
| --- | --- | --- |
| `getStopTypeInner` / `D0` 一路 | `ProcDetailInfo.userId` | `public int` 字段，与 `uid`、`realUid` 并列 |
| `getRemoveTaskFilterType` 一路 | `WindowProcessController.mUserId` | 实测字段（`WindowProcessController.java:122`）；取不到回退 `mInfo.uid / PER_USER_RANGE` |

`UserHandle` 的 `of()` / `getUserId()` / `myUserId()` / `getIdentifier()` **都不是公开 API**
（CI 侧 `android-36/android.jar` 用 `javap` 实测确认，对照 `SharedPreferences#getStringSet` 在），
因此 userId 一律用 `uid / 100000`（`PER_USER_RANGE`）换算，不引用 `UserHandle` 的访问器。

### 7.3 `G0` 的闸门是 user 感知的

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

### 7.4 同 profile group 的跨 user 查询不过滤

分身 user 与当前 user **同 profile group**（`parentId=0`），
`filterAppAccess` 对同 profile group 的跨 user 查询**不过滤**；
`LauncherAppsService#getLauncherActivities` → `canAccessProfile` → `isProfileAccessible`
对同 profileGroupId 的已启用 user 返回 true。Termux（`uid=10366 u0_a366`，**无 su**）实测：

```
cmd package list packages -U --user 998  → package:com.xunmeng.pinduoduo uid:99810367
```

uid 前缀 998 证明查询真的落在目标 user，不是回退到 0。

> 模块侧如何利用该结论枚举分身、以及分身在 UI 里怎么呈现，见 docs/architecture.md；
> 名单 key 格式 `<pkg>#<userId>` 见 docs/data-model.md。

## 8. ColorOS 开机窗口闸门

### 8.1 启动窗口本身

- 闸门：`isPreventBootStartData`（`OplusAppStartupManager.java:4082`，默认 `preventDuration = 30s`），
  名单常量 `BOOT_PREVENT_START_APPLIST`。
- 实测：**完整重启后启动期 60 次拉取全部失败**，直到用户打开 App 才成功；
  期间第三方 App 完全拉不起来。软重启 zygote 不解除该窗口（只有完整重启才会重新进入窗口期）。

### 8.2 provider 场景冷启动的判定链

ColorOS 的启动管控会拦掉第三方 App 的 provider 场景冷启动——**即使调用方是 system_server**：

```
W/OplusAppStartupManager: prevent start io.github.lmq00.swipeclean,
  cmp ComponentInfo{…/ConfigProvider} by contentprovider android callingUid 1000, scenePriority = 0
E/ActivityThread: Failed to find provider info for io.github.lmq00.swipeclean.config
```

判定链（`OplusAppStartupManager.java`，jadx 自 `/system/framework/oplus-services.jar`）：

```
AMS → shouldPreventStartProvider(proc, providerRecord, appInfo, callingPackage, callingUid)  // 2062
       -> validStartupWithRestrict(providerRecord, null, 0, null, "provider")                 // 2068
       -> handleStartProvider(providerRecord, proc)                                           // 2070
            （callerApp.uid <= 10000 时直接放行）
       -> !isAllowStartFromProvider(proc, providerRecord, appInfo, …)                          // 2080
            - isRootOrShell(callingUid)                                                       // 2261（uid 1000 不算）
            - isDefaultAllowStart(appInfo) || isInLruProcessesLocked(appInfo.uid)              // 2325
            - inProtectWhiteList(pkg)                                                         // 2335
            - isAllowAssociateByList(64, …)                                                   // 2352
            - 全不满足 → 2380 打上面那条日志并 return false（= 不允许启动）
```

`OplusAppStartupManagerExtImpl` 只做配置场景转发（`notifyConfigExSceneUpdate`），没有 provider 闸门。

该闸门的存在是模块侧必须放行自身 provider 冷启动的原因；模块怎么放行见 docs/architecture.md。

## 9. 失效风险

1. **ColorOS 版本差异**：`OplusAthenaManager` / `ActivityTaskSupervisorExtImpl` /
   `FilterHelper#getStopTypeInner` 均为私有实现，跨大版本可能改名或改变返回值语义。
2. **划卡动作类 `...prockill.clear.v` 是混淆名**（`v`/`d`/`b` 等单字母，同一 APK 内稳定，
   跨 Athena 版本可能变化）。类找不到时只打日志（`swipe class not found: ...`），
   `getStopTypeInner` 仍然生效 —— 此时「划卡不杀」正常，
   「划卡必杀」退化为 athena 自己的判定（可能被 `D0` 内闸门拦掉）。
3. **`getStopTypeInner` 的作用域比「划卡」宽**：它也被内存清理、深度清理等调用方使用（见 §4.1），
   因此名单内应用同时不会被 athena 的后台清理回收 —— 这与「保后台」的目标一致，但需知悉。
4. **系统应用走另一条分支**：`G0()` 把 `procDetailInfo.system == true` 的应用交给 `I0()`
   （`v.java:171`），`I0` 不调用 `getStopType`，而是用自己的配置
   （`swipe_up_kill_system_audio_enabled` / `swipe_up_kill_system_pip_enabled` /
   `swipe_up_kill_system_visible_window_enabled` / `swipe_up_force_kill_system_process`）。
   因此「划卡不杀」对系统应用不生效。
   （另注：`system_process_force_cast_list` / `no_system_process_force_cast_list` 可以改写
   `system` 判定，属于系统自带白名单，模块不介入。）
5. **`WindowProcessController` 字段**：`mInfo` / `mName` 为包内可见字段，若被重命名则取不到包名，
   该次调用按「默认」处理。
6. **多任务场景**：`G0()` 中 `J0()` 在**同一 userId** 下该包还有其它任务时跳过 kill；
   此时划掉一张卡不会杀进程，属系统既有行为。见 §7.3。
7. **最近任务锁定**：用户手动锁定过的卡片由 `isRecentLockTask` 保护，不受名单控制。
8. **provider 闸门被改动**：若 ColorOS 后续改掉
   `OplusAppStartupManager#shouldPreventStartProvider`，第三方 App 的 provider 冷启动会被拦，
   表现为模块日志 `config bridge not ready after N attempts` 或 `config pull failed: …`。

## 10. 实机验证记录

设备：realme UI（Android 16），LSPosed v2.1.1，Athena 6.0.1（`60944a5f…`）。

已确认（LSPosed 模块日志）：

```
(system)[io.github.lmq00.swipeclean,SwipeClean] loaded: process=system, systemServer=true, api=102
(system)[io.github.lmq00.swipeclean,SwipeClean] swipe hooks installed: 2
(system)[io.github.lmq00.swipeclean,SwipeClean] config loaded: keep=[...] kill=[...]
(system)[io.github.lmq00.swipeclean,SwipeClean] filter query: com.omarea.vtools
(system)[io.github.lmq00.swipeclean,SwipeClean] swipe-up keep: com.omarea.vtools
```

- [x] 模块注入 system_server（作用域必须写进程名 `system`，写 `android` 不会注入）。
- [x] 路径 A 的 Hook 命中并返回「不杀」。
- [x] 名单已生效（Hook 日志出现 `config loaded: keep=[...] kill=[...]`）。
- [x] 路径 B 的 Hook 命中（`athena hooks installed: 1`、`athena keep:`、`athena swipe keep:`）。
- [x] 名单内 App 划卡后进程存活（`com.omarea.vtools`：卡片消失、进程保留）。
- [x] 必杀对带常驻服务的 App 生效（`com.tencent.mm`）：

  ```
  athena swipe force kill: com.tencent.mm     ← 00:55:03.129
  /proc/<pid> 启动时间                         ← 00:55:06.539（新进程）
  ```

  即旧进程在划卡瞬间结束、3 秒后由系统重新拉起。

### 10.1 2026-09-22：必杀对拼多多（`com.xunmeng.pinduoduo`）

```
20:53:51.779  config loaded: keep=[com.termux, github.tornaco.android.thanos.pro, com.omarea.vtools] kill=[com.xunmeng.pinduoduo]
20:53:51.779  athena swipe force kill: com.xunmeng.pinduoduo
20:53:51.782  swipe-up force kill: com.xunmeng.pinduoduo      ← 框架侧 Hook 1/2，共 3 次

20:53:40.311  am_proc_start: [0,24234,10367,com.xunmeng.pinduoduo,next-top-activity,MainFrameActivity]
20:53:51.806  am_kill: [0,24380,com.xunmeng.pinduoduo:titan,450,stop com.xunmeng.pinduoduo due to o-stop(0),337952]
20:53:51.815  am_kill: [0,24645,com.xunmeng.pinduoduo:sandboxed_process0,450,stop com.xunmeng.pinduoduo due to o-stop(0),389080]
20:53:51.820  am_kill: [0,24234,com.xunmeng.pinduoduo,0,stop com.xunmeng.pinduoduo due to o-stop(0),750660]
```

- [x] 必杀命中后，主进程 + `:titan` + `:sandboxed_process0` **三个进程瞬间全部结束**。
- [x] 之后 100 秒（每秒采样）无进程、无 `am_proc_start` → **force-stop 后不会被自动拉起**。
      （与上条 `com.tencent.mm` 的「3 秒后被拉起」不同：微信有常驻推送链路，拼多多此处没有。）
- [x] 该轮 `am_proc_start` 的 reason 全为 `next-top-activity`（用户主动打开），无自启类型记录。

> 判读「是否被自动拉起」必须看 `am_proc_start` 的 reason 字段：
> `next-top-activity` = 用户主动打开，`broadcast` / `service` / `content provider` = 自启。
> 只看进程是否存在会被「用户手动打开」污染。

> 该轮之前有一次「划卡杀不掉」的观察，根因是**当时配置未生效**（旧配置通道失效，
> 见 docs/decisions.md），不是 force-stop 不够强。

### 10.2 2026-09-22：应用分身（多开）

ColorOS 分身 = 独立 user（类型 `MultiApp`，`parentId=0`），**包名与本体相同**，仅 uid 不同：

```
UserInfo{998:MultiApp:4001010} serialNo=11 isPrimary=false parentId=0
UserInfo{999:MultiApp:4001010} serialNo=10 isPrimary=false parentId=0

pm list packages -U --user 0    → package:com.xunmeng.pinduoduo uid:10367
pm list packages -U --user 998  → package:com.xunmeng.pinduoduo uid:99810367
pm list packages -U --user 999  → package:com.xunmeng.pinduoduo uid:99910367
```

`99810367 = 10367 + 998 × 100000`。`MultiAppConstants.java:44`（`OplusMultiApp.apk`）的
`USER_ID_MULTI_APP = 999` 只是**首个**分身的 id，**userId 不固定**。

划卡实测（两个分身同时在运行，各划一次）：

```
21:29:51.060  am_kill: [998,20017,com.xunmeng.pinduoduo:titan,475,stop … due to o-stop(0),…]
21:29:51.065  am_kill: [998,19671,com.xunmeng.pinduoduo,475,stop … due to o-stop(0),…]
21:29:51.078  am_kill: [998,20519,com.xunmeng.pinduoduo:support,0,stop … due to o-stop(0),…]
21:29:51.375  am_kill: [999,18737,com.xunmeng.pinduoduo:titan,0,stop … due to o-stop(0),…]
21:29:51.383  am_kill: [999,18468,com.xunmeng.pinduoduo,500,stop … due to o-stop(0),…]
21:32:08.952  am_kill: [0,23107,com.xunmeng.pinduoduo,0,stop … due to o-stop(0),…]
```

- [x] **分身受包名名单控制**：998 / 999 / 0 的进程均被 `o-stop(0)` 结束。
- [x] **强杀路径的 userId 传递正确**：`am_kill` 首字段分别标记 998 / 999 / 0，未互相误伤。
- [x] 998 与 999 同时有任务时，划掉 998 的卡只杀 998 —— 印证 §7.3 的 `J0` 按 userId 隔离。
- [ ] 当时**无法区分本体与分身**：名单是包级 `StringSet`，一条记录同时命中三者（待改造）。

> 注：当时的模块日志只打 `pkg`，看不出 userId；带 userId 的日志与按 `<pkg>#<userId>`
> 分治的现状见 docs/testing.md。

**测试样本**：拼多多 `com.xunmeng.pinduoduo`，user 998 / 999 各一个分身
（uid `99810367` / `99910367`），本体 uid `10367`。

未覆盖（见 §9）：系统应用分支 `I0()`、最近任务锁定的卡片、
**同一 userId 下**同包多任务（`J0` 跳过）。