package io.github.lmq00.swipeclean.hook

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 划卡杀进程的判定点。
 *
 * 调用链（设备实测版本：Athena 6.0.1 / `services.jar` + `oplus-services.jar`）：
 *
 * ```
 * ActivityTaskSupervisor#killTaskProcessesIfPossible(Task)              // services.jar
 *   -> ActivityTaskSupervisorExtImpl#getRemoveTaskFilterType(proc)      // oplus-services.jar
 *     -> OplusAthenaManager#getRemoveTaskFilterType(proc)               // oplus-services.jar
 * ```
 *
 * 返回值语义（`ActivityTaskSupervisor` 中的分支）：
 * - `0`：杀，但进程持有 foreground service 时保留
 * - `1`：不杀（包级白名单）
 * - `2`：不杀（进程级白名单）
 * - `3`：强制杀，即使持有 foreground service
 *
 * 因此 `1` 即「划卡不杀」，`3` 即「划卡必杀」。
 *
 * 两处都挂：`ActivityTaskSupervisorExtImpl` 是唯一调用点，能保证拦截；
 * `OplusAthenaManager` 是真正的判定实现，覆盖其它直接调用者。
 */
internal object SwipeKillHooks {

    private const val TAG = ModuleMain.TAG

    private const val METHOD_NAME = "getRemoveTaskFilterType"

    private const val FILTER_SKIP = 1
    private const val FILTER_FORCE_KILL = 3

    /** `UserHandle.PER_USER_RANGE`：uid 里 user 部分的步长（uid = userId * 100000 + appId）。 */
    private const val PER_USER_RANGE = 100_000

    private val TARGET_CLASSES = listOf(
        "com.android.server.wm.ActivityTaskSupervisorExtImpl",
        "com.android.server.wm.OplusAthenaManager",
    )

    private var infoField: Field? = null
    private var nameField: Field? = null
    private var userField: Field? = null

    /** 前若干次判定打日志，用来确认 Hook 是否真的被划卡路径调用。 */
    private var queries = 0

    fun install(module: XposedModule, classLoader: ClassLoader) {
        var hooked = 0
        for (className in TARGET_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (clazz == null) {
                module.log(Log.WARN, TAG, "class not found: $className")
                continue
            }
            for (method in clazz.declaredMethods) {
                if (method.name == METHOD_NAME && method.parameterCount == 1) {
                    hook(module, method)
                    hooked++
                }
            }
        }
        module.log(Log.INFO, TAG, "swipe hooks installed: $hooked")
    }

    private fun hook(module: XposedModule, method: Method) {
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val proc = chain.getArg(0)
                val pkg = packageOf(proc)
                val userId = userIdOf(proc)
                val key = if (pkg == null) null else Config.key(pkg, userId)
                if (queries < 5) {
                    queries++
                    module.log(Log.INFO, TAG, "filter query: $key")
                }
                when (if (pkg == null) Config.MODE_DEFAULT else ConfigBridge.modeOf(module, pkg, userId)) {
                    Config.MODE_KEEP -> {
                        module.log(Log.INFO, TAG, "swipe-up keep: $key")
                        FILTER_SKIP
                    }
                    Config.MODE_KILL -> {
                        module.log(Log.INFO, TAG, "swipe-up force kill: $key")
                        FILTER_FORCE_KILL
                    }
                    else -> chain.proceed()
                }
            }
    }

    /** `WindowProcessController.mInfo`（ApplicationInfo），包内可见，需 setAccessible。 */
    private fun infoOf(proc: Any?): ApplicationInfo? {
        if (proc == null) return null
        val clazz = proc.javaClass
        return runCatching {
            (infoField ?: clazz.getDeclaredField("mInfo").also {
                it.isAccessible = true
                infoField = it
            }).get(proc) as? ApplicationInfo
        }.getOrNull()
    }

    /**
     * `WindowProcessController` 只暴露包级/进程级字段：`mInfo`(ApplicationInfo) 与 `mName`。
     * 两者都是包内可见，需 setAccessible。
     */
    private fun packageOf(proc: Any?): String? {
        if (proc == null) return null
        infoOf(proc)?.packageName?.let { return it }
        val clazz = proc.javaClass
        return runCatching {
            (nameField ?: clazz.getDeclaredField("mName").also {
                it.isAccessible = true
                nameField = it
            }).get(proc) as? String
        }.getOrNull()?.substringBefore(':')
    }

    /**
     * `WindowProcessController.mUserId`（实测字段，`WindowProcessController.java:122`）；
     * 取不到时回退 `mInfo.uid` 换算（`uid / PER_USER_RANGE`），再取不到按 0 处理。
     */
    private fun userIdOf(proc: Any?): Int {
        if (proc == null) return 0
        val direct = runCatching {
            (userField ?: proc.javaClass.getDeclaredField("mUserId").also {
                it.isAccessible = true
                userField = it
            }).getInt(proc)
        }.getOrNull()
        if (direct != null) return direct
        val uid = infoOf(proc)?.uid ?: return 0
        // UserHandle.getUserId(uid) 是系统 API，编译期不可见；等价于 uid / PER_USER_RANGE。
        return uid / PER_USER_RANGE
    }
}