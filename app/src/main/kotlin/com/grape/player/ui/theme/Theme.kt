package com.grape.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 应用主题 —— 支持封面主色驱动。
 *
 * @param seedRgb 封面提取出的 ARGB 主色；null 时用默认紫色系。
 * @param dynamicColorEnabled 用户是否开启了「封面取色」（设置页可关）。
 */
@Composable
fun WebDAVPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedRgb: Int? = null,
    dynamicColorEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val effectiveSeed = if (dynamicColorEnabled) seedRgb else null
    val scheme = remember(darkTheme, effectiveSeed) {
        colorSchemeFromSeed(effectiveSeed, darkTheme)
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
