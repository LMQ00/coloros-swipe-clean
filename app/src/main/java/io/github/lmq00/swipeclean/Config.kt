package io.github.lmq00.swipeclean

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * 配置的单一来源定义。
 *
 * 写入端是模块 App 的 UI（[ConfigStore]）；读取端是运行在 system_server 里的 Hook
 * （`hook.ConfigBridge`），经 `contentResolver.call()` 读本 App 的 [ConfigProvider]。
 *
 * 两边不是同一份存储，因此每次写入后由 [ConfigStore] 发一条 [ACTION_CONFIG_CHANGED] 广播，
 * Hook 收到后立即重新拉取；Hook 侧另有 2 秒 TTL 兜底。
 */
object Config {
    /** 本地 SharedPreferences 文件名。沿用旧名 "config"，升级不丢配置。 */
    const val PREFS = "config"

    /** [ConfigProvider] 的 authority。必须与 AndroidManifest.xml 中的声明逐字一致。 */
    const val AUTHORITY = "io.github.lmq00.swipeclean.config"

    /** [ConfigProvider.call] 的取配置方法名。 */
    const val METHOD_GET = "get"

    /** 配置变更广播：App 每次写入后发出，Hook 收到后立即重新拉取。 */
    const val ACTION_CONFIG_CHANGED = "io.github.lmq00.swipeclean.CONFIG_CHANGED"

    /** 划卡不杀名单（StringSet，元素由 [key] 生成）。 */
    const val KEY_KEEP = "keep"

    /** 划卡必杀名单（StringSet，元素由 [key] 生成）。 */
    const val KEY_KILL = "kill"

    /** 跟随系统默认行为。 */
    const val MODE_DEFAULT = 0

    /** 划卡时不杀死该应用。 */
    const val MODE_KEEP = 1

    /** 划卡时强制停止该应用。 */
    const val MODE_KILL = 2

    /**
     * 名单元素：`<pkg>#<userId>`。
     *
     * 应用分身是独立 user，包名与本体完全相同（实测：拼多多本体 uid `10367`、
     * 分身 uid `99810367` / `99910367`），只有带上 userId 才能区分；
     * 且分身 userId 不固定（实测同时存在 998 与 999），不得硬编码。
     */
    fun key(pkg: String, userId: Int): String = "$pkg#$userId"

    /** 旧格式判定：不含 `#` 的纯包名条目。 */
    fun isLegacy(entry: String): Boolean = '#' !in entry
}

/** 模块 App 侧的配置读写：本地落盘 + 通知 Hook 重新拉取。 */
object ConfigStore {

    private const val TAG = "SwipeClean"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

    /** 列表渲染用：一次取出全部名单，避免每个应用各读一遍 SharedPreferences。 */
    fun modeMap(prefs: SharedPreferences): Map<String, Int> {
        val keep = prefs.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty()
        val kill = prefs.getStringSet(Config.KEY_KILL, emptySet()).orEmpty()
        val modes = HashMap<String, Int>(keep.size + kill.size)
        for (key in keep) modes[key] = Config.MODE_KEEP
        for (key in kill) modes[key] = Config.MODE_KILL
        return modes
    }

    /** 写入某个应用（或它的某个分身）的划卡行为。 */
    fun setMode(context: Context, key: String, mode: Int) = setModes(context, listOf(key), mode)

    /**
     * 批量写入多个目标的划卡行为：本地落盘一次、广播一次，
     * 批量操作的开销不随条目数量放大。
     * SharedPreferences 的 StringSet 返回值不可直接修改，因此这里先复制再提交。
     */
    fun setModes(context: Context, keys: Collection<String>, mode: Int) {
        if (keys.isEmpty()) return
        val prefs = prefs(context)
        val keep = (prefs.getStringSet(Config.KEY_KEEP, emptySet()) ?: emptySet()).toMutableSet()
        val kill = (prefs.getStringSet(Config.KEY_KILL, emptySet()) ?: emptySet()).toMutableSet()
        for (key in keys) {
            keep.remove(key)
            kill.remove(key)
            when (mode) {
                Config.MODE_KEEP -> keep.add(key)
                Config.MODE_KILL -> kill.add(key)
            }
        }
        prefs.edit()
            .putStringSet(Config.KEY_KEEP, keep)
            .putStringSet(Config.KEY_KILL, kill)
            .apply()
        notifyChanged(context)
    }

    /** 是否还有旧格式条目（纯包名）。 */
    fun needsMigration(context: Context): Boolean {
        val prefs = prefs(context)
        return (prefs.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty() +
            prefs.getStringSet(Config.KEY_KILL, emptySet()).orEmpty()).any { Config.isLegacy(it) }
    }

    /**
     * 把旧格式 `<pkg>` 展开为 `<pkg>#0` + 该包实际存在的分身 user（保持升级前的行为，
     * 想拆分再手动改）。幂等：没有旧格式条目时返回 false 且不写盘。
     *
     * 用 `commit()` 而非 `apply()`：迁移后 UI 与 [ConfigProvider] 会立刻读同一份 prefs。
     */
    fun migrate(context: Context, dual: DualApps): Boolean {
        val prefs = prefs(context)
        val keep = prefs.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty()
        val kill = prefs.getStringSet(Config.KEY_KILL, emptySet()).orEmpty()
        if ((keep + kill).none { Config.isLegacy(it) }) return false
        fun expand(entries: Set<String>): Set<String> {
            val out = HashSet<String>(entries.size * 2)
            for (entry in entries) {
                if (!Config.isLegacy(entry)) {
                    out.add(entry)
                    continue
                }
                out.add(Config.key(entry, 0))
                for (userId in dual.usersOf(entry)) out.add(Config.key(entry, userId))
            }
            return out
        }
        val newKeep = expand(keep)
        val newKill = expand(kill)
        prefs.edit()
            .putStringSet(Config.KEY_KEEP, newKeep)
            .putStringSet(Config.KEY_KILL, newKill)
            .commit()
        Log.i(TAG, "config migrated: keep=$newKeep kill=$newKill")
        notifyChanged(context)
        return true
    }

    /** 通知 Hook 立即重新拉取；失败只记日志（Hook 的 2 秒 TTL 兜底）。 */
    private fun notifyChanged(context: Context) {
        runCatching { context.sendBroadcast(Intent(Config.ACTION_CONFIG_CHANGED)) }
            .onFailure { Log.w(TAG, "broadcast config change failed", it) }
    }
}