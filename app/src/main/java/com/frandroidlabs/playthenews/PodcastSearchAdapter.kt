package com.frandroidlabs.playthenews

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class PodcastSearchAdapter(
    private val podcasts: List<PodcastSearchResult>,
    private val selectedPodcasts: MutableSet<PodcastSearchResult>,
    private val onSelectionChanged: (PodcastSearchResult, Boolean) -> Unit
) : RecyclerView.Adapter<PodcastSearchAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val artworkImageView: ImageView = view.findViewById(R.id.podcastArtwork)
        val titleTextView: TextView = view.findViewById(R.id.podcastTitle)
        val authorTextView: TextView = view.findViewById(R.id.podcastAuthor)
        val checkBox: CheckBox = view.findViewById(R.id.podcastCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_podcast_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val podcast = podcasts[position]

        holder.titleTextView.text = podcast.title
        holder.authorTextView.text = podcast.author ?: holder.itemView.context.getString(R.string.unknown_author)

        // Load artwork
        if (podcast.artworkUrl != null) {
            Picasso.get()
                .load(podcast.artworkUrl)
                .placeholder(R.drawable.ic_default_icon)
                .error(R.drawable.ic_error_icon)
                .into(holder.artworkImageView)
        } else {
            holder.artworkImageView.setImageResource(R.drawable.ic_default_icon)
        }

        // Set checkbox state
        holder.checkBox.isChecked = selectedPodcasts.contains(podcast)

        // Handle checkbox changes
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            onSelectionChanged(podcast, isChecked)
        }

        // Make entire item clickable to toggle checkbox
        holder.itemView.setOnClickListener {
            holder.checkBox.isChecked = !holder.checkBox.isChecked
        }
    }

    override fun getItemCount() = podcasts.size
}