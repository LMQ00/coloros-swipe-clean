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

只改 UI 不需要重启。软重启足以让新代码注入 system_server（但**不足以**恢复配置通道，见下）。

### 重装 APK 后的两件事

1. **`apk_path` 可能失效**（安装路径含随机目录）。LSPosed 配置库里记录的路径与实际不符时，
   手动同步：

   ```bash
   su -c 'cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/'
   # 用 python sqlite3 打开（会自动合并 -wal），更新 modules.apk_path，再写回并删除 -wal/-shm
   ```

2. **旧配置通道会失效**（改造为 ContentProvider + 广播后此限制消失）：LSPosed 每 uid
   每轮开机只下发一次 binder，重装后 uid 不变 → 不再下发 → App 写入只落本地。
   表现为 UI 里改有反馈、实际行为不变。判定：`su -c 'logcat -d -s SwipeClean'` 若无
   `xposed service bound:` 输出，即通道断了。
   **恢复方式：完整重启设备**（软重启 zygote 无效），重启后打开一次 App 即自动收敛。

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
athena hooks installed: 1
athena swipe hooks installed: 1
```

### 验证配置是否生效

**必须连 `-wal` 一起读**：LSPosed 用 WAL 模式，只复制 `modules_config.db` 会读到旧快照，
得到「配置为空」的错误结论。

```bash
su -c 'cp /data/adb/lspd/config/modules_config.db* /data/data/com.termux/files/home/tmp/'
python3 -c "
import sqlite3
c=sqlite3.connect('/data/data/com.termux/files/home/tmp/modules_config.db')
for k,d in c.execute(\"select key_name,data from module_configs where group_name='config'\"):
    print(k, d.hex())
"
```

`keep` / `kill` 是 Java `HashSet` 序列化结果，尾部 `770c <capacity> 3f400000 <size>` 后跟
`74 <len> <utf8>` 元素串。`size=0` 即空集。

更快的判据：模块日志里出现 `config loaded: keep=[...] kill=[...]`，说明 Hook 已读到新名单。

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

**测试样本**：拼多多 `com.xunmeng.pinduoduo`，user 998 / 999 各一个分身
（uid `99810367` / `99910367`），本体 uid `10367`。

## 排查清单

模块没生效时按顺序检查：

1. LSPosed 里模块是否启用、作用域里**系统框架**是否勾选（必须是进程名 `system`）。
2. 模块日志里是否有 `swipe hooks installed: 2` / `athena hooks installed: 1` /
   `athena swipe hooks installed: 1`。
3. 模块日志里是否有 `config loaded:` 且名单正确——没有则配置没到 Hook 侧（见上「重装 APK 后的两件事」）。
4. 出现 `swipe-up keep:` / `athena swipe keep:` 但进程仍死，属未覆盖路径（见
   [`architecture.md`](architecture.md) 的已知限制），附日志反馈。
5. **改了配置但不生效**：先确认不是配置通道失效（第 3 步），再确认划卡时该包在最近任务里
   是否只有一张卡（**同一 userId 下**同包多任务会被 athena 的 `J0` 跳过）。
6. **分身场景**：确认 `am_kill` 首字段是期望的 userId；若本体设置影响了分身或反之，
   说明名单 key 未带 userId（改造前的预期行为，见 [`architecture.md`](architecture.md)）。