package io.github.lmq00.swipeclean.hook

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config

/**
 * Hook 侧读取模块 App 通过 LSPosed 服务写入的名单。
 *
 * 注意这里读的是**框架侧**存储，不是模块 App 自己的 SharedPreferences：
 * App 侧必须用 `XposedService#getRemotePreferences(GROUP)` 写入，两边才通。
 *
 * 每次读取都会走框架，这里做 2 秒 TTL 缓存，避免判定路径上的高频调用带来额外开销。
 */
internal object ConfigBridge {

    private const val TAG = ModuleMain.TAG
    private const val TTL_MS = 2_000L

    private var loadedAt = 0L
    private var keep: Set<String> = emptySet()
    private var kill: Set<String> = emptySet()

    fun modeOf(module: XposedModule, pkg: String): Int {
        refresh(module)
        return when {
            keep.contains(pkg) -> Config.MODE_KEEP
            kill.contains(pkg) -> Config.MODE_KILL
            else -> Config.MODE_DEFAULT
        }
    }

    @Synchronized
    private fun refresh(module: XposedModule) {
        val now = SystemClock.elapsedRealtime()
        if (now - loadedAt < TTL_MS) return
        loadedAt = now
        try {
            val prefs = module.getRemotePreferences(Config.GROUP)
            val newKeep = prefs.getStringSet(Config.KEY_KEEP, emptySet())?.toSet() ?: emptySet()
            val newKill = prefs.getStringSet(Config.KEY_KILL, emptySet())?.toSet() ?: emptySet()
            if (newKeep != keep || newKill != kill) {
                module.log(Log.INFO, TAG, "config loaded: keep=$newKeep kill=$newKill")
            }
            keep = newKeep
            kill = newKill
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "read remote preferences failed", t)
        }
    }
}