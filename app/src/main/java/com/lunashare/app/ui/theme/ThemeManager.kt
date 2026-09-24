package com.lunashare.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Theme mode options for the app.
 */
enum class ThemeMode {
    LIGHT,      // Force light theme
    DARK,       // Force dark theme
    SYSTEM      // Follow system setting
}

/**
 * Manages app theme state, persisting user preference to SharedPreferences.
 */
class ThemeManager(context: Context) {
    private val prefs = context.getSharedPreferences("lunashare_settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(loadThemeMode())
        private set

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.SYSTEM }
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun isDarkTheme(): Boolean? {
        return when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> null
        }
    }
}
