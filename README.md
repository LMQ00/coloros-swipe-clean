# 划卡控制（ColorOS Swipe Clean）

ColorOS / realme UI 上自由控制「最近任务划卡能否杀死 App」的 LSPosed 模块。

系统内置了一套白名单（`remove_task_filter_pkg` / `remove_task_filter_proc` 等）决定划掉卡片后
进程是否被杀，普通用户无法修改。本模块在判定点上做运行时 Hook，提供 UI 让用户按 App 选择行为。

## 行为

| 选项 | 效果 |
| --- | --- |
| 默认 | 完全跟随系统原逻辑 |
| 划卡不杀 | 划掉卡片后进程保留（卡片照常消失） |
| 划卡必杀 | 划掉卡片后强制结束进程（即使持有前台服务） |

## 使用

- 顶部搜索框按应用名/包名过滤；右侧 **系统应用** 开关决定是否列出系统应用。
- 点击任意一行弹出选择：默认 / 划卡不杀 / 划卡必杀。
- **长按任意一行进入批量选择**：标题栏变为「已选 N 项 / 全选」，行尾箭头换成勾选框，
  底部出现 **默认 / 划卡不杀 / 划卡必杀** 三个批量按钮，一次应用到全部选中项。
  选择模式下搜索与系统应用开关仍然可用，「全选」只作用于当前筛选结果——
  因此可以「先搜索再全选」批量处理一批应用。按返回键或左上角 ✕ 退出。
- 行尾显示当前设置：未设置（默认）为弱化色，已设置为主题色。
- 改动即时生效，无需重启。

## 原理

划卡后进程会不会被杀由**两条独立路径**决定，模块两条都挂：

```
路径 A（框架）：ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)
  -> ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)
    -> OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)

路径 B（athena）：SwipeUpClearAction#e1 -> G0 -> D0（划卡决策点）
  -> FilterHelper#getStopTypeInner
  -> stopType == 2 时 utils.p.b(...) 强制结束进程
```

| 路径 | 保留 | 强杀 |
| --- | --- | --- |
| A `getRemoveTaskFilterType` | `1` / `2` | `3` |
| B `FilterHelper#getStopTypeInner` | `0` | `2` |

实测只改返回值不够：框架侧 `killProcessesForRemovedTask` 对「有 started service / 有 receiver /
非后台态」的进程只 `setWaitingToKill` 不立即杀，athena 侧 `D0` 内还有保护名单闸门，
微信这类常驻应用两条都会被放行。因此模块额外挂 `D0` 本身：必杀时直接调用 athena 自己的
force-stop（`utils.p.b`），与系统清理走同一条路。

细节、返回值语义与出处见 [`doc/athena-reverse.md`](doc/athena-reverse.md)。

## 安装

1. 安装 APK。
2. LSPosed 中启用模块。作用域会显示全部应用，其中 **系统框架** 由 `scope.list` 预勾选为推荐项。
   本模块的 Hook 全在 system_server（进程名 `system`），勾选其它应用不会生效（也无害）。
3. 重启设备。
4. **打开一次 App**：配置通过 libxposed 服务通道写入框架侧，框架只在 App 进程启动时下发该通道。
   之后每次改动名单都会实时推送。

## 构建

需要 Android SDK（platform 36 / build-tools 36）与 JDK 17：

```bash
./gradlew assembleRelease
```

APK 产物：`app/build/outputs/apk/release/app-release.apk`。
签名密钥不入库（存于 GitHub Secrets），CI 构建出的 APK 使用固定签名，因此可直接覆盖安装。
克隆仓库自行构建时没有密钥，会自动退回 debug 签名，产出的 APK 同样可安装，
但无法覆盖由官方 Release 安装的版本。

CI：推送到 `main` 后由 GitHub Actions 编译，产物在 Actions 的 Artifacts（`swipe-clean-release`）中下载。

## 兼容性

- 目标：ColorOS 16（Android 16）/ Athena 6.0.1（`versionCode 601`，构建提交 `62c260e`）。
  已在 realme UI 实机验证。
- 其它 ColorOS 版本可能因框架类名或返回值语义变化而失效；失效时模块只记录日志，不改变系统行为。

## 已知限制

- **系统应用不受名单控制**：`G0()` 把 `procDetailInfo.system == true` 的应用交给另一分支 `I0()`，
  该分支不经过 `getStopType`。UI 默认不显示系统应用，与此一致。
- **最近任务里手动锁定过的卡片**由 `isRecentLockTask` 保护，本模块不覆盖。
- 「划卡不杀」名单同时会让该应用不被 athena 的后台内存清理回收（两者共用同一判定入口）。

## 排查

模块没生效时按顺序检查：

1. LSPosed 里模块是否启用、作用域里 **系统框架** 是否勾选（必须是进程名 `system`）。
2. 日志：`/data/adb/lspd/log/modules_*.log` 里搜 `SwipeClean`，正常应有
   `swipe hooks installed: 2` / `athena hooks installed: 1` / `athena swipe hooks installed: 1`。
3. 若出现 `swipe-up keep:` / `athena swipe keep:` 但进程仍死，属未覆盖的路径，请附日志反馈。
4. **改了配置但不生效**（最常见）：配置要经 libxposed 服务通道写到框架侧，而该通道只在
   模块 App 进程启动时由框架下发。**每次重装 APK 后通道都会失效，直到重启一次**，
   表现为 UI 里改有反馈、实际行为不变。判定：`su -c 'logcat -d -s SwipeClean'` 里
   没有 `xposed service bound:` 输出。重启后打开一次 App 即可自动把名单同步过去。

开发与接手说明见 [`AGENTS.md`](AGENTS.md)。