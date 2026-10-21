package com.videowallpaper

import androidx.compose.runtime.Composer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.lang.reflect.Method

/**
 * 宿主主题明暗判定。
 *
 * SPW 的主题是**应用内设置**（外观 → 主题：跟随系统 / 浅色 / 深色），和 Windows 系统深色模式解耦，
 * 所以不能用 `isSystemInDarkTheme()`。这里沿用 1.19.0 反汇编核实过的链路：
 *
 * ```
 * androidx.compose.ui.ʰ.Ԩ(Composer)  ->  androidx.compose.ui.ʹ   // 主题 -> 调色板
 * androidx.compose.ui.ʹ.Ԩ()          ->  long                    // 内容色（打包的 Compose Color）
 * ```
 *
 * 调色板颜色存在 MutableState 里，组合期读取会自动订阅主题切换。
 */
internal object HostTheme {

    /** 主题访问器类（`androidx.compose.ui.ʰ`，U+02B0） */
    private const val CLS_ACCESSOR = "androidx.compose.ui.\u02b0"

    /** 访问器方法名 `Ԩ`（U+0528）；调色板的内容色也是 `Ԩ(): long` */
    private const val M_NAME = "\u0528"

    private const val DARK_TEXT_THRESHOLD = 0.5f

    private var warned = false

    private val paletteMethod: Method? by lazy {
        runCatching {
            Class.forName(CLS_ACCESSOR).declaredMethods.firstOrNull { m ->
                m.name == M_NAME &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0].name == "androidx.compose.runtime.Composer"
            }
        }.onFailure { VwLog.line("主题访问器不可用：${it.message}") }.getOrNull()
    }

    /** @return true = 宿主当前是深色主题 */
    fun isDark(composer: Composer): Boolean {
        val pm = paletteMethod ?: return fallback("访问器缺失")
        return try {
            val palette = pm.invoke(null, composer) ?: return fallback("调色板为空")
            contentColorDark(palette) ?: medianDark(palette) ?: fallback("颜色读取失败")
        } catch (t: Throwable) {
            fallback(t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun contentColorDark(palette: Any): Boolean? = runCatching {
        val m = palette.javaClass.getDeclaredMethod(M_NAME)
        m.isAccessible = true
        val packed = m.invoke(palette) as Long
        Color(packed.toULong()).luminance() > DARK_TEXT_THRESHOLD
    }.getOrNull()

    private fun medianDark(palette: Any): Boolean? = runCatching {
        val values = palette.javaClass.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            .mapNotNull { m ->
                m.isAccessible = true
                (m.invoke(palette) as? Long)?.let { Color(it.toULong()).luminance() }
            }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        sorted[sorted.size / 2] > DARK_TEXT_THRESHOLD
    }.getOrNull()

    private fun fallback(reason: String): Boolean {
        if (!warned) {
            warned = true
            VwLog.line("明暗判定退化为深色（$reason）")
        }
        return true
    }
}
