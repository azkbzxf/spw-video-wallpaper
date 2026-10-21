package com.videowallpaper.hook

import com.videowallpaper.VwLog

/**
 * 注册失败时的延迟重试。
 *
 * 宿主 `WorkshopHookRegistrar` 只允许在「插件正在启动的线程」或「插件已 started 之后」注册 hook，
 * 否则抛 `Hooks require a starting or started plugin: <id>`。
 * 同步注册若踩到时机问题，就从后台线程按退避重试；那时插件必然已 started，校验一定放行。
 * 如果是真错误（找不到调用点），重试同样失败，只会多几行日志。
 */
internal object HookInstaller {

    private val retryDelaysMs = longArrayOf(1200L, 3500L, 8000L)

    @Volatile
    private var stopped = false

    fun install(name: String, register: () -> Boolean) {
        stopped = false
        VwLog.line("$name 注册未成功，转入后台重试")
        Thread({
            for ((index, delayMs) in retryDelaysMs.withIndex()) {
                runCatching { Thread.sleep(delayMs) }
                if (stopped) return@Thread
                if (register()) {
                    VwLog.line("$name 第 ${index + 1} 次重试成功")
                    return@Thread
                }
            }
            VwLog.line("$name 重试全部失败")
        }, "vw-$name").apply { isDaemon = true }.start()
    }

    fun onPluginStop() {
        stopped = true
    }
}
