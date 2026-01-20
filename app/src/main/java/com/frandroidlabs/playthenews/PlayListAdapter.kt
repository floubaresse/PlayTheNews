package com.frandroidlabs.playthenews

import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import java.util.Collections

private val TAG = "PlayTheNews"

class PlaylistAdapter(
    private var tracks: MutableList<Track>,
    private var currentlyPlayingIndex: Int? = null,
    private val onTrackClick: ((Int) -> Unit)? = null,
    private val onItemMoved: ((Int, Int) -> Unit)? = null,
    private val onItemDeleted: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.TrackViewHolder>() {

    private var isEditMode = false
    private var touchHelper: ItemTouchHelper? = null

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
        holder.bind(track, isPlaying = (position == currentlyPlayingIndex), isEditMode = isEditMode)

        // Add click listener (only when not in edit mode)
        holder.itemView.setOnClickListener {
            if (!isEditMode) {
                onTrackClick?.invoke(position)
            }
        }

        // Set up drag handle
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isEditMode) {
                touchHelper?.startDrag(holder)
                true
            } else {
                false
            }
        }
    }

    // Provide an update function to set the new playing index and refresh
    fun setCurrentlyPlayingIndex(index: Int?) {
        val previousIndex = currentlyPlayingIndex
        currentlyPlayingIndex = index
        if (previousIndex != index) {
            Log.d(TAG, "setCurrentlyPlayingIndex: previousIndex: $previousIndex, index: $index")
            if (previousIndex != null && previousIndex != -1 && previousIndex < tracks.size) {
                notifyItemChanged(previousIndex)
            }
            if (index != null && index != -1 && index < tracks.size) {
                notifyItemChanged(index)
            }
        }
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

        // Update currently playing index if needed
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

        // Update currently playing index if needed
        if (currentlyPlayingIndex != null) {
            when {
                currentlyPlayingIndex == position -> currentlyPlayingIndex = null
                currentlyPlayingIndex!! > position -> currentlyPlayingIndex = currentlyPlayingIndex!! - 1
            }
        }

        notifyItemRemoved(position)
        onItemDeleted?.invoke(position)
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.trackTitle)
        val playingIndicator: ImageView = itemView.findViewById(R.id.playingIndicator)
        private val trackIcon: ImageView = itemView.findViewById(R.id.trackIcon)
        val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)

        fun bind(track: Track, isPlaying: Boolean, isEditMode: Boolean) {
            titleTextView.text = track.title

            Picasso.get()
                .load(track.iconUrl)
                .placeholder(R.drawable.ic_default_icon)
                .error(R.drawable.ic_error_icon)
                .into(trackIcon)

            // Show/hide drag handle based on edit mode
            dragHandle.visibility = if (isEditMode) View.VISIBLE else View.GONE

            if (isPlaying) {
                itemView.setBackgroundResource(R.color.currently_playing_bg)
                playingIndicator.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                playingIndicator.visibility = View.INVISIBLE
            }
        }
    }

    fun updateTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    fun getTracks(): List<Track> = tracks.toList()
}