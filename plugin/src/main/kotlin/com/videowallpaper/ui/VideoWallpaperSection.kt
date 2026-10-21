package com.videowallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videowallpaper.HostBridge
import com.videowallpaper.HostTheme
import com.videowallpaper.VwConfig
import com.videowallpaper.VwLog
import com.videowallpaper.video.PlayState
import com.videowallpaper.video.SourceMode
import com.videowallpaper.video.VideoEngine
import com.videowallpaper.video.VideoLibrary
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 壁纸设置页里的「视频壁纸」区块。
 *
 * 挂点：`WallpaperFilePanel`（androidx.compose.ui.ރ，U+0783）里 Column 内容末尾那条说明文字
 * （`WallpaperFilePanel.desktop.kt:73` 的 lambda，对 `androidx.compose.ui.ࠤ.Ϳ(String, ࠨ, Modifier, …)`
 * 的调用在整类里唯一），追加内容因此成为面板 Column 的最后一个子节点。
 */
@Composable
fun VideoWallpaperSection() {
    val composer = currentComposer
    val dark = HostTheme.isDark(composer)

    val status = VideoEngine.status
    var busy by remember { mutableStateOf(false) }
    var busyHint by remember { mutableStateOf("") }

    val cardBg = if (dark) Color(0x14FFFFFF) else Color(0x0D000000)
    val titleColor = if (dark) Color(0xFFF0F0F0) else Color(0xFF1A1A1A)
    val bodyColor = if (dark) Color(0xB3F0F0F0) else Color(0xB31A1A1A)
    val hintColor = if (dark) Color(0x80F0F0F0) else Color(0x801A1A1A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = "视频壁纸",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = titleColor),
        )
        BasicText(
            text = "选一个视频，或选一个文件夹：每次窗口从最小化恢复时，从文件夹里随机换一个。静音、铺满窗口。",
            style = TextStyle(fontSize = 12.sp, color = bodyColor, lineHeight = 17.sp),
        )

        StatusLine(status, bodyColor, hintColor)

        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = if (busy && busyHint == "file") "打开中…" else "选择视频",
                dark = dark,
                primary = true,
                enabled = !busy,
            ) {
                busy = true
                busyHint = "file"
                pickVideo { picked ->
                    busy = false
                    if (picked != null) applyVideo(picked)
                }
            }
            Spacer(Modifier.width(8.dp))
            PillButton(
                text = if (busy && busyHint == "folder") "扫描中…" else "选择文件夹",
                dark = dark,
                primary = true,
                enabled = !busy,
            ) {
                busy = true
                busyHint = "folder"
                pickFolder { folder ->
                    if (folder == null) {
                        busy = false
                        return@pickFolder
                    }
                    VwLog.line("选择文件夹: ${folder.absolutePath}")
                    scanFolderAsync(folder) { files ->
                        busy = false
                        applyFolder(folder, files)
                    }
                }
            }
        }

        if (status.state != PlayState.Idle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = if (status.state == PlayState.Paused) "继续播放" else "暂停",
                    dark = dark,
                ) {
                    VideoEngine.togglePause()
                }
                if (status.mode == SourceMode.FOLDER && status.total > 1) {
                    Spacer(Modifier.width(8.dp))
                    PillButton(text = "换一个", dark = dark) {
                        VideoEngine.next()
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillButton(text = "移除", dark = dark) {
                    clearVideo()
                }
            }
        }

        if (status.state != PlayState.Idle) {
            BasicText(
                text = if (status.mode == SourceMode.FOLDER) {
                    "随机池 ${status.total} 个视频；当前片段循环播放，每次窗口从最小化恢复时换一个。"
                } else {
                    "壁纸文件不会被复制，移动或删除原文件后需要重新选择。"
                },
                style = TextStyle(fontSize = 11.sp, color = hintColor, lineHeight = 15.sp),
            )
        }
    }
}

@Composable
private fun StatusLine(
    status: com.videowallpaper.video.WallpaperStatus,
    bodyColor: Color,
    hintColor: Color,
) {
    val (text, color) = when (status.state) {
        PlayState.Idle -> "当前未启用视频壁纸" to hintColor
        PlayState.Loading -> "正在打开视频…" to bodyColor
        PlayState.Playing, PlayState.Paused -> {
            val prefix = if (status.state == PlayState.Paused) "已暂停" else "播放中"
            val name = File(status.path).name
            val counter = if (status.mode == SourceMode.FOLDER && status.total > 1) {
                " · ${status.index}/${status.total}"
            } else {
                ""
            }
            val meta = buildString {
                if (status.width > 0) append("${status.width}×${status.height}")
                if (status.fps > 0.5) append(if (isEmpty()) "${"%.0f".format(status.fps)}fps" else " · ${"%.0f".format(status.fps)}fps")
                if (status.durationSec > 0 && status.mode == SourceMode.FILE) {
                    append(" · ${formatDuration(status.durationSec)}")
                }
            }
            ("$prefix：$name$counter" + if (meta.isNotEmpty()) "（$meta）" else "") to bodyColor
        }
        PlayState.Error -> ("无法播放：${status.error ?: "未知错误"}") to Color(0xFFE05B5B)
    }
    BasicText(
        text = text,
        style = TextStyle(fontSize = 12.sp, color = color, lineHeight = 16.sp),
    )
}

@Composable
private fun PillButton(
    text: String,
    dark: Boolean,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> if (dark) Color(0x14FFFFFF) else Color(0x0D000000)
        primary -> if (dark) Color(0xFF8FB4FF) else Color(0xFF3F6ED8)
        else -> if (dark) Color(0x22FFFFFF) else Color(0x14000000)
    }
    val fg = when {
        !enabled -> if (dark) Color(0x4DFFFFFF) else Color(0x4D000000)
        primary -> Color(0xFF10131A)
        else -> if (dark) Color(0xE6FFFFFF) else Color(0xE6000000)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg),
        )
    }
}

private fun formatDuration(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}

// ---------------- 行为 ----------------

private fun applyVideo(file: File) {
    if (!VideoEngine.isVideoFile(file.name)) {
        showToast("这不是视频文件：${file.name}", warn = true)
        return
    }
    VwLog.line("选择视频: ${file.absolutePath}")
    VwConfig.setFileSource(file.absolutePath)
    HostBridge.setWallpaperPath(file.absolutePath)
    VideoEngine.start(file.absolutePath)
}

private fun applyFolder(folder: File, files: List<String>) {
    if (files.isEmpty()) {
        showToast("这个文件夹里没有找到视频：${folder.name}", warn = true)
        VwLog.line("文件夹为空: ${folder.absolutePath}")
        return
    }
    VwLog.line("文件夹视频数: ${files.size}")
    VwConfig.setFolderSource(folder.absolutePath)
    // 宿主只认「壁纸路径非空」这一件事，随便取一个当锚点即可（层挂载后由引擎自己轮播）
    HostBridge.setWallpaperPath(files.first())
    VideoEngine.startPlaylist(files, folder.absolutePath)
    showToast("已开始随机轮播 ${files.size} 个视频")
}

private fun clearVideo() {
    VwLog.line("移除视频壁纸")
    VwConfig.clearSource()
    VideoEngine.stop()
    HostBridge.setWallpaperPath("")
}

private fun scanFolderAsync(folder: File, onDone: (List<String>) -> Unit) {
    val worker = Thread({
        val files = VideoLibrary.scan(folder)
        SwingUtilities.invokeLater { onDone(files) }
    }, "vw-scan")
    worker.isDaemon = true
    worker.start()
}

private fun pickVideo(onResult: (File?) -> Unit) {
    val worker = Thread({
        var result: File? = null
        try {
            val frames = Window.getWindows().filterIsInstance<Frame>().filter { it.isVisible }
            val owner = frames.firstOrNull { it.isActive } ?: frames.firstOrNull()
            val dialog = FileDialog(owner, "选择视频壁纸", FileDialog.LOAD)
            dialog.isMultipleMode = false
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) {
                val f = File(dir, name)
                if (f.isFile) result = f
            }
        } catch (t: Throwable) {
            VwLog.failure("打开文件选择器失败", t)
        }
        val picked = result
        SwingUtilities.invokeLater { onResult(picked) }
    }, "vw-picker")
    worker.isDaemon = true
    worker.start()
}

/** 目录选择：AWT 的 FileDialog 只能选文件，这里用 Swing 的 JFileChooser（DIRECTORIES_ONLY） */
private fun pickFolder(onResult: (File?) -> Unit) {
    val worker = Thread({
        var result: File? = null
        try {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser()
                chooser.dialogTitle = "选择视频文件夹"
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.isAcceptAllFileFilterUsed = false
                chooser.isMultiSelectionEnabled = false
                val last = VwConfig.folderPath
                if (last.isNotBlank()) {
                    val dir = File(last)
                    if (dir.isDirectory) chooser.currentDirectory = dir
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result = chooser.selectedFile
                }
            }
        } catch (t: Throwable) {
            VwLog.failure("打开文件夹选择器失败", t)
        }
        val picked = result
        SwingUtilities.invokeLater { onResult(picked) }
    }, "vw-folder-picker")
    worker.isDaemon = true
    worker.start()
}

private fun showToast(message: String, warn: Boolean = false) {
    runCatching {
        val api = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi")
        val ui = api.getMethod("ui").invoke(null)
        val type = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi\$Ui\$ToastType")
        val constant = type.enumConstants.firstOrNull {
            (it as Enum<*>).name == if (warn) "Warning" else "Success"
        }
        ui.javaClass.getMethod("toast", String::class.java, type).invoke(ui, message, constant)
    }.onFailure { VwLog.line("toast 失败: ${it.message} / $message") }
}
