package com.videowallpaper.video

import com.videowallpaper.VwLog
import java.io.File

/**
 * 文件夹视频清单。
 *
 * 只做一次浅层递归扫描（默认 4 层、上限 600 个文件），避免用户误选到 `C:\` 这种根目录时
 * 把线程卡死。隐藏目录与系统目录直接跳过。
 */
object VideoLibrary {

    const val DEFAULT_MAX_DEPTH = 4
    const val DEFAULT_LIMIT = 600

    fun scan(
        folder: File,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        if (!folder.isDirectory) return emptyList()
        val out = ArrayList<String>()
        try {
            val walk = folder.walkTopDown()
                .maxDepth(maxDepth)
                .onEnter { dir ->
                    val name = dir.name
                    !name.startsWith(".") &&
                        !name.equals("System Volume Information", ignoreCase = true) &&
                        !name.equals("node_modules", ignoreCase = true)
                }
                .filter { it.isFile && isVideoPath(it.name) }
            for (f in walk) {
                out.add(f.absolutePath)
                if (out.size >= limit) break
            }
        } catch (t: Throwable) {
            VwLog.failure("扫描文件夹失败: ${folder.absolutePath}", t)
        }
        return out
    }
}
