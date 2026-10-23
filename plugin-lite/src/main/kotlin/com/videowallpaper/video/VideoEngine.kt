package com.videowallpaper.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.videowallpaper.VwConfig
import com.videowallpaper.VwLog
import dev.gaboron.spwmedia.protocol.PlayerOptions
import dev.gaboron.spwmedia.sdk.ConnectionOptions
import dev.gaboron.spwmedia.sdk.MediaBuffer
import dev.gaboron.spwmedia.sdk.MediaRuntime
import dev.gaboron.spwmedia.sdk.Player
import dev.gaboron.spwmedia.sdk.RuntimeConnection
import dev.gaboron.spwmedia.sdk.SharedBuffer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 壁纸播放引擎（Lite 版）：解码交给 **SPW Media Runtime**，本插件只负责「拿帧 → 画到壁纸层」。
 *
 * 和 FFmpeg 版的区别：
 *  - 插件包里没有 FFmpeg（28 MB → 约 250 KB），但用户必须先安装并启用
 *    [SPW Media Runtime](https://github.com/GaBoron/SPW-Media-Runtime)；
 *  - 解码/播放由 Runtime 的 libmpv 负责，这里通过 `playback.open` 创建一个无窗口、无声音的播放器，
 *    再用 `player.frames()` 拿共享帧缓冲，轮询 `latest()` 得到 BGRA 帧；
 *  - 换片直接在同一个播放器上 `load` 新文件（比例变化时才重建播放器）。
 *
 * 对外 API（status / frame / start / stop / next …）与 FFmpeg 版保持一致，
 * 所以 UI、hook、窗口监听这些代码两个版本共用。
 */
object VideoEngine {

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    var status by mutableStateOf(WallpaperStatus())
        private set

    val isRunning: Boolean get() = worker?.isAlive == true

    fun isVideoFile(path: String): Boolean = isVideoPath(path)

    // ---- 运行时连接 ----
    private var connection: RuntimeConnection? = null

    // ---- 当前会话 ----
    private var player: Player? = null
    private var buffer: SharedBuffer? = null
    private var playerWidth = 0
    private var playerHeight = 0

    private var worker: Thread? = null
    private var poller: Thread? = null

    @Volatile private var generation = 0
    @Volatile private var pausedFlag = false
    @Volatile private var skipFlag = false

    /** 输出宽度上限：Runtime 是软件渲染 + CPU 上传，1080p 全尺寸会明显吃 CPU */
    private const val MAX_FRAME_EDGE = 1280

    fun start(path: String) {
        if (path.isBlank()) {
            stop()
            return
        }
        startInternal(listOf(path), SourceMode.FILE, folder = "")
    }

    fun startPlaylist(paths: List<String>, folder: String) {
        if (paths.isEmpty()) {
            stop()
            return
        }
        startInternal(paths, SourceMode.FOLDER, folder)
    }

    fun stop() {
        generation++
        skipFlag = false
        stopWorker()
        closeSession()
        frame = null
        status = WallpaperStatus()
    }

    fun pause() {
        pausedFlag = true
        VwConfig.setPaused(true)
        runCatching { player?.pause() }
        if (status.state == PlayState.Playing) status = status.copy(state = PlayState.Paused)
    }

    fun resume() {
        pausedFlag = false
        VwConfig.setPaused(false)
        runCatching { player?.play() }
        if (status.state == PlayState.Paused) status = status.copy(state = PlayState.Playing)
    }

    fun togglePause() {
        if (pausedFlag) resume() else pause()
    }

    /** 从池子里随机换一个片段（文件夹模式；单文件模式无操作） */
    fun next() {
        if (!isRunning || status.mode != SourceMode.FOLDER || status.total <= 1) return
        skipFlag = true
        VwLog.line("请求换片")
    }

    // ---------------- 内部 ----------------

    private fun startInternal(paths: List<String>, mode: SourceMode, folder: String) {
        val gen = ++generation
        skipFlag = false
        stopWorker()
        VwLog.line("引擎启动（lite）: mode=$mode 片段=${paths.size} first=${paths.first()}")
        status = WallpaperStatus(
            path = paths.first(),
            state = PlayState.Loading,
            mode = mode,
            folder = folder,
            index = if (mode == SourceMode.FILE) 1 else 0,
            total = paths.size,
        )
        pausedFlag = VwConfig.paused
        val t = Thread({ runLoop(paths, mode, folder, gen) }, "vw-lite")
        t.isDaemon = true
        worker = t
        t.start()
    }

    private fun stopWorker() {
        val w = worker ?: return
        worker = null
        runCatching { w.interrupt() }
        runCatching { w.join(1500) }
    }

    private fun runLoop(paths: List<String>, mode: SourceMode, folder: String, gen: Int) {
        var current = if (paths.size > 1) paths.random() else paths.first()
        var lastPlayed: String? = null
        var playedTotal = 0
        var failures = 0

        try {
            while (gen == generation) {
                playedTotal++
                status = status.copy(
                    path = current,
                    mode = mode,
                    folder = folder,
                    index = paths.indexOf(current).coerceAtLeast(0) + 1,
                    total = paths.size,
                    played = playedTotal,
                    error = null,
                    state = PlayState.Loading,
                )
                val ok = playOne(current, gen)
                if (gen != generation) return

                if (!ok) {
                    failures++
                    VwLog.line("片段打不开（第 $failures 次）: $current")
                    if (failures >= 3) {
                        status = status.copy(
                            state = PlayState.Error,
                            error = status.error ?: "视频无法播放：${current.substringAfterLast('\\')}",
                        )
                        return
                    }
                    Thread.sleep(1500)
                    if (paths.size > 1) current = pickNext(paths, current)
                    continue
                }
                failures = 0
                if (skipFlag) {
                    skipFlag = false
                    if (paths.size > 1) {
                        lastPlayed = current
                        current = pickNext(paths, current)
                        VwLog.line("换片 -> ${current.substringAfterLast('\\')}")
                    }
                }
            }
        } catch (_: InterruptedException) {
            // 正常停止
        } catch (t: Throwable) {
            if (gen == generation) {
                VwLog.failure("解码线程异常", t)
                status = status.copy(state = PlayState.Error, error = t.message ?: t::class.java.simpleName)
                frame = null
            }
        } finally {
            if (gen == generation) {
                // 只有自然结束（错误）才清掉画面
            }
        }
    }

    /** 播放一个片段，阻塞到「被换片 / 被停止」为止 */
    private fun playOne(path: String, gen: Int): Boolean {
        val runtime = ensureRuntime() ?: return false
        val (w, h) = probeSize(runtime, path)
        if (player == null || w != playerWidth || h != playerHeight) {
            closeSession()
            if (!openPlayer(runtime, w, h)) return false
        }
        val p = player ?: return false

        try {
            p.load(path).toCompletableFuture().get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            VwLog.failure("加载失败: $path", t)
            status = status.copy(error = friendly(t))
            return false
        }
        if (gen != generation) return false

        val buf = runCatching { p.frames() }.getOrNull()
        if (buf == null) {
            status = status.copy(error = "无法获取视频帧")
            return false
        }
        buffer = buf
        startPoller(buf, gen)

        status = status.copy(
            state = if (pausedFlag) PlayState.Paused else PlayState.Playing,
            width = playerWidth,
            height = playerHeight,
            fps = 0.0,
            durationSec = 0.0,
        )
        VwLog.line("播放中 ${playerWidth}x$playerHeight: ${path.substringAfterLast('\\')}")
        if (pausedFlag) runCatching { p.pause() }

        // 停在这里等换片/停止；帧由 poller 线程持续搬运
        while (gen == generation && !skipFlag) {
            Thread.sleep(120)
        }
        return true
    }

    private fun ensureRuntime(): MediaRuntime? {
        if (!MediaRuntime.isInstalled()) {
            val msg = "未检测到 SPW Media Runtime —— 请先安装并启用它"
            VwLog.line(msg)
            status = status.copy(state = PlayState.Error, error = msg)
            return null
        }
        var conn = connection
        if (conn == null) {
            conn = MediaRuntime.connectWhenAvailable(
                VwConfig.pluginId,
                ConnectionOptions(),
            ) { CompletableFuture.completedFuture(null) }
            connection = conn
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val rt = conn.current
            if (rt != null) return rt
            val snap = runCatching { conn.snapshot }.getOrNull()
            if (snap != null && snap.error != null) {
                VwLog.line("Runtime 连接失败: ${snap.error?.code} ${snap.error?.message}")
            }
            Thread.sleep(200)
        }
        val msg = "连接 SPW Media Runtime 超时"
        VwLog.line(msg)
        status = status.copy(state = PlayState.Error, error = msg)
        return null
    }

    private fun openPlayer(runtime: MediaRuntime, w: Int, h: Int): Boolean {
        return try {
            val task = runtime.playback.open(
                PlayerOptions(
                    options = mapOf("audio" to "no", "loop-file" to "inf"),
                    frameWidth = w,
                    frameHeight = h,
                )
            )
            val created = task.result.toCompletableFuture().get(20, TimeUnit.SECONDS)
            player = created
            playerWidth = w
            playerHeight = h
            VwLog.line("播放器就绪 ${w}x$h")
            true
        } catch (t: Throwable) {
            VwLog.failure("创建播放器失败", t)
            status = status.copy(state = PlayState.Error, error = friendly(t))
            false
        }
    }

    /** 按视频原始比例算出输出尺寸（长边不超过 [MAX_FRAME_EDGE]，取偶数） */
    private fun probeSize(runtime: MediaRuntime, path: String): Pair<Int, Int> {
        val fallback = 1280 to 720
        return try {
            val task = runtime.processing.probe(Path.of(path))
            val info = task.result.toCompletableFuture().get(8, TimeUnit.SECONDS)
            val stream = info.streams.firstOrNull { it.type == "video" && it.width > 0 && it.height > 0 }
                ?: return fallback
            fit(stream.width, stream.height)
        } catch (t: Throwable) {
            VwLog.line("探测尺寸失败，回落 1280x720: ${t.javaClass.simpleName}")
            fallback
        }
    }

    private fun fit(srcW: Int, srcH: Int): Pair<Int, Int> {
        val longEdge = maxOf(srcW, srcH).toDouble()
        val scale = if (longEdge > MAX_FRAME_EDGE) MAX_FRAME_EDGE / longEdge else 1.0
        val w = ((srcW * scale).toInt().coerceAtLeast(2)) and 0x7FFFFFFE
        val h = ((srcH * scale).toInt().coerceAtLeast(2)) and 0x7FFFFFFE
        return w to h
    }

    private fun startPoller(buf: SharedBuffer, gen: Int) {
        runCatching { poller?.interrupt() }
        val t = Thread({
            var lastSeq = -1L
            while (gen == generation) {
                val f = runCatching { buf.latest() }.getOrNull()
                if (f != null && f.sequence != lastSeq) {
                    lastSeq = f.sequence
                    val bmp = toImageBitmap(f)
                    if (bmp != null) frame = bmp
                }
                Thread.sleep(15)
            }
        }, "vw-lite-frames")
        t.isDaemon = true
        poller = t
        t.start()
    }

    private fun closeSession() {
        runCatching { poller?.interrupt() }
        poller = null
        // 顺序按 Runtime 的约定：先停显示 → 再关 buffer → 最后关播放器
        frame = null
        runCatching { buffer?.close() }
        buffer = null
        runCatching { player?.close() }
        player = null
        playerWidth = 0
        playerHeight = 0
    }

    private fun pickNext(paths: List<String>, current: String): String {
        if (paths.size <= 1) return paths.firstOrNull() ?: current
        val candidates = paths.filter { it != current }
        return (candidates.ifEmpty { paths }).random()
    }

    private fun toImageBitmap(f: MediaBuffer): ImageBitmap? {
        return try {
            val color = if (f.bytes.size == f.width * f.height * 4) ColorType.BGRA_8888 else ColorType.BGRA_8888
            val info = ImageInfo(f.width, f.height, color, ColorAlphaType.OPAQUE)
            Image.makeRaster(info, f.bytes, f.width * 4).toComposeImageBitmap()
        } catch (t: Throwable) {
            VwLog.failure("帧转换失败", t)
            null
        }
    }

    private fun friendly(t: Throwable): String {
        var cause = t
        while (cause.cause != null && (cause is java.util.concurrent.ExecutionException ||
                cause is java.util.concurrent.CompletionException)
        ) {
            cause = cause.cause!!
        }
        val detail = runCatching { dev.gaboron.spwmedia.sdk.MediaRuntimeException.unwrap(cause)?.detail }.getOrNull()
        return detail?.let { "${it.code}: ${it.message}" } ?: (cause.message ?: cause.javaClass.simpleName)
    }
}
