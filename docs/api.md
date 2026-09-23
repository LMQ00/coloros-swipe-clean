# API 契约

**本文回答——模块与外部的契约是什么：provider / 广播 / 名单 key / mode 值 / 信任边界。**

两端：写入端是模块 App 的 UI（`ConfigStore`，进程 `io.github.lmq00.swipeclean`）；
读取端是注入 system_server 的 Hook（`ConfigBridge`，uid 1000）。两者不是同一份存储，
靠 provider `call("get")` + 配置广播同步。

## 1. ConfigProvider 契约

| 项 | 值 | 出处 |
| --- | --- | --- |
| 类 | `ConfigProvider`（`ConfigProvider.kt`） | `AndroidManifest.xml` `<provider android:name=".ConfigProvider">` |
| authority | `io.github.lmq00.swipeclean.config`（`Config.AUTHORITY` = `MODULE_PACKAGE + ".config"`） | `Config.kt`；manifest 里 `android:authorities` 必须逐字一致 |
| `exported` | `true`（system_server 经 AMS 调用，必须导出） | `AndroidManifest.xml` |
| 方法名 | `get`（`Config.METHOD_GET`） | `Config.kt` |
| 调用形状 | `contentResolver.call(Uri.parse("content://" + Config.AUTHORITY), Config.METHOD_GET, null, null)` | `ConfigBridge.kt` |
| 返回值 | `Bundle`，键 `keep` / `kill`（`Config.KEY_KEEP` / `Config.KEY_KILL`），值类型 **`StringArray`** | `ConfigProvider.call` |
| 拒绝条件 | `Binder.getCallingUid() != 1000` → 记 `rejected config call from uid <uid>` 并返回 `null`；`method != "get"` → 返回 `null` | `ConfigProvider.call` |
| 其它操作 | `query` / `getType` / `insert` / `delete` / `update` 一律抛 `UnsupportedOperationException("config provider only supports call()")` | `ConfigProvider.kt` |
| `onCreate` | 返回 `true`，不做初始化 | `ConfigProvider.onCreate` |
| 首次调用副作用 | `ConfigStore.needsMigration(context)` 为真时执行 `ConfigStore.migrate(context, AppRepository.loadDualApps(context))`，让 Hook 不打开 UI 也能拿到迁移后的名单 | `ConfigProvider.call` |

```kotlin
// 返回的 Bundle 形状（元素编码见 docs/data-model.md）
Bundle()
  .putStringArray("keep", keepSet.toTypedArray())
  .putStringArray("kill", killSet.toTypedArray())
```

- `SYSTEM_UID = 1000` 是字面量常量，不引用 `android.os.Process.SYSTEM_UID`（后者在 SDK 里是系统 API，编译期不可见）。
- 用 `putStringArray` / `getStringArray` 而**不是** `putStringSet` / `getStringSet`：后者不是公开 API，CI 的 `android-36/android.jar` 里不存在。编译期限制清单见 `见 docs/runbook.md §8. 编译期被 CI 打回`。

**Bundle 里传的是什么形状**：`StringArray`，每个元素是 `<pkg>#<userId>`。
key 格式的定义、迁移规则与取值实测见 `见 docs/data-model.md §名单模型`。

## 2. 配置广播

| 项 | 值 |
| --- | --- |
| action | `io.github.lmq00.swipeclean.CONFIG_CHANGED`（`Config.ACTION_CONFIG_CHANGED`） |
| 发送方 | `ConfigStore.notifyChanged(context)`：`setModes` 落盘后发一次，`migrate` 成功后发一次 |
| 发送失败 | 只记 `broadcast config change failed`，不影响落盘；Hook 侧 2 秒 TTL 兜底 |
| 接收方 | `ConfigBridge.registerReceiver`，`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` |
| 接收行为 | 收到即起后台线程 `pull()`（广播在主线程派发，拉取不进主线程，避免阻塞 system_server） |
| 发送方校验 | **不校验**。任意应用都能触发一次重新拉取，但不构成提权（理由见 §5） |

`RECEIVER_EXPORTED` 是必需的：system_server 必须收得到。
手动触发一次拉取（验证用）见 `见 docs/testing.md §3 步骤 4`。

## 3. mode 值语义

以 `Config.kt` 为准：

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `MODE_DEFAULT` | `0` | 跟随系统默认行为（模块不改写） |
| `MODE_KEEP` | `1` | 划卡时不杀死该应用 |
| `MODE_KILL` | `2` | 划卡时强制停止该应用 |

- 这是**模块内部**的 mode 值，不是框架 / athena 的返回值。mode → Hook 返回值的映射见 `见 docs/architecture.md §判定链与 Hook 点`。
- 名单 → mode 的换算（`ConfigStore.modeMap`）：`keep` 集合里的 key 记为 `MODE_KEEP`，`kill` 集合里的记为 `MODE_KILL`，都不在则为 `MODE_DEFAULT`；两处都出现时 **`kill` 覆盖 `keep`**（`modeMap` 先填 keep 再填 kill）。
- 写入侧 `ConfigStore.setModes` 先把这个 key 从两组都移除再加入目标组，因此正常情况下同一 key 只存在于一组。

## 4. Hook 侧消费方式

入口：`ConfigBridge.modeOf(module, pkg, userId): Int`（`hook/ConfigBridge.kt`）。判定顺序：

1. `pkg == Config.MODULE_PACKAGE` → 直接返回 `MODE_KEEP`（在刷新逻辑之前，模块自身永不被划卡杀掉；UI 里不列出本模块）。
2. 已有可用名单（`loaded`）→ 走 2 秒 TTL 惰性刷新：过期时起后台线程 `pull()`，**本次判定仍用当前缓存**（非阻塞）。
3. 从未 loaded（例如 App 被卸载且从未拉取成功）且距上次 `loadedAt` ≥ 2 秒 → **同步**补拉一次，避免拿空名单判定（`pull` 每次都刷新 `loadedAt`，天然限频）。
4. 用 `Config.key(pkg, userId)` 匹配：命中 `keep` → `MODE_KEEP`，命中 `kill` → `MODE_KILL`，否则 `MODE_DEFAULT`。

TTL 与启动期重试：

| 常量 | 值 | 作用 |
| --- | --- | --- |
| `TTL_MS` | `2000L` | 判定路径上的惰性刷新阈值 |
| `RETRY_FAST` | `30` | 启动期前 30 次重试间隔 1 秒 |
| `RETRY_TOTAL` | `90` | 之后每 5 秒一次，共 90 次（约 5.5 分钟） |
| `CACHE_FILE` | `/data/system/swipeclean_config.json` | Hook 侧落盘缓存 |

`install()` 顺序：先 `restoreFromCache`（纯文件 I/O，不依赖 AMS 与 App），再起线程重试「注册接收器 + 拉取」，
直到两者都成功 → 记 `config bridge ready (attempt=N)`；用尽次数仍失败 → 记
`config bridge not ready after N attempts (receiver=<b> pulled=<b> loaded=<b>); 2s TTL fallback`（不静默）。

失败降级：

- `call` 抛异常或返回 `null` → 沿用上次成功缓存；失败原因**变化时**才打一条
  `config pull failed: <SimpleName: message>` 或 `config pull failed: provider unavailable (call returned null)`，既不刷屏又能看出为什么失败。
- 拉取成功且内容与当前缓存不同 → 打 `config loaded: keep=[…] kill=[…]` 并回写缓存文件。
- **绝不拿空名单静默判定**：从未成功过时先同步补拉；补拉也失败则按空名单走系统默认，并留有上面的失败日志。

## 5. 信任边界

| 边界 | 机制 | 说明 |
| --- | --- | --- |
| provider 调用方 | `Binder.getCallingUid() == 1000` | uid 来自内核 Binder 标记，调用方无法伪造；非 1000 记日志并返回 `null` |
| provider 冷启动 | 第 5 个 Hook `AppStartupHooks` | ColorOS 的启动管控会拦第三方 App 的 provider 场景冷启动（**即使 caller 是 uid 1000**）；该 Hook 只当目标包名 == 本模块时把「要拦」改写为放行，其它包一律 `proceed()`。见 `见 docs/architecture.md §第 5 个 Hook：放行自身 provider 冷启动（配置通道的前提）` |
| Hook 注入范围 | LSPosed 作用域 = 进程名 `system`（`META-INF/xposed/scope.list`，`staticScope=false`） | 配置读取端只存在于 system_server；选错表现为「模块已启用但完全没有日志」 |
| 广播接收端 | 不校验来源（`RECEIVER_EXPORTED`） | 任意应用可触发一次重新拉取；拉取目标固定为本模块 provider，且 provider 只接受 uid 1000，最坏结果是多一次拉取 → 因此不加签名权限 |
| 缓存文件 | `/data/system/swipeclean_config.json`，system_server 可写 | App 卸载后缓存仍在，模块继续按最后一次名单执行（真相来源仍是 App，见 `见 docs/data-model.md §真相来源与副本`） |

配置通道唯一性（不再引入 LSPosed remote prefs / 服务通道）的理由与时间证据见 `见 docs/decisions.md §2. 配置通道唯一`。