package io.github.lmq00.swipeclean

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 绑定 LSPosed 的模块服务。
 *
 * Hook 侧读的是框架侧存储（`XposedInterface#getRemotePreferences`），
 * 而不是本 App 的 SharedPreferences——两者只能通过 [XposedService] 打通：
 * 框架调用本 App 的 `XposedProvider`（authority `<applicationId>.XposedService`）
 * 下发 binder，之后 App 用 `getRemotePreferences(GROUP)` 写入的那份数据，
 * 就是 Hook 侧 `getRemotePreferences(GROUP)` 读到的那份。
 */
class SwipeCleanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "xposed service bound: ${service.frameworkName} ${service.frameworkVersion}")
                ConfigStore.attach(this@SwipeCleanApp, service)
            }

            override fun onServiceDied(service: XposedService) {
                Log.w(TAG, "xposed service died")
                ConfigStore.detach(service)
            }
        })
    }

    private companion object {
        const val TAG = "SwipeClean"
    }
}