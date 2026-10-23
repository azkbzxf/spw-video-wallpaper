package com.videowallpaper.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.videowallpaper.VwConfig
import com.videowallpaper.VwLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * 壁纸播放引擎：一条后台解码线程 + 一个 Compose 状态（当前帧）。
 *
 * 节奏按帧 PTS 走（不是死循环快解），这样不同帧率的片子都能按原速播放；
 * 解码跟不上时丢帧而不是拖慢整条时间轴。
 *
 * 支持两种来源：
 *  - 单文件：播完 seek 回 0 继续（不重新打开，循环无空档）；
 *  - 文件夹：**不做自动连播**。当前片段照样自己循环，只有在窗口从最小化恢复时
 *    （见 [com.videowallpaper.WindowRestoreWatcher]）才从池子里随机换一个。
 *    这样看片中途不会被打断，又能在每次重新打开窗口时换换口味。
 */
object VideoEngine {

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    var status by mutableStateOf(WallpaperStatus())
        private set

    /** UI 侧读这个值来显示「正在播放/已暂停」 */
    val isRunning: Boolean get() = worker?.isAlive == true

    fun isVideoFile(path: String): Boolean = isVideoPath(path)

    private var worker: Thread? = null

    @Volatile private var generation = 0

    @Volatile private var pausedFlag = false

    /** 「下一个」请求：让当前片段提前收尾 */
    @Volatile private var skipFlag = false

    /** 播放速度，1.0 = 原速 */
    @Volatile var speed: Float = 1f
        set(value) {
            field = value.coerceIn(0.1f, 3f)
        }

    // ---------------- 对外 API ----------------

    fun start(path: String) {
        if (path.isBlank()) {
            stop()
            return
        }
        startInternal(listOf(path), SourceMode.FILE, folder = "", folderLabel = "")
    }

    fun startPlaylist(paths: List<String>, folder: String) {
        if (paths.isEmpty()) {
            stop()
            return
        }
        startInternal(paths, SourceMode.FOLDER, folder, folderLabel = folder)
    }

    fun stop() {
        generation++
        skipFlag = false
        stopWorker()
        frame = null
        status = WallpaperStatus()
    }

    fun pause() {
        pausedFlag = true
        VwConfig.setPaused(true)
        if (status.state == PlayState.Playing) status = status.copy(state = PlayState.Paused)
    }

    fun resume() {
        pausedFlag = false
        VwConfig.setPaused(false)
        if (status.state == PlayState.Paused) status = status.copy(state = PlayState.Playing)
    }

    fun togglePause() {
        if (pausedFlag) resume() else pause()
    }

    /** 立刻从池子里随机换一个片段（文件夹模式；单文件模式无操作） */
    fun next() {
        if (!isRunning || status.mode != SourceMode.FOLDER || status.total <= 1) return
        skipFlag = true
        VwLog.line("请求换片")
    }

    // ---------------- 内部 ----------------

    private fun startInternal(
        paths: List<String>,
        mode: SourceMode,
        folder: String,
        folderLabel: String,
    ) {
        val gen = ++generation
        skipFlag = false
        stopWorker()
        VwLog.line("引擎启动: mode=$mode 片段=${paths.size} folder=$folderLabel first=${paths.first()}")
        status = WallpaperStatus(
            path = paths.first(),
            state = PlayState.Loading,
            mode = mode,
            folder = folder,
            index = if (mode == SourceMode.FILE) 1 else 0,
            total = paths.size,
        )
        pausedFlag = VwConfig.paused
        speed = VwConfig.speed
        val t = Thread({ runLoop(paths, mode, folder, gen) }, "vw-decode")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY + 1
        worker = t
        t.start()
    }

    private fun stopWorker() {
        val w = worker ?: return
        worker = null
        runCatching { w.interrupt() }
        runCatching { w.join(1200) }
    }

    private fun runLoop(paths: List<String>, mode: SourceMode, folder: String, gen: Int) {
        if (paths.isEmpty()) return
        // 文件夹模式下随机挑一个开局；之后只有「窗口从最小化恢复」才会换
        var current = if (paths.size > 1) paths.random() else paths.first()
        var failures = 0
        var playedTotal = 0

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
                )
                val result = playOne(current, gen)
                if (gen != generation) return

                if (result.frames == 0) {
                    failures++
                    VwLog.line("片段打不开（第 $failures 次）: $current")
                    if (failures >= 3) {
                        status = status.copy(
                            state = PlayState.Error,
                            error = "视频无法播放：${current.substringAfterLast('\\')}",
                        )
                        return
                    }
                    Thread.sleep(1500)
                    if (paths.size > 1) current = pickNext(paths, current)
                    continue
                }

                failures = 0
                // 只有被「换片」打断才会走到这里；正常播放时 playOne 内部一直循环当前片段
                if (result.skipped && paths.size > 1) {
                    val nextUp = pickNext(paths, current)
                    VwLog.line("换片 -> ${nextUp.substringAfterLast('\\')}")
                    current = nextUp
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
        }
    }

    /** 从池子里随机挑一个「不是当前这个」的片段 */
    private fun pickNext(paths: List<String>, current: String): String {
        if (paths.size <= 1) return paths.firstOrNull() ?: current
        val candidates = paths.filter { it != current }
        return (candidates.ifEmpty { paths }).random()
    }

    private data class PlayResult(val frames: Int, val skipped: Boolean)

    /**
     * 循环播放一个片段，直到被「换片」打断或被中断。
     *
     * @return [PlayResult.frames] = 成功提交的帧数（0 表示这个文件根本没解开）；
     *         [PlayResult.skipped] = 是否是被 [next] 提前打断的
     */
    private fun playOne(path: String, gen: Int): PlayResult {
        var decoder: VideoDecoder? = null
        var frames = 0
        try {
            decoder = VideoDecoder(path, maxWidth = 1600)
            decoder.open()
            if (gen != generation) return PlayResult(0, false)

            VwLog.line(
                "解码器就绪 ${decoder.width}x${decoder.height} " +
                    "fps=${"%.2f".format(decoder.fps)} 时长=${"%.1f".format(decoder.durationSec)}s"
            )
            status = status.copy(
                state = PlayState.Playing,
                width = decoder.width,
                height = decoder.height,
                fps = decoder.fps,
                durationSec = decoder.durationSec,
            )

            var clockStart = System.nanoTime()
            var basePts = -1.0
            var lastPts = 0.0
            var pauseBegan = 0L
            var loops = 0

            while (true) {
                if (gen != generation) return PlayResult(frames, false)
                if (skipFlag) {
                    skipFlag = false
                    return PlayResult(frames, true)
                }
                if (pausedFlag) {
                    if (pauseBegan == 0L) {
                        pauseBegan = System.nanoTime()
                        if (status.state != PlayState.Paused) status = status.copy(state = PlayState.Paused)
                    }
                    Thread.sleep(40)
                    continue
                }
                if (pauseBegan != 0L) {
                    // 暂停期间的时间轴不推进
                    clockStart += System.nanoTime() - pauseBegan
                    pauseBegan = 0L
                    if (status.state != PlayState.Playing) status = status.copy(state = PlayState.Playing)
                }

                val ok = decoder.nextFrame({ bytes, w, h, pts ->
                    if (gen != generation) return@nextFrame
                    if (basePts < 0) basePts = if (pts >= 0) pts else 0.0
                    val rel = if (pts >= 0) pts - basePts else lastPts
                    lastPts = rel

                    val sp = speed.toDouble().coerceAtLeast(0.1)
                    val target = clockStart + (rel / sp * 1_000_000_000L).toLong()
                    val now = System.nanoTime()
                    val waitMs = (target - now) / 1_000_000
                    if (waitMs > 0) {
                        // 分片睡，保证暂停/停止/切歌能及时响应
                        var left = waitMs
                        while (left > 0 && gen == generation && !skipFlag) {
                            val chunk = left.coerceAtMost(12)
                            Thread.sleep(chunk)
                            left -= chunk
                            if (pausedFlag) break
                        }
                    } else if (waitMs < -260) {
                        // 落后太多：跳过这一帧，避免时间轴越拖越远
                        return@nextFrame
                    }

                    val bmp = toImageBitmap(bytes, w, h)
                    if (bmp != null) {
                        frame = bmp
                        frames++
                    }
                }) { gen != generation || skipFlag }

                if (!ok) {
                    if (gen != generation) return PlayResult(frames, false)
                    if (skipFlag) {
                        skipFlag = false
                        return PlayResult(frames, true)
                    }
                    // 回到开头继续循环当前片段（换片只由窗口恢复/手动触发）
                    loops++
                    if (loops % 20 == 0) VwLog.line("已循环 $loops 次")
                    decoder.rewind()
                    basePts = -1.0
                    lastPts = 0.0
                    clockStart = System.nanoTime()
                    Thread.sleep(60) // 空文件时别把 CPU 占满
                }
            }
        } catch (_: InterruptedException) {
            return PlayResult(frames, false)
        } catch (t: Throwable) {
            if (gen == generation) {
                VwLog.failure("片段播放失败: $path", t)
            }
            return PlayResult(frames, false)
        } finally {
            runCatching { decoder?.close() }
        }
    }

    private fun toImageBitmap(bytes: ByteArray, w: Int, h: Int): ImageBitmap? {
        return try {
            val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val bmp = Bitmap()
            bmp.allocPixels(info)
            bmp.installPixels(info, bytes, w * 4)
            val img = Image.makeFromBitmap(bmp)
            bmp.close()
            img.toComposeImageBitmap()
        } catch (t: Throwable) {
            VwLog.failure("帧转换失败", t)
            null
        }
    }
}
