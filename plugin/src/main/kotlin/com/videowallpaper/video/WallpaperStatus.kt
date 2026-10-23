package com.videowallpaper.video

/** 播放状态（两个后端共用） */
enum class PlayState { Idle, Loading, Playing, Paused, Error }

/** 壁纸来源：单文件循环 / 文件夹随机池 */
enum class SourceMode { FILE, FOLDER }

/** 认得的视频扩展名（其余一律当作图片壁纸，不抢） */
val VIDEO_EXTS = setOf(
    "mp4", "m4v", "mov", "mkv", "webm", "avi", "flv", "wmv",
    "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "ogv",
)

fun isVideoPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

/**
 * 壁纸状态。UI（[com.videowallpaper.ui.VideoWallpaperSection]）只读这个对象，
 * 与具体解码后端无关：ffmpeg 版由自带解码器填充，lite 版由 SPW Media Runtime 填充。
 */
data class WallpaperStatus(
    val path: String = "",
    val state: PlayState = PlayState.Idle,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val durationSec: Double = 0.0,
    val error: String? = null,
    val mode: SourceMode = SourceMode.FILE,
    val folder: String = "",
    /** 当前是这一轮里的第几个（1-based）；单文件模式恒为 1 */
    val index: Int = 0,
    /** 这一轮一共有几个视频片段 */
    val total: Int = 0,
    /** 累计播放过的片段数（换片计数） */
    val played: Int = 0,
) {
    val isPlaylist: Boolean get() = mode == SourceMode.FOLDER && total > 1
}
