# 开发与调试

## 构建

**本地不执行构建**：本机（Termux/aarch64）没有 Android SDK 与 `aapt2`，构建交给 CI。

CI：`.github/workflows/build.yml`，push 到 `main` 触发（`**.md` 与 `docs/**` 的纯文档改动不触发，
需要时用 `workflow_dispatch` 手动跑）。产物 artifact 名 `swipe-clean-release`。

```bash
gh run list --limit 3
gh run download <run-id> -R LMQ00/coloros-swipe-clean -D ~/tmp/apk
su -c 'cp ~/tmp/apk/swipe-clean-release/app-release.apk /data/local/tmp/swipeclean.apk'
su -c 'pm install -r /data/local/tmp/swipeclean.apk'
```

签名密钥存于 GitHub Secrets（`KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
CI 解码到 `KEYSTORE_PATH` 注入；签名固定 → 新构建可直接覆盖安装。
本地/无密钥时自动退回 debug 签名。

### 发布 Release

版本号在 `app/build.gradle.kts`（`versionCode` 递增、`versionName` 为 `X.Y`），
发版时打**轻量 tag** 并建 Release，附件名与既有版本保持一致：

```bash
git tag v1.2 <发版提交> && git push origin v1.2
cp ~/tmp/apk/swipe-clean-release/app-release.apk ~/tmp/swipe-clean-v1.2.apk
gh release create v1.2 ~/tmp/swipe-clean-v1.2.apk --title "划卡控制 v1.2" --notes-file <notes.md>
```

`gh release create` 用 `文件#标签` 只会改显示标签、**不改附件名**，附件名要按上面的方式先重命名。

## 设备侧维护

设备：realme UI / ColorOS 16（Android 16），已 root（KernelSU），LSPosed `v2.1.1-it`。

### 作用域

必须是**进程名 `system`**，不是包名 `android`。两者在 LSPosed 界面里都显示成「Android 系统」，
写错会表现为「模块已启用但完全没有日志」。

`module.prop` 的 `staticScope=false`：作用域由用户在 LSPosed 里选，`scope.list` 只作推荐预勾选。

### 改 Hook 后必须重启

```bash
su -c 'setprop ctl.restart zygote'   # 约 1 分钟
```

只改 UI 不需要重启。软重启足以让新代码注入 system_server（约 1 分钟）。
配置由 Hook 在 system_server 启动时自行拉取，重启后无需打开 App 即可恢复。

### 重装 APK 后

**`apk_path` 可能失效**（安装路径含随机目录）。LSPosed 配置库里记录的路径与实际不符时，
手动同步：

```bash
su -c 'cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/'
# 用 python sqlite3 打开（会自动合并 -wal），更新 modules.apk_path，再写回并删除 -wal/-shm
```

实测两次重装（2026-09-23）LSPosed 都自动跟上了新路径，未出现不一致；仍建议 `pm path` 核对一次。

配置通道**不再需要**任何重装后处理：旧 LSPosed remote prefs 通道的「重装后需完整重启」
限制已随 ContentProvider + 广播改造消失。

## 日志与验证

### 日志位置

| 来源 | 位置 |
| --- | --- |
| 模块内 `module.log(...)` | `/data/adb/lspd/log/modules_*.log` |
| App 内 `Log.*`（tag `SwipeClean`） | `logcat` |

```bash
su -c 'grep -a SwipeClean /data/adb/lspd/log/modules_$(ls -t /data/adb/lspd/log | grep modules | head -1)'
```

模块正常注入时应有：

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

- `config bridge ready (attempt=N)` 前的失败是**预期**的：`onSystemServerStarting` 早于 AMS 初始化，
  注册接收器与拉取 provider 都会 NPE，`ConfigBridge` 带重试（1s × 30 + 5s × 60）。
- `config restored from cache` 在**完整重启**后才关键：ColorOS 开机窗口内会拦第三方 App 启动
  （`isPreventBootStartData`），实测启动期 60 次拉取全失败、直到用户打开 App 才成功；
  有缓存就不会出现名单为空的空窗。
- 若始终只有 `config bridge not ready after N attempts` 且没有 `config restored from cache`，
  说明既拉不到、也没有缓存（例如全新安装后第一次开机）——此时名单为空（全走系统默认），
  打开一次 App 即可写入缓存。
- `config pull failed: <异常>` 只在**失败原因变化**时打一条，不刷屏。

### 验证配置是否生效

配置的唯一真相来源是模块 App 的 SharedPreferences：

```bash
su -c 'cat /data/data/io.github.lmq00.swipeclean/shared_prefs/config.xml'
```

元素形如 `<pkg>#<userId>`，两个 `<set>` 即 keep / kill 名单。例：

```xml
<set name="keep">
    <string>com.xunmeng.pinduoduo#999</string>
    <string>com.termux#0</string>
</set>
<set name="kill">
    <string>com.xunmeng.pinduoduo#0</string>
</set>
```

更快的判据：模块日志里出现 `config loaded: keep=[…] kill=[…]`，说明 Hook 已读到当前名单
（每次内容变化都会打一条）。改配置后应在 1 秒内看到新的一条。

Hook 侧另有一份落盘缓存，开机时优先读它：

```bash
su -c 'cat /data/system/swipeclean_config.json'
# {"keep":[…],"kill":[…]}
```

正常运行时它的内容与 App 的 prefs 一致（每次拉取内容变化时回写）。

**改动是否即时生效**（不重启、不打开 UI）：

```bash
su -c 'am broadcast -a io.github.lmq00.swipeclean.CONFIG_CHANGED'
# Hook 侧注册的接收器收到后立即重新拉取；随后模块日志出现新的 config loaded
```

> 旧通道时代需要连 `-wal` 一起读 LSPosed 的 `modules_config.db`——那套已废弃。
> `module_configs` 表里的 `keep`/`kill` 只是历史残留，模块不再读取。

### 验证划卡效果

```bash
# 划卡前后进程
su -c 'ps -A | grep -i <pkg>'

# 谁杀了它 / 谁拉起了它（第一个字段是 userId）
su -c 'logcat -b events -d' | grep -a -E "am_proc_start|am_kill" | grep -a <pkg>
```

判读要点：

- `am_kill: [<userId>,<pid>,<procName>,…,stop <pkg> due to o-stop(0),…]`
  = Hook 4 调用的 athena force-stop 生效；**第一个字段是 userId**，
  据此可确认杀的是本体（`0`）还是某个分身（`998` / `999`）。
- `am_proc_start` 的 reason 字段决定进程是谁拉起的：
  `next-top-activity` = 用户主动打开；`broadcast` / `service` / `content provider` = 自启。
  判断「force-stop 后会不会被自动拉起」必须看这个字段，不能只看进程是否存在
  （用户手动打开会污染观察）。

## 验证应用分身

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
- 同包名的其它 user **不受影响**：`G0` 的 `J0` 闸门同时比较 `pkgName` 与 `userId`
  （`v.java:245`），`Z0` 也带 uid

**模块侧枚举**（已实现）：`AppRepository.loadDualApps()` 用 `LauncherApps.getProfiles()` +
`getActivityList(null, user)`，userId 由 `ApplicationInfo.uid / 100000` 反推；
分身序号用 `getLauncherUserInfo(user).userSerialNumber` 排名。打开 App 后其进程日志里应有：

```
dual users=[998, 999] ordinals={999=1, 998=2} packages={998=…, 999=…}
```

列表里本体条目下会展开出 `拼多多 · 分身1` / `拼多多 · 分身2` 子项（图标带 ROM 的分身序号角标），
各自独立设置。

**测试样本**：拼多多 `com.xunmeng.pinduoduo`，user 998 / 999 各一个分身
（uid `99810367` / `99910367`），本体 uid `10367`。

## 排查清单

模块没生效时按顺序检查：

1. LSPosed 里模块是否启用、作用域里**系统框架**是否勾选（必须是进程名 `system`）。
2. 模块日志里是否有 `swipe hooks installed: 2` / `app startup hooks installed: 1` /
   `athena hooks installed: 1` / `athena swipe hooks installed: 1`。
3. 模块日志里是否有 `config restored from cache:` / `config bridge ready (attempt=N)` /
   `config loaded:` 且名单正确——只有 `config bridge not ready after N attempts` 说明既拉不到
   也没有缓存（ColorOS 拦了 provider 冷启动，见「第 5 个 Hook」）。
4. 出现 `swipe-up keep:` / `athena swipe keep:` 但进程仍死，属未覆盖路径（见
   [`architecture.md`](architecture.md) 的已知限制），附日志反馈。
5. **改了配置但不生效**：先确认不是配置通道问题（第 3 步），再确认划卡时该包在最近任务里
   是否只有一张卡（**同一 userId 下**同包多任务会被 athena 的 `J0` 跳过）。
6. **分身场景**：确认命中日志里的 key 是期望的 `<pkg>#<userId>`（如
   `athena swipe force kill: com.xunmeng.pinduoduo#0`），以及 `am_kill` 首字段是对应的 userId；
   若本体设置影响了分身或反之，说明名单 key 未带 userId。