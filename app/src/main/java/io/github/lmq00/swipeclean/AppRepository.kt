package io.github.lmq00.swipeclean

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** 列表中的一行：一个已安装应用及其当前的划卡行为。 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val system: Boolean,
)

object AppRepository {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                        ?: pm.defaultActivityIcon,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            .toList()
    }
}