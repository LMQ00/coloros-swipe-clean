package io.github.lmq00.swipeclean.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed 现代 API 入口（见 META-INF/xposed/java_init.list）。
 *
 * 划卡判定发生在 `com.android.server.wm`，因此只需要 system_server 作用域。
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
    }

    companion object {
        const val TAG = "SwipeClean"
    }
}