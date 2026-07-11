package com.frandroidlabs.playthenews

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object PositionStore {
    private const val PREFS = "prefs"
    private const val KEY_LAST_ACTIVE_URL = "last_active_url"
    private const val KEY_POSITION_DEBUG_EVENTS = "position_debug_events"
    private const val TAG = "PlayTheNews"
    private const val BACKWARD_TOLERANCE_MS = 2000L
    private const val DEBUG_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_DEBUG_EVENTS = 500

    data class DebugEvent(
        val timestampMs: Long,
        val type: String,
        val key: String,
        val source: String,
        val oldPositionMs: Long,
        val newPositionMs: Long,
        val accepted: Boolean,
        val reason: String
    )

    fun savePosition(context: Context, url: String, positionMs: Long) {
        savePosition(
            context = context,
            url = url,
            positionMs = positionMs,
            source = "legacy",
            allowBackward = false
        )
    }

    fun savePosition(
        context: Context,
        url: String,
        positionMs: Long,
        source: String,
        allowBackward: Boolean
    ): Boolean {
        if (positionMs <= 0) return false

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldPos = prefs.getLong("pos:$url", 0L)

        val backwardJump = oldPos > 0 && positionMs + BACKWARD_TOLERANCE_MS < oldPos
        if (backwardJump && !allowBackward) {
            Log.d(
                TAG,
                "PositionStore.save BLOCKED: key=$url old=${oldPos}ms new=${positionMs}ms source=$source reason=non_user_backward"
            )
            appendDebugEvent(
                appContext,
                DebugEvent(
                    timestampMs = System.currentTimeMillis(),
                    type = "save",
                    key = url,
                    source = source,
                    oldPositionMs = oldPos,
                    newPositionMs = positionMs,
                    accepted = false,
                    reason = "non_user_backward"
                )
            )
            return false
        }

        Log.d(TAG, "PositionStore.save: key=$url pos=${positionMs}ms")
        appContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong("pos:$url", positionMs) }
        appendDebugEvent(
            appContext,
            DebugEvent(
                timestampMs = System.currentTimeMillis(),
                type = "save",
                key = url,
                source = source,
                oldPositionMs = oldPos,
                newPositionMs = positionMs,
                accepted = true,
                reason = if (allowBackward) "explicit_user_seek" else "monotonic"
            )
        )
        return true
    }

    fun savedPosition(context: Context, url: String): Long {
        val appContext = context.applicationContext
        val pos = appContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("pos:$url", 0L)
        Log.d(TAG, "PositionStore.read: key=$url -> ${pos}ms")
        return pos
    }

    fun recordRestoreAttempt(
        context: Context,
        url: String,
        targetPositionMs: Long,
        source: String,
        accepted: Boolean,
        reason: String
    ) {
        val oldPos = savedPosition(context, url)
        appendDebugEvent(
            context.applicationContext,
            DebugEvent(
                timestampMs = System.currentTimeMillis(),
                type = "restore",
                key = url,
                source = source,
                oldPositionMs = oldPos,
                newPositionMs = targetPositionMs,
                accepted = accepted,
                reason = reason
            )
        )
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

    fun clearSavedPositionsForKeys(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val uniqueKeys = keys.filter { it.isNotBlank() }.toSet()
        if (uniqueKeys.isEmpty()) return

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastActive = prefs.getString(KEY_LAST_ACTIVE_URL, null)
        prefs.edit {
            uniqueKeys.forEach { remove("pos:$it") }
            if (lastActive != null && uniqueKeys.contains(lastActive)) {
                remove(KEY_LAST_ACTIVE_URL)
            }
        }
    }

    fun recentDebugEvents(
        context: Context,
        keyFilter: String? = null,
        limit: Int = 20
    ): List<DebugEvent> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_POSITION_DEBUG_EVENTS, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val events = mutableListOf<DebugEvent>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val event = eventFromJson(obj) ?: continue
            if (keyFilter == null || event.key == keyFilter) {
                events.add(event)
            }
        }
        return events.takeLast(limit)
    }

    private fun appendDebugEvent(context: Context, event: DebugEvent) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val oldestAllowed = now - DEBUG_RETENTION_MS

        val existing = try {
            JSONArray(prefs.getString(KEY_POSITION_DEBUG_EVENTS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val kept = mutableListOf<JSONObject>()
        for (i in 0 until existing.length()) {
            val obj = existing.optJSONObject(i) ?: continue
            if (obj.optLong("timestampMs", 0L) >= oldestAllowed) kept.add(obj)
        }
        kept.add(eventToJson(event))

        val trimmed = JSONArray()
        val start = (kept.size - MAX_DEBUG_EVENTS).coerceAtLeast(0)
        for (i in start until kept.size) trimmed.put(kept[i])

        prefs.edit {
            putString(KEY_POSITION_DEBUG_EVENTS, trimmed.toString())
        }
    }

    private fun eventToJson(event: DebugEvent) = JSONObject().apply {
        put("timestampMs", event.timestampMs)
        put("type", event.type)
        put("key", event.key)
        put("source", event.source)
        put("oldPositionMs", event.oldPositionMs)
        put("newPositionMs", event.newPositionMs)
        put("accepted", event.accepted)
        put("reason", event.reason)
    }

    private fun eventFromJson(obj: JSONObject): DebugEvent? {
        val key = obj.optString("key", "")
        if (key.isBlank()) return null
        return DebugEvent(
            timestampMs = obj.optLong("timestampMs", 0L),
            type = obj.optString("type", "unknown"),
            key = key,
            source = obj.optString("source", "unknown"),
            oldPositionMs = obj.optLong("oldPositionMs", 0L),
            newPositionMs = obj.optLong("newPositionMs", 0L),
            accepted = obj.optBoolean("accepted", false),
            reason = obj.optString("reason", "")
        )
    }
}
