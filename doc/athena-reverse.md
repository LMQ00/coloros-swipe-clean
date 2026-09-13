# Athena 逆向笔记：划卡（recents swipe）杀不杀的判定链

> 素材：`Athena_6.0.1_new.apk`（设备 `/system_ext/app/Athena/Athena.apk`，
> md5 `60944a5ffc5afb6d393a5ea52d77d90c`，versionCode 601 / versionName 6.0.1，
> 构建提交 `62c260e`，构建日期 `260724`）。
> 该 APK 的 `com.oplus.athena.*` 包未被混淆，类名/方法名可直接引用。
>
> 框架侧素材：`/system/framework/services.jar`、`/system/framework/oplus-services.jar`。
>
> 复现命令：
> ```bash
> jadx -d ~/tmp/athena_new Athena_6.0.1_new.apk
> unzip -p /system/framework/oplus-services.jar classes3.dex > /tmp/oplus.dex
> ```

## 1. 结论（先看这个）

划卡能否杀掉一个 App，最终由**框架**在任务移除后决定：

```
com.android.server.wm.ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)
  -> com.android.server.wm.ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)
    -> com.android.server.wm.OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)
```

`OplusAthenaManager#getRemoveTaskFilterType` 的返回值语义（`ActivityTaskSupervisor` 中的分支，
见 `services.jar` → `com/android/server/wm/ActivityTaskSupervisor.java:1235-1253`）：

| 返回值 | 含义 |
| --- | --- |
| `0` | 杀；若进程持有 foreground service 则保留 |
| `1` | **不杀**（包级白名单命中） |
| `2` | **不杀**（进程级白名单命中） |
| `3` | **强制杀**，即使持有 foreground service |

因此：返回 `1` = 划卡不杀，返回 `3` = 划卡必杀。本模块即挂在此单点上。

## 2. athena 概览

| 项 | 值 | 出处 |
| --- | --- | --- |
| 包名 | `com.oplus.athena` | `AndroidManifest.xml:package` |
| 共享 uid | `android.uid.system` | `AndroidManifest.xml:android:sharedUserId` |
| `coreApp` | `true` | `AndroidManifest.xml:coreApp` |
| 常驻 | `android:persistent="true"` | `AndroidManifest.xml:application` |
| 系统服务进程 | `com.oplus.athena.systemservice.OplusAthenaSystemService`，`android:process="system"` | `AndroidManifest.xml:86-89` |

`android:process="system"` 意味着 athena 的系统服务部分**运行在 system_server 内**，
其类由 system_server 的 ClassLoader 加载（`coreApp=true`）。

## 3. 划卡调用链（athena 侧）

最近任务（`com.oplus.recents`）划掉一张卡后，会向 athena 发起
`oplus.intent.action.REQUEST_CLEAR_SPEC_APP`（`type=13`）：

```
com.oplus.athena.systemservice.action.prockill.clear.d#G0(Bundle)          // ClearSpecAppAction
  d.java:438  i3 == 13 && caller == "com.oplus.recents"
  -> v.A0().e1(bundle)                                                     // SwipeUpClearAction
     v.java:600-628  e1(Bundle)：逐条 F("swipeup_forcestop_clear", 40) -> G0(...)
       -> G0(String,...)   v.java:150-190
          - Z0(): 正在通话 -> 跳过
          - J0(): 该包还有其它任务 -> 跳过
          - 系统应用走 I0()，普通应用走 D0()
       -> D0(...)          v.java:97-120
          int stopType = FilterHelper.getInstance().getStopType(procDetailInfo, aVar);
          - stopType == 2 -> force-stop
          - stopType != 0 -> 检查音频/PiP/可见窗口/连续划卡后杀进程
          - stopType == 0 -> 什么都不做（进程保留）
```

`clear type` 编号 40 的映射表见 `d.java:137`：`arrayMap.put("com.oplus.recents", 40)`；
`SwipeUpClearAction` 自身也以 `F("swipeup_forcestop_clear", 40)` 设定，`r0.a#b()` 即返回该值。

> 这一层是 athena 自己的清理动作（会把进程 `kill`/`force-stop`）。
> 但它不是划卡结果的唯一决定者：任务本身由 `ActivityManager.removeTask` 移除，
> 进程是否随之被杀，取决于第 4 节的框架判定。

## 4. 框架侧判定（真正的白名单）

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

### 4.2 白名单来源

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

| 类 | 方法 | 作用 |
| --- | --- | --- |
| `com.android.server.wm.OplusAthenaManager` | `getRemoveTaskFilterType(WindowProcessController)` | 真正的判定实现 |
| `com.android.server.wm.ActivityTaskSupervisorExtImpl` | 同名 | 唯一调用点，保证拦截 |

命中用户名单时直接返回 `1`（不杀）或 `3`（强制杀），否则 `chain.proceed()` 交回系统原逻辑。
包名从 `WindowProcessController#mInfo`（`ApplicationInfo`）取，回退到 `mName` 的 `:` 前缀。

## 6. 失效风险

1. **框架版本差异**：`OplusAthenaManager` / `ActivityTaskSupervisorExtImpl` 为 ColorOS 私有实现，
   跨大版本可能改名或改变返回值语义。Hook 失败时模块只打日志、不改变系统行为（返回 `proceed()`）。
2. **`WindowProcessController` 字段**：`mInfo` / `mName` 为包内可见字段，若被重命名则取不到包名，
   该次调用按「默认」处理。
3. **多任务场景**：`G0()` 中 `J0()`（该包还有其它任务）会跳过 kill；此时划掉一张卡不会杀进程，
   属系统既有行为，不受本模块控制。
4. **最近任务锁定**：用户手动锁定过的卡片由 `isRecentLockTask` 保护，本模块当前不覆盖该路径。

## 7. 待实机验证项

- [ ] `OplusAthenaManager` 上 Hook 命中日志出现（`swipe-up keep` / `swipe-up force kill`）。
- [ ] 名单内 App 划卡后进程存活；名单外（默认）行为与系统一致。
- [ ] `3`（强制杀）对持有 foreground service 的 App 生效。
- [ ] LSPosed remote preferences 在 system_server 内可读（`read remote preferences failed` 不出现）。