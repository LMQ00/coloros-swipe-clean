package io.github.lmq00.swipeclean.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * athena 侧的划卡清理判定。
 *
 * 框架（`ActivityTaskSupervisor`）只是移除任务，真正 force-stop 进程的是 athena
 * 自己的清理动作：
 *
 * ```
 * com.oplus.recents --REQUEST_CLEAR_SPEC_APP(type=13)-->
 *   com.oplus.athena.systemservice.action.prockill.clear.d#G0(Bundle)
 *     -> ...clear.v (SwipeUpClearAction)
 *        -> D0(...) v.java:97-120
 *             int stopType = FilterHelper.getInstance().getStopType(procDetailInfo, aVar);
 *             stopType == 2 -> com.oplus.athena.systemservice.utils.p.b(...)  // force-stop
 *             stopType == 0 -> 不做任何事
 * ```
 *
 * `getStopTypeInner` 是 `getStopType`（2 参、3 参两个重载）的共同实现，因此只需挂这一处：
 *
 * - `0`：保留（各调用方语义一致：`l.java` / `i.java` / `b.java` 中 0 都走「保留」分支）
 * - `2`：强杀
 *
 * athena 的系统服务跑在 `android:process="system"`，与 system_server 同进程，
 * 所以作用域 `system` 即可覆盖。
 */
internal object AthenaHooks {

    private const val TAG = ModuleMain.TAG

    private const val TARGET_CLASS = "com.oplus.athena.common.parser.athena.FilterHelper"

    private const val ATHENA_PACKAGE = "com.oplus.athena"

    private const val METHOD_NAME = "getStopTypeInner"

    /** `getStopType` 返回值：保留进程。 */
    private const val STOP_KEEP = 0

    /** `getStopType` 返回值：强制结束进程。 */
    private const val STOP_FORCE_KILL = 2

    private val installed = AtomicBoolean(false)

    private var pkgField: Field? = null

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
                hook(module, method)
                hooked++
            }
        }
        module.log(Log.INFO, TAG, "athena hooks installed: $hooked")
    }

    private fun hook(module: XposedModule, method: Method) {
        module.hook(method).intercept { chain ->
            val pkg = packageOf(chain.getArg(0))
            when (if (pkg == null) Config.MODE_DEFAULT else ConfigBridge.modeOf(module, pkg)) {
                Config.MODE_KEEP -> {
                    module.log(Log.INFO, TAG, "athena keep: $pkg")
                    STOP_KEEP
                }
                Config.MODE_KILL -> {
                    module.log(Log.INFO, TAG, "athena force kill: $pkg")
                    STOP_FORCE_KILL
                }
                else -> chain.proceed()
            }
        }
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
}