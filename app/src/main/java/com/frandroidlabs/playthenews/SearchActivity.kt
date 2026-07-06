package com.frandroidlabs.playthenews

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URL
import java.net.URLEncoder

data class PodcastSearchResult(
    val title: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val author: String?
)

class SearchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SearchActivity"
    }

    private lateinit var searchView: SearchView
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var searchAdapter: PodcastSearchAdapter
    private val searchResults = mutableListOf<PodcastSearchResult>()
    private val selectedPodcasts = mutableSetOf<PodcastSearchResult>()
    private var hasChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.search_title)

        recyclerView = findViewById(R.id.searchRecyclerView)
        progressBar = findViewById(R.id.searchProgressBar)

        searchAdapter = PodcastSearchAdapter(searchResults, selectedPodcasts) { podcast, isSelected ->
            if (isSelected) {
                selectedPodcasts.add(podcast)
            } else {
                selectedPodcasts.remove(podcast)
            }
            invalidateOptionsMenu() // Update menu to show/hide "Add" button
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = searchAdapter

        // Handle back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithResult()
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.search_menu, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_query_hint)
        searchView.maxWidth = Integer.MAX_VALUE

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchPodcasts(it) }
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return true
            }
        })

        // Expand the search view by default
        searchItem.expandActionView()

        // Update "Add" button visibility
        val addItem = menu.findItem(R.id.action_add_selected)
        addItem.isVisible = selectedPodcasts.isNotEmpty()

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishWithResult()
                true
            }
            R.id.action_add_selected -> {
                addSelectedPodcasts()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun searchPodcasts(query: String) {
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    performSearch(query)
                }

                searchResults.clear()
                searchResults.addAll(results)
                searchAdapter.notifyDataSetChanged()

                if (results.isEmpty()) {
                    Toast.makeText(this@SearchActivity, R.string.search_no_results, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                Toast.makeText(
                    this@SearchActivity,
                    getString(R.string.search_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun performSearch(query: String): List<PodcastSearchResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val urlString = "https://itunes.apple.com/search?media=podcast&limit=50&term=$encodedQuery"

        val url = URL(urlString)
        val response = url.readText()
        val json = JSONObject(response)
        val results = json.getJSONArray("results")

        val podcasts = mutableListOf<PodcastSearchResult>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)

            // Use optString with default values to handle nulls properly
            val collectionName = item.optString("collectionName", "Unknown")
            val feedUrl = item.optString("feedUrl", "")
            val artworkUrl = item.optString("artworkUrl100").takeIf { it.isNotEmpty() }
            val artistName = item.optString("artistName").takeIf { it.isNotEmpty() }

            podcasts.add(
                PodcastSearchResult(
                    title = collectionName,
                    feedUrl = feedUrl,
                    artworkUrl = artworkUrl,
                    author = artistName
                )
            )
        }

        return podcasts.filter { it.feedUrl.isNotEmpty() }
    }

    private fun addSelectedPodcasts() {
        if (selectedPodcasts.isEmpty()) {
            Toast.makeText(this, R.string.search_no_selection, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val currentOpml = prefs.getString("opml", null) ?: run {
            // Load default OPML if none exists
            assets.open("subscriptions.opml").bufferedReader().use { it.readText() }
        }

        val updatedOpml = addPodcastsToOpml(currentOpml, selectedPodcasts.toList())

        prefs.edit {
            putString("opml", updatedOpml)
        }

        hasChanges = true
        Toast.makeText(
            this,
            resources.getQuantityString(R.plurals.podcasts_added, selectedPodcasts.size, selectedPodcasts.size),
            Toast.LENGTH_SHORT
        ).show()

        finishWithResult()
    }

    private fun addPodcastsToOpml(opml: String, podcasts: List<PodcastSearchResult>): String {
        // Find the </body> tag and insert new outlines before it
        val bodyEndIndex = opml.lastIndexOf("</body>")
        if (bodyEndIndex == -1) return opml

        val newOutlines = podcasts.joinToString("\n") { podcast ->
            val title = escapeXml(podcast.title)
            val feedUrl = escapeXml(podcast.feedUrl)
            "    <outline text=\"$title\" title=\"$title\" type=\"rss\" xmlUrl=\"$feedUrl\" />"
        }

        return opml.substring(0, bodyEndIndex) +
                newOutlines + "\n" +
                opml.substring(bodyEndIndex)
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun finishWithResult() {
        if (hasChanges) {
            setResult(RESULT_OK)
        }
        finish()
    }
}