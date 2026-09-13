package io.github.lmq00.swipeclean

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService

/**
 * 配置的单一来源定义。
 *
 * 写入端是模块 App 的 UI（[ConfigStore.setMode]）；
 * 读取端是运行在 system_server 里的 Hook，通过 `XposedInterface#getRemotePreferences` 读取。
 *
 * 两者不是同一份存储：Hook 侧读的是**框架侧**（LSPosed 持有）的那份，
 * 只能由 App 通过 [XposedService] 写入，见 [ConfigStore.attach]。
 */
object Config {
    /** SharedPreferences 组名，Hook 侧用 getRemotePreferences(GROUP) 读取。 */
    const val GROUP = "config"

    /** 划卡不杀名单（StringSet）。 */
    const val KEY_KEEP = "keep"

    /** 划卡必杀名单（StringSet）。 */
    const val KEY_KILL = "kill"

    /** 跟随系统默认行为。 */
    const val MODE_DEFAULT = 0

    /** 划卡时不杀死该应用。 */
    const val MODE_KEEP = 1

    /** 划卡时强制停止该应用。 */
    const val MODE_KILL = 2
}

/** 模块 App 侧的配置读写：本地落盘 + 推送框架侧。 */
object ConfigStore {

    private const val TAG = "SwipeClean"

    /** 框架侧配置。LSPosed 未绑定（未启用模块 / 未重启）时为 null。 */
    @Volatile
    private var remote: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE)

    /** LSPosed 服务就绪：接上框架侧配置，并把本地已有的名单同步过去。 */
    fun attach(context: Context, service: XposedService) {
        val frameworkPrefs = runCatching { service.getRemotePreferences(Config.GROUP) }
            .onFailure { Log.w(TAG, "remote preferences unavailable", it) }
            .getOrNull() ?: return
        remote = frameworkPrefs
        push(prefs(context))
        Log.i(TAG, "framework preferences attached")
    }

    fun detach(service: XposedService) {
        remote = null
    }

    /** 列表渲染用：一次取出全部名单，避免每个应用各读一遍 SharedPreferences。 */
    fun modeMap(prefs: SharedPreferences): Map<String, Int> {
        val keep = prefs.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty()
        val kill = prefs.getStringSet(Config.KEY_KILL, emptySet()).orEmpty()
        val modes = HashMap<String, Int>(keep.size + kill.size)
        for (pkg in keep) modes[pkg] = Config.MODE_KEEP
        for (pkg in kill) modes[pkg] = Config.MODE_KILL
        return modes
    }

    /**
     * 写入某个应用的划卡行为：本地与框架侧各写一份。
     * SharedPreferences 的 StringSet 返回值不可直接修改，因此这里先复制再提交。
     */
    fun setMode(context: Context, pkg: String, mode: Int) {
        val prefs = prefs(context)
        val keep = (prefs.getStringSet(Config.KEY_KEEP, emptySet()) ?: emptySet()).toMutableSet()
        val kill = (prefs.getStringSet(Config.KEY_KILL, emptySet()) ?: emptySet()).toMutableSet()
        keep.remove(pkg)
        kill.remove(pkg)
        when (mode) {
            Config.MODE_KEEP -> keep.add(pkg)
            Config.MODE_KILL -> kill.add(pkg)
        }
        prefs.edit()
            .putStringSet(Config.KEY_KEEP, keep)
            .putStringSet(Config.KEY_KILL, kill)
            .apply()
        push(prefs)
    }

    /** 把本地名单整份推到框架侧，Hook 读到的就是这里写的内容。 */
    private fun push(local: SharedPreferences) {
        val target = remote ?: return
        val keep = local.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty()
        val kill = local.getStringSet(Config.KEY_KILL, emptySet()).orEmpty()
        runCatching {
            target.edit()
                .putStringSet(Config.KEY_KEEP, keep)
                .putStringSet(Config.KEY_KILL, kill)
                .commit()
        }.onFailure { Log.w(TAG, "push to framework failed", it) }
    }
}