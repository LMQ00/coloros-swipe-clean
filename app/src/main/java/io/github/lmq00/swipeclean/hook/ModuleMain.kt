package io.github.lmq00.swipeclean.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed 现代 API 入口（见 META-INF/xposed/java_init.list）。
 *
 * 划卡杀不杀由两处共同决定，作用域都落在 system_server（进程名 `system`）：
 * - 框架：`ActivityTaskSupervisorExtImpl` / `OplusAthenaManager#getRemoveTaskFilterType`
 * - athena：`FilterHelper#getStopTypeInner`
 */
class ModuleMain : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "loaded: process=${param.processName}, systemServer=${param.isSystemServer}, api=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        SwipeKillHooks.install(this, param.classLoader)
        // 若此时 athena 的类已可见就直接挂上；否则等 onPackageLoaded 或兜底重试。
        AthenaHooks.install(this, param.classLoader)
        AthenaHooks.installWhenReady(this)
    }

    /** athena 的类由它自己的 APK 提供，要等该包在 system_server 内加载后再挂。 */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == ATHENA_PACKAGE) {
            AthenaHooks.install(this, param.defaultClassLoader)
        }
    }

    companion object {
        const val TAG = "SwipeClean"

        private const val ATHENA_PACKAGE = "com.oplus.athena"
    }
}