package com.decloud.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages file browser view type with persistence
 */
object ViewTypeManager {

    private const val PREFS_NAME = "decloud_prefs"
    private const val KEY_VIEW_TYPE = "view_type"

    enum class ViewType(val value: Int, val displayName: String) {
        LIST(0, "List"),
        GRID(1, "Grid")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get current view type
     */
    fun getViewType(context: Context): ViewType {
        val value = getPrefs(context).getInt(KEY_VIEW_TYPE, ViewType.LIST.value)
        return ViewType.entries.find { it.value == value } ?: ViewType.LIST
    }

    /**
     * Set view type
     */
    fun setViewType(context: Context, type: ViewType) {
        getPrefs(context).edit().putInt(KEY_VIEW_TYPE, type.value).apply()
    }

    /**
     * Cycle to next view type
     */
    fun cycleViewType(context: Context): ViewType {
        val current = getViewType(context)
        val nextValue = (current.value + 1) % ViewType.entries.size
        val nextType = ViewType.entries[nextValue]
        setViewType(context, nextType)
        return nextType
    }
}
