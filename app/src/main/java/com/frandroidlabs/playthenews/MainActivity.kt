package com.frandroidlabs.playthenews

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.ComponentName
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


data class Track(val title: String, val url: String, var feed: FeedInfo? = null, val iconUrl: String?)
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
            val iconUrl = null

            if (!xmlUrl.isNullOrBlank()) {
                result.add(FeedInfo(title, xmlUrl, iconUrl))
            }
        }
        eventType = parser.next()
    }
    return result
}

suspend fun extractTrackFromRss(rssUrl: String, fallbackTitle: String): Track? = withContext(Dispatchers.IO) {
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
        var insideItem = false
        var insideChannel = false
        var foundFirstItem = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> insideChannel = true
                        "item" -> {
                            insideItem = true
                        }
                        "title" -> if (insideItem && title == null) {
                            title = parser.nextText()
                        }
                        "enclosure" -> if (insideItem && enclosureUrl == null) {
                            val type = parser.getAttributeValue(null, "type")
                            if (type != null && type.startsWith("audio/")) {
                                enclosureUrl = parser.getAttributeValue(null, "url")
                            }
                        }
                        "itunes:image" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (insideItem && iconInItem == null) {
                                iconInItem = href
                            } else if (insideChannel && channelIcon == null) {
                                channelIcon = href
                            }
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
                            if (insideItem && iconInItem == null) {
                                iconInItem = urlAttr
                            } else if (insideChannel && channelIcon == null) {
                                channelIcon = urlAttr
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && insideItem) {
                        foundFirstItem = true
                        break
                    }
                }
            }
            eventType = parser.next()
        }
        stream.close()

        val iconUrl = iconInItem ?: channelIcon

        return@withContext if (enclosureUrl != null) {
            Track(
                title = title ?: fallbackTitle,
                url = enclosureUrl,
                iconUrl = iconUrl
            )
        } else null
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }
}

fun loadPlaylistFromOpml(opml: String, callback: (List<Track>) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        val feeds = parseOpml(opml)
        val playlist = mutableListOf<Track>()
        for (feed in feeds) {
            val track = extractTrackFromRss(feed.xmlUrl, feed.title)
            if (track != null) {
                track.feed = feed
                playlist.add(track)
            }
        }
        callback(playlist)
    }
}

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val TAG = "PlayTheNews"
    private val playlist = mutableListOf<Track>()
    private var player: MediaController? = null
    private var isPrepared = false

    private lateinit var urlRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private lateinit var playerView: PlayerView
    private lateinit var playlistItemTouchHelper: ItemTouchHelper

    // null = nothing highlighted until actual playback begins
    private var currentTrackIndex: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaylistInitialized = false
    private var isEditMode = false

    // Set to true by playFromTrack() just before it calls seekTo() with the
    // saved position, so onMediaItemTransition knows NOT to seek again.
    // Cleared immediately after the transition handler reads it.
    private var positionAlreadyRestored = false

    // -------------------------------------------------------------------------
    // Playback position persistence
    //
    // Positions are stored in SharedPreferences keyed by track URL so they
    // survive playlist reorders, app kills, and new sessions.
    // Key format:  "pos:<url>"
    // -------------------------------------------------------------------------

    private fun positionKey(url: String) = "pos:$url"

    /** Persist the playback position for a given track URL (milliseconds). */
    private fun savePosition(url: String, positionMs: Long) {
        if (positionMs <= 0) return
        applicationContext
            .getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit { putLong(positionKey(url), positionMs) }
        Log.d(TAG, "savePosition: $url -> ${positionMs}ms")
    }

    /** Return the saved playback position for a URL, or 0 if none. */
    private fun savedPosition(url: String): Long =
        applicationContext
            .getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getLong(positionKey(url), 0L)

    /** Save the position of whatever track is currently loaded in the player. */
    private fun saveCurrentPosition() {
        val p = playerView.player ?: return
        val idx = p.currentMediaItemIndex
        if (idx in playlist.indices) {
            val pos = p.currentPosition
            savePosition(playlist[idx].url, pos)
        }
    }

    // -------------------------------------------------------------------------

    private val searchResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Returned from SearchActivity with new data. Reloading podcasts.")
            val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val opmlContent = prefs.getString("opml", null)
            if (opmlContent != null) {
                updateList(opmlContent)
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        urlRecyclerView = findViewById(R.id.urlRecyclerView)
        playlistAdapter = PlaylistAdapter(
            playlist.toMutableList(),
            null, // null = nothing highlighted at startup
            onTrackClick = { position -> playFromTrack(position) },
            onItemMoved = { fromPos, toPos -> onPlaylistItemMoved(fromPos, toPos) },
            onItemDeleted = { position -> onPlaylistItemDeleted(position) }
        )
        urlRecyclerView.layoutManager = LinearLayoutManager(this)
        urlRecyclerView.adapter = playlistAdapter

        val touchHelperCallback = PlaylistItemTouchHelper(playlistAdapter)
        playlistItemTouchHelper = ItemTouchHelper(touchHelperCallback)
        playlistItemTouchHelper.attachToRecyclerView(urlRecyclerView)
        playlistAdapter.setItemTouchHelper(playlistItemTouchHelper)

        supportActionBar?.title = ""

        Log.d(TAG, "onCreate: playlist: " + playlist.size)
    }

    override fun onStart() {
        super.onStart()

        val sessionToken =
            SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        var opmlContent = prefs.getString("opml", null)
        if (opmlContent == null) {
            opmlContent = assets.open("subscriptions.opml").bufferedReader().use { it.readText() }
        }

        Log.d(TAG, "onStart: playlist: " + playlist.size)

        controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()

        controllerFuture.addListener({
            val controller = controllerFuture.get()
            player = controller
            playerView.player = controller
            Log.d(TAG, "onStart: got the player, mediaItemCount=${controller.mediaItemCount}")

            // Re-load the playlist if this is the first connect OR if the service
            // was killed while dormant and came back with an empty ExoPlayer instance.
            if (!isPlaylistInitialized || controller.mediaItemCount == 0) {
                Log.d(TAG, "onStart: (re)initialising playlist")
                isPlaylistInitialized = true
                updateList(opmlContent)
            }

            playerView.setControllerShowTimeoutMs(0)
            playerView.controllerAutoShow = true

            playerView.player?.prepare()
            Log.d(TAG, "onStart: prepared player")

            // Sync highlight from the actual player state on reconnect
            val actualIndex = controller.currentMediaItemIndex
            if (controller.mediaItemCount > 0) {
                currentTrackIndex = actualIndex
                playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
            }

            updatePlaybackControls()

            playerView.player?.addListener(object : Player.Listener {

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    runOnUiThread {
                        val newIndex = playerView.player?.currentMediaItemIndex
                        Log.d(TAG, "onMediaItemTransition: newIndex=$newIndex reason=$reason positionAlreadyRestored=$positionAlreadyRestored")
                        if (newIndex != null && newIndex != currentTrackIndex) {
                            // Persist position of the track we are leaving
                            saveCurrentPosition()
                            currentTrackIndex = newIndex
                            playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
                            updateTitleAndButtons()
                        }
                        // Restore saved position for the new track unless playFromTrack()
                        // already called seekTo() with the correct position just before
                        // triggering this transition.
                        if (positionAlreadyRestored) {
                            positionAlreadyRestored = false
                        } else if (newIndex != null && newIndex in playlist.indices) {
                            val savedPos = savedPosition(playlist[newIndex].url)
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

    override fun onStop() {
        super.onStop()
        // Persist position before releasing the controller
        saveCurrentPosition()
        handler.removeCallbacksAndMessages(null)
        MediaController.releaseFuture(controllerFuture)
        player = null
    }

    private fun playTheNews() {
        playerView.player?.prepare()
        playerView.player?.playWhenReady = true
    }

    private fun updatePlaybackControls() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = playerView.player ?: return

                val currentItemIndex = p.currentMediaItemIndex
                if (currentItemIndex != currentTrackIndex) {
                    Log.d(TAG, "updatePlaybackControls: index changed $currentTrackIndex -> $currentItemIndex")
                    currentTrackIndex = currentItemIndex
                    playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
                    updateTitleAndButtons()
                }

                // Persist position every second while actively playing
                if (p.isPlaying) {
                    saveCurrentPosition()
                }

                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onResume() {
        super.onResume()
        // Sync from player on resume in case the index changed while paused
        val actualIndex = playerView.player?.currentMediaItemIndex
        if (actualIndex != null && actualIndex != currentTrackIndex) {
            currentTrackIndex = actualIndex
        }
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
    }

    override fun onPause() {
        super.onPause()
        // Save position whenever the activity goes to background
        saveCurrentPosition()
    }

    fun setPlaylist() {
        playerView.player?.clearMediaItems()

        Log.d(TAG, "setPlaylist: " + playlist.size)
        playlist.forEach { track ->
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.feed?.title ?: "Podcast")

            if (!track.iconUrl.isNullOrBlank()) {
                metadataBuilder.setArtworkUri(Uri.parse(track.iconUrl))
            }

            val metadata = metadataBuilder.build()

            val mediaItem = MediaItem.Builder()
                .setUri(track.url)
                .setMediaMetadata(metadata)
                .build()

            playerView.player?.addMediaItem(mediaItem)
            Log.d(TAG, "setPlaylist: ${track.url} with metadata: ${track.title}")
        }

        // Prepare so the player accepts seekTo calls
        playerView.player?.prepare()

        // Restore each track's saved position using seekTo(windowIndex, positionMs).
        // ExoPlayer records these offsets per window and applies them when that
        // item is loaded, so all tracks resume from where the user left off.
        playlist.forEachIndexed { index, track ->
            val savedPos = savedPosition(track.url)
            if (savedPos > 0) {
                Log.d(TAG, "setPlaylist restore: index=$index pos=${savedPos}ms url=${track.url}")
                playerView.player?.seekTo(index, savedPos)
            }
        }

        // Reset highlight — will be synced by updatePlaybackControls / listeners
        currentTrackIndex = null
        playlistAdapter.setCurrentlyPlayingIndex(null)
    }

    private fun updateList(opmlContent: String) {
        loadPlaylistFromOpml(opmlContent) { loadedPlaylist ->
            playlist.clear()
            playlist.addAll(loadedPlaylist)
            playlistAdapter.updateTracks(playlist)
            Log.d(TAG, "updateList: playlist: " + playlist.size)
            setPlaylist()
            val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putString("opml", opmlContent)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.player_menu, menu)
        updateEditMenuItem(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_load_opml -> {
                pickOpmlLauncher.launch(arrayOf(OPML_MIME))
                true
            }
            R.id.action_search -> {
                val intent = Intent(this, SearchActivity::class.java)
                searchResultLauncher.launch(intent)
                true
            }
            R.id.action_edit -> {
                toggleEditMode()
                invalidateOptionsMenu()
                true
            }
            R.id.action_about -> {
                val url = "https://frandroidlabs.meimeidream.com/playthenews.html"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = url.toUri()
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun readOpmlFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun playFromTrack(index: Int?) {
        if (index == null || index !in playlist.indices) return

        val idx = index.toInt()
        Log.d(TAG, "Playing the list from $idx")

        // Update highlight immediately on user tap
        currentTrackIndex = idx
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)

        // Resume from the saved position for this track, or from the start if none
        val savedPos = savedPosition(playlist[idx].url)
        if (savedPos > 0) {
            Log.d(TAG, "playFromTrack: resuming at ${savedPos}ms for index $idx")
            positionAlreadyRestored = true
            playerView.player?.seekTo(idx, savedPos)
        } else {
            playerView.player?.seekTo(idx, 0)
        }

        playTheNews()
    }

    private fun formatTime(millis: Int): String {
        val secs = millis / 1000
        val min = secs / 60
        val sec = secs % 60
        return String.format("%d:%02d", min, sec)
    }

    private fun skip(amountMs: Int) {
        val currentPosition = player?.currentPosition ?: 0
        player?.seekTo(currentPosition + amountMs)
    }

    private fun updateTitleAndButtons() {
        if (currentTrackIndex != null && currentTrackIndex!! >= 0 && currentTrackIndex!! < playlist.size) {
            val ct: Int = currentTrackIndex!!
            val (title, _) = playlist[ct]
            supportActionBar?.title = title
        }
    }

    private fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }

    private fun skipNext() {
        val size = playlist.size
        if (size == 0) return
        currentTrackIndex = ((currentTrackIndex ?: -1) + 1) % size
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
        playFromTrack(currentTrackIndex)
    }

    private fun skipPrev() {
        val size = playlist.size
        if (size == 0) return
        val prev = (currentTrackIndex ?: 0) - 1
        currentTrackIndex = if (prev < 0) size - 1 else prev
        playlistAdapter.setCurrentlyPlayingIndex(currentTrackIndex)
        playFromTrack(currentTrackIndex)
    }

    private fun updateUrlList() {
        for ((index, item) in playlist.withIndex()) {
            val urlView = TextView(this)
            urlView.text = "${item.title}: ${item.url}"
            urlView.setPadding(8, 8, 8, 8)
            urlView.textSize = 14f
            if (index == currentTrackIndex) {
                urlView.setTypeface(null, Typeface.BOLD)
                urlView.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                )
            } else {
                urlView.setTypeface(null, Typeface.NORMAL)
                urlView.setTextColor(
                    ContextCompat.getColor(this, R.color.track_unselected_text)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: ")
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        playlistAdapter.setEditMode(isEditMode)

        if (isEditMode) {
            Toast.makeText(this, "Edit mode: Drag to reorder, swipe to delete", Toast.LENGTH_SHORT).show()
        }
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
        val opmlBuilder = StringBuilder()
        opmlBuilder.append("<?xml version='1.0' encoding='UTF-8' standalone='no' ?>\n")
        opmlBuilder.append("<opml version=\"2.0\">\n")
        opmlBuilder.append("  <head>\n")
        opmlBuilder.append("    <title>Play the News Subscriptions</title>\n")
        opmlBuilder.append("  </head>\n")
        opmlBuilder.append("  <body>\n")

        for (track in playlist) {
            track.feed?.let { feed ->
                val title = escapeXml(feed.title)
                val xmlUrl = escapeXml(feed.xmlUrl)
                opmlBuilder.append("    <outline text=\"$title\" title=\"$title\" type=\"rss\" xmlUrl=\"$xmlUrl\" />\n")
            }
        }

        opmlBuilder.append("  </body>\n")
        opmlBuilder.append("</opml>")

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putString("opml", opmlBuilder.toString())
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}