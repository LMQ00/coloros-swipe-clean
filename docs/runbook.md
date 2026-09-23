# 排障

本文回答——出故障怎么查、坑在哪。

- 怎么证明它正常工作、期望输出长什么样：见 docs/testing.md
- 构建 / 安装 / 重启等维护动作：见 docs/development.md
- 未做的事 / 已知缺口不在这里：见 docs/交接文档.md

## 0. 先确认模块真的在跑

三条判据（全部可观察，缺一不可）：

1. LSPosed 里模块**已启用**，且作用域勾选的是**进程名 `system`**（界面显示「Android 系统」）。
2. 模块日志里有 4 条安装行：`swipe hooks installed: 2`、`app startup hooks installed: 1`、
   `athena hooks installed: 1`、`athena swipe hooks installed: 1`，以及
   `loaded: process=system, systemServer=true, api=102`。
3. 配置侧：`config restored from cache:` / `config bridge ready (attempt=N)` / `config loaded:`。

挂点位置与数量见 docs/architecture.md §判定链与 Hook 点。

## 1. 模块已启用但完全没有日志

- **现象**：LSPosed 里模块显示已启用，`modules_*.log` 里没有任何 SwipeClean 行。
- **判据**：作用域写成了**包名 `android`**。两者在 LSPosed 界面里都显示成「Android 系统」，
  但只有进程名 `system` 会注入。
- **处置**：把作用域改成进程名 `system`，然后
  `su -c 'setprop ctl.restart zygote'`（约 1 分钟）。

## 2. LSPosed modules 日志丢行

- **现象**：应出现的 `config loaded` / `config bridge ready` 行缺失，而**同线程更早的行在**
  （实测 `12:28:14` 这两条未落盘）。
- **判据**：日志不是完整记录，不能只靠它判断通道状态。
- **处置**：改用下面两条命令确认状态，并用缓存文件 mtime 交叉验证是否刚拉取过：

```bash
su -c 'dumpsys activity providers' | grep -a -A3 'io.github.lmq00.swipeclean/.ConfigProvider'
su -c 'dumpsys activity broadcasts' | grep -a 'io.github.lmq00.swipeclean.CONFIG_CHANGED'
su -c 'ls -l /data/system/swipeclean_config.json'
```

## 3. `config bridge not ready after N attempts`

- **现象**：日志里始终只有 `config bridge not ready after N attempts`，
  既没有 `config restored from cache`，也没有 `config loaded`。
- **判据**：既拉不到、也没有缓存（例如全新安装后第一次开机）→ 名单为空，划卡全走系统默认。
- **处置**：打开一次 App 即可写入缓存（App 写 prefs → 广播 → Hook 拉取成功 → 回写缓存文件）。
  之后完整重启就有 `config restored from cache`。

相关：`config bridge ready (attempt=N)` **之前**的失败是**预期**的：

- `config receiver register failed (retry pending)` / `config pull failed: NullPointerException: …`
  —— `onSystemServerStarting` 早于 AMS 初始化，此时注册接收器与拉取 provider 都会 NPE，
  `ConfigBridge` 带重试（1s × 30 + 5s × 60）。实测本回调 22:51:03.958 失败，AMS 就绪约 22:51:04.3。
- `config pull failed: <异常>` 只在**失败原因变化**时打一条，不刷屏。
- `athena class not found: com.oplus.athena.common.parser.athena.FilterHelper`
  —— athena 尚未加载，预期；之后由 `onPackageLoaded` 补挂。

## 4. 开机窗口内拉取全失败

- **现象**：完整重启后启动期 60 次拉取全部失败，直到用户打开 App 才成功。
- **判据**：ColorOS 拦开机窗口内的第三方 App 启动，闸门是
  `OplusAppStartupManager#isPreventBootStartData()`（`OplusAppStartupManager.java:4082`，
  默认 `preventDuration = 30s`，名单 `BOOT_PREVENT_START_APPLIST`），**不在 Hook 5 覆盖范围**
  （Hook 5 只覆盖 provider 冷启动，见 docs/architecture.md §第 5 个 Hook）。
- **不要误判**：**不是等固定时长就放行**——本会话曾误判为「约 5 分钟」，实为用户恰好那时打开了 App。
- **处置**：靠 `ConfigBridge` 的开机缓存绕过，保证 `/data/system/swipeclean_config.json` 存在即可；
  功能上不受影响。

## 5. 划卡无效 / 无法伪造划卡

- **现象**：想用 `am` / shell 触发一次划卡，失败或毫无反应。
- **判据**：清理请求的 caller 被限定为 `com.oplus.recents`，**无法伪造** → 必须手动在最近任务里划卡。
- 其它「划卡无效」的判读：

| 现象 | 判据 | 处置 |
| --- | --- | --- |
| 有 `swipe-up keep:` / `athena swipe keep:` 但进程仍死 | 属未覆盖路径（已知限制见 docs/architecture.md §已知限制） | 附日志反馈 |
| 改了配置但划卡行为不变 | 见 §7 | 见 §7 |
| 同包多任务时划一张卡不杀进程 | 同一 userId 下该包还有其它任务时 athena 的 `J0` 跳过整段，属系统既有行为，本模块不介入 | 无（见 docs/references.md §7.3 G0 的闸门是 user 感知的） |

## 6. UI 自动化与截图在本 ROM 的限制

| 现象 | 判据 | 处置 |
| --- | --- | --- |
| `uiautomator dump` 无输出 | 返回 rc=1 且无输出，本 ROM 不可用 | 改用截图 + 手工坐标 `[INFERENCE]` |
| 息屏时 `input` / 截图无效 | 处于 `Dozing` 状态 | 做 UI 自动化前先确认亮屏 `[INFERENCE]` |
| 点击/滑动落到错误位置 | `NotificationShade` 抢焦点 | 先确认没有下拉通知 `[INFERENCE]` |
| 截图写不进去 | `/data/local/tmp` 对 Termux uid **不可写** | 截图用 `screencap -p`，**输出必须写到 `~/tmp`**：`screencap -p ~/tmp/screen.png` |

## 7. 名单改了不生效

按顺序查：

1. 先确认不是配置通道问题：手动触发一次拉取，看是否出现新的 `config loaded`：

   ```bash
   su -c 'am broadcast -a io.github.lmq00.swipeclean.CONFIG_CHANGED'
   ```

   详见 docs/testing.md §3 步骤 4。
2. 确认划卡时该包在最近任务里**只有一张卡**（**同一 userId 下**同包多任务会被 athena 的 `J0` 跳过）。
3. 确认命中日志里的 key 带了 userId：本体设置影响到分身（或反之）说明 key 未带 userId；
   key 格式见 docs/data-model.md §1. 名单模型。实测日志形如
   `athena swipe force kill: com.xunmeng.pinduoduo#0`、`athena swipe keep: com.xunmeng.pinduoduo#998`。
4. 确认缓存与真相源一致：`/data/system/swipeclean_config.json` 只是副本。App 被卸载且缓存仍在时，
   模块会继续按最后一次名单执行（真相来源与副本见 docs/data-model.md §4. 真相来源与副本）。

## 8. 编译期被 CI 打回

已被 CI 打回三次，集中在**非公开 API 与版本下限**（为什么这些版本见 docs/tech-stack.md）：

| 坑 | 原因 / 替代 |
| --- | --- |
| `Bundle#putStringSet/getStringSet` | 不是公开 API，CI 的 `android-36/android.jar` 里没有 → 用 `putStringArray/getStringArray` |
| `UserHandle#of/getUserId/myUserId/getIdentifier` | 同上 → 用 `uid / PER_USER_RANGE`；需要 `UserHandle` 实例时只能用 `LauncherApps#getProfiles()` 给的 |
| `Process#SYSTEM_UID` | 同上 → 写死字面量 `1000` |
| `LauncherUserInfo#getUserSerialNumber()` 返回 `Int` | 不是 Long，比较前 `?.toLong()` |
| `runCatching{…}.getOrDefault(-1L)` 类型推断 | 会被推成 `Nothing` → 用显式类型或 `getOrNull()` |
| `compileSdk` 必须是 36 | libxposed `api` 制品声明 `minCompileSdk=36`（`targetSdk` 仍 35） |
| Kotlin 必须 ≥ 2.2 | 低版本在该 classpath 下触发 FIR 崩溃（`FirIncompatibleClassTypeChecker`） |
| 已验证公开可用 | `LauncherActivityInfo#getBadgedIcon(int)`、`LauncherApps#getProfiles/getActivityList/getLauncherUserInfo`、`UserManager#getUserProfiles`、`SharedPreferences#getStringSet` |

## 9. 运行时系统行为坑

| 坑 | 说明 / 处置 |
| --- | --- |
| LSPosed 作用域必须是进程名 `system`，不是包名 `android` | 见 §1 |
| `onSystemServerStarting` 早于 AMS 就绪 | 此时注册接收器与 `resolver.call()` 都会 NPE，**必须**带重试（`ConfigBridge.install` 的 1s×30 + 5s×60）；不要「修掉」重试 |
| ColorOS 会拦第三方 App 的 provider 冷启动 | 即使 caller 是 uid 1000；靠 Hook 5（`AppStartupHooks`）放行，**只对本模块生效，不得放宽到其它包** |
| 开机窗口内 ColorOS 还拦第三方 App 启动 | 闸门 `isPreventBootStartData`，不在 Hook 5 覆盖范围；靠开机缓存绕过，见 §4 |
| 名单 key 必须带 userId | 分身 userId 实测有 998、999 多个值，**不得硬编码**（格式见 docs/data-model.md §1. 名单模型） |
| 不介入 athena 的 `G0`/`J0` | 同包名不同 user 互不影响，属系统既有行为（判定链见 docs/references.md §7.3 G0 的闸门是 user 感知的；userId 取值见 §7.2 判定链取 userId） |
| 必杀路径不要改 | 走 athena `utils.p.b` force-stop 已实测有效；改成模块侧直调 `forceStopPackageAsUser` 属未验证路径（理由见 docs/decisions.md §1. 必杀走 athena 自己的 force-stop（`utils.p.b`）） |
| 判断「当前名单是什么」时看错文件 | `/data/system/swipeclean_config.json` 只是副本；App 卸载后缓存仍在，模块会继续按最后一次名单执行（真相来源与副本见 docs/data-model.md §4. 真相来源与副本） |
| 本机不执行构建 | Termux/aarch64 无 Android SDK、无 `aapt2` |

## 10. 环境坑

| 现象 | 判据 / 处置 |
| --- | --- |
| 重启杀掉了 Termux/omp 会话 | `su -c 'setprop ctl.restart zygote'` 会连带杀掉本机会话。定时重启用 `nohup sh -c "sleep N; setprop ctl.restart zygote" &`；取消要按 pid `kill -9`（`pkill -f "sleep N"` 不可靠） |
| 本机到 GitHub 间歇不可用 | push / `gh run download` 要带重试循环 |
| 重装后 LSPosed 记录路径与实际不符 | 核对/修复见 docs/development.md §重装 APK 后（实测两次重装都自动跟上，仍建议 `pm path` 核对一次） |