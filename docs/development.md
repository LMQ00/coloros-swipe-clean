# 开发

本文回答——怎么构建、发布、安装、取日志、回滚。

- 怎么证明它工作（判据 + 命令 + 期望输出）：见 docs/testing.md
- 出现异常现象怎么查、坑在哪：见 docs/runbook.md

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

构建是否成功、产物是否装对版本的核对命令与期望输出见 docs/testing.md §3。

## 发布 Release

版本号在 `app/build.gradle.kts`（`versionCode` 递增、`versionName` 为 `X.Y`），
发版时打**轻量 tag** 并建 Release，附件名与既有版本保持一致：

```bash
git tag v1.2 <发版提交> && git push origin v1.2
cp ~/tmp/apk/swipe-clean-release/app-release.apk ~/tmp/swipe-clean-v1.2.apk
gh release create v1.2 ~/tmp/swipe-clean-v1.2.apk --title "划卡控制 v1.2" --notes-file <notes.md>
```

**坑**：`gh release create` 用 `文件#标签` 只会改显示标签、**不改附件名**，附件名要按上面的方式先重命名。

## 设备侧维护

设备：realme UI / ColorOS 16（Android 16），已 root（KernelSU），LSPosed `v2.1.1-it`。

### 作用域必须是进程名 `system`

必须是**进程名 `system`**，不是包名 `android`。两者在 LSPosed 界面里都显示成「Android 系统」，
写错会表现为「模块已启用但完全没有日志」（处置见 docs/runbook.md）。

`module.prop` 的 `staticScope=false`：作用域由用户在 LSPosed 里选，`scope.list` 只作推荐预勾选。

### 改 Hook 后必须重启

```bash
su -c 'setprop ctl.restart zygote'   # 约 1 分钟
```

只改 UI 不需要重启。软重启足以让新代码注入 system_server（约 1 分钟）。
配置由 Hook 在 system_server 启动时自行拉取，重启后无需打开 App 即可恢复。

重启会杀掉 Termux/omp 会话，定时重启与取消的写法见 docs/runbook.md。

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

## 安装与回滚

覆盖升级（签名固定，可直接覆盖安装）：

```bash
su -c 'cp ~/tmp/apk/swipe-clean-release/app-release.apk /data/local/tmp/swipeclean.apk'
su -c 'pm install -r /data/local/tmp/swipeclean.apk'
su -c 'dumpsys package io.github.lmq00.swipeclean | grep -aE "versionCode|versionName"'
```

回滚 = 装旧 tag 的 Release 附件（附件名形如 `swipe-clean-v<X.Y>.apk`）：

```bash
gh release download v1.1 -R LMQ00/coloros-swipe-clean -D ~/tmp/old
su -c 'cp ~/tmp/old/swipe-clean-v1.1.apk /data/local/tmp/swipeclean.apk'
su -c 'pm install -r /data/local/tmp/swipeclean.apk'
```

- 回滚后版本号递减（`versionCode` 降低），系统可能拒绝覆盖安装，此时加 `-d`：
  `su -c 'pm install -r -d /data/local/tmp/swipeclean.apk'` `[INFERENCE]`
- 重装 APK **不需要**重启设备；只有代码里的 Hook 变了才需要
  `su -c 'setprop ctl.restart zygote'`
- 名单与配置在 `/data` 持久区，覆盖安装不丢；配置的唯一真相来源见 docs/data-model.md §4. 真相来源与副本

## 日志

### 位置

| 来源 | 位置 |
| --- | --- |
| 模块内 `module.log(...)` | `/data/adb/lspd/log/modules_*.log` |
| App 内 `Log.*`（tag `SwipeClean`） | `logcat` |

```bash
su -c 'grep -a SwipeClean /data/adb/lspd/log/modules_$(ls -t /data/adb/lspd/log | grep modules | head -1)'
```

取最新日志文件名再 grep（多步操作时更稳）：

```bash
LOG=$(su -c 'ls -t /data/adb/lspd/log/modules_* | head -1' | tr -d '\r')
su -c "grep -a SwipeClean '$LOG'"
```

模块正常注入时应有哪几行、每行含义、以及哪些失败是预期的，见 docs/testing.md §3；
日志**会丢行**，判断状态不要只看日志，见 docs/runbook.md。