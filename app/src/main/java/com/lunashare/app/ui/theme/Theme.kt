package com.lunashare.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
)

@Composable
fun LunaShareTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: systemDark

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    // 在**组合期**读取页面覆盖值（StatusBarOverride 的字段是 Compose 状态）。
    // 这样 LinkScreen 写入覆盖 → 本组件重组 → 下面的 SideEffect 重跑 → 状态栏才真正刷新。
    // 反例（踩过）：把读取写进 SideEffect 里，由于写值不触发重组，状态栏会一直停在旧颜色上。
    val overrideColor = StatusBarOverride.color
    val overrideLightStatusBar = StatusBarOverride.lightStatusBar
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 页面覆盖优先：Link Tab 打开 DSH 页面时状态栏要跟着页面走（见 StatusBarOverride）。
            //
            // ⚠️ 真机实测（Android 16 / SER-AN00）：`window.statusBarColor` 已经是**空操作**
            // ——写成品红后读回仍是 0，截图上那条色带纹丝不动（targetSdk 36 = 强制 edge-to-edge，
            // 这个 deprecated setter 不再绘制）。保留这行只是为了兼容未强制 edge-to-edge 的旧系统，
            // **不要**再指望它染状态栏。真正决定状态栏颜色的是最外层 Scaffold 的 containerColor
            // （见 MainActivity 里的说明）。
            window.statusBarColor = overrideColor ?: colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                overrideLightStatusBar ?: (isDark != true)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
