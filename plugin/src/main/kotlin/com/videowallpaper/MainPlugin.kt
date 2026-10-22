package com.videowallpaper

import com.videowallpaper.hook.HookInstaller
import com.videowallpaper.hook.Hooks
import com.videowallpaper.video.SourceMode
import com.videowallpaper.video.VideoEngine
import com.videowallpaper.video.VideoLibrary
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File

/**
 * 视频壁纸 —— SPW（Salt Player for Windows）1.19 插件。
 *
 * 入口：外观 → 壁纸 → 面板底部的「视频壁纸」卡片
 * 效果：把 MP4 / WebM / MKV 等视频当作窗口壁纸，按 PTS 实时循环播放（静音）。
 *
 * 版本基线：SPW 1.19.0 / Workshop API 0.1.0-dev23。
 * 权限：CLASS_TRANSFORM（注入壁纸渲染层与设置面板，未授权时插件静默降级）。
 */
@OptIn(UnstableSpwWorkshopApi::class)
class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        val dir = runCatching {
            File(pluginContext.pluginPath).let { if (it.isDirectory) it else it.parentFile }
        }.getOrNull()

        dir?.let {
            VwLog.init(it)
            VwConfig.init(it)
        }

        VwLog.line("==== start ==== spw=${pluginContext.spwVersion} plugin=${pluginContext.pluginVersion}")
        VwLog.line("dir=$dir")

        val wallpaperOk = Hooks.registerWallpaper()
        val panelOk = Hooks.registerPanel()
        if (!wallpaperOk) HookInstaller.install("wallpaper") { Hooks.registerWallpaper() }
        if (!panelOk) HookInstaller.install("panel") { Hooks.registerPanel() }

        // 恢复上次的视频壁纸
        resumePersisted()

        // 文件夹模式：只有窗口从最小化恢复时才随机换片
        WindowRestoreWatcher.start {
            if (VwConfig.sourceMode == SourceMode.FOLDER) VideoEngine.next()
        }

        writeStatus(wallpaperOk, panelOk)

        val msg = when {
            wallpaperOk && panelOk -> "视频壁纸已就绪：外观 → 壁纸 → 视频壁纸"
            else -> "视频壁纸：注入未完成，已转后台重试（详见插件目录 last-start.txt）"
        }
        runCatching {
            WorkshopApi.ui.toast(
                msg,
                if (wallpaperOk && panelOk) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning,
            )
        }
        if (!wallpaperOk || !panelOk) {
            // hook 失败：15 秒后再写一次最终状态（后台重试可能已经成功）
            scheduleStatusRefresh()
        } else {
            // 正常：也补一次，让 last-start.txt 里的引擎状态是「真正在播」而不是启动瞬间的 Loading
            Thread({
                runCatching { Thread.sleep(6_000L) }
                writeStatus(true, true)
            }, "vw-status").apply { isDaemon = true }.start()
        }
    }

    override fun stop() {
        HookInstaller.onPluginStop()
        Hooks.unregister()
        WindowRestoreWatcher.stop()
        VideoEngine.stop()
        VwLog.line("stopped")
    }

    private fun resumePersisted() {
        when (VwConfig.sourceMode) {
            SourceMode.FILE -> resumeFile()
            SourceMode.FOLDER -> resumeFolder()
        }
    }

    private fun resumeFile() {
        val path = VwConfig.videoPath
        if (path.isBlank()) return
        if (!File(path).isFile) {
            VwLog.line("上次的视频已不存在: $path")
            return
        }
        // 宿主壁纸路径若不是这个视频，说明用户后来换回了图片壁纸 —— 尊重用户选择
        val hostPath = HostBridge.wallpaperPath()
        if (hostPath.isNotBlank() && hostPath != path && !VideoEngine.isVideoFile(hostPath)) {
            VwLog.line("宿主壁纸已改为 $hostPath，不恢复视频")
            return
        }
        if (hostPath.isBlank()) HostBridge.setWallpaperPath(path)
        VideoEngine.start(path)
        VwLog.line("恢复视频壁纸: $path")
    }

    /** 文件夹要扫盘，放到后台，别拖慢插件启动 */
    private fun resumeFolder() {
        val folder = File(VwConfig.folderPath)
        if (!folder.isDirectory) {
            VwLog.line("上次的文件夹已不存在: ${folder.absolutePath}")
            return
        }
        Thread({
            runCatching { Thread.sleep(600) }
            try {
                val files = VideoLibrary.scan(folder)
                if (files.isEmpty()) {
                    VwLog.line("文件夹里没有视频: ${folder.absolutePath}")
                    return@Thread
                }
                val hostPath = HostBridge.wallpaperPath()
                if (hostPath.isNotBlank() && !VideoEngine.isVideoFile(hostPath)) {
                    VwLog.line("宿主壁纸已改为图片，不恢复文件夹轮播")
                    return@Thread
                }
                if (hostPath.isBlank()) HostBridge.setWallpaperPath(files.first())
                VideoEngine.startPlaylist(files, folder.absolutePath)
                VwLog.line("恢复文件夹轮播: ${files.size} 个视频")
            } catch (t: Throwable) {
                VwLog.failure("恢复文件夹轮播失败", t)
            }
        }, "vw-resume").apply { isDaemon = true }.start()
    }

    private fun scheduleStatusRefresh() {
        Thread({
            runCatching { Thread.sleep(15_000L) }
            writeStatus(Hooks.wallpaperMounted, Hooks.panelMounted)
        }, "vw-status").apply { isDaemon = true }.start()
    }

    private fun writeStatus(wallpaperOk: Boolean, panelOk: Boolean) {
        runCatching {
            val dir = File(pluginContext.pluginPath).let { if (it.isDirectory) it else it.parentFile }
            File(dir, "last-start.txt").writeText(
                buildString {
                    appendLine("time=" + java.time.LocalDateTime.now())
                    appendLine("spwVersion=" + pluginContext.spwVersion)
                    appendLine("wallpaperLayerMounted=$wallpaperOk")
                    appendLine("panelMounted=$panelOk")
                    appendLine("hostBridgeAvailable=" + HostBridge.available)
                    appendLine("hostWallpaperPath=" + HostBridge.wallpaperPath())
                    appendLine("sourceMode=" + VwConfig.sourceMode)
                    appendLine("videoPath=" + VwConfig.videoPath)
                    appendLine("folderPath=" + VwConfig.folderPath)
                    appendLine("engineState=" + VideoEngine.status.state)
                    appendLine("engineIndex=" + VideoEngine.status.index + "/" + VideoEngine.status.total)
                    appendLine("engineError=" + (VideoEngine.status.error ?: "-"))
                    appendLine("--- log ---")
                    VwLog.snapshot().takeLast(80).forEach { appendLine(it) }
                }
            )
        }
    }
}
