package io.github.lmq00.swipeclean

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
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
    /** 分身在 ColorOS 里的序号（与 launcher 图标角标一致）；`0` = 本体或序号未知。 */
    val ordinal: Int = 0,
) {
    /** 配置名单里的元素：`<pkg>#<userId>`。 */
    val key: String get() = Config.key(packageName, userId)

    /**
     * 列表与设置面板显示用标题：分身优先显示 ColorOS 的序号（与图标角标里的数字一致），
     * 序号拿不到时退回真实 userId。
     */
    fun title(context: Context): String = when {
        userId == 0 -> label
        ordinal > 0 -> context.getString(R.string.dual_ordinal, label, ordinal)
        else -> context.getString(R.string.dual_suffix, label, userId)
    }
}

/** 分身枚举结果：user 列表 + 每个 user 下已安装（有 launcher 入口）的包集合。 */
data class DualApps(
    val userIds: List<Int>,
    val packages: Map<Int, Set<String>>,
    /** 分身 userId → ColorOS 序号；空表示取不到（UI 会退回显示 userId）。 */
    val ordinals: Map<Int, Int> = emptyMap(),
) {
    /** 该包存在分身的 user 列表；本体（0）不在此列。 */
    fun usersOf(pkg: String): List<Int> = userIds.filter { pkg in packages[it].orEmpty() }
}

object AppRepository {

    private const val TAG = "SwipeClean"

    /** `UserHandle.PER_USER_RANGE`：uid 里 user 部分的步长（uid = userId * 100000 + appId）。 */
    private const val PER_USER_RANGE = 100_000

    /**
     * 分身 userId → `UserHandle`。只能用 `LauncherApps#getProfiles()` 给的实例，
     * 因为 `UserHandle.of(int)` / `getUserId(int)` 都不是公开 API（见 Config.MODULE_PACKAGE 注释）。
     */
    private val handles = ConcurrentHashMap<Int, UserHandle>()

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
     * userId 由 `ApplicationInfo.uid` 反推（`uid / PER_USER_RANGE`）：`UserHandle` 的
     * `of()/getUserId()/myUserId()/getIdentifier()` 都是系统 API，普通应用编译期不可见。
     *
     * 局限：只覆盖带 launcher 入口（`MAIN`/`LAUNCHER`）的应用；无入口的分身应用不会列出。
     */
    fun loadDualApps(context: Context): DualApps {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return DualApps(emptyList(), emptyMap())
        val self = Process.myUid() / PER_USER_RANGE
        val profiles = runCatching { launcherApps.profiles }.getOrElse {
            Log.w(TAG, "enumerate profiles failed", it)
            return DualApps(emptyList(), emptyMap())
        }
        val packages = LinkedHashMap<Int, Set<String>>()
        val found = LinkedHashMap<Int, UserHandle>()
        for (profile in profiles) {
            val apps = runCatching {
                launcherApps.getActivityList(null, profile).map { it.applicationInfo }
            }.onFailure { Log.w(TAG, "list activities failed", it) }.getOrDefault(emptyList())
            // 该 user 下一个 launcher 入口都没有：没有可配置的行，直接跳过。
            if (apps.isEmpty()) continue
            val userId = apps.first().uid / PER_USER_RANGE
            if (userId == self) continue
            packages[userId] = apps.map { it.packageName }.toSet()
            found[userId] = profile
        }
        handles.putAll(found)

        // ColorOS 的分身序号（= launcher 图标角标里的数字）：按 user serial 升序排名。
        // 实测 999→1、998→2（serialNo 10 / 11），与角标一致。
        // getLauncherUserInfo 是 API 35+，且只做 canAccessProfile 校验（与 getActivityList 同一道门）；
        // 取不到时 ordinals 留空，UI 退回显示真实 userId。
        val ordinals = LinkedHashMap<Int, Int>()
        if (Build.VERSION.SDK_INT >= 35) {
            val serials = HashMap<Int, Long>()
            for ((userId, handle) in found) {
                val serial = runCatching { launcherApps.getLauncherUserInfo(handle)?.userSerialNumber }
                    .getOrNull()
                if (serial != null && serial >= 0L) serials[userId] = serial
            }
            val bySerial = serials.entries.sortedBy { it.value }
            for (index in bySerial.indices) {
                ordinals[bySerial[index].key] = index + 1
            }
        }

        val userIds = packages.keys.sorted()
        Log.i(
            TAG,
            "dual users=$userIds ordinals=$ordinals packages=${packages.mapValues { it.value.size }}",
        )
        return DualApps(userIds, packages, ordinals)
    }

    /**
     * 一行的图标。
     *
     * 本体走 `PackageManager`；分身走 `LauncherApps#getActivityList(pkg, user)` 得到的
     * `LauncherActivityInfo#getBadgedIcon()`——这是公开 API（API 21+），文档即
     * 「带该 user 角标的图标」，ColorOS 在这里画的就是 launcher 里看到的分身数字角标。
     * 序号由 ROM 决定，模块不自行编号。
     *
     * 取不到（该包在目标 user 下没有 launcher 入口）时退回本体的图标。
     */
    fun iconOf(context: Context, packageName: String, userId: Int): Drawable? {
        if (userId != 0) {
            val handle = handles[userId]
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            if (handle != null && launcherApps != null) {
                val density = context.resources.displayMetrics.densityDpi
                runCatching {
                    launcherApps.getActivityList(packageName, handle)
                        .firstOrNull()
                        ?.getBadgedIcon(density)
                }.getOrNull()?.let { return it }
            }
        }
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
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
                entries.add(
                    AppEntry(label, info.packageName, userId, system, dual.ordinals[userId] ?: 0),
                )
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

    /** 缓存键按 `<pkg>#<userId>` 区分：分身行的图标带角标，与本体不同。 */
    fun load(context: Context, packageName: String, userId: Int, onLoaded: (Drawable) -> Unit) {
        val key = Config.key(packageName, userId)
        cache[key]?.let {
            onLoaded(it)
            return
        }
        val appContext = context.applicationContext
        pool.execute {
            val icon = AppRepository.iconOf(appContext, packageName, userId) ?: return@execute
            cache.putIfAbsent(key, icon)
            main.post { onLoaded(icon) }
        }
    }
}