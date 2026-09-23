# 技术栈

本文回答：这个模块用什么语言 / 构建 / 依赖，为什么不是替代方案。

类型：tech-stack。改动前先读本文件；模块结构见 [`architecture.md`](architecture.md)，
被否决方案的完整理由见 [`decisions.md`](decisions.md)。无出处标注的「替代方案」判断为
`[INFERENCE]`。

## 版本矩阵

| 项 | 值 | 为什么 | 为什么不是替代方案 |
| --- | --- | --- | --- |
| 语言 | Kotlin `2.2.21`（根 `build.gradle.kts`），要求 ≥ 2.2 | 低于 2.2 会在 libxposed classpath 下触发 FIR 崩溃（`FirIncompatibleClassTypeChecker`），编译期直接失败；2.2.21 是当前 CI 实际编译通过的版本 | 旧 Kotlin 版本不可用（同因，不是「能跑但更好」的问题）；更高版本无收益且未验证 `[INFERENCE]` |
| 构建插件 | AGP `8.10.1`（根 `build.gradle.kts`） | 当前 CI 实际编译通过的版本，与 `compileSdk 36` + JDK 17 组合已验证 | 更低版本能否吃下 `compileSdk 36` 未验证 `[INFERENCE]` |
| Gradle | `8.13`（`gradle/wrapper/gradle-wrapper.properties`，`-bin` 发行版） | 用 wrapper 固定版本，CI 与本地同版本 | 不用系统 Gradle：CI 无预装、版本不可控 `[INFERENCE]`；不用 `-all` 发行版：源码文档对构建无价值 `[INFERENCE]` |
| JDK | `17`（`sourceCompatibility`/`targetCompatibility = VERSION_17`、`jvmTarget = JVM_17`；CI `temurin` 17） | 与 AGP 8.10.1 / `compileSdk 36` 组合已验证；CI 显式 setup 17 | 11 / 21 组合未验证 `[INFERENCE]` |
| `compileSdk` | `36` | **硬要求**：libxposed 制品声明 `minCompileSdk=36`，低于 36 依赖解析即失败（`app/build.gradle.kts` 注释亦写明） | 不能降到 35 —— 制品元数据不满足 |
| `targetSdk` | `35` | 只用新 SDK 编译，运行时行为仍按 35 走，避免目标版本升级引入未验证的行为差异 | 不用 36：无功能收益 `[INFERENCE]` |
| `minSdk` | `26` | 覆盖目标设备（ColorOS / realme UI 16，实测设备 Android 16），并保住 `LauncherActivityInfo#getBadgedIcon`（API 21+）等公开 API 的可用性 | 抬高只会缩小可安装范围 `[INFERENCE]`；降低后 Hook 目标类与 UI API 组合未验证 `[INFERENCE]` |
| 框架 | libxposed 现代 API：`compileOnly("io.github.libxposed:api:101.0.1")`；`module.prop` 的 `minApiVersion` / `targetApiVersion` = `101`；入口 `META-INF/xposed/java_init.list` | 现代 API（`XposedModule` + `XposedModuleInterface`）；`compileOnly` 表示运行时由 LSPosed 提供，绝不打进 APK（源码注释：`Provided by LSPosed at runtime; never bundled into the APK`） | 不用旧 Xposed API `[INFERENCE]`；不用 `implementation`：会把框架 API 塞进 APK，与注入方冲突 |
| UI 框架 | Material 3，`com.google.android.material:material:1.12.0` | 需要 Material 3 组件；`SwipeCleanApp` 只保留 `DynamicColors`（动态取色） | 不用 Material 2 / 纯 AppCompat 主题：没有动态取色 `[INFERENCE]` |
| 其他依赖 | `androidx.core:core-ktx:1.13.1`、`androidx.appcompat:appcompat:1.7.0`、`androidx.recyclerview:recyclerview:1.3.2` | 列表（`AppListAdapter`）、`ContextCompat` 注册接收器等最小集 | 不引 Compose：只有一屏列表，收益不足 `[INFERENCE]` |
| 签名 | 密钥**不入库**；CI 从 GitHub Secrets（`KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）解码到 `KEYSTORE_PATH` 后注入；无密钥时自动退回 debug 签名 | 克隆仓库的人无需私钥也能构建出可安装的 APK；有密钥时各次构建可互相覆盖安装（源码注释：`有密钥就用固定签名…没有则退回 debug 签名`） | 不把 keystore 入库（泄漏）；不强制签名（会让 CI / 新克隆直接失败） |
| CI | GitHub Actions，`.github/workflows/build.yml`；产物 artifact 名 `swipe-clean-release`；`actions/checkout@v7`、`actions/setup-java@v6`、`gradle/actions/setup-gradle@v6`、`actions/upload-artifact@v7` | 本机不构建（见下），CI 是唯一构建通道；push `main` 触发，`**.md` 与 `docs/**` 的纯文档改动不触发（workflow 内注释：`纯文档改动不产出任何 APK 差异，不触发编译`） | 不用本地 Gradle：本机（Termux/aarch64）没有 Android SDK 与 `aapt2`；换其他 CI 无理由（仓库托管在 GitHub）`[INFERENCE]` |

## 为什么构建只在 CI

本机（Termux / aarch64）**没有 Android SDK，也没有 `aapt2`**，`assembleRelease` 无法运行，
因此本仓库不执行本地构建：编译验证 = CI 构建，安装与观察在真机完成。
流程与实测输出见 `见 docs/development.md §构建`；本机不构建已写进规则，理由见
`见 docs/decisions.md §本地不构建`。

## 被否决的制品与方案

| 制品 / 方案 | 现状 | 为什么不行 |
| --- | --- | --- |
| `io.github.libxposed:service` | **已移除**（随旧配置通道一起删除，`app/build.gradle.kts` 里不再声明） | 它带来的 `XposedProvider` / `IXposedService` binder 下发通道每个 uid 每轮开机只下发一次，重装 APK 后静默失效；配置通道已改为 App `ContentProvider` + 广播 |
| `getRemotePreferences`（Hook 侧读框架存储） | **否决** | 读的是 LSPosed 框架侧存储，不是模块 App 的 SharedPreferences；App 未注册过该 group 时 Hook 读到空集，且写入必须走服务通道 |
| `XposedServiceHelper`（App 侧注册监听拿服务） | **否决** | 依赖上面那条 binder 下发通道，与重装 APK 后静默失效同源 |

配置通道唯一性与上述否决的完整理由见 `见 docs/decisions.md §配置通道唯一`；
编译期只能用公开 API 的约束与被打回清单见 `见 docs/runbook.md §8 编译期被 CI 打回`。