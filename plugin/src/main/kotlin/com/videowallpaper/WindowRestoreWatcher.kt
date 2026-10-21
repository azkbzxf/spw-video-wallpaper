package com.videowallpaper

import java.awt.Frame
import java.awt.Window

/**
 * 盯着宿主主窗口的「最小化 → 恢复」。
 *
 * 文件夹模式下的换片时机就是这里：用户把窗口最小化再打开时，才从视频池里随机换一个。
 * 播放过程中不自动连播，避免看片中途被打断。
 *
 * 用轮询而不是 `WindowStateListener`：宿主的主窗口在插件加载之后才创建/替换，
 * 监听器容易挂到旧窗口上；350ms 一次的 `Window.getWindows()` 开销可以忽略。
 * 「最小化到托盘」在 AWT 里表现为 `isVisible=false`，所以两种都要算进去。
 */
object WindowRestoreWatcher {

    @Volatile
    private var running = false

    private var worker: Thread? = null

    fun start(onRestore: () -> Unit) {
        if (running) return
        running = true
        val t = Thread({
            var wasMinimized = false
            var primed = false
            while (running) {
                val w = runCatching { findHostWindow() }.getOrNull()
                if (w != null) {
                    val visible = runCatching { w.isVisible }.getOrDefault(false)
                    if (!visible) {
                        // 宿主启动阶段窗口还没显示出来 —— 这不是「最小化」，别当成恢复点。
                        // 只有见过窗口真正显示之后，隐藏/最小化才算数。
                        if (primed) wasMinimized = true
                    } else {
                        val minimized = runCatching { isIconified(w) }.getOrDefault(false)
                        if (!primed) {
                            // 第一次看到窗口：只记录基线，不触发
                            primed = true
                            wasMinimized = minimized
                        } else {
                            if (wasMinimized && !minimized) {
                                VwLog.line("窗口从最小化恢复")
                                // 给窗口动画让点时间，别和解码线程重新 open 抢资源
                                runCatching { Thread.sleep(200) }
                                if (running) runCatching { onRestore() }
                            }
                            wasMinimized = minimized
                        }
                    }
                }
                runCatching { Thread.sleep(350) }
            }
        }, "vw-window-watch")
        t.isDaemon = true
        worker = t
        t.start()
    }

    fun stop() {
        running = false
        worker = null
    }

    private fun isIconified(w: Window): Boolean {
        val frame = w as? Frame ?: return false
        return (frame.extendedState and Frame.ICONIFIED) != 0
    }

    private fun findHostWindow(): Window? {
        val windows = Window.getWindows().filter { it.isDisplayable }
        windows.filterIsInstance<Frame>()
            .firstOrNull { it.title?.contains("Salt Player", ignoreCase = true) == true }
            ?.let { return it }
        // 兜底：面积最大的那个 Frame（主窗口）
        return windows.filterIsInstance<Frame>().maxByOrNull { it.width.toLong() * it.height }
    }
}
