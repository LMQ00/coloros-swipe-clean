package io.github.lmq00.swipeclean

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口：只负责 DynamicColors。
 *
 * 配置通道是 [ConfigProvider]（Hook 主动 `call()` 拉取）+ [Config.ACTION_CONFIG_CHANGED] 广播，
 * 不再需要绑定 LSPosed 服务，因此这里没有任何初始化逻辑。
 */
class SwipeCleanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 跟随系统取色（Material You），与 KernelSU 等系统工具的观感一致。
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}