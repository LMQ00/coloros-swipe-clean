package io.github.lmq00.swipeclean.hook

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config
import java.lang.reflect.Method

/**
 * 让 ColorOS 放行**本模块 App 自己**的 provider 冷启动。
 *
 * 为什么需要：配置通道靠 Hook 主动 `contentResolver.call()` 拉取，而 ColorOS 的
 * `OplusAppStartupManager` 会拦截第三方 App 的 provider 场景冷启动——即使调用方是
 * system_server。实测 `logcat`（调用方 uid 1000）：
 *
 * ```
 * W/OplusAppStartupManager: prevent start io.github.lmq00.swipeclean,
 *   cmp ComponentInfo{…/ConfigProvider} by contentprovider android callingUid 1000, scenePriority = 0
 * E/ActivityThread: Failed to find provider info for io.github.lmq00.swipeclean.config
 * ```
 *
 * 判定链（jadx 自 `/system/framework/oplus-services.jar`，`OplusAppStartupManager.java`）：
 *
 * ```
 * ActivityManagerService
 *   -> OplusAppStartupManager#shouldPreventStartProvider(proc, providerRecord, appInfo,
 *                                                       callingPackage, callingUid)   // 2062 ← 挂这里
 *        -> validStartupWithRestrict(providerRecord, null, 0, null, "provider")        // 2068
 *        -> handleStartProvider(providerRecord, proc)                                  // 2070
 *             （callerApp.uid <= 10000 时直接返回 false，即不拦）
 *        -> !isAllowStartFromProvider(proc, providerRecord, appInfo, …)                // 2080
 *             - isRootOrShell(callingUid)                                              // 2261（uid 1000 不算）
 *             - isDefaultAllowStart(appInfo) || isInLruProcessesLocked(appInfo.uid)    // 2325
 *             - inProtectWhiteList(pkg)                                                // 2335
 *             - isAllowAssociateByList(64, …)                                          // 2352
 *             - 全不满足 -> 2380 打上面那条日志并 return false（= 不允许启动）
 * ```
 *
 * 本 Hook 只在**厂商确实决定拦截、且目标就是本模块自己**时把结果改成「不拦截」：
 * 先执行原方法，仅当原结果为 true（要拦）才返回 false。因此
 * - 其它应用的启动策略完全不受影响（包名不匹配时直接 `proceed`）；
 * - 若将来 ROM 自己放行（例如用户手动把本 App 加进自启动白名单），本 Hook 自动退化为空操作。
 */
internal object AppStartupHooks {

    private const val TAG = ModuleMain.TAG

    private const val TARGET_CLASS = "com.android.server.am.OplusAppStartupManager"

    private const val METHOD_NAME = "shouldPreventStartProvider"

    /** 前若干次「改写拦截」打日志，避免刷屏。 */
    private const val LOG_LIMIT = 10

    private var componentNameMethod: Method? = null

    private var overridden = 0

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val clazz = runCatching { Class.forName(TARGET_CLASS, false, classLoader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, TAG, "app startup manager not found: $TARGET_CLASS")
            return
        }
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name == METHOD_NAME && method.parameterCount == 5) {
                module.hook(method).intercept { chain ->
                    // 参数顺序：0=ProcessRecord, 1=ContentProviderRecord, 2=ApplicationInfo,
                    // 3=callingPackage, 4=callingUid
                    val target = targetPackage(chain.getArg(1), chain.getArg(2))
                    if (target != Config.MODULE_PACKAGE) {
                        chain.proceed()
                    } else {
                        val prevented = chain.proceed() as? Boolean ?: false
                        if (!prevented) {
                            false
                        } else {
                            if (overridden < LOG_LIMIT) {
                                overridden++
                                module.log(
                                    Log.INFO,
                                    TAG,
                                    "allowed provider start for ${Config.MODULE_PACKAGE}" +
                                        " (vendor block overridden)",
                                )
                            }
                            false
                        }
                    }
                }
                hooked++
            }
        }
        module.log(Log.INFO, TAG, "app startup hooks installed: $hooked")
    }

    /**
     * 目标包名：优先 `ApplicationInfo#packageName`（即厂商日志里的 calledPackageName），
     * 取不到再回退 `ContentProviderRecord#getComponentName()`。
     */
    private fun targetPackage(providerRecord: Any?, appInfo: Any?): String? {
        (appInfo as? ApplicationInfo)?.packageName?.let { return it }
        if (providerRecord == null) return null
        val method = componentNameMethod ?: runCatching {
            providerRecord.javaClass.getMethod("getComponentName")
        }.getOrNull() ?: return null
        componentNameMethod = method
        return runCatching { (method.invoke(providerRecord) as? ComponentName)?.packageName }
            .getOrNull()
    }
}