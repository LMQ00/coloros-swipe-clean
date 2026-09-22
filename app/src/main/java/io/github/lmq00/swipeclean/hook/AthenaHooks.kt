package io.github.lmq00.swipeclean.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * athena 侧的划卡判定。
 *
 * 框架（`ActivityTaskSupervisor`）只负责移除任务，真正 force-stop 进程的是 athena
 * 自己的清理动作：
 *
 * ```
 * com.oplus.recents --REQUEST_CLEAR_SPEC_APP(type=13)-->
 *   com.oplus.athena.systemservice.action.prockill.clear.d#G0(Bundle)
 *     -> ...clear.v (SwipeUpClearAction)#e1(Bundle)
 *          -> G0(String,...)   v.java:134-190
 *               - Z0(): 正在通话 / J0(): 该包还有其它任务 -> 跳过
 *                 （两者都比较 pkgName 与 userId：Z0 用 t0.o.b(pkgName, uid)、
 *                  J0 比较 recentTaskInfo.userId，同包名不同 user 互不影响，故本模块不介入）
 *               - 系统应用 -> I0()，普通应用 -> D0()
 *          -> E0()             v.java:121  统一移除任务卡片（与杀不杀无关）
 *        -> D0(...)            v.java:97-120   ← 本模块挂这里
 *             int stopType = FilterHelper.getInstance().getStopType(procDetailInfo, aVar);
 *             stopType == 2 -> utils.p.b(...) -> j1.h.g(...) -> 强制结束进程
 * ```
 *
 * 两处 Hook：
 *
 * 1. `FilterHelper#getStopTypeInner` —— `getStopType`（2 参/3 参两个重载）的共同实现。
 *    名单内返回 `0`（保留）。这一处同时覆盖内存清理、深度清理等其它调用方。
 * 2. `...clear.v#D0` —— 划卡决策点本身（只有 `G0` 一个调用者）。
 *    保留名单直接跳过；必杀名单由本模块**直接调用 athena 自己的 force-stop**
 *    （`com.oplus.athena.systemservice.utils.p.b`）。
 *
 * 为什么必杀不能只靠 `getStopType` 返回 `2`：`D0` 里还有两道闸门
 * （`t0()` 的保护名单、`aVar.e()`/`O0()` 的最近任务锁），实测微信会被拦掉，
 * 且框架侧 `killProcessesForRemovedTask` 对「有 started service / 有 receiver /
 * 非后台态」的进程只 `setWaitingToKill` 而不立即杀。直接调用 athena 自己的
 * force-stop 才和系统「清理」语义一致。
 *
 * 跳过 `D0` 不会影响卡片移除：任务 id 在 `G0` 里就已登记进 `f1446s`，
 * 由 `e1` 末尾的 `E0()` 统一移除。
 */
internal object AthenaHooks {

    private const val TAG = ModuleMain.TAG

    private const val TARGET_CLASS = "com.oplus.athena.common.parser.athena.FilterHelper"

    private const val ATHENA_PACKAGE = "com.oplus.athena"

    private const val METHOD_NAME = "getStopTypeInner"

    /** 划卡动作类（`v` 是该 APK 的混淆名，日志里其 TAG 为 `SwipeUpClearAction`）。 */
    private const val SWIPE_CLASS = "com.oplus.athena.systemservice.action.prockill.clear.v"

    private const val SWIPE_METHOD = "D0"

    private const val PROC_DETAIL_INFO = "com.oplus.app.athena.ProcDetailInfo"

    /** athena 自己的 force-stop 封装。 */
    private const val FORCE_STOP_HELPER = "com.oplus.athena.systemservice.utils.p"

    /** `getStopType` 返回值：保留进程。 */
    private const val STOP_KEEP = 0

    /** `getStopType` 返回值：强制结束进程。 */
    private const val STOP_FORCE_KILL = 2

    private val installed = AtomicBoolean(false)

    private var pkgField: Field? = null
    private var userField: Field? = null
    private var forceStopMethod: Method? = null
    private var systemContext: Context? = null

    /**
     * 兜底：`onPackageLoaded` 未按预期回调时，直接从 `ActivityThread#mPackages` 取
     * athena 的 `LoadedApk` ClassLoader 重试。system_server 内多包共存，这条路径稳定。
     */
    fun installWhenReady(module: XposedModule) {
        Thread {
            for (attempt in 1..30) {
                if (installed.get()) return@Thread
                val classLoader = athenaClassLoader()
                if (classLoader != null) {
                    install(module, classLoader)
                    if (installed.get()) return@Thread
                }
                Thread.sleep(2_000L)
            }
            module.log(Log.WARN, TAG, "athena classloader unavailable after retries")
        }.apply { isDaemon = true }.start()
    }

    private fun athenaClassLoader(): ClassLoader? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null) ?: return null
        val packages = activityThread.getDeclaredField("mPackages")
            .apply { isAccessible = true }
            .get(current) as? Map<*, *> ?: return null
        val reference = packages[ATHENA_PACKAGE] as? java.lang.ref.WeakReference<*> ?: return null
        val loadedApk = reference.get() ?: return null
        loadedApk.javaClass.getMethod("getClassLoader").invoke(loadedApk) as? ClassLoader
    }.getOrNull()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val clazz = runCatching { Class.forName(TARGET_CLASS, false, classLoader) }.getOrNull()
        if (clazz == null) {
            installed.set(false)
            module.log(Log.WARN, TAG, "athena class not found: $TARGET_CLASS")
            return
        }
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name == METHOD_NAME && method.parameterCount == 2) {
                hookStopType(module, method)
                hooked++
            }
        }
        module.log(Log.INFO, TAG, "athena hooks installed: $hooked")

        resolveForceStop(classLoader)
        installSwipeHook(module, classLoader)
    }

    /** `getStopTypeInner`：名单内返回 0（保留），其余交回系统。 */
    private fun hookStopType(module: XposedModule, method: Method) {
        module.hook(method).intercept { chain ->
            val info = chain.getArg(0)
            val pkg = packageOf(info)
            val userId = userOf(info)
            val key = if (pkg == null) null else Config.key(pkg, userId)
            when (if (pkg == null) Config.MODE_DEFAULT else ConfigBridge.modeOf(module, pkg, userId)) {
                Config.MODE_KEEP -> {
                    module.log(Log.INFO, TAG, "athena keep: $key")
                    STOP_KEEP
                }
                Config.MODE_KILL -> {
                    module.log(Log.INFO, TAG, "athena force kill: $key")
                    STOP_FORCE_KILL
                }
                else -> chain.proceed()
            }
        }
    }

    /** `SwipeUpClearAction#D0`：划卡决策点。 */
    private fun installSwipeHook(module: XposedModule, classLoader: ClassLoader) {
        val clazz = runCatching { Class.forName(SWIPE_CLASS, false, classLoader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, TAG, "swipe class not found: $SWIPE_CLASS")
            return
        }
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name == SWIPE_METHOD &&
                method.parameterCount == 5 &&
                method.parameterTypes.last().name == PROC_DETAIL_INFO
            ) {
                module.hook(method).intercept { chain ->
                    val info = chain.getArg(4)
                    val pkg = packageOf(info)
                    val userId = userOf(info)
                    val key = if (pkg == null) null else Config.key(pkg, userId)
                    val mode = if (pkg == null) {
                        Config.MODE_DEFAULT
                    } else {
                        ConfigBridge.modeOf(module, pkg, userId)
                    }
                    when {
                        mode == Config.MODE_KEEP -> {
                            module.log(Log.INFO, TAG, "athena swipe keep: $key")
                            null
                        }
                        mode == Config.MODE_KILL && pkg != null -> {
                            module.log(Log.INFO, TAG, "athena swipe force kill: $key")
                            forceStopAsync(pkg, userId)
                            null
                        }
                        else -> chain.proceed()
                    }
                }
                hooked++
            }
        }
        module.log(Log.INFO, TAG, "athena swipe hooks installed: $hooked")
    }

    private fun resolveForceStop(classLoader: ClassLoader) {
        forceStopMethod = runCatching {
            Class.forName(FORCE_STOP_HELPER, false, classLoader).getMethod(
                "b",
                Context::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            )
        }.onFailure { Log.w(TAG, "athena force-stop helper not found", it) }.getOrNull()

        systemContext = ModuleMain.systemContext()
    }

    /**
     * 直接走 athena 自己的 force-stop（`utils.p.b` -> `j1.h.g` -> `OplusAthenaAmManager`
     * 的 forceStopWithReason，失败再退回 `ActivityManager#forceStopPackageAsUser`）。
     * 放到后台线程，避免在划卡流程里同步调用造成重入。
     */
    private fun forceStopAsync(pkg: String, userId: Int) {
        val method = forceStopMethod ?: return
        val context = systemContext ?: return
        Thread {
            runCatching { method.invoke(null, context, pkg, userId, 0, 0, null, "swipe clean") }
                .onFailure { Log.w(TAG, "athena force-stop failed: $pkg", it) }
        }.apply { isDaemon = true }.start()
    }

    /** `ProcDetailInfo#pkgName` 是 public 字段，无需 setAccessible。 */
    private fun packageOf(info: Any?): String? {
        if (info == null) return null
        val field = pkgField ?: runCatching { info.javaClass.getField("pkgName") }
            .onFailure { Log.w(TAG, "ProcDetailInfo.pkgName not found", it) }
            .getOrNull() ?: return null
        pkgField = field
        return runCatching { field.get(info) as? String }.getOrNull()
    }

    /** `ProcDetailInfo#userId` 同样是 public 字段。 */
    private fun userOf(info: Any?): Int {
        if (info == null) return 0
        val field = userField ?: runCatching { info.javaClass.getField("userId") }
            .getOrNull() ?: return 0
        userField = field
        return runCatching { field.getInt(info) }.getOrDefault(0)
    }
}