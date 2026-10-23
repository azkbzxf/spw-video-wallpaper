package com.videowallpaper

import com.videowallpaper.video.SourceMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简日志：写文件 + 内存环。插件跑在宿主进程里，出问题只能靠落盘证据排查。
 */
object VwLog {

    private const val MAX_LINES = 400
    private val ring = CopyOnWriteArrayList<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS")

    @Volatile
    var logFile: File? = null

    fun init(dir: File) {
        try {
            dir.mkdirs()
            logFile = File(dir, "video-wallpaper.log")
        } catch (_: Throwable) {
        }
    }

    fun line(msg: String) {
        val text = "${stamp.format(Date())} $msg"
        ring.add(text)
        while (ring.size > MAX_LINES) ring.removeAt(0)
        try {
            logFile?.appendText(text + "\n")
        } catch (_: Throwable) {
        }
    }

    fun failure(msg: String, t: Throwable? = null) {
        if (t == null) {
            line("[ERR] $msg")
            return
        }
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().lineSequence().take(14).joinToString("\n    ")
        line("[ERR] $msg: ${t::class.java.name}: ${t.message}\n    $stack")
    }

    fun snapshot(): List<String> = ring.toList()
}

/**
 * 插件自己的持久化配置（properties 文件放在插件目录下，宿主升级不影响）。
 * 用 java.util.Properties 手写，避免依赖 Workshop ConfigManager 的 schema 约定。
 */
object VwConfig {

    private val props = java.util.Properties()
    private var file: File? = null

    /** 总开关（DIAGNOSTIC：改 false 可临时停掉注入，用于 A/B） */
    @Volatile var enabled: Boolean = true
        private set

    /** 宿主版本 —— 混淆名各版本不同，排障时先看这个 */
    @Volatile var spwVersion: String = "?"

    /** 宿主分配的插件 ID（Lite 版要用它去连 SPW Media Runtime） */
    @Volatile var pluginId: String = "com.videowallpaper.spw"

    /** 来源模式：file = 单文件循环，folder = 文件夹随机轮播 */
    @Volatile var sourceMode: SourceMode = SourceMode.FILE
        private set

    @Volatile var videoPath: String = ""
        private set

    /** 文件夹模式的目录（随机轮播的取材范围） */
    @Volatile var folderPath: String = ""
        private set

    /** 静音/暂停：true 时解码线程停在当前帧 */
    @Volatile var paused: Boolean = false
        private set
    /** 播放速度倍率（1.0 = 原速） */
    @Volatile var speed: Float = 1f
        private set
    /** 叠加暗化遮罩（0..0.6），提升前景可读性 */
    @Volatile var dim: Float = 0.18f
        private set
    /** 是否跟随宿主「壁纸材质」做模糊（由宿主开关实时读取，这里只做本地覆盖） */
    @Volatile var posterPath: String = ""
        private set

    fun init(dir: File) {
        try {
            dir.mkdirs()
            val f = File(dir, "video-wallpaper.properties")
            file = f
            if (f.isFile) {
                // 必须按 UTF-8 读：Properties.load(InputStream) 是 ISO-8859-1，
                // 中文路径会直接变乱码（用户选中文目录时踩过）
                f.reader(Charsets.UTF_8).use { props.load(it) }
            }
            videoPath = props.getProperty("videoPath", "") ?: ""
            folderPath = props.getProperty("folderPath", "") ?: ""
            sourceMode = if (props.getProperty("sourceMode", "file") == "folder") {
                SourceMode.FOLDER
            } else {
                SourceMode.FILE
            }
            enabled = props.getProperty("enabled", "true").toBoolean()
            paused = props.getProperty("paused", "false").toBoolean()
            speed = props.getProperty("speed", "1.0").toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1f
            dim = props.getProperty("dim", "0.18").toFloatOrNull()?.coerceIn(0f, 0.8f) ?: 0.18f
            posterPath = props.getProperty("posterPath", "") ?: ""
        } catch (t: Throwable) {
            VwLog.failure("读取配置失败", t)
        }
    }

    private fun persist() {
        val f = file ?: return
        try {
            props.setProperty("videoPath", videoPath)
            props.setProperty("folderPath", folderPath)
            props.setProperty("sourceMode", if (sourceMode == SourceMode.FOLDER) "folder" else "file")
            props.setProperty("enabled", enabled.toString())
            props.setProperty("paused", paused.toString())
            props.setProperty("speed", speed.toString())
            props.setProperty("dim", dim.toString())
            props.setProperty("posterPath", posterPath)
            val tmp = File(f.parentFile, f.name + ".tmp")
            f.parentFile?.mkdirs()
            // 写出同样用 UTF-8，保证中文路径能原样读回
            tmp.writer(Charsets.UTF_8).use { props.store(it, "video wallpaper") }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            VwLog.failure("写入配置失败", t)
        }
    }

    fun setVideoPath(path: String) {
        videoPath = path
        persist()
    }

    /** 文件夹模式：记录目录，同时把来源切到 folder */
    fun setFolderSource(folder: String) {
        folderPath = folder
        sourceMode = SourceMode.FOLDER
        persist()
    }

    /** 单文件模式：记录文件，同时把来源切回 file */
    fun setFileSource(path: String) {
        videoPath = path
        sourceMode = SourceMode.FILE
        persist()
    }

    fun clearSource() {
        videoPath = ""
        folderPath = ""
        sourceMode = SourceMode.FILE
        persist()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        persist()
    }

    fun setPaused(value: Boolean) {
        paused = value
        persist()
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.1f, 3f)
        persist()
    }

    fun setDim(value: Float) {
        dim = value.coerceIn(0f, 0.8f)
        persist()
    }

    fun setPoster(path: String) {
        posterPath = path
        persist()
    }
}
