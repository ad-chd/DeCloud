package com.decloud.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate

/**
 * Manages app theme (light/dark mode) with persistence
 */
object ThemeManager {

    private const val PREFS_NAME = "decloud_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    /** Holds a screenshot of the old theme during transition */
    var transitionBitmap: Bitmap? = null

    /** Center coordinates for the circular reveal (button location) */
    var revealCenterX: Int = 0
    var revealCenterY: Int = 0

    enum class ThemeMode(val value: Int) {
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        DARK(AppCompatDelegate.MODE_NIGHT_YES),
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get current theme mode
     */
    fun getThemeMode(context: Context): ThemeMode {
        val value = getPrefs(context).getInt(KEY_THEME_MODE, ThemeMode.LIGHT.value)
        return ThemeMode.entries.find { it.value == value } ?: ThemeMode.LIGHT
    }

    /**
     * Set and apply theme mode
     */
    fun setThemeMode(context: Context, mode: ThemeMode) {
        getPrefs(context).edit().putInt(KEY_THEME_MODE, mode.value).apply()
        AppCompatDelegate.setDefaultNightMode(mode.value)
    }

    /**
     * Toggle between light and dark mode
     */
    fun toggleTheme(context: Context): ThemeMode {
        val current = getThemeMode(context)
        val newMode = if (current == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        setThemeMode(context, newMode)
        return newMode
    }

    /**
     * Check if dark mode is currently active
     */
    fun isDarkMode(context: Context): Boolean {
        return getThemeMode(context) == ThemeMode.DARK
    }

    /**
     * Apply saved theme on app start
     */
    fun applyTheme(context: Context) {
        val mode = getThemeMode(context)
        AppCompatDelegate.setDefaultNightMode(mode.value)
    }
}
