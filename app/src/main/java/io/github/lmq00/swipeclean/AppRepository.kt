package io.github.lmq00.swipeclean

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 列表中的一行：一个已安装应用（本体或其某个分身）及其当前的划卡行为。
 *
 * 应用分身是独立 user，包名与本体相同，只有 [userId] 不同；配置以 [key] 为元素。
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    /** `0` = 本体；其余为分身（独立 user）的 userId。 */
    val userId: Int,
    val system: Boolean,
) {
    /** 配置名单里的元素：`<pkg>#<userId>`。 */
    val key: String get() = Config.key(packageName, userId)
}

/** 分身枚举结果：user 列表 + 每个 user 下已安装（有 launcher 入口）的包集合。 */
data class DualApps(
    val userIds: List<Int>,
    val packages: Map<Int, Set<String>>,
) {
    /** 该包存在分身的 user 列表；本体（0）不在此列。 */
    fun usersOf(pkg: String): List<Int> = userIds.filter { pkg in packages[it].orEmpty() }
}

object AppRepository {

    private const val TAG = "SwipeClean"

    /**
     * 枚举当前 user 所属 profile group 内除本体外的 user，以及各 user 下已安装的应用。
     *
     * 依据（设备实测 + 框架代码）：
     * - `LauncherApps#getProfiles()` → `UserManagerService.getProfileIds(自己, true)`，
     *   只在 `userId != callingUserId` 时校验权限，因此普通应用可拿到整个 profile group
     *   （ColorOS 分身 user 的 `parentId=0`，与本体同组）。
     * - `LauncherAppsService#getLauncherActivities` → `canAccessProfile` → `isProfileAccessible`
     *   对同 profileGroupId 的已启用 user 返回 true。
     *
     * 局限：只覆盖带 launcher 入口（`MAIN`/`LAUNCHER`）的应用；无入口的分身应用不会列出。
     */
    fun loadDualApps(context: Context): DualApps {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return DualApps(emptyList(), emptyMap())
        val userIds = runCatching {
            launcherApps.profiles.map { it.identifier }
                .filter { it != UserHandle.myUserId() }
                .sorted()
        }.getOrElse {
            Log.w(TAG, "enumerate profiles failed", it)
            return DualApps(emptyList(), emptyMap())
        }
        val packages = userIds.associateWith { userId ->
            runCatching {
                launcherApps.getActivityList(null, UserHandle.of(userId))
                    .map { it.applicationInfo.packageName }
                    .toSet()
            }.onFailure { Log.w(TAG, "list activities for user $userId failed", it) }
                .getOrDefault(emptySet())
        }
        Log.i(TAG, "dual users=$userIds packages=${packages.mapValues { it.value.size }}")
        return DualApps(userIds, packages)
    }

    /**
     * 只读取名称与包名——图标加载慢，交给 [IconCache] 在行绑定时异步补齐，
     * 否则首次进入界面要等所有图标解码完才显示列表。
     *
     * 每个应用在本体条目之后追加它的分身子项（[dual] 由 [loadDualApps] 提供）。
     */
    fun load(context: Context, dual: DualApps): List<AppEntry> {
        val pm = context.packageManager
        val entries = ArrayList<AppEntry>()
        for (info in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (info.packageName == context.packageName) continue
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
            val system = (info.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            entries.add(AppEntry(label, info.packageName, 0, system))
            for (userId in dual.usersOf(info.packageName)) {
                entries.add(AppEntry(label, info.packageName, userId, system))
            }
        }
        entries.sortWith(
            compareBy({ it.system }, { it.label.lowercase() }, { it.packageName }, { it.userId }),
        )
        return entries
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