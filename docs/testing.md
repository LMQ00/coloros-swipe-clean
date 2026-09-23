# 测试与验证

本文回答——怎么证明它工作（判据 + 命令 + 期望输出）。

## 1. 验证策略

- **无自动化测试**：仓库没有 test 目录。验证 = CI 构建 + 真机观察；改动判定逻辑时只能靠
  §3 步骤 5 的真机划卡。
- **判据必须可观察**：日志行、`dumpsys` 输出、`am_kill`/`am_proc_start` 事件、进程存活、
  prefs 与缓存文件内容。无日志的「应该生效」不算完成。
- **本地不能编译**：Termux/aarch64 无 Android SDK、无 `aapt2`，构建在 CI，见 docs/development.md §构建。
- **划卡无法伪造**：清理请求的 caller 被限定为 `com.oplus.recents`，不能用 `am`/shell 触发，
  涉及划卡的判据**必须手动在最近任务里划**。
- 挂点位置与数量（5 个 Hook 点）见 docs/architecture.md §判定链与 Hook 点；本文只验「有没有命中」。

## 2. 已完成能力的判据

每项能力的判据 = 命令 + 可观察输出。

| 能力 | 判据命令 | 期望输出 |
| --- | --- | --- |
| 划卡不杀 | 手动划卡后 `su -c "grep -a SwipeClean '$LOG' \| grep -aE 'keep\|kill' \| tail -10"`；`su -c 'ps -A \| grep -i <pkg>'` | `swipe-up keep: com.omarea.vtools#0`、`athena swipe keep: com.xunmeng.pinduoduo#998`；进程仍在 |
| 划卡必杀 | 同上 + `su -c 'logcat -b events -d' \| grep -a -E "am_kill" \| grep -a <pkg>` | `swipe-up force kill: com.xunmeng.pinduoduo#0`；`am_kill` 首字段 = userId；实测拼多多 3 个进程全灭、100 秒内无 `am_proc_start` |
| 配置通道 + 开机缓存 | `su -c 'dumpsys activity providers' \| grep -a -A3 'io.github.lmq00.swipeclean/.ConfigProvider'`；`su -c 'dumpsys activity broadcasts' \| grep -a 'io.github.lmq00.swipeclean.CONFIG_CHANGED'`；`su -c 'cat /data/system/swipeclean_config.json'` | `ContentProviderRecord{… io.github.lmq00.swipeclean/.ConfigProvider}`、`proc=ProcessRecord{… 8735:io.github.lmq00.swipeclean/u0a465}`、`Action: "io.github.lmq00.swipeclean.CONFIG_CHANGED"`；完整重启后 **1 秒**（`12:28:02.754`）出现 `config restored from cache: keep=[…] kill=[…]`，早于任何拉取 |
| 模块自身恒不杀 | 在最近任务里划本模块卡片（两次） | `swipe-up keep: io.github.lmq00.swipeclean#0`，进程 pid 不变（实测 pid 8735 存活、未重启） |
| 分身独立设置 | 本体与两个分身同时有任务，只划其中一张；`su -c 'logcat -b events -d' \| grep -a -E "am_kill\|am_proc_start" \| grep -a <pkg>` | 划本体卡 → `am_kill [0,…]` ×3（`com.xunmeng.pinduoduo` / `:titan` / `:sandboxed_process0`）；划分身卡 → `athena swipe keep: com.xunmeng.pinduoduo#998`（或 `#999`），`u998_a367`（或 `u999_a367`）三进程存活，另一 user 不受影响 |
| 5 个 Hook 命中 | `su -c "grep -a SwipeClean '$LOG' \| grep -aE 'loaded:\|hooks installed'"` | `loaded: process=system, systemServer=true, api=102`、`swipe hooks installed: 2`、`app startup hooks installed: 1`、`athena hooks installed: 1`、`athena swipe hooks installed: 1` |

补充判据：

- **旧名单自动迁移**：软重启后首次拉取即完成，判据是日志 `config migrated: keep=… kill=…`；
  随后 `config loaded` 里是展开后的名单（展开规则见 docs/data-model.md §3. 旧名单迁移规则）。
- **名单内容与 App 一致**：`su -c 'cat /data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml'`
  与 `su -c 'cat /data/system/swipeclean_config.json'` 内容一致（后者是 Hook 侧副本，
  见 docs/data-model.md §4. 真相来源与副本）。
  名单元素格式见 docs/data-model.md §1. 名单模型。

## 3. 六步可复现验证

### 步骤 1：CI 构建

```bash
cd ~/coloros保后台
gh workflow run build.yml --ref main      # 或 push 到 main
gh run list --limit 1                     # 等 conclusion=success
```

实测（2026-09-23，HEAD `5562ef9`）：

```
35820482374  completed  success  Build APK  main  workflow_dispatch  ...
```

判据：`conclusion=success`。

### 步骤 2：取产物并安装

```bash
gh run download <run-id> -R LMQ00/coloros-swipe-clean -D ~/tmp/apk
su -c 'cp ~/tmp/apk/swipe-clean-release/app-release.apk /data/local/tmp/swipeclean.apk'
su -c 'pm install -r /data/local/tmp/swipeclean.apk'
su -c 'dumpsys package io.github.lmq00.swipeclean | grep -aE "versionCode|versionName"'
```

实测：

```
    versionCode=3 minSdk=26 targetSdk=35
    versionName=1.2
```

判据：装上的版本与要验的构建一致。重装 APK **不需要**重启设备。

### 步骤 3：设备侧基线检查（重启后跑这一组）

```bash
LOG=$(su -c 'ls -t /data/adb/lspd/log/modules_* | head -1' | tr -d '\r')
su -c "grep -a SwipeClean '$LOG' | grep -aE 'loaded:|hooks installed|config restored'"
su -c 'cat /data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml'
su -c 'cat /data/system/swipeclean_config.json'
su -c 'dumpsys activity providers' | grep -a -A3 'io.github.lmq00.swipeclean/.ConfigProvider'
su -c 'dumpsys activity broadcasts' | grep -a 'io.github.lmq00.swipeclean.CONFIG_CHANGED'
```

实测输出（2026-09-23 完整重启后）：

```
loaded: process=system, systemServer=true, api=102
swipe hooks installed: 2
app startup hooks installed: 1
config restored from cache: keep=[com.omarea.vtools#0, github.tornaco.android.thanos.pro#0, com.termux#0] kill=[com.xunmeng.pinduoduo#999, com.xunmeng.pinduoduo#0, com.xunmeng.pinduoduo#998]
athena hooks installed: 1
athena swipe hooks installed: 1
```

```
{"keep":["com.termux#0","com.omarea.vtools#0","github.tornaco.android.thanos.pro#0"],"kill":["com.xunmeng.pinduoduo#999","com.xunmeng.pinduoduo#0","com.xunmeng.pinduoduo#998"]}
```

```
  * ContentProviderRecord{… io.github.lmq00.swipeclean/.ConfigProvider}
    proc=ProcessRecord{… 8735:io.github.lmq00.swipeclean/u0a465}
      Action: "io.github.lmq00.swipeclean.CONFIG_CHANGED"
```

判据：`config restored from cache` 在启动后 1 秒内出现（先于拉取）→ 开机即有名单；
`ConfigProvider` 与接收器都在 → 桥接就绪。

启动期完整日志长这样（含预期失败，判读见 docs/runbook.md）：

```
loaded: process=system, systemServer=true, api=102
swipe hooks installed: 2
app startup hooks installed: 1
athena class not found: com.oplus.athena.common.parser.athena.FilterHelper   ← athena 尚未加载，预期
config restored from cache: keep=[…] kill=[…]                                ← 开机即用上次名单（不依赖 App）
config receiver register failed (retry pending)                              ← 首次尝试，AMS 未就绪，预期
config pull failed: NullPointerException: …                                  ← 同上
athena hooks installed: 1
athena swipe hooks installed: 1
allowed provider start for io.github.lmq00.swipeclean (vendor block overridden)
config bridge ready (attempt=13)
config loaded: keep=[…] kill=[…]
```

### 步骤 4：配置是否即时生效（不改 UI 也能验）

```bash
su -c 'am broadcast -a io.github.lmq00.swipeclean.CONFIG_CHANGED'   # 触发一次拉取
su -c "grep -a SwipeClean '$LOG' | tail -5"                         # 内容变化才会有 config loaded
```

判据：UI 改配置后 **1 秒内** 日志出现新的一条 `config loaded: keep=[…] kill=[…]`，
内容与 prefs 一致。接收器收到广播后立即重新拉取。

### 步骤 5：划卡实测（**必须真划卡**）

划卡清理请求的 caller 被限定为 `com.oplus.recents`，**无法用 `am`/shell 伪造**。
手动在最近任务里划卡片，然后：

```bash
su -c "grep -a SwipeClean '$LOG' | grep -aE 'keep|kill' | tail -10"
su -c 'logcat -b events -d' | grep -a -E "am_kill" | grep -a pinduoduo
```

判据（实测样本 `com.xunmeng.pinduoduo`，本体 + 分身 998/999）：

- 不杀：`athena swipe keep: com.xunmeng.pinduoduo#998` / `swipe-up keep: com.omarea.vtools#0`，进程仍在
- 必杀：`swipe-up force kill: com.xunmeng.pinduoduo#0`，`am_kill` 首字段（userId）与配置一致
- 模块自身：`swipe-up keep: io.github.lmq00.swipeclean#0`，pid 不变

判读要点（`logcat -b events`）：

- `am_kill: [<userId>,<pid>,<procName>,…,stop <pkg> due to o-stop(0),…]` = 必杀路径生效；
  **第一个字段是 userId**，据此确认杀的是本体（`0`）还是某个分身（`998` / `999`）。
- `am_proc_start` 的 reason 字段决定进程是谁拉起的：`next-top-activity` = 用户主动打开；
  `broadcast` / `service` / `content provider` = 自启。判断「force-stop 后会不会被自动拉起」
  必须看这个字段，不能只看进程是否存在（用户手动打开会污染观察）。

### 步骤 6：改 Hook 后重启

```bash
su -c 'setprop ctl.restart zygote'      # 约 1 分钟；会杀掉 Termux/omp 会话
```

只改 UI / 只重装 APK **不需要**重启。重启后重跑步骤 3 的基线检查，
判据是出现新的 `* hooks installed` 行（重启的维护细节见 docs/development.md §改 Hook 后必须重启）。

## 4. 分身验证

分身 = 独立 user（类型 `MultiApp`，`parentId=0`），**包名与本体完全相同**，只有 uid 不同。

```bash
# 1. 枚举分身 user
su -c 'dumpsys user | grep -E "UserInfo\{[0-9]+"'
#   UserInfo{998:MultiApp:4001010} serialNo=11 isPrimary=false parentId=0
#   UserInfo{999:MultiApp:4001010} serialNo=10 isPrimary=false parentId=0

# 2. 某 user 下已安装的应用及其 uid
su -c 'pm list packages -U --user 998 | grep -i <pkg>'
#   package:com.xunmeng.pinduoduo uid:99810367

# 3. 对照本体
su -c 'pm list packages -U --user 0 | grep -i <pkg>'
#   package:com.xunmeng.pinduoduo uid:10367
```

uid 关系：`分身 uid = 本体 uid + userId × 100000`（`99810367 = 10367 + 998 × 100000`）。

**普通应用身份（无 su）也能查询同 profile group 的 user** —— 这是 UI 枚举分身可行的依据：

```bash
cmd package list packages -U --user 998    # 无 su 也有结果，uid 前缀是 998 而非 10367
```

分身划卡的验证：

```bash
# 同时打开本体与分身，只划掉其中一张卡
su -c 'logcat -b events -d' | grep -a -E "am_kill|am_proc_start" | grep -a <pkg>
```

- 划哪张卡就杀哪个 user（`am_kill` 首字段与所划的 user 一致）→ 精确生效
- 同包名的其它 user **不受影响**（判定链为什么天然 user 感知见 docs/references.md §7.3 G0 的闸门是 user 感知的）

**模块侧枚举**（已实现）：`AppRepository.loadDualApps()` 用 `LauncherApps.getProfiles()` +
`getActivityList(null, user)`，userId 由 `ApplicationInfo.uid / 100000` 反推
（该换算规则的出处见 docs/references.md §7.2 判定链取 userId）；
分身序号用 `getLauncherUserInfo(user).userSerialNumber` 排名。打开 App 后其进程日志里应有：

```
dual users=[998, 999] ordinals={999=1, 998=2} packages={998=…, 999=…}
```

列表里本体条目下会展开出 `拼多多 · 分身1` / `拼多多 · 分身2` 子项（图标带 ROM 的分身序号角标），
各自独立设置。

**测试样本**：拼多多 `com.xunmeng.pinduoduo`，user 998 / 999 各一个分身
（uid `99810367` / `99910367`），本体 uid `10367`。