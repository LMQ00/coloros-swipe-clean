# 划卡控制（ColorOS Swipe Clean）

ColorOS / realme UI 上自由控制「最近任务划卡能否杀死 App」的 LSPosed 模块。

系统内置了一套白名单（`remove_task_filter_pkg` / `remove_task_filter_proc` 等），
决定划掉卡片后进程是否被杀，普通用户无法修改。本模块在框架判定点上做运行时 Hook，
提供 UI 让用户按 App 选择行为。

## 行为

| 选项 | 效果 |
| --- | --- |
| 默认 | 完全跟随系统原逻辑 |
| 划卡不杀 | 划掉卡片后进程保留 |
| 划卡必杀 | 划掉卡片后强制结束进程（即使持有前台服务） |

## 原理

划卡后由框架决定是否杀进程：

```
ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)
  -> ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(WindowProcessController)
    -> OplusAthenaManager#getRemoveTaskFilterType(WindowProcessController)
```

返回值：`0` 杀（有前台服务则保留）、`1`/`2` 不杀、`3` 强制杀。
模块 Hook 该单点，按用户名单返回 `1` 或 `3`。

细节与出处见 [`doc/athena-reverse.md`](doc/athena-reverse.md)。

## 构建

本地需要 Android SDK（platform 35 / build-tools 35）与 JDK 17：

```bash
./gradlew assembleRelease
```

APK 产物：`app/build/outputs/apk/release/app-release.apk`。
签名使用仓库内固定 keystore（`keystore/swipeclean.jks`），因此不同构建产出的 APK 可直接覆盖安装。

CI：推送到 `main` 后由 GitHub Actions 编译，产物在 Actions 的 Artifacts 中下载。

## 安装

1. 安装 APK。
2. LSPosed 中启用模块，作用域只需勾选 **系统框架（android）**。
3. 重启设备。
4. 打开 App，为需要控制的应用选择行为。

## 兼容性

- 目标：ColorOS 16（Android 16）/ Athena 6.0.1（`versionCode 601`，构建提交 `62c260e`）。
- 其它 ColorOS 版本可能因框架类名或返回值语义变化而失效；失效时模块只记录日志，不改变系统行为。