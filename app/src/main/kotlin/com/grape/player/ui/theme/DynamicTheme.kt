package com.grape.player.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 由封面主色生成一套 Material 3 配色，驱动全站强调色与背景。
 * 做法参考 Material Theme Builder：从种子色取色相，按固定饱和度/明度梯度派生各角色色。
 * 这样无论封面是冷色还是暖色，文字对比度都稳定可控。
 */
internal fun colorSchemeFromSeed(seed: Int?, darkTheme: Boolean): ColorScheme {
    if (seed == null) return if (darkTheme) DarkColors else LightColors

    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed, hsv)
    val hue = hsv[0]

    fun hc(saturation: Float, value: Float): Color =
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

    return if (darkTheme) {
        darkColorScheme(
            primary = hc(0.45f, 0.92f),
            onPrimary = hc(0.55f, 0.16f),
            primaryContainer = hc(0.42f, 0.32f),
            onPrimaryContainer = hc(0.30f, 0.90f),
            secondary = hc(0.22f, 0.82f),
            onSecondary = hc(0.25f, 0.18f),
            secondaryContainer = hc(0.20f, 0.30f),
            onSecondaryContainer = hc(0.20f, 0.88f),
            tertiary = Color(AndroidColor.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.35f, 0.88f))),
            surface = hc(0.08f, 0.10f),
            surfaceContainer = hc(0.08f, 0.14f),
            background = hc(0.08f, 0.09f),
            surfaceTint = hc(0.45f, 0.92f),
        )
    } else {
        lightColorScheme(
            primary = hc(0.62f, 0.62f),
            onPrimary = Color.White,
            primaryContainer = hc(0.50f, 0.90f),
            onPrimaryContainer = hc(0.62f, 0.20f),
            secondary = hc(0.28f, 0.62f),
            onSecondary = Color.White,
            secondaryContainer = hc(0.24f, 0.91f),
            onSecondaryContainer = hc(0.28f, 0.24f),
            tertiary = Color(AndroidColor.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.32f, 0.52f))),
            surface = hc(0.06f, 0.99f),
            surfaceContainer = hc(0.07f, 0.96f),
            background = hc(0.06f, 0.99f),
            surfaceTint = hc(0.62f, 0.62f),
        )
    }
}

/** 静态兜底配色（未取到主色时用） */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF625B71),
    surface = Color(0xFFFFFBFE),
    background = Color(0xFFFFFBFE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    secondary = Color(0xFFCCC2DC),
)
