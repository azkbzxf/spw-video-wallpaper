package com.videowallpaper.hook

import androidx.compose.runtime.Composable
import com.videowallpaper.VwConfig
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
 * 两个注入点。类名/方法名都是 R8 后的单字符混淆名，码点必须精确。
 *
 * **这些名字会随宿主版本变**：1.19.0 → 1.19.2 就把 `Wallpaper` 从 U+03F0 改成了 U+03CD，
 * 调用点从 U+054A 改成了 U+0450（方法名 `Ϳ` U+037F 倒是两版一致）。
 * 所以每个注入点都是一组 [UiPoint] 候选，注册时逐个试，第一个成功的生效。
 * 宿主再换名字时，只要往列表里补一条即可，不用改动别处。
 *
 * ## 1) 壁纸渲染层
 *
 * ```
 * com.xuncorp.voxzen.ui.main.Wallpaper (Wallpaper.desktop.kt:26)
 *   fun Wallpaper(modifier: Modifier = Modifier)
 *
 * <MainWindowContentKt>.Ϳ(AnimatedVisibilityScope, Composer, int) {
 *     AnimatedVisibility(visible = wallpaperFilePath.isNotEmpty(), fadeIn/fadeOut 1000ms) {
 *         Wallpaper(Modifier)          // ← 全类唯一调用点（两版都在 offset 57）
 *     }
 * }
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
 * 这一组名字在 1.19.0 / 1.19.2 上都没变。
 */
/**
 * 一个注入点的候选描述。
 *
 * 宿主的混淆名**每个版本都可能变**：1.19.0 → 1.19.2 就把 `Wallpaper` 从
 * `ϰ`(U+03F0) 改成了 `ύ`(U+03CD)、调用点从 `Պ`(U+054A) 改成了 `ѐ`(U+0450)。
 * 所以这里不写死单一名字，而是一组候选：注册时逐个尝试，第一个成功的生效。
 */
private data class UiPoint(
    val targetClass: String,
    val callSiteClass: String,
    val parameterTypes: List<String>,
    val label: String,
) {
    /** 日志里用码点显示，避免混淆字符肉眼难辨 */
    val summary: String
        get() = buildString {
            append(short(targetClass))
            append('@')
            append(short(callSiteClass))
        }

    private fun short(name: String): String {
        val simple = name.substringAfterLast('.')
        if (simple.length == 1) return "%s(U+%04X)".format(simple, simple[0].code)
        // com.xuncorp.… 这种没被混淆的类，直接用简名
        return simple
    }
}

@OptIn(UnstableSpwWorkshopApi::class)
object Hooks {

    /** 宿主所有方法名都是 Ϳ = U+037F（希腊问号），不是 ѿ = U+047F */
    private const val M_NAME = "\u037F"

    /**
     * 壁纸渲染层：在 `Wallpaper(modifier)` 的调用点之后追加绘制层。
     * 顺序 = 优先匹配新版本，失败再退到旧版。
     */
    private val WALLPAPER_POINTS = listOf(
        // SPW 1.19.2（Steam 版实测）：
        //   Wallpaper = androidx.compose.ui.ύ (U+03CD)
        //   调用点   = androidx.compose.ui.ѐ (U+0450, MainWindowContentKt)，全类唯一调用
        UiPoint(
            "androidx.compose.ui.\u03CD",
            "androidx.compose.ui.\u0450",
            listOf("androidx.compose.ui.Modifier"),
            "1.19.2",
        ),
        // SPW 1.19.0：
        //   Wallpaper = androidx.compose.ui.ϰ (U+03F0)
        //   调用点   = androidx.compose.ui.Պ (U+054A)
        UiPoint(
            "androidx.compose.ui.\u03F0",
            "androidx.compose.ui.\u054A",
            listOf("androidx.compose.ui.Modifier"),
            "1.19.0",
        ),
    )

    /**
     * 壁纸设置面板：在说明文字之后追加「视频壁纸」卡片。
     * 1.19.0 与 1.19.2 这两个名字没变，但同样留成候选列表，方便以后加。
     */
    private val PANEL_POINTS = listOf(
        UiPoint(
            "androidx.compose.ui.\u0824",                                  // ࠤ 面板设置项/说明文字
            "com.xuncorp.voxzen.ui.screen.appearance.\u0783",              // ރ WallpaperFilePanel
            listOf(
                "java.lang.String",
                "androidx.compose.ui.\u0828",                              // ࠨ 上面方法的第二个参数类型
                "androidx.compose.ui.Modifier",
            ),
            "1.19.0–1.19.2",
        ),
    )

    private var wallpaperHandle: HookHandle? = null
    private var panelHandle: HookHandle? = null

    val wallpaperMounted: Boolean get() = wallpaperHandle != null
    val panelMounted: Boolean get() = panelHandle != null
    val mounted: Boolean get() = wallpaperMounted && panelMounted

    fun registerWallpaper(): Boolean {
        if (wallpaperHandle != null) return true
        if (!ensurePermission("壁纸渲染层")) return false
        for (point in WALLPAPER_POINTS) {
            val ok = runCatching {
                wallpaperHandle = WorkshopApi.hookRegistrar.afterComposable(
                    point.targetClass,
                    M_NAME,
                    point.callSiteClass,
                    point.parameterTypes,
                    50,
                    object : ComposableHook() {
                        @Composable
                        override fun Content(call: UiHookCall) {
                            if (!enabled()) return
                            VideoWallpaperLayer()
                        }
                    },
                )
                true
            }.onFailure {
                VwLog.line("壁纸渲染层候选 ${point.summary}（${point.label}）不可用：${it.message}")
            }.getOrDefault(false)
            if (ok) {
                VwLog.line("壁纸渲染层挂载成功：${point.summary}（宿主 ${VwConfig.spwVersion}）")
                return true
            }
        }
        VwLog.line("壁纸渲染层所有候选都不匹配（宿主 ${VwConfig.spwVersion}），需要重新适配混淆名")
        return false
    }

    fun registerPanel(): Boolean {
        if (panelHandle != null) return true
        if (!ensurePermission("壁纸设置面板")) return false
        for (point in PANEL_POINTS) {
            val ok = runCatching {
                panelHandle = WorkshopApi.hookRegistrar.afterComposable(
                    point.targetClass,
                    M_NAME,
                    point.callSiteClass,
                    point.parameterTypes,
                    50,
                    object : ComposableHook() {
                        @Composable
                        override fun Content(call: UiHookCall) {
                            if (!enabled()) return
                            VideoWallpaperSection()
                        }
                    },
                )
                true
            }.onFailure {
                VwLog.line("壁纸设置面板候选 ${point.summary}（${point.label}）不可用：${it.message}")
            }.getOrDefault(false)
            if (ok) {
                VwLog.line("壁纸设置面板挂载成功：${point.summary}（宿主 ${VwConfig.spwVersion}）")
                return true
            }
        }
        VwLog.line("壁纸设置面板所有候选都不匹配（宿主 ${VwConfig.spwVersion}），需要重新适配混淆名")
        return false
    }

    private fun enabled(): Boolean = VwConfig.enabled

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
