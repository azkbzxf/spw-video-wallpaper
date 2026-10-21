package com.videowallpaper.hook

import androidx.compose.runtime.Composable
import com.videowallpaper.VwLog
import com.videowallpaper.ui.VideoWallpaperLayer
import com.videowallpaper.ui.VideoWallpaperSection
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall

/**
 * 两个注入点，都基于 1.19.0 反汇编核实（类名/方法名均为 R8 后的单字符，码点必须精确）。
 *
 * ## 1) 壁纸渲染层
 *
 * ```
 * com.xuncorp.voxzen.ui.main.Wallpaper (Wallpaper.desktop.kt:26)   = androidx.compose.ui.ϰ (U+03F0)
 *   fun Wallpaper(modifier: Modifier = Modifier)
 *
 * androidx.compose.ui.Պ (U+054A = MainWindowContentKt)
 *   private fun Ϳ(AnimatedVisibilityScope, Composer, int) {
 *       AnimatedVisibility(visible = wallpaperFilePath.isNotEmpty(), fadeIn/fadeOut 1000ms) {
 *           Wallpaper(Modifier)          // ← 全类唯一调用点（offset 57）
 *       }
 *   }
 * ```
 *
 * 追加内容因此与图片壁纸同级、同尺寸重叠，绘制在其后 → 盖住图片壁纸、位于所有界面内容之下。
 *
 * ## 2) 壁纸设置面板
 *
 * ```
 * com.xuncorp.voxzen.ui.screen.appearance.ރ (U+0783 = WallpaperFilePanel.desktop.kt)
 *   Column {
 *       ...选择壁纸图片 / 壁纸材质 / 删除壁纸 / 说明文字
 *       ࠤ(U+0824).Ϳ(String, ࠨ(U+0828), Modifier, …)   // ← 说明文字，全类唯一（签名唯一）
 *   }
 * ```
 *
 * 追加内容成为 Column 的最后一个子节点，即面板底部的「视频壁纸」卡片。
 */
@OptIn(UnstableSpwWorkshopApi::class)
object Hooks {

    /** androidx.compose.ui.ϰ —— Wallpaper.desktop.kt 的 Wallpaper(modifier) */
    private const val CLS_WALLPAPER = "androidx.compose.ui.\u03F0"

    /** androidx.compose.ui.Պ —— MainWindowContentKt（壁纸调用点所在类） */
    private const val CLS_MAIN_CONTENT = "androidx.compose.ui.\u054A"

    /** androidx.compose.ui.ރ —— WallpaperFilePanel */
    private const val CLS_WALLPAPER_PANEL = "com.xuncorp.voxzen.ui.screen.appearance.\u0783"

    /** androidx.compose.ui.ࠤ —— 面板里的设置项/说明文字 */
    private const val CLS_PANEL_ITEM = "androidx.compose.ui.\u0824"

    /** androidx.compose.ui.ࠨ —— 上面那个方法的第二个参数类型 */
    private const val CLS_PANEL_ITEM_ARG = "androidx.compose.ui.\u0828"

    /** 宿主所有方法名都是 Ϳ = U+037F（希腊问号），不是 ѿ = U+047F */
    private const val M_NAME = "\u037F"

    private var wallpaperHandle: HookHandle? = null
    private var panelHandle: HookHandle? = null

    val wallpaperMounted: Boolean get() = wallpaperHandle != null
    val panelMounted: Boolean get() = panelHandle != null
    val mounted: Boolean get() = wallpaperMounted && panelMounted

    fun registerWallpaper(): Boolean {
        if (wallpaperHandle != null) return true
        if (!ensurePermission("壁纸渲染层")) return false
        return runCatching {
            wallpaperHandle = WorkshopApi.hookRegistrar.afterComposable(
                CLS_WALLPAPER,
                M_NAME,
                CLS_MAIN_CONTENT,
                listOf("androidx.compose.ui.Modifier"),
                50,
                object : ComposableHook() {
                    @Composable
                    override fun Content(call: UiHookCall) {
                        if (!enabled()) return
                        VideoWallpaperLayer()
                    }
                },
            )
            VwLog.line("壁纸渲染层挂载成功")
            true
        }.onFailure {
            VwLog.failure("壁纸渲染层挂载失败", it)
        }.getOrDefault(false)
    }

    fun registerPanel(): Boolean {
        if (panelHandle != null) return true
        if (!ensurePermission("壁纸设置面板")) return false
        return runCatching {
            panelHandle = WorkshopApi.hookRegistrar.afterComposable(
                CLS_PANEL_ITEM,
                M_NAME,
                CLS_WALLPAPER_PANEL,
                listOf("java.lang.String", CLS_PANEL_ITEM_ARG, "androidx.compose.ui.Modifier"),
                50,
                object : ComposableHook() {
                    @Composable
                    override fun Content(call: UiHookCall) {
                        if (!enabled()) return
                        VideoWallpaperSection()
                    }
                },
            )
            VwLog.line("壁纸设置面板挂载成功")
            true
        }.onFailure {
            VwLog.failure("壁纸设置面板挂载失败", it)
        }.getOrDefault(false)
    }

    private fun enabled(): Boolean = com.videowallpaper.VwConfig.enabled

    private fun ensurePermission(what: String): Boolean {
        val granted = runCatching {
            WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
        }.getOrDefault(false)
        if (!granted) VwLog.line("CLASS_TRANSFORM 未授权，无法注入$what")
        return granted
    }

    fun unregister() {
        runCatching { wallpaperHandle?.unhook() }
        runCatching { panelHandle?.unhook() }
        wallpaperHandle = null
        panelHandle = null
    }
}
