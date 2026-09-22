package io.github.lmq00.swipeclean.hook

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedModule
import io.github.lmq00.swipeclean.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook 侧配置读取：直接向模块 App 的 [io.github.lmq00.swipeclean.ConfigProvider] 拉取
 * （`contentResolver.call("get")`）。
 *
 * 两个触发点，同一条拉取路径：
 * - 判定路径上的惰性刷新（2 秒 TTL；非阻塞——过期时起后台线程拉取，本次判定仍用当前缓存）
 * - 收到 App 的配置变更广播后立即拉取
 *
 * 拉取失败沿用上次成功的缓存并记日志，绝不静默失败。
 */
internal object ConfigBridge {

    private const val TAG = ModuleMain.TAG
    private const val TTL_MS = 2_000L

    private val URI = Uri.parse("content://" + Config.AUTHORITY)

    @Volatile
    private var resolver: ContentResolver? = null

    @Volatile
    private var keep: Set<String> = emptySet()

    @Volatile
    private var kill: Set<String> = emptySet()

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var firstPull = true

    @Volatile
    private var lastPullOk = true

    private val refreshing = AtomicBoolean(false)

    /** 取 systemContext、注册配置广播、做首次拉取。整体放后台线程，不阻塞 system_server 启动。 */
    fun install(module: XposedModule) {
        Thread {
            val context = ModuleMain.systemContext()
            if (context == null) {
                module.log(Log.WARN, TAG, "system context unavailable, config bridge not installed")
                return@Thread
            }
            resolver = context.contentResolver
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) =
                            refresh(module, force = true)
                    },
                    IntentFilter(Config.ACTION_CONFIG_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onFailure {
                module.log(Log.WARN, TAG, "config receiver register failed (2s TTL fallback)", it)
            }
            refresh(module, force = true)
        }.apply { isDaemon = true }.start()
    }

    /** 名单 key 为 `<pkg>#<userId>`：分身是独立 user，包名与本体相同。 */
    fun modeOf(module: XposedModule, pkg: String, userId: Int): Int {
        refresh(module, force = false)
        val key = Config.key(pkg, userId)
        return when {
            keep.contains(key) -> Config.MODE_KEEP
            kill.contains(key) -> Config.MODE_KILL
            else -> Config.MODE_DEFAULT
        }
    }

    private fun refresh(module: XposedModule, force: Boolean) {
        if (resolver == null) return
        if (!force && SystemClock.elapsedRealtime() - loadedAt < TTL_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                pull(module)
            } finally {
                refreshing.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pull(module: XposedModule) {
        val resolver = resolver ?: return
        val result = runCatching { resolver.call(URI, Config.METHOD_GET, null, null) }
        loadedAt = SystemClock.elapsedRealtime()
        val bundle = result.getOrNull()
        if (bundle == null) {
            // 只在「上一次成功」时打一次，避免 App 被卸载/冻结后刷屏。
            if (lastPullOk) module.log(Log.WARN, TAG, "config pull failed", result.exceptionOrNull())
            lastPullOk = false
            return
        }
        lastPullOk = true
        val newKeep = bundle.getStringArray(Config.KEY_KEEP)?.toSet() ?: emptySet()
        val newKill = bundle.getStringArray(Config.KEY_KILL)?.toSet() ?: emptySet()
        if (firstPull || newKeep != keep || newKill != kill) {
            firstPull = false
            module.log(Log.INFO, TAG, "config loaded: keep=$newKeep kill=$newKill")
        }
        keep = newKeep
        kill = newKill
    }
}