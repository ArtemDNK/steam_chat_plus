package com.artemdnk.steamchatplus

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {
    const val NOTIFICATIONS = "notifications"
    const val KEEP_ALIVE = "keep_alive"
    const val DESKTOP_UA = "desktop_ua"
    const val PAGE_WIDTH = "page_width"
    const val TEXT_ZOOM = "text_zoom"
    const val CUSTOM_CSS = "custom_css"

    /** Keys whose change requires re-injecting the page script and reloading. */
    val PAGE_KEYS = setOf(DESKTOP_UA, PAGE_WIDTH, TEXT_ZOOM, CUSTOM_CSS)

    private fun sp(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun notifications(ctx: Context) = sp(ctx).getBoolean(NOTIFICATIONS, true)
    fun keepAlive(ctx: Context) = sp(ctx).getBoolean(KEEP_ALIVE, false)
    fun desktopUa(ctx: Context) = sp(ctx).getBoolean(DESKTOP_UA, true)
    fun pageWidth(ctx: Context) = sp(ctx).getString(PAGE_WIDTH, "0")?.trim()?.toIntOrNull()?.coerceIn(0, 4000) ?: 0
    fun textZoom(ctx: Context) = sp(ctx).getInt(TEXT_ZOOM, 100).coerceIn(50, 250)
    fun customCss(ctx: Context) = sp(ctx).getString(CUSTOM_CSS, "") ?: ""
}
