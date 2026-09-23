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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook 侧配置读取：向模块 App 的 [io.github.lmq00.swipeclean.ConfigProvider] 拉取
 * （`contentResolver.call("get")`），并把最后一次成功的结果落盘成自己的缓存。
 *
 * 三条读取路径：
 * 1. **开机即读缓存文件**（[restoreFromCache]）——纯文件 I/O，不依赖 AMS、不依赖 App 能否被拉起。
 *    ColorOS 在开机窗口内会拦第三方 App 启动（`isPreventBootStartData`，
 *    `OplusAppStartupManager.java:4082`），实测完整重启后首次拉取要等约 5 分钟；
 *    有缓存就不会出现「重启后名单为空」的空窗。
 * 2. **App 的配置变更广播** → 立即重新拉取。
 * 3. **判定路径上的惰性刷新**（2 秒 TTL；非阻塞——过期时起后台线程拉取，本次判定仍用当前缓存）。
 *
 * 拉取失败沿用上次成功缓存并记日志（失败原因变化时打一条），绝不静默失败。
 */
internal object ConfigBridge {

    private const val TAG = ModuleMain.TAG
    private const val TTL_MS = 2_000L

    /** 启动期重试节奏：前 [RETRY_FAST] 次 1 秒一次，之后 5 秒一次，共 [RETRY_TOTAL] 次（约 5.5 分钟）。 */
    private const val RETRY_FAST = 30
    private const val RETRY_TOTAL = 90

    /** Hook 自己的落盘缓存（system_server 可写；App 卸载后仍可用最后一次的名单）。 */
    private const val CACHE_FILE = "/data/system/swipeclean_config.json"

    private val URI = Uri.parse("content://" + Config.AUTHORITY)

    @Volatile
    private var resolver: ContentResolver? = null

    @Volatile
    private var receiverRegistered = false

    /** 是否有可用名单（来自缓存或成功拉取）。 */
    @Volatile
    private var loaded = false

    /** 是否成功从 App 拉取过至少一次（本进程内）。 */
    @Volatile
    private var pulled = false

    @Volatile
    private var keep: Set<String> = emptySet()

    @Volatile
    private var kill: Set<String> = emptySet()

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var registerFailureLogged = false

    private val refreshing = AtomicBoolean(false)

    /**
     * 先读落盘缓存（立刻可用），再带重试地注册广播接收器并拉取一次。
     *
     * **必须带重试**：`onSystemServerStarting` 早于 AMS 初始化，此时 `ActivityThread#mgr`
     * （IActivityManager）为 null，注册接收器（`ContextImpl.registerReceiverInternal`）与
     * 拉取 provider（`ActivityThread.acquireProvider`）都会 NPE。
     */
    fun install(module: XposedModule) {
        restoreFromCache(module)
        Thread {
            var attempt = 0
            while (attempt < RETRY_TOTAL) {
                attempt++
                val context = ModuleMain.systemContext()
                if (context != null) {
                    resolver = context.contentResolver
                    if (!receiverRegistered) registerReceiver(module, context)
                    if (!pulled) pull(module)
                }
                if (receiverRegistered && pulled) break
                Thread.sleep(if (attempt <= RETRY_FAST) 1_000L else 5_000L)
            }
            if (receiverRegistered && pulled) {
                module.log(Log.INFO, TAG, "config bridge ready (attempt=$attempt)")
            } else {
                module.log(
                    Log.WARN,
                    TAG,
                    "config bridge not ready after $attempt attempts" +
                        " (receiver=$receiverRegistered pulled=$pulled loaded=$loaded);" +
                        " 2s TTL fallback",
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /** 名单 key 为 `<pkg>#<userId>`：分身是独立 user，包名与本体相同。 */
    fun modeOf(module: XposedModule, pkg: String, userId: Int): Int {
        // 模块自身的 App 恒视为「不杀」：它是配置通道的一端，被划卡或 athena 的内存清理杀掉
        // 只会带来无谓的冷启动（开机窗口内还可能被 ROM 拦住）。UI 里不列出它，用户无从冲突。
        if (pkg == Config.MODULE_PACKAGE) return Config.MODE_KEEP
        if (loaded) {
            refresh(module)
        } else if (SystemClock.elapsedRealtime() - loadedAt >= TTL_MS) {
            // 连缓存都没有（例如 App 被卸载且从未拉取成功）：同步补一次，
            // 避免拿空名单判定。pull 每次都刷新 loadedAt，天然限频。
            pull(module)
        }
        val key = Config.key(pkg, userId)
        return when {
            keep.contains(key) -> Config.MODE_KEEP
            kill.contains(key) -> Config.MODE_KILL
            else -> Config.MODE_DEFAULT
        }
    }

    /** 开机即从缓存恢复：system_server 启动早期也能用，不依赖 AMS 与 App 进程。 */
    private fun restoreFromCache(module: XposedModule) {
        val file = File(CACHE_FILE)
        if (!file.isFile) return
        val json = runCatching { JSONObject(file.readText()) }.getOrElse {
            module.log(Log.WARN, TAG, "config cache unreadable, ignored", it)
            return
        }
        keep = json.optJSONArray(Config.KEY_KEEP).toSet()
        kill = json.optJSONArray(Config.KEY_KILL).toSet()
        loaded = true
        module.log(Log.INFO, TAG, "config restored from cache: keep=$keep kill=$kill")
    }

    /** 把当前名单落盘，供下次开机使用。失败只记日志，不影响本次运行。 */
    private fun persist(module: XposedModule) {
        val json = JSONObject()
            .put(Config.KEY_KEEP, JSONArray(keep.toList()))
            .put(Config.KEY_KILL, JSONArray(kill.toList()))
        runCatching { File(CACHE_FILE).writeText(json.toString()) }
            .onFailure { module.log(Log.WARN, TAG, "config cache write failed", it) }
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
            // 只在失败原因变化时打一条，既不刷屏又能看出为什么失败。
            val reason = result.exceptionOrNull()
                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "provider unavailable (call returned null)"
            if (reason != lastFailure) {
                lastFailure = reason
                module.log(Log.WARN, TAG, "config pull failed: $reason")
            }
            return
        }
        lastFailure = null
        val newKeep = bundle.getStringArray(Config.KEY_KEEP)?.toSet() ?: emptySet()
        val newKill = bundle.getStringArray(Config.KEY_KILL)?.toSet() ?: emptySet()
        val changed = !pulled || newKeep != keep || newKill != kill
        if (changed) {
            module.log(Log.INFO, TAG, "config loaded: keep=$newKeep kill=$newKill")
        }
        loaded = true
        pulled = true
        keep = newKeep
        kill = newKill
        if (changed) persist(module)
    }

    private fun JSONArray?.toSet(): Set<String> {
        if (this == null) return emptySet()
        val out = HashSet<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out
    }
}