package io.github.lmq00.swipeclean

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 列表中的一行：一个已安装应用及其当前的划卡行为。 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val system: Boolean,
)

object AppRepository {

    /**
     * 只读取名称与包名——图标加载慢，交给 [IconCache] 在行绑定时异步补齐，
     * 否则首次进入界面要等所有图标解码完才显示列表。
     */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    packageName = info.packageName,
                    system = (info.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                )
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            .toList()
    }
}

/** 应用图标的内存缓存 + 后台加载。 */
object IconCache {

    private val cache = ConcurrentHashMap<String, Drawable>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, packageName: String, onLoaded: (Drawable) -> Unit) {
        cache[packageName]?.let {
            onLoaded(it)
            return
        }
        val appContext = context.applicationContext
        pool.execute {
            val icon = runCatching { appContext.packageManager.getApplicationIcon(packageName) }
                .getOrNull() ?: return@execute
            cache.putIfAbsent(packageName, icon)
            main.post { onLoaded(icon) }
        }
    }
}