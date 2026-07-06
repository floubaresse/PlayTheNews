package com.frandroidlabs.playthenews

import android.content.Context
import android.util.Log
import androidx.core.content.edit

object PositionStore {
    private const val PREFS = "prefs"
    private const val KEY_LAST_ACTIVE_URL = "last_active_url"
    private const val TAG = "PlayTheNews"

    fun savePosition(context: Context, url: String, positionMs: Long) {
        if (positionMs <= 0) return
        Log.d(TAG, "PositionStore.save: key=$url pos=${positionMs}ms")
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong("pos:$url", positionMs) }
    }

    fun savedPosition(context: Context, url: String): Long {
        val pos = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("pos:$url", 0L)
        Log.d(TAG, "PositionStore.read: key=$url -> ${pos}ms")
        return pos
    }

    fun saveLastActiveUrl(context: Context, url: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_LAST_ACTIVE_URL, url) }
    }

    fun lastActiveUrl(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ACTIVE_URL, null)

    fun clearAllSavedPositions(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it.startsWith("pos:") || it == KEY_LAST_ACTIVE_URL }
        if (keysToRemove.isEmpty()) return
        prefs.edit {
            keysToRemove.forEach { remove(it) }
        }
    }
}
