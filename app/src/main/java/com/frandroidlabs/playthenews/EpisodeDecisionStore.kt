package com.frandroidlabs.playthenews

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object EpisodeDecisionStore {
    private const val PREFS = "prefs"
    private const val KEY_EVENTS = "episode_decision_events"
    private const val EVENT_KEY_PREFIX = "episode_decision_last:"
    private const val SNAPSHOT_KEY_PREFIX = "episode_snapshot_latest:"

    private const val MAX_EVENTS = 500
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

    data class EpisodeSnapshot(
        val title: String,
        val guid: String?,
        val url: String,
        val stableKey: String
    )

    data class DecisionEvent(
        val timestampMs: Long,
        val feedXmlUrl: String,
        val feedTitle: String,
        val decision: String,
        val reason: String,
        val oldEpisode: EpisodeSnapshot?,
        val newEpisode: EpisodeSnapshot?
    )

    fun saveLatestSnapshot(context: Context, feedXmlUrl: String, snapshot: EpisodeSnapshot) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString("$SNAPSHOT_KEY_PREFIX$feedXmlUrl", snapshotToJson(snapshot).toString()) }
    }

    fun latestSnapshot(context: Context, feedXmlUrl: String): EpisodeSnapshot? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$SNAPSHOT_KEY_PREFIX$feedXmlUrl", null)
            ?: return null
        return try {
            snapshotFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun appendDecision(context: Context, event: DecisionEvent) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val oldestAllowed = now - RETENTION_MS

        val existing = try {
            JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val kept = mutableListOf<JSONObject>()
        for (i in 0 until existing.length()) {
            val obj = existing.optJSONObject(i) ?: continue
            val ts = obj.optLong("timestampMs", 0L)
            if (ts >= oldestAllowed) kept.add(obj)
        }
        kept.add(eventToJson(event))

        val trimmed = JSONArray()
        val start = (kept.size - MAX_EVENTS).coerceAtLeast(0)
        for (i in start until kept.size) trimmed.put(kept[i])

        prefs.edit {
            putString(KEY_EVENTS, trimmed.toString())
            putString("$EVENT_KEY_PREFIX${event.feedXmlUrl}", eventToJson(event).toString())
        }
    }

    fun lastDecisionForFeed(context: Context, feedXmlUrl: String): DecisionEvent? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$EVENT_KEY_PREFIX$feedXmlUrl", null)
            ?: return null
        return try {
            eventFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    private fun snapshotToJson(snapshot: EpisodeSnapshot) = JSONObject().apply {
        put("title", snapshot.title)
        put("guid", snapshot.guid ?: JSONObject.NULL)
        put("url", snapshot.url)
        put("stableKey", snapshot.stableKey)
    }

    private fun snapshotFromJson(obj: JSONObject) = EpisodeSnapshot(
        title = obj.optString("title", ""),
        guid = if (obj.isNull("guid")) null else obj.optString("guid").takeIf { it.isNotBlank() },
        url = obj.optString("url", ""),
        stableKey = obj.optString("stableKey", "")
    )

    private fun eventToJson(event: DecisionEvent) = JSONObject().apply {
        put("timestampMs", event.timestampMs)
        put("feedXmlUrl", event.feedXmlUrl)
        put("feedTitle", event.feedTitle)
        put("decision", event.decision)
        put("reason", event.reason)
        put("oldEpisode", event.oldEpisode?.let { snapshotToJson(it) } ?: JSONObject.NULL)
        put("newEpisode", event.newEpisode?.let { snapshotToJson(it) } ?: JSONObject.NULL)
    }

    private fun eventFromJson(obj: JSONObject): DecisionEvent {
        val oldEpisode = obj.optJSONObject("oldEpisode")?.let { snapshotFromJson(it) }
        val newEpisode = obj.optJSONObject("newEpisode")?.let { snapshotFromJson(it) }
        return DecisionEvent(
            timestampMs = obj.optLong("timestampMs", 0L),
            feedXmlUrl = obj.optString("feedXmlUrl", ""),
            feedTitle = obj.optString("feedTitle", ""),
            decision = obj.optString("decision", "UNKNOWN"),
            reason = obj.optString("reason", ""),
            oldEpisode = oldEpisode,
            newEpisode = newEpisode
        )
    }
}



