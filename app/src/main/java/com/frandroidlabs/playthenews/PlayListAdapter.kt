package com.frandroidlabs.playthenews

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import java.util.Collections

private const val TAG = "PlayTheNews"

class PlaylistAdapter(
    private var tracks: MutableList<Track>,
    private var currentlyPlayingIndex: Int? = null,
    private val onTrackClick: ((Int) -> Unit)? = null,
    private val onItemMoved: ((Int, Int) -> Unit)? = null,
    private val onItemDeleted: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.TrackViewHolder>() {

    private var isEditMode = false
    private var touchHelper: ItemTouchHelper? = null

    // Progress values (0–1000) keyed by track stable key so they survive reorders
    // and remain aligned with persisted resume positions.
    // 0 means no saved position → bar hidden. >0 shows the bar.
    private val progressMap = mutableMapOf<String, Int>()

    fun setItemTouchHelper(helper: ItemTouchHelper) {
        touchHelper = helper
    }

    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        notifyDataSetChanged()
    }

    fun isEditMode(): Boolean = isEditMode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(v)
    }

    override fun getItemCount() = tracks.size

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val progress = progressMap[track.stableKey] ?: 0
        holder.bind(
            track,
            isPlaying = (position == currentlyPlayingIndex),
            isEditMode = isEditMode,
            progress = progress
        )

        holder.itemView.setOnClickListener {
            if (!isEditMode) {
                onTrackClick?.invoke(position)
            }
        }

        holder.dragHandle.setOnLongClickListener {
            if (isEditMode) {
                touchHelper?.startDrag(holder)
                true
            } else {
                false
            }
        }
    }

    fun setCurrentlyPlayingIndex(index: Int?) {
        val previousIndex = currentlyPlayingIndex
        currentlyPlayingIndex = index
        if (previousIndex != index) {
            Log.d(TAG, "setCurrentlyPlayingIndex: previousIndex: $previousIndex, index: $index")
            if (previousIndex != null && previousIndex in 0 until tracks.size) {
                notifyItemChanged(previousIndex)
            }
            if (index != null && index in 0 until tracks.size) {
                notifyItemChanged(index)
            }
        }
    }

    /**
     * Update the progress bar for a single track identified by stable key.
     * [progressValue] is in the range 0–1000 (per ProgressBar max).
     * Pass 0 to hide the bar.
     * Only triggers a rebind if the value actually changed.
     */
    fun setTrackProgress(stableKey: String, progressValue: Int) {
        val old = progressMap[stableKey] ?: 0
        if (old == progressValue) return
        progressMap[stableKey] = progressValue
        val idx = tracks.indexOfFirst { it.stableKey == stableKey }
        if (idx != -1) notifyItemChanged(idx)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(tracks, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(tracks, i, i - 1)
            }
        }

        if (currentlyPlayingIndex != null) {
            when {
                currentlyPlayingIndex == fromPosition -> currentlyPlayingIndex = toPosition
                fromPosition < currentlyPlayingIndex!! && toPosition >= currentlyPlayingIndex!! -> currentlyPlayingIndex = currentlyPlayingIndex!! - 1
                fromPosition > currentlyPlayingIndex!! && toPosition <= currentlyPlayingIndex!! -> currentlyPlayingIndex = currentlyPlayingIndex!! + 1
            }
        }

        notifyItemMoved(fromPosition, toPosition)
        onItemMoved?.invoke(fromPosition, toPosition)
    }

    fun removeItem(position: Int) {
        tracks.removeAt(position)

        if (currentlyPlayingIndex != null) {
            when {
                currentlyPlayingIndex == position -> currentlyPlayingIndex = null
                currentlyPlayingIndex!! > position -> currentlyPlayingIndex = currentlyPlayingIndex!! - 1
            }
        }

        notifyItemRemoved(position)
        onItemDeleted?.invoke(position)
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.trackTitle)
        val playingIndicator: ImageView = itemView.findViewById(R.id.playingIndicator)
        private val trackIcon: ImageView = itemView.findViewById(R.id.trackIcon)
        val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private val trackProgress: ProgressBar = itemView.findViewById(R.id.trackProgress)

        fun bind(track: Track, isPlaying: Boolean, isEditMode: Boolean, progress: Int) {
            titleTextView.text = track.title

            Picasso.get()
                .load(track.iconUrl)
                .placeholder(R.drawable.ic_default_icon)
                .error(R.drawable.ic_error_icon)
                .into(trackIcon)

            dragHandle.visibility = if (isEditMode) View.VISIBLE else View.GONE

            if (isPlaying) {
                itemView.setBackgroundResource(R.color.currently_playing_bg)
                playingIndicator.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                playingIndicator.visibility = View.INVISIBLE
            }

            // Show progress bar whenever there is a saved position (progress > 0)
            if (progress > 0) {
                trackProgress.visibility = View.VISIBLE
                trackProgress.progress = progress
            } else {
                trackProgress.visibility = View.GONE
            }
        }
    }

    fun updateTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

}