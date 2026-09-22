package io.github.lmq00.swipeclean

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Hook（运行在 system_server）读取配置的入口。
 *
 * 只接受 uid 1000（system_server）的调用，其余一律拒绝并记日志。
 * 非 [call] 的 ContentProvider 操作一律不支持。
 *
 * 选 ContentProvider 而不是 LSPosed remote prefs：`call()` 只会拉起本 App 的**进程**，
 * 不创建 Activity、不切前台、无通知，且不依赖「框架每 uid 每轮开机只下发一次 binder」，
 * 因此重装 APK 后不会静默失效。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID) {
            Log.w(TAG, "rejected config call from uid $uid")
            return null
        }
        if (method != Config.METHOD_GET) return null
        val context = context ?: return null
        // 旧名单在第一次被读到时展开，Hook 不打开 UI 也能拿到迁移后的名单。
        if (ConfigStore.needsMigration(context)) {
            ConfigStore.migrate(context, AppRepository.loadDualApps(context))
        }
        val prefs = ConfigStore.prefs(context)
        return Bundle().apply {
            putStringSet(
                Config.KEY_KEEP,
                prefs.getStringSet(Config.KEY_KEEP, emptySet()).orEmpty().toSet(),
            )
            putStringSet(
                Config.KEY_KILL,
                prefs.getStringSet(Config.KEY_KILL, emptySet()).orEmpty().toSet(),
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("config provider only supports call()")

    override fun getType(uri: Uri): String =
        throw UnsupportedOperationException("config provider only supports call()")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("config provider only supports call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("config provider only supports call()")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("config provider only supports call()")

    private companion object {
        const val TAG = "SwipeClean"
    }
}