package com.videowallpaper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.videowallpaper.HostBridge
import com.videowallpaper.VwConfig
import com.videowallpaper.video.PlayState
import com.videowallpaper.video.VideoEngine
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 视频壁纸渲染层。
 *
 * 挂点：宿主 `Wallpaper(modifier)` 之后（Wallpaper.desktop.kt:26）。
 * 父作用域是 `AnimatedVisibility(wallpaperFilePath.isNotEmpty())` 的 content，
 * 多个子节点重叠绘制，所以这一层正好盖在图片壁纸上、又在所有界面内容之下。
 *
 * 性能要点：帧状态只在 **draw 阶段**读取（`Canvas` 的 onDraw lambda），
 * 每帧只触发重绘、不触发重组。
 */
@Composable
fun VideoWallpaperLayer(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val bmp = VideoEngine.frame ?: return@Canvas
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || bmp.width <= 0 || bmp.height <= 0) return@Canvas

        // ContentScale.Crop：短边铺满，超出部分裁掉
        val scale = max(w / bmp.width, h / bmp.height)
        val dstW = (bmp.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (bmp.height * scale).roundToInt().coerceAtLeast(1)
        val dx = ((w - dstW) / 2f).roundToInt()
        val dy = ((h - dstH) / 2f).roundToInt()

        drawImage(
            image = bmp,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bmp.width, bmp.height),
            dstOffset = IntOffset(dx, dy),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.Low,
        )

        val dim = VwConfig.dim
        if (dim > 0f) {
            drawRect(color = Color.Black, alpha = dim)
        }
    }

    // 宿主若把壁纸路径清空（用户在壁纸页点「删除壁纸」），顺手把解码线程收掉
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            val hostPath = HostBridge.wallpaperPath()
            val enginePath = VideoEngine.status.path
            if (hostPath.isBlank() && enginePath.isNotBlank() && VideoEngine.isRunning) {
                VideoEngine.stop()
            } else if (hostPath.isNotBlank() &&
                enginePath.isNotBlank() &&
                hostPath != enginePath &&
                VideoEngine.status.state != PlayState.Error
            ) {
                // 用户换回了图片壁纸（文件夹模式下列表内切换不算）
                if (!VideoEngine.isVideoFile(hostPath)) VideoEngine.stop()
            }
        }
    }

    // 层被移出组合（壁纸被清空）时，别让解码线程空转
    DisposableEffect(Unit) {
        onDispose {
            if (HostBridge.wallpaperPath().isBlank()) VideoEngine.stop()
        }
    }
}
