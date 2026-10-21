package com.videowallpaper

import javax.swing.SwingUtilities

/**
 * 宿主（Salt Player for Windows 1.19）内部状态的反射桥。
 *
 * 插件的 compileOnly 里没有宿主 jar，所以宿主的类只能按名字反射访问；
 * 混淆后的类名/成员名一律先在本项目 `host-analysis/` 里核对过再用。
 */
object HostBridge {

    private const val CLS_APPCONFIG = "com.xuncorp.voxzen.util.AppConfig"

    private val appConfig: Any? by lazy {
        runCatching {
            Class.forName(CLS_APPCONFIG).getField("INSTANCE").get(null)
        }.onFailure { VwLog.line("AppConfig 不可用：${it.message}") }.getOrNull()
    }

    val available: Boolean get() = appConfig != null

    /** 宿主当前壁纸路径（图片或视频；空串 = 没设壁纸） */
    fun wallpaperPath(): String = runCatching {
        appConfig!!.javaClass.getMethod("getWallpaperFilePath").invoke(appConfig) as? String ?: ""
    }.getOrDefault("")

    /** 写宿主壁纸路径。宿主内部是 Compose 状态，写完顺手推进一次快照。 */
    fun setWallpaperPath(path: String): Boolean = runCatching {
        uiThread {
            runCatching {
                appConfig!!.javaClass
                    .getMethod("setWallpaperFilePath", String::class.java)
                    .invoke(appConfig, path)
                requestRecompose()
            }.onFailure { VwLog.failure("setWallpaperFilePath 失败", it) }
        }
        true
    }.getOrDefault(false)

    /** 「壁纸材质」开关：开启时宿主会对壁纸基底层做 haze 模糊 */
    fun wallpaperMaterial(): Boolean = runCatching {
        appConfig!!.javaClass.getMethod("getWallpaperMaterial").invoke(appConfig) as? Boolean ?: false
    }.getOrDefault(false)

    /** 推进一次全局快照，让宿主 Compose 读到我们改的值 */
    private fun requestRecompose() {
        runCatching {
            val cls = Class.forName("androidx.compose.runtime.snapshots.Snapshot")
            val companion = Class.forName("androidx.compose.runtime.snapshots.Snapshot\$Companion")
                .getField("INSTANCE").get(null)
            cls.getMethod("sendApplyNotifications").invoke(null)
            // 有些版本 sendApplyNotifications 是 companion 上的，兜底再来一次
            runCatching { companion.javaClass.getMethod("sendApplyNotifications").invoke(companion) }
        }
    }

    fun uiThread(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }
}
