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
 * 三个触发点，同一条拉取路径：
 * - system_server 启动期：带重试的初始化（见 [install]），直到「接收器注册成功 + 首次拉取成功」
 * - 收到 App 的配置变更广播后立即拉取
 * - 判定路径上的惰性刷新（2 秒 TTL；非阻塞——过期时起后台线程拉取，本次判定仍用当前缓存）
 *
 * 拉取失败沿用上次成功的缓存并记日志；从未成功拉取过时，判定路径会做一次同步补拉，
 * 绝不拿空名单静默判定。
 */
internal object ConfigBridge {

    private const val TAG = ModuleMain.TAG
    private const val TTL_MS = 2_000L
    private const val RETRY_MS = 1_000L

    /** 启动期重试上限：AMS 就绪通常在 1~2 秒内，60 次留足余量。 */
    private const val RETRY_LIMIT = 60

    private val URI = Uri.parse("content://" + Config.AUTHORITY)

    @Volatile
    private var resolver: ContentResolver? = null

    @Volatile
    private var receiverRegistered = false

    /** 是否成功拉取过至少一次。 */
    @Volatile
    private var loaded = false

    @Volatile
    private var keep: Set<String> = emptySet()

    @Volatile
    private var kill: Set<String> = emptySet()

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var lastPullOk = true

    @Volatile
    private var registerFailureLogged = false

    private val refreshing = AtomicBoolean(false)

    /**
     * 取 systemContext、注册配置广播、做首次拉取。整体放后台线程，不阻塞 system_server 启动。
     *
     * **必须带重试**：`onSystemServerStarting` 早于 AMS 初始化，此时 `ActivityThread#mgr`
     * （IActivityManager）为 null，注册接收器（`ContextImpl.registerReceiverInternal`）与
     * 拉取 provider（`ActivityThread.acquireProvider`）都会 NPE。实测时间线：
     * 本回调 22:51:03.958 失败，AMS 就绪约在 22:51:04.3。
     */
    fun install(module: XposedModule) {
        Thread {
            var attempt = 0
            while (attempt < RETRY_LIMIT) {
                attempt++
                val context = ModuleMain.systemContext()
                if (context != null) {
                    resolver = context.contentResolver
                    if (!receiverRegistered) registerReceiver(module, context)
                    if (!loaded) pull(module)
                }
                if (receiverRegistered && loaded) break
                Thread.sleep(RETRY_MS)
            }
            if (receiverRegistered && loaded) {
                module.log(Log.INFO, TAG, "config bridge ready (attempt=$attempt)")
            } else {
                module.log(
                    Log.WARN,
                    TAG,
                    "config bridge not ready after $attempt attempts" +
                        " (receiver=$receiverRegistered loaded=$loaded); 2s TTL fallback",
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /** 名单 key 为 `<pkg>#<userId>`：分身是独立 user，包名与本体相同。 */
    fun modeOf(module: XposedModule, pkg: String, userId: Int): Int {
        if (loaded) {
            refresh(module)
        } else if (SystemClock.elapsedRealtime() - loadedAt >= TTL_MS) {
            // 从未成功拉取过（例如 App 被卸载）：同步补一次，避免拿空名单判定。
            // pull 每次都刷新 loadedAt，天然限频。
            pull(module)
        }
        val key = Config.key(pkg, userId)
        return when {
            keep.contains(key) -> Config.MODE_KEEP
            kill.contains(key) -> Config.MODE_KILL
            else -> Config.MODE_DEFAULT
        }
    }

    /** 注册配置变更广播。失败只记一次日志，重试由 [install] 负责。 */
    private fun registerReceiver(module: XposedModule, context: Context) {
        val result = runCatching {
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    // 广播在主线程派发，拉取走后台线程，避免阻塞 system_server 主线程。
                    override fun onReceive(c: Context?, intent: Intent?) {
                        Thread { pull(module) }.apply { isDaemon = true }.start()
                    }
                },
                IntentFilter(Config.ACTION_CONFIG_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        receiverRegistered = result.isSuccess
        if (result.isFailure && !registerFailureLogged) {
            registerFailureLogged = true
            module.log(
                Log.WARN,
                TAG,
                "config receiver register failed (retry pending)",
                result.exceptionOrNull(),
            )
        }
    }

    /** 2 秒 TTL 惰性刷新：过期时起后台线程拉取，本次判定仍用当前缓存。 */
    private fun refresh(module: XposedModule) {
        if (resolver == null) return
        if (SystemClock.elapsedRealtime() - loadedAt < TTL_MS) return
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
        if (!loaded || newKeep != keep || newKill != kill) {
            module.log(Log.INFO, TAG, "config loaded: keep=$newKeep kill=$newKill")
        }
        loaded = true
        keep = newKeep
        kill = newKill
    }
}