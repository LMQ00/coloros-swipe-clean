# Athena 逆向笔记：划卡（recents swipe）杀不杀的判定链

> 素材：`Athena_6.0.1_new.apk`（设备 `/system_ext/app/Athena/Athena.apk`，
> md5 `60944a5ffc5afb6d393a5ea52d77d90c`，versionCode 601 / versionName 6.0.1，
> 构建提交 `62c260e`，构建日期 `260724`）。
> 该 APK 的 `com.oplus.athena.*` 包未被混淆，类名/方法名可直接引用。
>
> 框架侧素材：`/system/framework/services.jar`、`/system/framework/oplus-services.jar`。
> 设备：realme UI / ColorOS（Android 16），LSPosed v2.1.1。
>
> 复现命令：
> ```bash
> jadx -d ~/tmp/athena_new Athena_6.0.1_new.apk
> unzip -p /system/framework/oplus-services.jar classes3.dex > /tmp/oplus.dex
> ```

## 1. 结论

划卡后进程会不会被杀，由**两条独立路径**共同决定，任何一条放行都不够：

```
路径 A（框架）：ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)
  -> ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)
    -> OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)

路径 B（athena）：com.oplus.recents --REQUEST_CLEAR_SPEC_APP(13)-->
  com.oplus.athena.systemservice.action.prockill.clear.v (SwipeUpClearAction)
    -> FilterHelper#getStopType -> FilterHelper#getStopTypeInner
       -> stopType == 2 时 com.oplus.athena.systemservice.utils.p.b(...) -> j1.h.g(...) 强制结束进程
```

实机验证过：**只挂路径 A 时，`getRemoveTaskFilterType` 已返回「不杀」，进程仍被 athena 杀掉**。
两条路径的返回值语义：

| 路径 | 方法 | 保留 | 强杀 |
| --- | --- | --- | --- |
| A | `getRemoveTaskFilterType` | `1`（包级）/ `2`（进程级） | `3`；`0` = 杀（有前台服务则保留） |
| B | `FilterHelper#getStopTypeInner` | `0` | `2` |

出处：`services.jar` → `com/android/server/wm/ActivityTaskSupervisor.java:1235-1253`；
`Athena` → `.../prockill/clear/v.java:97-120`。

## 2. athena 概览

| 项 | 值 | 出处 |
| --- | --- | --- |
| 包名 | `com.oplus.athena` | `AndroidManifest.xml:package` |
| 共享 uid | `android.uid.system` | `AndroidManifest.xml:android:sharedUserId` |
| `coreApp` | `true` | `AndroidManifest.xml:coreApp` |
| 常驻 | `android:persistent="true"` | `AndroidManifest.xml:application` |
| 系统服务进程 | `com.oplus.athena.systemservice.OplusAthenaSystemService`，`android:process="system"` | `AndroidManifest.xml:86-89` |

`android:process="system"` 表示 athena 的系统服务与 system_server **同进程**，因此
LSPosed 作用域只需 `system`（进程名），athena 的类由它自己的 APK 提供，
需等 `onPackageLoaded("com.oplus.athena")` 拿到对应 ClassLoader 再挂。

## 3. 路径 B：athena 的划卡清理动作

最近任务（`com.oplus.recents`）划掉一张卡后，向 athena 发起
`oplus.intent.action.REQUEST_CLEAR_SPEC_APP`（`type=13`）：

```
com.oplus.athena.systemservice.action.prockill.clear.d#G0(Bundle)          // ClearSpecAppAction
  d.java:438  i3 == 13 && caller == "com.oplus.recents"
  -> v.A0().e1(bundle)                                                     // SwipeUpClearAction
     v.java:581  e1(Bundle)：逐条 F("swipeup_forcestop_clear", 40) -> G0(...)
       -> G0(String,...)   v.java:134-190
          - Z0(): 正在通话 -> 跳过
          - J0(): 该包还有其它任务 -> 跳过
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

### 3.1 `getStopType` 的返回值语义（各调用方一致）

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

因此模块对名单内应用统一返回 **`0`（保留）**，对「必杀」名单返回 **`2`**。

## 4. 路径 A：框架侧判定

### 4.1 判定实现

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

### 4.2 系统自带白名单来源

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

### 4.3 另一条相关机制：最近任务锁定

`OplusAthenaManager#isRecentLockTask(Task)`（`OplusAthenaManager.java:378-392`）读取
`OplusListManagerImpl#getRecentLockListWithUserIdAsUser`（对应 athena 的 `recent_lock_list`，
由 `FilterHelper#isRecentLockApp` 暴露给 `PermStatusProvider`）。
调用点在 `com.android.server.am.ActivityManagerService`。
这是「最近任务卡片上的锁」特性，与本节的白名单相互独立。

## 5. 本模块的 Hook 点

| # | 类 | 方法 | 名单命中时返回 |
| --- | --- | --- | --- |
| 1 | `com.android.server.wm.OplusAthenaManager` | `getRemoveTaskFilterType(WindowProcessController)` | `1` / `3` |
| 2 | `com.android.server.wm.ActivityTaskSupervisorExtImpl` | 同上 | `1` / `3` |
| 3 | `com.oplus.athena.common.parser.athena.FilterHelper` | `getStopTypeInner(ProcDetailInfo, r0.a)` | `0` / `2` |

未命中时 `chain.proceed()` 交回系统原逻辑。包名来源：

- Hook 1/2：`WindowProcessController#mInfo`（`ApplicationInfo.packageName`，包内可见，需 `setAccessible`），
  回退 `mName` 的 `:` 前缀。
- Hook 3：`ProcDetailInfo#pkgName`（`public String`，`com.oplus.app.athena.ProcDetailInfo:36`）。

Hook 3 挂在 `getStopTypeInner` 而非 `getStopType`，是因为两个重载都收敛到它，一处即可覆盖。

## 6. 模块 ↔ 框架的配置通道

Hook 侧 `XposedInterface#getRemotePreferences(group)` 读的是**框架侧存储**（LSPosed 数据库
`modules_config.db` 的 `module_configs` 表），不是模块 App 自己的 SharedPreferences。
写入必须走 libxposed 服务通道：

1. App 依赖 `io.github.libxposed:service`，其 manifest 并入
   `<provider android:name="io.github.libxposed.service.XposedProvider"
   android:authorities="${applicationId}.XposedService" android:exported="true"/>`。
2. 框架在**模块 App 进程启动时**调用该 provider（`SendBinder`）下发 `IXposedService` binder
   （LSPosed `LSPModuleService.uidStarts`：仅对 `!legacy` 的模块、且每个 uid 只发一次）。
3. App 通过 `XposedServiceHelper.registerListener` 拿到服务，
   用 `service.getRemotePreferences(GROUP).edit()...commit()` 写入。

两个坑：

- 若 App 在**尚未带上 `XposedProvider` 的版本**时启动过，框架那一次下发失败但 uid 已记入
  `uidSet`，之后同一轮开机不会再发 —— 需要重启设备后才恢复正常。
- `getRemotePreferences` 只在 App 注册过该 group 之后才对 Hook 侧可见；
  App 未启动过时 Hook 读到空集（表现为所有应用都按「默认」放行）。

## 7. 失效风险

1. **ColorOS 版本差异**：`OplusAthenaManager` / `ActivityTaskSupervisorExtImpl` /
   `FilterHelper#getStopTypeInner` 均为私有实现，跨大版本可能改名或改变返回值语义。
   Hook 失败时模块只打日志、不改变系统行为。
2. **`FilterHelper` 的类名稳定，但划卡动作类 `...prockill.clear.v` 是混淆名**（`v`/`d`/`b` 等
   单字母）。本模块刻意不挂这些混淆类，只挂 `FilterHelper` 的稳定入口。
3. **Hook 3 的作用域比「划卡」宽**：`getStopTypeInner` 也被内存清理、深度清理等调用方使用，
   因此名单内应用同时不会被 athena 的后台清理回收 —— 这与「保后台」的目标一致，但需知悉。
4. **`WindowProcessController` 字段**：`mInfo` / `mName` 为包内可见字段，若被重命名则取不到包名，
   该次调用按「默认」处理。
5. **多任务场景**：`G0()` 中 `J0()`（该包还有其它任务）会跳过 kill；此时划掉一张卡不会杀进程，
   属系统既有行为，不受本模块控制。
6. **最近任务锁定**：用户手动锁定过的卡片由 `isRecentLockTask` 保护，本模块不覆盖该路径。

## 8. 实机验证记录

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
- [x] 配置通道打通（`module_configs` 表出现 `keep` / `kill` 两组数据）。
- [ ] 路径 B 的 Hook 命中（`athena keep:` 日志）。
- [ ] 名单内 App 划卡后进程存活。
- [ ] 「必杀」（返回 `2` / `3`）对持有 foreground service 的 App 生效。