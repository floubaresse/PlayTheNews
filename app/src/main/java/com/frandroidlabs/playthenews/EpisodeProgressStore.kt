package com.frandroidlabs.playthenews

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Persists per-episode playback progress keyed by feed URL.
 *
 * Stored JSON per feed key:
 *   { "episodeUrl": "...", "positionMs": 12345, "completed": false }
 *
 * "completed" is true when the user listened past [COMPLETION_THRESHOLD_RATIO] of the episode.
 */
object EpisodeProgressStore {

    private const val PREFS_NAME = "episode_progress"

    /**
     * Fraction of episode that must be heard before it is considered "completed"
     * and skipped on the next playlist load.
     */
    const val COMPLETION_THRESHOLD_RATIO = 0.92

    // ----- write -----

    fun saveProgress(context: Context, feedUrl: String, episodeUrl: String, positionMs: Long, durationMs: Long) {
        val completed = durationMs > 0 && positionMs.toDouble() / durationMs >= COMPLETION_THRESHOLD_RATIO
        val json = JSONObject().apply {
            put("episodeUrl", episodeUrl)
            put("positionMs", positionMs)
            put("completed", completed)
        }
        prefs(context).edit { putString(feedUrl, json.toString()) }
    }

    fun markCompleted(context: Context, feedUrl: String, episodeUrl: String) {
        val json = JSONObject().apply {
            put("episodeUrl", episodeUrl)
            put("positionMs", 0L)
            put("completed", true)
        }
        prefs(context).edit { putString(feedUrl, json.toString()) }
    }

    // ----- read -----

    /** Returns saved state for this feed, or null if nothing is stored. */
    fun getProgress(context: Context, feedUrl: String): EpisodeProgress? {
        val raw = prefs(context).getString(feedUrl, null) ?: return null
        return try {
            val json = JSONObject(raw)
            EpisodeProgress(
                episodeUrl = json.getString("episodeUrl"),
                positionMs  = json.getLong("positionMs"),
                completed   = json.getBoolean("completed")
            )
        } catch (_: Exception) { null }
    }

    // ----- helpers -----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

data class EpisodeProgress(
    val episodeUrl: String,
    val positionMs: Long,
    val completed: Boolean
)
