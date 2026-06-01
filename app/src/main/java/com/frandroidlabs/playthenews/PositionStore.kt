package com.frandroidlabs.playthenews

import android.content.Context
import androidx.core.content.edit

object PositionStore {
    private const val PREFS = "prefs"
    private const val KEY_LAST_ACTIVE_URL = "last_active_url"

    fun savePosition(context: Context, url: String, positionMs: Long) {
        if (positionMs <= 0) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong("pos:$url", positionMs) }
    }

    fun savedPosition(context: Context, url: String): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("pos:$url", 0L)

    fun saveLastActiveUrl(context: Context, url: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_LAST_ACTIVE_URL, url) }
    }

    fun lastActiveUrl(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ACTIVE_URL, null)
}
