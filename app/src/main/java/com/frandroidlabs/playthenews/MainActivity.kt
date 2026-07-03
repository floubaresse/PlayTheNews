package com.frandroidlabs.playthenews

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL
import kotlinx.coroutines.*

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionToken
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.common.MediaMetadata
import androidx.core.net.toUri
import kotlinx.coroutines.sync.withPermit
import java.text.DateFormat
import java.util.Date


data class Track(
    val title: String,
    val url: String,
    var feed: FeedInfo? = null,
    val iconUrl: String?,
    val guid: String? = null
) {
    val stableKey: String get() = guid?.takeIf { it.isNotBlank() } ?: url
}
data class FeedInfo(val title: String, val xmlUrl: String, val iconUrl: String?)


fun parseOpml(opml: String): List<FeedInfo> {
    val result = mutableListOf<FeedInfo>()
    val factory = XmlPullParserFactory.newInstance()
    val parser = factory.newPullParser()
    parser.setInput(opml.reader())

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "outline") {
            val title =
                parser.getAttributeValue(null, "title") ?: parser.getAttributeValue(null, "text")
                ?: "Untitled"
            val xmlUrl = parser.getAttributeValue(null, "xmlUrl")

            if (!xmlUrl.isNullOrBlank()) {
                result.add(FeedInfo(title, xmlUrl, null))
            }
        }
        eventType = parser.next()
    }
    return result
}

suspend fun extractTrackFromRss(rssUrl: String, fallbackTitle: String): Track? =
    withContext(Dispatchers.IO) {
        try {
            val url = URL(rssUrl)
            val stream = url.openStream()
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(stream.reader())

            var title: String? = null
            var enclosureUrl: String? = null
            var iconInItem: String? = null
            var channelIcon: String? = null
            var guid: String? = null
            var insideItem = false
            var insideChannel = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> insideChannel = true
                            "item"    -> insideItem = true
                            "title"   -> if (insideItem && title == null) {
                                title = parser.nextText()
                            }
                            "guid"    -> if (insideItem && guid == null) {
                                guid = parser.nextText()
                            }
                            "enclosure" -> if (insideItem && enclosureUrl == null) {
                                val type = parser.getAttributeValue(null, "type")
                                if (type != null && type.startsWith("audio/")) {
                                    enclosureUrl = parser.getAttributeValue(null, "url")
                                }
                            }
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (insideItem && iconInItem == null) iconInItem = href
                                else if (insideChannel && channelIcon == null) channelIcon = href
                            }
                            "image" -> if (insideChannel && channelIcon == null) {
                                var imgEvent = parser.next()
                                while (!(imgEvent == XmlPullParser.END_TAG && parser.name == "image")) {
                                    if (imgEvent == XmlPullParser.START_TAG && parser.name == "url") {
                                        channelIcon = parser.nextText()
                                    }
                                    imgEvent = parser.next()
                                }
                            }
                            "media:thumbnail", "media:content" -> {
                                val urlAttr = parser.getAttributeValue(null, "url")
                                if (insideItem && iconInItem == null) iconInItem = urlAttr
                                else if (insideChannel && channelIcon == null) channelIcon = urlAttr
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && insideItem) break
                    }
                }
                eventType = parser.next()
            }
            stream.close()

            return@withContext if (enclosureUrl != null) {
                Track(
                    title   = title ?: fallbackTitle,
                    url     = enclosureUrl,
                    iconUrl = iconInItem ?: channelIcon,
                    guid    = guid
                )
            } else null
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

fun loadPlaylistFromOpml(opml: String, callback: (List<Track>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        val feeds = parseOpml(opml)

        // Launch all RSS fetches in parallel
        val semaphore = kotlinx.coroutines.sync.Semaphore(6) // max 6 concurrent fetches

        val deferreds = feeds.map { feed ->
            async {
                semaphore.withPermit {
                    val track = extractTrackFromRss(feed.xmlUrl, feed.title)
                    if (track != null) track.feed = feed
                    track
                }
            }
        }

        // Wait for all of them, preserving original order
        val playlist = deferreds.awaitAll().filterNotNull()

        withContext(Dispatchers.Main) {
            callback(playlist)
        }
    }
}

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val TAG = "PlayTheNews"
    private val playlist = mutableListOf<Track>()

    private lateinit var urlRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private lateinit var playerView: PlayerView
    private lateinit var playlistItemTouchHelper: ItemTouchHelper

    private var currentTrackIndex: Int? = null   // null = nothing highlighted yet
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaylistInitialized = false
    private var isEditMode = false
    private var forcedStartIndexOnNextPlaylistSet: Int? = null

    // See playFromTrack() / onMediaItemTransition for why this flag exists
    private var positionAlreadyRestored = false

    private val ASSUMED_DURATION_MS = 60 * 60 * 1000L // 60 min fallback

    private data class EpisodeChangeOutcome(
        val firstNewIndex: Int?,
        val changedFeeds: Int
    )

    // -------------------------------------------------------------------------
    // Playback position persistence
    // -------------------------------------------------------------------------

    private fun saveCurrentPosition() {
        val p = playerView.player ?: return
        val idx = p.currentMediaItemIndex
        if (idx in playlist.indices) {
            val key = playlist[idx].stableKey
            val pos = p.currentPosition
            PositionStore.savePosition(applicationContext, key, pos)
            PositionStore.saveLastActiveUrl(applicationContext, key)
            Log.d(TAG, "saveCurrentPosition: $key -> ${pos}ms")
        }
    }

    // -------------------------------------------------------------------------
    // Activity result launchers
    // -------------------------------------------------------------------------

    private val searchResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Returned from SearchActivity with new data. Reloading podcasts.")
            val prefs = applicationContext.getSharedPreferences("prefs", MODE_PRIVATE)
            val opmlContent = prefs.getString("opml", null)
            if (opmlContent != null) updateList(opmlContent)
        }
    }

    val OPML_MIME = "*/*"
    val pickOpmlLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val content = readOpmlFromUri(it)
            if (content != null) {
                updateList(content)
                playlistAdapter.updateTracks(playlist)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView    = findViewById(R.id.player_view)
        urlRecyclerView = findViewById(R.id.urlRecyclerView)

        playlistAdapter = PlaylistAdapter(
            playlist.toMutableList(),
            null,   // nothing highlighted at startup
            onTrackClick  = { position -> playFromTrack(position) },
            onItemMoved   = { fromPos, toPos -> onPlaylistItemMoved(fromPos, toPos) },
            onItemDeleted = { position -> onPlaylistItemDeleted(position) }
        )
        urlRecyclerView.layoutManager = LinearLayoutManager(this)
        urlRecyclerView.adapter = playlistAdapter
        // Disable the cross-fade animation that fires on every notifyItemChanged call
        (urlRecyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        val touchHelperCallback = PlaylistItemTouchHelper(playlistAdapter)
        playlistItemTouchHelper = ItemTouchHelper(touchHelperCallback)
        playlistItemTouchHelper.attachToRecyclerView(urlRecyclerView)
        playlistAdapter.setItemTouchHelper(playlistItemTouchHelper)

        supportActionBar?.title = ""
    }

    override fun onStart() {
        super.onStart()

        val sessionToken = SessionToken(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java)
        )

        val prefs = applicationContext.getSharedPreferences("prefs", MODE_PRIVATE)
        val opmlContent = prefs.getString("opml", null)
            ?: assets.open("subscriptions.opml").bufferedReader().use { it.readText() }

        controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()

        controllerFuture.addListener({
            val controller = controllerFuture.get()
            playerView.player = controller
            Log.d(TAG, "onStart: got player, mediaItemCount=${controller.mediaItemCount}")

            if (!isPlaylistInitialized || controller.mediaItemCount == 0) {
                Log.d(TAG, "onStart: (re)initialising playlist")
                isPlaylistInitialized = true
                updateList(opmlContent)
            }

            playerView.setControllerShowTimeoutMs(0)
            playerView.controllerAutoShow = true
            controller.prepare()

            // Sync highlight from actual player state on reconnect
            if (controller.mediaItemCount > 0) {
                currentTrackIndex = controller.currentMediaItemIndex
                playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
            }

            updatePlaybackControls()

            controller.addListener(object : Player.Listener {

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    runOnUiThread {
                        val newIndex = playerView.player?.currentMediaItemIndex
                        Log.d(TAG, "onMediaItemTransition: newIndex=$newIndex reason=$reason positionAlreadyRestored=$positionAlreadyRestored")
                        if (newIndex != null && newIndex != currentTrackIndex) {
                            // Do not call saveCurrentPosition() here: by the time this
                            // callback fires the player has already moved to newIndex, so
                            // currentMediaItemIndex returns the NEW track and we would
                            // overwrite the new track's position with 0. The service's
                            // periodic save has already captured the old track within 1s.
                            currentTrackIndex = newIndex
                            playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
                            updateTitleAndButtons()
                        }
                        if (positionAlreadyRestored) {
                            positionAlreadyRestored = false
                        } else if (newIndex != null && newIndex in playlist.indices) {
                            val savedPos = PositionStore.savedPosition(applicationContext, playlist[newIndex].stableKey)
                            if (savedPos > 0) {
                                Log.d(TAG, "onMediaItemTransition restore: index=$newIndex pos=${savedPos}ms")
                                playerView.player?.seekTo(savedPos)
                            }
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    runOnUiThread {
                        Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                        updateTitleAndButtons()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        Log.d(TAG, "onIsPlayingChanged: $isPlaying")
                        val newIndex = playerView.player?.currentMediaItemIndex
                        if (newIndex != null && newIndex != currentTrackIndex) {
                            currentTrackIndex = newIndex
                            playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
                        }
                        updateTitleAndButtons()
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    override fun onResume() {
        super.onResume()
        val actualIndex = playerView.player?.currentMediaItemIndex
        if (actualIndex != null && actualIndex != currentTrackIndex) {
            currentTrackIndex = actualIndex
        }
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
        handler.removeCallbacksAndMessages(null)
        MediaController.releaseFuture(controllerFuture)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------------------------
    // Playback controls
    // -------------------------------------------------------------------------

    private fun playTheNews() {
        playerView.player?.prepare()
        playerView.player?.playWhenReady = true
    }

    /**
     * Polls every second to:
     *  1. Detect index changes (drives highlight + title).
     *  2. Push exact progress to the active track's progress bar.
     *  3. Persist playback position while playing.
     *
     * setCurrentlyPlayingIndex is only called when the index actually changes,
     * so the currently-playing row is never needlessly rebound — no more flash.
     */
    private fun updatePlaybackControls() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = playerView.player ?: return

                val currentItemIndex = p.currentMediaItemIndex

                // Only notify the adapter when the index actually changes
                if (currentItemIndex != currentTrackIndex) {
                    Log.d(TAG, "updatePlaybackControls: index $currentTrackIndex -> $currentItemIndex")
                    currentTrackIndex = currentItemIndex
                    playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
                    updateTitleAndButtons()
                }

                // Push exact progress for the active track (partial bind — no flash)
                if (currentItemIndex in playlist.indices) {
                    val posMs = p.currentPosition
                    val durMs = p.duration.takeIf { it > 0 } ?: ASSUMED_DURATION_MS
                    val progress = ((posMs.toFloat() / durMs) * 1000).toInt().coerceIn(0, 1000)
                    playlistAdapter.setTrackProgress(playlist[currentItemIndex].stableKey, progress)
                }

                if (p.isPlaying) saveCurrentPosition()

                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    fun playFromTrack(index: Int?) {
        if (index == null || index !in playlist.indices) return

        Log.d(TAG, "playFromTrack: $index")
        currentTrackIndex = index
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)

        val savedPos = PositionStore.savedPosition(applicationContext, playlist[index].stableKey)
        if (savedPos > 0) {
            Log.d(TAG, "playFromTrack: resuming at ${savedPos}ms")
            positionAlreadyRestored = true
            playerView.player?.seekTo(index, savedPos)
        } else {
            playerView.player?.seekTo(index, 0)
        }

        playTheNews()
    }

    // -------------------------------------------------------------------------
    // Playlist management
    // -------------------------------------------------------------------------

    fun setPlaylist() {
        playerView.player?.clearMediaItems()

        playlist.forEach { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.feed?.title ?: "Podcast")
                .apply {
                    if (!track.iconUrl.isNullOrBlank())
                        setArtworkUri(track.iconUrl.toUri())
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(track.url)
                .setMediaId(track.stableKey)
                .setMediaMetadata(metadata)
                .build()

            playerView.player?.addMediaItem(mediaItem)
        }

        playerView.player?.prepare()

        val forcedStartIndex = forcedStartIndexOnNextPlaylistSet
        if (forcedStartIndex != null && forcedStartIndex in playlist.indices) {
            playerView.player?.seekTo(forcedStartIndex, 0L)
            val forcedTrack = playlist[forcedStartIndex]
            val feedUrl = forcedTrack.feed?.xmlUrl
            val event = feedUrl?.let { EpisodeDecisionStore.lastDecisionForFeed(applicationContext, it) }
            Log.i(
                TAG,
                "NEW_EPISODE_PLAY: feed=${forcedTrack.feed?.title ?: "unknown"} " +
                    "old=${event?.oldEpisode?.title ?: "(none)"} " +
                    "new=${event?.newEpisode?.title ?: forcedTrack.title} " +
                    "reason=${event?.reason ?: "forced_start"}"
            )
            forcedStartIndexOnNextPlaylistSet = null
            currentTrackIndex = null
            playlistAdapter.setCurrentlyPlayingIndex(null)
            return
        }

        // Seek once to the correct starting track. Using lastActiveUrl we can
        // pinpoint the track the user was on when the app last stopped; the
        // fallback is the last track in the list that has any saved position
        // (preserves the behaviour that existed before lastActiveUrl was stored).
        // Positions for other tracks with saved data are restored individually
        // by onMediaItemTransition as the player advances to each one.
        val lastKey = PositionStore.lastActiveUrl(applicationContext)
        val startIndex: Int? = when {
            lastKey != null -> {
                val idx = playlist.indexOfFirst { it.stableKey == lastKey }
                if (idx >= 0 && PositionStore.savedPosition(applicationContext, playlist[idx].stableKey) > 0)
                    idx
                else
                    playlist.indexOfLast { PositionStore.savedPosition(applicationContext, it.stableKey) > 0 }.takeIf { it >= 0 }
            }
            else -> playlist.indexOfLast { PositionStore.savedPosition(applicationContext, it.stableKey) > 0 }.takeIf { it >= 0 }
        }
        if (startIndex != null) {
            val savedPos = PositionStore.savedPosition(applicationContext, playlist[startIndex].stableKey)
            Log.d(TAG, "setPlaylist restore: index=$startIndex pos=${savedPos}ms")
            playerView.player?.seekTo(startIndex, savedPos)
        }

        currentTrackIndex = null
        playlistAdapter.setCurrentlyPlayingIndex(null)
    }

    /**
     * Push saved progress for every track into the adapter immediately after a
     * load/reload so bars are visible before any item is buffered.
     */
    private fun pushAllSavedProgress() {
        playlist.forEach { track ->
            val savedPos = PositionStore.savedPosition(applicationContext, track.stableKey)
            val progress = if (savedPos > 0)
                ((savedPos.toFloat() / ASSUMED_DURATION_MS) * 1000).toInt().coerceIn(1, 1000)
            else 0
            playlistAdapter.setTrackProgress(track.stableKey, progress)
        }
    }

    private fun normalizedTitle(input: String): String =
        input.lowercase().replace("[^a-z0-9]+".toRegex(), " ").trim()

    private fun likelySameEpisodeTitle(oldTitle: String, newTitle: String): Boolean {
        val oldNorm = normalizedTitle(oldTitle)
        val newNorm = normalizedTitle(newTitle)
        if (oldNorm.isBlank() || newNorm.isBlank()) return false
        return oldNorm == newNorm ||
            (oldNorm.length > 10 && newNorm.contains(oldNorm)) ||
            (newNorm.length > 10 && oldNorm.contains(newNorm))
    }

    private fun computeEpisodeChanges(loadedPlaylist: List<Track>): EpisodeChangeOutcome {
        var firstNewIndex: Int? = null
        var changedFeeds = 0

        loadedPlaylist.forEachIndexed { index, track ->
            val feed = track.feed ?: return@forEachIndexed
            val oldSnapshot = EpisodeDecisionStore.latestSnapshot(applicationContext, feed.xmlUrl)
            val newSnapshot = EpisodeDecisionStore.EpisodeSnapshot(
                title = track.title,
                guid = track.guid,
                url = track.url,
                stableKey = track.stableKey
            )

            val (decision, reason) = when {
                oldSnapshot == null -> "UNCHANGED" to "no_previous_snapshot"
                oldSnapshot.stableKey == newSnapshot.stableKey -> "UNCHANGED" to "stable_key_same"
                likelySameEpisodeTitle(oldSnapshot.title, newSnapshot.title) ->
                    "SUSPECT_FALSE_NEW" to "title_looks_same_but_key_changed"
                !oldSnapshot.guid.isNullOrBlank() && !newSnapshot.guid.isNullOrBlank() ->
                    "NEW_EPISODE" to "guid_changed"
                else -> "NEW_EPISODE" to "fallback_key_changed"
            }

            if (decision != "UNCHANGED") {
                changedFeeds += 1
                if (firstNewIndex == null) firstNewIndex = index
                Log.i(
                    TAG,
                    "EPISODE_DECISION: feed=${feed.xmlUrl} decision=$decision reason=$reason " +
                        "oldTitle=${oldSnapshot?.title ?: "(none)"} oldGuid=${oldSnapshot?.guid ?: ""} oldUrl=${oldSnapshot?.url ?: ""} " +
                        "newTitle=${newSnapshot.title} newGuid=${newSnapshot.guid ?: ""} newUrl=${newSnapshot.url}"
                )
            }

            EpisodeDecisionStore.appendDecision(
                applicationContext,
                EpisodeDecisionStore.DecisionEvent(
                    timestampMs = System.currentTimeMillis(),
                    feedXmlUrl = feed.xmlUrl,
                    feedTitle = feed.title,
                    decision = decision,
                    reason = reason,
                    oldEpisode = oldSnapshot,
                    newEpisode = newSnapshot
                )
            )
            EpisodeDecisionStore.saveLatestSnapshot(applicationContext, feed.xmlUrl, newSnapshot)
        }

        return EpisodeChangeOutcome(firstNewIndex = firstNewIndex, changedFeeds = changedFeeds)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showWhyThisIsNewDialog() {
        val idx = playerView.player?.currentMediaItemIndex ?: currentTrackIndex
        if (idx == null || idx !in playlist.indices) {
            Toast.makeText(this, "No active episode", Toast.LENGTH_SHORT).show()
            return
        }
        val track = playlist[idx]
        val feed = track.feed
        if (feed == null) {
            Toast.makeText(this, "No feed metadata for this track", Toast.LENGTH_SHORT).show()
            return
        }
        val event = EpisodeDecisionStore.lastDecisionForFeed(applicationContext, feed.xmlUrl)
        if (event == null) {
            Toast.makeText(this, "No decision history for this feed yet", Toast.LENGTH_SHORT).show()
            return
        }

        val formattedTime = DateFormat.getDateTimeInstance().format(Date(event.timestampMs))
        val details = buildString {
            append("Feed: ${event.feedTitle}\n")
            append("Decision: ${event.decision}\n")
            append("Reason: ${event.reason}\n")
            append("When: $formattedTime\n\n")
            append("Previous episode:\n")
            append("- Title: ${event.oldEpisode?.title ?: "(none)"}\n")
            append("- Guid: ${event.oldEpisode?.guid ?: "(none)"}\n")
            append("- Url: ${event.oldEpisode?.url ?: "(none)"}\n")
            append("- Key: ${event.oldEpisode?.stableKey ?: "(none)"}\n\n")
            append("Current latest episode:\n")
            append("- Title: ${event.newEpisode?.title ?: track.title}\n")
            append("- Guid: ${event.newEpisode?.guid ?: "(none)"}\n")
            append("- Url: ${event.newEpisode?.url ?: track.url}\n")
            append("- Key: ${event.newEpisode?.stableKey ?: track.stableKey}")
        }

        AlertDialog.Builder(this)
            .setTitle("Why is this considered new?")
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("episode-decision", details)
                Toast.makeText(this, "Decision details copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateList(opmlContent: String) {
        loadPlaylistFromOpml(opmlContent) { loadedPlaylist ->
            val outcome = computeEpisodeChanges(loadedPlaylist)
            if (outcome.firstNewIndex != null) {
                PositionStore.clearAllSavedPositions(applicationContext)
                forcedStartIndexOnNextPlaylistSet = outcome.firstNewIndex
                Log.i(TAG, "NEW_EPISODE_POLICY: changedFeeds=${outcome.changedFeeds} startIndex=${outcome.firstNewIndex} old_progress_cleared=true")
            }

            playlist.clear()
            playlist.addAll(loadedPlaylist)
            playlistAdapter.updateTracks(playlist)
            setPlaylist()
            pushAllSavedProgress()
            applicationContext.getSharedPreferences("prefs", MODE_PRIVATE)
                .edit { putString("opml", opmlContent) }
        }
    }

    private fun onPlaylistItemMoved(fromPosition: Int, toPosition: Int) {
        val movedTrack = playlist.removeAt(fromPosition)
        playlist.add(toPosition, movedTrack)
        savePlaylistToOpml()
        setPlaylist()
    }

    private fun onPlaylistItemDeleted(position: Int) {
        playlist.removeAt(position)
        savePlaylistToOpml()
        setPlaylist()
        Toast.makeText(this, "Podcast removed", Toast.LENGTH_SHORT).show()
    }

    private fun savePlaylistToOpml() {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='no' ?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head><title>Play the News Subscriptions</title></head>\n")
        sb.append("  <body>\n")
        for (track in playlist) {
            track.feed?.let { feed ->
                sb.append("    <outline text=\"${escapeXml(feed.title)}\" title=\"${escapeXml(feed.title)}\" type=\"rss\" xmlUrl=\"${escapeXml(feed.xmlUrl)}\" />\n")
            }
        }
        sb.append("  </body>\n</opml>")
        applicationContext.getSharedPreferences("prefs", MODE_PRIVATE)
            .edit { putString("opml", sb.toString()) }
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.player_menu, menu)
        updateEditMenuItem(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_load_opml -> { pickOpmlLauncher.launch(arrayOf(OPML_MIME)); true }
            R.id.action_search    -> {
                searchResultLauncher.launch(Intent(this, SearchActivity::class.java)); true
            }
            R.id.action_why_new   -> { showWhyThisIsNewDialog(); true }
            R.id.action_edit      -> { toggleEditMode(); invalidateOptionsMenu(); true }
            R.id.action_about     -> {
                startActivity(Intent(Intent.ACTION_VIEW,
                    "https://frandroidlabs.meimeidream.com/playthenews.html".toUri())); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        playlistAdapter.setEditMode(isEditMode)
        if (isEditMode)
            Toast.makeText(this, "Edit mode: Drag to reorder, swipe to delete", Toast.LENGTH_SHORT).show()
    }

    private fun updateEditMenuItem(menu: Menu) {
        val editItem = menu.findItem(R.id.action_edit)
        if (isEditMode) {
            editItem?.title = "Done"
            editItem?.setIcon(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            editItem?.title = "Edit"
            editItem?.setIcon(android.R.drawable.ic_menu_edit)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun readOpmlFromUri(uri: Uri): String? =
        try { contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() } }
        catch (e: Exception) { e.printStackTrace(); null }

    private fun updateTitleAndButtons() {
        val idx = currentTrackIndex ?: return
        if (idx in playlist.indices) {
            supportActionBar?.title = playlist[idx].title
        }
    }

    private fun escapeXml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}