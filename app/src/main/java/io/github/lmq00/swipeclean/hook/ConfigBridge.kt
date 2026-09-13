package io.github.lmq00.swipeclean.hook

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config

/**
 * Hook 侧读取模块 App 写入的名单。
 *
 * LSPosed 的 remote preferences 每次读取都会走框架，这里做 2 秒 TTL 缓存，
 * 避免判定路径上的高频调用带来额外开销。
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
            keep = prefs.getStringSet(Config.KEY_KEEP, emptySet())?.toSet() ?: emptySet()
            kill = prefs.getStringSet(Config.KEY_KILL, emptySet())?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "read remote preferences failed", t)
        }
    }
}