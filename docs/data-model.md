# 数据模型

**本文回答——名单这个领域对象长什么样、怎么迁移、真相来源在哪。**

## 1. 名单模型

名单 = 两组 key 的集合，存在 App 的 SharedPreferences（`Config.PREFS = "config"`）里：

| 组 | 键名 | 含义 |
| --- | --- | --- |
| keep | `keep`（`Config.KEY_KEEP`） | 划卡不杀的 `<pkg>#<userId>` |
| kill | `kill`（`Config.KEY_KILL`） | 划卡必杀的 `<pkg>#<userId>` |

**key 格式：`<pkg>#<userId>`**，由 `Config.key(pkg, userId)` 生成；`Config.isLegacy(entry)` 判定旧格式 = 不含 `#`。

- `userId = 0` 是本体；其余是分身所在的独立 user。
- 同一 key 至多存在于一组：`ConfigStore.setModes` 写入前先把它从两组都移除；读取时 `modeMap` 若两组都命中，`kill` 覆盖 `keep`。
- 实测（2026-09-23，realme UI / ColorOS 16）：拼多多本体 uid `10367`、分身 uid `99810367` / `99910367`
  （`99810367 = 10367 + 998 × 100000`），**包名与本体完全相同，仅 uid 不同**；
  分身 userId **不固定**（实测同时存在 998 与 999，`MultiAppConstants.java:44` 的 `USER_ID_MULTI_APP = 999` 只是首个分身的 id）→ **key 不得硬编码 999**。
- 因此同一应用的本体与各分身互不影响：划掉 998 的卡只杀 998，999 完好（判定链本身就比较 userId，见 `见 docs/references.md §7.3 G0 的闸门是 user 感知的`）。

## 2. 关键类型

| 类型 | 位置 | 职责与字段 |
| --- | --- | --- |
| `Config` | `Config.kt` | 共享常量：`PREFS="config"`、`MODULE_PACKAGE="io.github.lmq00.swipeclean"`、`AUTHORITY=MODULE_PACKAGE+".config"`、`METHOD_GET="get"`、`ACTION_CONFIG_CHANGED="io.github.lmq00.swipeclean.CONFIG_CHANGED"`、`KEY_KEEP="keep"`、`KEY_KILL="kill"`、`MODE_DEFAULT=0` / `MODE_KEEP=1` / `MODE_KILL=2`；函数 `key(pkg, userId)`、`isLegacy(entry)` |
| `ConfigStore` | `Config.kt` | App 侧读写：`prefs(context)`（`MODE_PRIVATE`）、`modeMap(prefs)`（一次取出全部名单 → key→mode，列表渲染用）、`setMode` / `setModes`（批量落盘一次 + 广播一次）、`needsMigration`、`migrate`、`notifyChanged`（私有） |
| `ConfigProvider` | `ConfigProvider.kt` | Hook 读配置的入口（`call("get")`）；契约见 `见 docs/api.md §ConfigProvider 契约` |
| `AppEntry` | `AppRepository.kt` | 列表中的一行：`label` / `packageName` / `userId`（`0`=本体）/ `system` / `ordinal`（ColorOS 分身序号，`0`=本体或未知）；派生 `key = Config.key(packageName, userId)`；`title(context)`：本体用 `label`，有序号用 `dual_ordinal`（`%1$s · 分身%2$d`），否则 `dual_suffix` 退回 userId |
| `DualApps` | `AppRepository.kt` | 分身枚举结果：`userIds: List<Int>`、`packages: Map<Int, Set<String>>`、`ordinals: Map<Int, Int>`（空 = 取不到序号）；`usersOf(pkg) = userIds.filter { pkg in packages[it] }`（本体 `0` 不在其中） |
| `AppRepository` | `AppRepository.kt` | `loadDualApps(context)`（见 §5）、`iconOf(context, pkg, userId)`（本体走 `PackageManager`，分身走 `LauncherActivityInfo#getBadgedIcon(density)`）、`load(context, dual)`（每个应用在本体条目后追加各分身子项，按 system/label/packageName/userId 排序） |
| `IconCache` | `AppRepository.kt` | 图标内存缓存 + 后台加载；缓存键就是 `Config.key(pkg, userId)`——分身行的图标带角标，与本体不同 |

## 3. 旧名单迁移规则

旧格式（纯包名，不含 `#`）→ `<pkg>#0` + 该包**实际存在**的各分身 userId：

```
expand(entries):
  非 legacy → 原样保留
  legacy   → 加入 <pkg>#0，再对 dual.usersOf(<pkg>) 的每个 userId 加入 <pkg>#<userId>
```

| 项 | 值 |
| --- | --- |
| 判定 | `ConfigStore.needsMigration(context)`：keep + kill 里任一元素 `Config.isLegacy` |
| 执行 | `ConfigStore.migrate(context, dual)` |
| 语义 | 旧名单升级前同时作用于本体与所有分身，展开后保持同样行为；想拆分再手动改 |
| 幂等 | 没有旧格式条目时返回 `false` 且不写盘 |
| 落盘 | 用 `commit()` 而非 `apply()`——迁移后 UI 与 `ConfigProvider` 会立刻读同一份 prefs |
| 日志 | `config migrated: keep=<newKeep> kill=<newKill>`，随后 `notifyChanged` 让 Hook 立刻重拉 |
| 触发点 1 | `ConfigProvider.call` 首次被调用时（`needsMigration` 判定），保证 Hook 不打开 UI 也拿到迁移后的名单 |
| 触发点 2 | `MainActivity` 加载列表时（先 `AppRepository.loadDualApps(this)` 再 `ConfigStore.migrate(this, dual)`，依赖其幂等） |

`dual` 必须是**实际枚举出来的分身**（见 §5）：不存在的 user 不展开，否则会写出永不命中的 key。

## 4. 真相来源与副本

```
唯一真相来源：App SharedPreferences
  /data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml   （Config.PREFS = "config"）
        │  ConfigProvider.call("get")  ← 拉取
        ▼
Hook 侧副本（system_server）：
  内存 keep/kill  ──内容变化时──▶  /data/system/swipeclean_config.json（ConfigBridge.CACHE_FILE）
```

| 项 | 说明 |
| --- | --- |
| 真相来源 | App 的 `shared_prefs/config.xml`；Hook 侧内存与文件都只是副本，每次拉取成功即被覆盖 |
| 开机读取 | `ConfigBridge.install()` 第一步 `restoreFromCache` 读 `/data/system/swipeclean_config.json`（纯文件 I/O，不依赖 AMS 与 App 进程），日志 `config restored from cache: keep=[…] kill=[…]`；实测完整重启后 **1 秒**即出现 |
| 回写时机 | `pull` 成功且 `newKeep/newKill` 与当前缓存**不同**时才 `persist`；内容相同不重复写盘 |
| 缓存格式 | JSON：`{"keep":[…],"kill":[…]}`，两组都是 `<pkg>#<userId>` 字符串数组 |
| 缓存读失败 | 记 `config cache unreadable, ignored` 并忽略；写失败记 `config cache write failed`，不影响本次运行 |
| App 卸载后 | 缓存文件仍在 → 模块继续按最后一次名单执行（模块本身通常也会同时被卸载） |

拉取节奏（2 秒 TTL、启动期重试、失败降级）见 `见 docs/api.md §Hook 侧消费方式`；
缓存存在的理由（开机窗口内 ColorOS 拦第三方 App 启动）见 `见 docs/decisions.md §4. 开机先读 Hook 侧缓存`。

## 5. 分身 user 枚举方式

`AppRepository.loadDualApps(context)` —— **按实际存在的 user 动态展开，不假设数量、不硬编码 id**：

1. `LauncherApps#getProfiles()`（`launcherApps.profiles`）→ 当前 user 所属 profile group 的全部 user。
   依据：`UserManagerService.getProfileIds(自己, true)`，只在 `userId != callingUserId` 时校验权限，普通应用可拿到整组；
   ColorOS 分身 user 的 `parentId=0`，与本体同组。
2. 对每个 profile 调 `LauncherApps#getActivityList(null, profile)` → 该 user 下带 launcher 入口的应用；
   一个入口都没有的 user 直接跳过。
3. userId 由 `ApplicationInfo.uid / PER_USER_RANGE`（`PER_USER_RANGE = 100_000`）反推——`UserHandle` 的
   `of()/getUserId()/myUserId()/getIdentifier()` 都不是公开 API。
4. 跳过当前 user 自己（`Process.myUid() / PER_USER_RANGE`）。
5. 序号：`LauncherApps#getLauncherUserInfo(handle)?.userSerialNumber`（API 35+，`Int` 需 `?.toLong()`）
   按 serial 升序排名，第 n 名 = 序号 n；取不到时 `ordinals` 留空，UI 退回显示真实 userId。

实测日志：`dual users=[998, 999] ordinals={999=1, 998=2} packages={998=…, 999=…}`（与 launcher 角标数字一致）。

**局限**：只覆盖带 launcher 入口（`MAIN` / `LAUNCHER`）的应用；无入口的分身应用不会列出，
`usersOf(pkg)` 也就不会为它展开迁移条目。